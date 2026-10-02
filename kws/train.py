"""
Nexus Agent 唤醒词（KWS）训练脚本
================================
训练一个极小的 CNN 模型识别「你好高总」唤醒词，导出 ONNX 供 Android onnxruntime 使用。

用法：
  1. 先运行 generate_data.py 合成训练数据（正样本：TTS「你好高总」；负样本：噪音/其他词）
  2. 再运行 python train.py 训练并导出 ONNX
  3. 产物：kws/output/nexus_kws.onnx

设计要点：
  - 输入：16kHz 单声道音频，切帧 32ms 窗 + 16ms 步长，取 40 维 MFCC
  - 每段音频固定 1000ms（= 61 帧 MFCC），不足补零、超长截断
  - 模型：2 层 Conv + GlobalPool + 全连接，输出 2 类 logits
  - 极小：参数量 < 100KB，可轻松在手机 CPU 上实时跑
"""
import os
import glob
import math
import random
import argparse

import numpy as np

# 首次运行需安装依赖：
#   pip install torch numpy scipy soundfile librosa onnx
try:
    import torch
    import torch.nn as nn
    import torch.nn.functional as F
except ImportError:
    raise SystemExit("缺少 torch，请先 pip install torch（用 V100 机器对应的 CUDA 版本）")

try:
    import soundfile as sf
except ImportError:
    sf = None

try:
    import librosa
except ImportError:
    librosa = None


# ---------------------------------------------------------------------------
# 音频前端：把 wav 转成 MFCC 特征
# ---------------------------------------------------------------------------
SAMPLE_RATE = 16000
FRAME_MS = 32          # 帧长 32ms
HOP_MS = 16            # 帧移 16ms
N_MFCC = 40            # MFCC 维度
WINDOW_MS = 1000       # 每段音频固定长度 1000ms
N_FRAMES = int((WINDOW_MS - FRAME_MS) / HOP_MS) + 1  # 61 帧


def load_audio(path):
    """加载 wav 到 float32 数组，重采样到 16kHz。"""
    if sf is not None:
        data, sr = sf.read(path, dtype="float32")
    elif librosa is not None:
        data, sr = librosa.load(path, sr=SAMPLE_RATE, mono=True)
        data = data.astype(np.float32)
    else:
        raise SystemExit("需要 soundfile 或 librosa，请 pip install soundfile librosa")

    # 若为立体声取均值
    if data.ndim > 1:
        data = data.mean(axis=1)

    # 重采样到 16k（librosa 加载时已做；soundfile 需要手动）
    if sf is not None and sr != SAMPLE_RATE:
        if librosa is not None:
            data = librosa.resample(data, orig_sr=sr, target_sr=SAMPLE_RATE)
        else:
            # 简单线性重采样
            n_out = int(len(data) * SAMPLE_RATE / sr)
            idx = np.linspace(0, len(data) - 1, n_out)
            data = np.interp(idx, np.arange(len(data)), data)

    return data


def _hz_to_mel(hz):
    """HTK 风格 hz -> mel（与 Android 端 WakeWordDetector.hzToMel 一致）。"""
    return 2595.0 * np.log10(1.0 + hz / 700.0)


def _mel_to_hz(mel):
    """HTK 风格 mel -> hz（与 Android 端 melToHz 一致）。"""
    return 700.0 * (np.power(10.0, mel / 2595.0) - 1.0)


def _mel_filterbank(n_fft, sr, n_mels):
    """生成 mel 滤波器组 (n_mels, n_fft//2+1)，HTK 三角滤波、无归一化。

    与 Android 端 WakeWordDetector.melFilterbank() 严格一致。
    """
    n_bins = n_fft // 2 + 1
    fmin, fmax = 0.0, sr / 2.0
    min_mel = _hz_to_mel(fmin)
    max_mel = _hz_to_mel(fmax)
    mel_points = np.linspace(min_mel, max_mel, n_mels + 2)
    hz_points = _mel_to_hz(mel_points)
    bin_points = (n_bins - 1) * hz_points / (sr / 2.0)

    fbank = np.zeros((n_mels, n_bins), dtype=np.float64)
    for m in range(1, n_mels + 1):
        left = bin_points[m - 1]
        center = bin_points[m]
        right = bin_points[m + 1]
        for k in range(n_bins):
            if left <= k <= center:
                fbank[m - 1, k] = (k - left) / (center - left) if center != left else 0.0
            elif center < k <= right:
                fbank[m - 1, k] = (right - k) / (right - center) if right != center else 0.0
    return fbank


