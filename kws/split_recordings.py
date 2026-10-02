"""
把长录音按静音自动切分成单条样本
====================================
用户录制若干段「你好高总」（正样本）和若干段日常话（负样本），
本脚本按静音把它们切成单条 wav，归入 data/positive 和 data/negative，
供 train.py 直接训练。

用法：
  python split_recordings.py \
      --positive "rec_pos_1.wav" "rec_pos_2.wav" ... \
      --negative "rec_neg_1.wav" ... \
      --data_dir data

也可以先把录音文件放进 raw/positive/ 和 raw/negative/ 目录，
不传参数直接跑，脚本会自动扫这两个目录：
  python split_recordings.py

输出：
  data/positive/real_0000.wav ...
  data/negative/real_0000.wav ...

依赖：
  pip install numpy soundfile librosa
"""
import os
import glob
import argparse

import numpy as np

try:
    import soundfile as sf
except ImportError:
    raise SystemExit("请先 pip install soundfile")

try:
    import librosa
except ImportError:
    raise SystemExit("请先 pip install librosa")

SAMPLE_RATE = 16000
# 静音切分参数（可调）
TOP_DB = 40          # 低于均值 40dB 视为静音
MIN_SEG = 0.35       # 单条最短 0.35s，太短的丢弃（噪声碎点）
MAX_SEG = 1.6        # 单条最长 1.6s，超出会被截断/丢弃
MIN_SILENCE = 0.25   # 静音间隔至少 0.25s 才算切分点


def load_mono(path):
    """加载任意格式音频 -> 16kHz 单声道 float32。

    顺序：soundfile（wav/flac）→ librosa（需 ffmpeg 支持）→ imageio_ffmpeg 转码兜底。
    手机录音默认 m4a（AAC），soundfile/libsndfile 不认，最终靠 ffmpeg 转码。"""
    ext = os.path.splitext(path)[1].lower()
    # 压缩格式（m4a/aac/mp3/ogg 等）直接走 ffmpeg 转码，最稳
    if ext in (".m4a", ".aac", ".mp3", ".ogg", ".opus", ".m4b", ".wma"):
        return _load_via_ffmpeg(path)
    # wav/flac 等无损，优先 soundfile
    try:
        data, sr = sf.read(path, dtype="float32")
    except Exception:
        return _load_via_ffmpeg(path)
    if data.ndim > 1:
        data = data.mean(axis=1)
    if sr != SAMPLE_RATE:
        data = librosa.resample(data, orig_sr=sr, target_sr=SAMPLE_RATE)
    return data.astype(np.float32)


def _load_via_ffmpeg(path):
    """用 imageio_ffmpeg 自带的 ffmpeg 把任意音频转成 16kHz 单声道 wav 再读。"""
    import subprocess
    import tempfile
    try:
        import imageio_ffmpeg
        ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    except Exception as e:
        raise SystemExit(f"读取 {path} 需要 ffmpeg，请先 pip install imageio-ffmpeg：{e}")
    tmp = os.path.join(tempfile.gettempdir(), f"kws_tmp_{os.getpid()}.wav")
    r = subprocess.run([ffmpeg, "-y", "-i", path, "-ar", str(SAMPLE_RATE), "-ac", "1", tmp],
                       capture_output=True)
    if r.returncode != 0:
        raise SystemExit(f"ffmpeg 转码失败 {path}: {r.stderr.decode(errors='ignore')[-300:]}")
    data, sr = sf.read(tmp, dtype="float32")
    try:
        os.remove(tmp)
    except OSError:
        pass
    if data.ndim > 1:
        data = data.mean(axis=1)
    return data.astype(np.float32)


def split_audio(data):
    """按静音切分，返回若干片段（每段 float32 数组）。"""
    intervals = librosa.effects.split(
        data,
        top_db=TOP_DB,
        frame_length=int(SAMPLE_RATE * 0.025),
        hop_length=int(SAMPLE_RATE * 0.010),
    )
    segs = []
    for start, end in intervals:
        seg = data[start:end]
        dur = len(seg) / SAMPLE_RATE
        if dur < MIN_SEG:
            continue
        if dur > MAX_SEG:
            # 超长：取前 MAX_SEG 秒
            seg = seg[:int(MAX_SEG * SAMPLE_RATE)]
        segs.append(seg)
    return segs


def pad_to(seg, target_sr, target_ms=1000):
    """把片段 pad/truncate 到 target_ms，保持 16kHz。"""
    n = int(target_sr * target_ms / 1000)
    if len(seg) >= n:
        return seg[:n]
    return np.pad(seg, (0, n - len(seg)), mode="constant")


def process_recordings(files, out_dir, prefix):
    """把一批录音文件切分后写入 out_dir，返回切出的条数。"""
    os.makedirs(out_dir, exist_ok=True)
    total = 0
    for f in files:
        if not os.path.exists(f):
            print(f"  [跳过] 文件不存在: {f}")
            continue
        try:
            data = load_mono(f)
            segs = split_audio(data)
            for seg in segs:
                seg = pad_to(seg, SAMPLE_RATE)
                out = os.path.join(out_dir, f"{prefix}_{total:04d}.wav")
                sf.write(out, seg, SAMPLE_RATE)
                total += 1
            print(f"  {os.path.basename(f)} -> 切出 {len(segs)} 条")
        except Exception as e:
            print(f"  [失败] {f}: {e}")
    return total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--positive", nargs="*", default=None,
                    help="正样本（唤醒词）录音文件列表")
    ap.add_argument("--negative", nargs="*", default=None,
                    help="负样本（日常话）录音文件列表")
    ap.add_argument("--raw_dir", default="raw",
                    help="原始录音目录（含 positive/ negative/ 子目录）")
    ap.add_argument("--data_dir", default="data",
                    help="输出数据根目录")
    args = ap.parse_args()

    pos_dir = os.path.join(args.data_dir, "positive")
    neg_dir = os.path.join(args.data_dir, "negative")

    # 若未显式传文件，则扫描 raw/positive 和 raw/negative
    if args.positive is None and args.negative is None:
        args.positive = sorted(glob.glob(os.path.join(args.raw_dir, "positive", "*")))
        args.negative = sorted(glob.glob(os.path.join(args.raw_dir, "negative", "*")))
        if not args.positive and not args.negative:
            raise SystemExit(
                "未找到录音文件。请把录音放进 raw/positive/ 和 raw/negative/，"
                "或用 --positive/--negative 显式指定。"
            )

    print("=== 切分正样本（唤醒词）===")
    n_pos = process_recordings(args.positive or [], pos_dir, "real")

    print("=== 切分负样本（日常话）===")
    n_neg = process_recordings(args.negative or [], neg_dir, "real")

    print(f"\n切分完成：正样本 {n_pos} 条，负样本 {n_neg} 条")
    print(f"  正样本目录: {pos_dir}")
    print(f"  负样本目录: {neg_dir}")
    if n_pos < 50:
        print(f"  ⚠ 正样本只有 {n_pos} 条，偏少，建议至少 50~100 条（即多录几遍）。")
    print("下一步: python train.py")


if __name__ == "__main__":
    main()