def my_mfcc(y, sr=SAMPLE_RATE, n_fft=512, hop=256, n_mels=128, n_mfcc=40):
    """自定义 MFCC，与 Android 端 WakeWordDetector.computeMfcc 严格一一对应。

    关键参数（两边必须完全一致）：
    - 分帧：center=False，帧 t 起始 = t * hop
    - 加窗：周期 Hann 窗 w[n] = 0.5 - 0.5*cos(2πn/n_fft)
    - 功率谱：|FFT|^2
    - mel：HTK 三角滤波器组、无归一化、fmin=0 fmax=sr/2
    - 对数：10*log10(mel 能量 + 1e-10)
    - DCT-II，norm='ortho'，取 n_mfcc 维

    返回 (n_frames, n_mfcc)。
    """
    n = len(y)
    n_frames = (n - n_fft) // hop + 1
    if n_frames < 1:
        raise ValueError("音频太短，无法分帧")

    # 周期 Hann 窗（与 Android 端一致）
    window = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(n_fft) / n_fft)

    fbank = _mel_filterbank(n_fft, sr, n_mels)

    mel_energies = np.zeros((n_frames, n_mels), dtype=np.float64)
    for t in range(n_frames):
        start = t * hop
        frame = y[start:start + n_fft] * window
        spec = np.fft.rfft(frame, n_fft)      # (n_fft//2+1,) 复数
        power = np.abs(spec) ** 2              # 功率谱
        for m in range(n_mels):
            e = float(np.dot(fbank[m], power))
            mel_energies[t, m] = 10.0 * np.log10(e + 1e-10)

    # DCT-II ortho
    mfcc = np.zeros((n_frames, n_mfcc), dtype=np.float64)
    norm0 = 1.0 / np.sqrt(n_mels)
    normk = np.sqrt(2.0 / n_mels)
    m_idx = np.arange(n_mels)
    for c in range(n_mfcc):
        coeff = np.zeros(n_frames)
        for m in range(n_mels):
            coeff += mel_energies[:, m] * np.cos(np.pi * c * (m + 0.5) / n_mels)
        norm = norm0 if c == 0 else normk
        mfcc[:, c] = norm * coeff
    return mfcc.astype(np.float32)


def audio_to_mfcc(data):
    """把 16kHz float32 音频转成 (T, N_MFCC) 的 MFCC 特征（自定义实现，对齐 Android）。"""
    return my_mfcc(data, n_mfcc=N_MFCC)  # (T, 40)


def featurize(data):
    """音频 -> 固定长度特征 (N_FRAMES, N_MFCC)。"""
    mfcc = audio_to_mfcc(data)          # (T, 40)
    t = mfcc.shape[0]
    if t >= N_FRAMES:
        mfcc = mfcc[:N_FRAMES]
    else:
        mfcc = np.pad(mfcc, ((0, N_FRAMES - t), (0, 0)), mode="constant")
    return mfcc.astype(np.float32)


def load_features(path):
    data = load_audio(path)
    return featurize(data)


# ---------------------------------------------------------------------------
# 模型定义：小 CNN
# ---------------------------------------------------------------------------
class KwsModel(nn.Module):
    """轻量 CNN，输入 (N, 1, 61, 40) -> 输出 (N, 2)。"""

    def __init__(self, n_classes=2):
        super().__init__()
        self.conv1 = nn.Conv2d(1, 16, kernel_size=(3, 3), padding=1)
        self.bn1 = nn.BatchNorm2d(16)
        self.conv2 = nn.Conv2d(16, 32, kernel_size=(3, 3), padding=1)
        self.bn2 = nn.BatchNorm2d(32)
        self.conv3 = nn.Conv2d(32, 64, kernel_size=(3, 3), padding=1)
        self.bn3 = nn.BatchNorm2d(64)
        self.pool = nn.AdaptiveAvgPool2d((1, 1))
        self.fc = nn.Linear(64, n_classes)

    def forward(self, x):
        x = F.relu(self.bn1(self.conv1(x)))
        x = F.max_pool2d(x, 2)
        x = F.relu(self.bn2(self.conv2(x)))
        x = F.max_pool2d(x, 2)
        x = F.relu(self.bn3(self.conv3(x)))
        x = self.pool(x)                 # (N, 64, 1, 1)
        x = x.view(x.size(0), -1)        # (N, 64)
        return self.fc(x)                # (N, 2)


# ---------------------------------------------------------------------------
# 数据加载
# ---------------------------------------------------------------------------
def load_dataset(pos_dir, neg_dir):
    """加载正负样本，返回特征数组 X (N, 1, 61, 40) 和标签 y (N,)。"""
    X, y = [], []
    pos_files = sorted(glob.glob(os.path.join(pos_dir, "*.wav")))
    neg_files = sorted(glob.glob(os.path.join(neg_dir, "*.wav")))

    print(f"正样本 {len(pos_files)} 个，负样本 {len(neg_files)} 个")

    for f in pos_files:
        try:
            feat = load_features(f)
            X.append(feat)
            y.append(1)
        except Exception as e:
            print(f"跳过 {f}: {e}")

    for f in neg_files:
        try:
            feat = load_features(f)
            X.append(feat)
            y.append(0)
        except Exception as e:
            print(f"跳过 {f}: {e}")

    X = np.stack(X, axis=0)[:, None, :, :]  # (N, 1, 61, 40)
    y = np.array(y, dtype=np.int64)
    return X, y


def _inline_onnx_weights(onnx_path):
    """把 torch 外置的 onnx.data 权重合并回单个 onnx 文件。

    torch 2.2+ 导出时偶尔会把权重写成同目录的 `<name>.onnx.data` 外部文件，
    导致 Android 端 onnxruntime 只读到图结构、缺权重而加载失败。
    这里用 onnx 库重新 load + save，权重即内嵌进单文件，之后删除 .data 残留。
    """
    import onnx
    from onnx.external_data_helper import convert_model_to_external_data, load_external_data_for_model

    model = onnx.load(onnx_path)
    load_external_data_for_model(model, os.path.dirname(onnx_path))
    # 存回单文件（不带 external data）
    onnx.save(model, onnx_path)
    # 删除可能残留的外部数据文件
    data_path = onnx_path + ".data"
    if os.path.exists(data_path):
        os.remove(data_path)
    print("  已把权重内嵌进 ONNX 单文件")


def train(args):
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    print(f"使用设备: {device}")

    X, y = load_dataset(args.pos_dir, args.neg_dir)
    if len(X) < 10:
        raise SystemExit("样本太少，请先运行 generate_data.py 生成数据")

    # 划分训练/验证集（8:2）
    idx = np.random.permutation(len(X))
    n_val = max(1, int(len(X) * 0.2))
    val_idx, train_idx = idx[:n_val], idx[n_val:]

    X_train, y_train = X[train_idx], y[train_idx]
    X_val, y_val = X[val_idx], y[val_idx]

    X_train = torch.from_numpy(X_train).to(device)
    y_train = torch.from_numpy(y_train).to(device)
    X_val = torch.from_numpy(X_val).to(device)
    y_val = torch.from_numpy(y_val).to(device)

    model = KwsModel().to(device)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"模型参数量: {n_params} ({n_params * 4 / 1024:.1f} KB)")

    # 类别不均衡（负样本可能远多于正样本），给正样本更高权重
    pos_w = len(y_train) / (2 * (y_train == 1).sum().item())
    neg_w = len(y_train) / (2 * (y_train == 0).sum().item())
    class_weights = torch.tensor([neg_w, pos_w], device=device)

    criterion = nn.CrossEntropyLoss(weight=class_weights)
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)

    best_acc = 0.0
    for epoch in range(args.epochs):
        model.train()
        perm = torch.randperm(len(X_train), device=device)
        total_loss, total_correct = 0.0, 0
        for i in range(0, len(perm), args.batch_size):
            batch_idx = perm[i:i + args.batch_size]
            xb, yb = X_train[batch_idx], y_train[batch_idx]

            optimizer.zero_grad()
            logits = model(xb)
            loss = criterion(logits, yb)
            loss.backward()
            optimizer.step()

            total_loss += loss.item() * len(batch_idx)
            total_correct += (logits.argmax(1) == yb).sum().item()

        train_acc = total_correct / len(X_train)
        train_loss = total_loss / len(X_train)

        # 验证
        model.eval()
        with torch.no_grad():
            val_logits = model(X_val)
            val_loss = criterion(val_logits, y_val).item()
            val_acc = (val_logits.argmax(1) == y_val).float().mean().item()

        if val_acc > best_acc:
            best_acc = val_acc

        print(f"Epoch {epoch + 1}/{args.epochs} | "
              f"train_loss={train_loss:.4f} train_acc={train_acc:.3f} | "
              f"val_loss={val_loss:.4f} val_acc={val_acc:.3f}")

    print(f"\n最佳验证准确率: {best_acc:.3f}")

    # 导出 ONNX
    os.makedirs(args.output_dir, exist_ok=True)
    onnx_path = os.path.join(args.output_dir, "nexus_kws.onnx")
    model.eval()
    dummy = torch.randn(1, 1, N_FRAMES, N_MFCC, device=device)
    torch.onnx.export(
        model,
        dummy,
        onnx_path,
        input_names=["mfcc"],
        output_names=["logits"],
        dynamic_axes={"mfcc": {0: "batch"}},
        opset_version=13,
    )
    # torch 2.2+ 可能把权重外置成 onnx.data 文件（>2GB 才应外置，但新版有误判）。
    # 这里强制把外部数据合并回单个 onnx 文件，方便 Android onnxruntime 直接加载。
    _inline_onnx_weights(onnx_path)
    print(f"ONNX 已导出（权重已内嵌）: {onnx_path}")
    print("下一步：把 nexus_kws.onnx 拷贝到 Android 的 app/src/main/assets/ 目录")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pos_dir", default="data/positive", help="正样本（唤醒词）wav 目录")
    ap.add_argument("--neg_dir", default="data/negative", help="负样本（非唤醒词）wav 目录")
    ap.add_argument("--output_dir", default="output", help="ONNX 输出目录")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch_size", type=int, default=32)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()
    train(args)


if __name__ == "__main__":
    main()
