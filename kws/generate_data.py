"""
合成唤醒词训练数据
==================
生成「你好高总」唤醒词的正样本（TTS 合成）和负样本（噪音/其他语音/静音）。

正样本来源（按优先级）：
  1. edge-tts（微软 TTS，需联网一次）——多种音色读「你好高总」，贴近真人发音
  2. 若无 edge-tts，退化为用 numpy 合成的简单音节（效果差，仅用于跑通链路）

负样本来源：
  - 白噪音 / 粉噪音
  - 随机音调
  - 其他短语（用 TTS 读「你好」「再见」「打开设置」等非唤醒词）
  - 静音

用法：
  python generate_data.py --count 200
  会在 data/positive/ 和 data/negative/ 下生成 wav 文件。

建议：
  - 先跑通链路用 --count 100 快速看效果
  - 正式训练用 --count 500+，且最好补真录音（见 README）
"""
import os
import argparse
import random

import numpy as np

try:
    import soundfile as sf
except ImportError:
    raise SystemExit("请先 pip install soundfile")

SAMPLE_RATE = 16000
WAKE_WORD = "你好高总"


def make_silence(duration=1.0):
    return np.zeros(int(SAMPLE_RATE * duration), dtype=np.float32)


def make_white_noise(duration=1.0, amp=0.3):
    return (np.random.randn(int(SAMPLE_RATE * duration)) * amp).astype(np.float32)


def make_pink_noise(duration=1.0, amp=0.3):
    n = int(SAMPLE_RATE * duration)
    white = np.random.randn(n)
    # 简单粉噪：累积积分
    pink = np.cumsum(white) / np.sqrt(np.arange(1, n + 1))
    pink = pink / (np.max(np.abs(pink)) + 1e-8)
    return (pink * amp).astype(np.float32)


def make_tone(duration=1.0, freq=440.0, amp=0.3):
    t = np.arange(int(SAMPLE_RATE * duration)) / SAMPLE_RATE
    return (np.sin(2 * np.pi * freq * t) * amp).astype(np.float32)


def make_synthetic_word(duration=1.0, amp=0.4):
    """合成一段伪语音（若干正弦片段拼接），仅用于无 TTS 时的兜底。"""
    t = np.arange(int(SAMPLE_RATE * duration)) / SAMPLE_RATE
    seg = np.zeros_like(t)
    n_seg = 5
    for i in range(n_seg):
        f = random.uniform(200, 800)
        start = int(len(t) * i / n_seg)
        end = int(len(t) * (i + 1) / n_seg)
        seg[start:end] = np.sin(2 * np.pi * f * t[start:end])
    # 加包络
    env = np.hanning(len(t))
    return (seg * env * amp).astype(np.float32)


def tts_available():
    try:
        import edge_tts  # noqa
        return True
    except ImportError:
        return False


def tts_to_wav(text, out_path, voice):
    """用 edge-tts 把文字合成到 wav。需要联网。"""
    import asyncio
    import edge_tts

    async def _run():
        communicate = edge_tts.Communicate(text, voice)
        await communicate.save(out_path)

    asyncio.run(_run())


def generate_positive_tts(out_dir, count):
    """用 edge-tts 多种音色合成「你好高总」正样本。"""
    if not tts_available():
        print("  未安装 edge-tts，回退到合成音节（精度低）")
        return generate_positive_synthetic(out_dir, count)

    voices = ["zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural", "zh-CN-YunyangNeural",
              "zh-CN-XiaoyiNeural", "zh-CN-YunjianNeural"]
    print(f"  使用 edge-tts，{len(voices)} 种音色")
    generated = 0
    for i in range(count):
        voice = voices[i % len(voices)]
        path = os.path.join(out_dir, f"pos_{i:04d}.wav")
        try:
            tts_to_wav(WAKE_WORD, path, voice)
            generated += 1
        except Exception as e:
            print(f"    TTS 失败 {path}: {e}")
            # 降级：合成音节
            sf.write(path, make_synthetic_word(), SAMPLE_RATE)
            generated += 1
    print(f"  正样本生成 {generated} 个")
    return generated


def generate_positive_synthetic(out_dir, count):
    generated = 0
    for i in range(count):
        path = os.path.join(out_dir, f"pos_{i:04d}.wav")
        sf.write(path, make_synthetic_word(duration=random.uniform(0.8, 1.2)), SAMPLE_RATE)
        generated += 1
    print(f"  正样本（合成音节）生成 {generated} 个")
    return generated


def generate_negative(out_dir, count):
    """生成负样本：噪音 + 音调 + 静音 + 其他短语（TTS）。"""
    neg_words = ["你好", "再见", "打开设置", "今天天气", "播放音乐", "谢谢", "关闭"]
    os.makedirs(out_dir, exist_ok=True)
    generated = 0
    i = 0
    while generated < count:
        path = os.path.join(out_dir, f"neg_{i:04d}.wav")
        kind = generated % 6
        try:
            if kind == 0:
                sf.write(path, make_white_noise(), SAMPLE_RATE)
            elif kind == 1:
                sf.write(path, make_pink_noise(), SAMPLE_RATE)
            elif kind == 2:
                sf.write(path, make_tone(freq=random.uniform(200, 1500)), SAMPLE_RATE)
            elif kind == 3:
                sf.write(path, make_silence(duration=random.uniform(0.8, 1.2)), SAMPLE_RATE)
            elif kind == 4:
                sf.write(path, make_synthetic_word(), SAMPLE_RATE)
            else:
                # 其他短语（TTS 若有）
                if tts_available():
                    word = random.choice(neg_words)
                    tts_to_wav(word, path, "zh-CN-XiaoxiaoNeural")
                else:
                    sf.write(path, make_synthetic_word(), SAMPLE_RATE)
            generated += 1
        except Exception as e:
            print(f"    负样本失败 {path}: {e}")
            sf.write(path, make_white_noise(), SAMPLE_RATE)
            generated += 1
        i += 1
    print(f"  负样本生成 {generated} 个")
    return generated


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--count", type=int, default=200, help="正样本数量（负样本会生成同量）")
    ap.add_argument("--data_dir", default="data", help="数据根目录")
    args = ap.parse_args()

    pos_dir = os.path.join(args.data_dir, "positive")
    neg_dir = os.path.join(args.data_dir, "negative")
    os.makedirs(pos_dir, exist_ok=True)
    os.makedirs(neg_dir, exist_ok=True)

    print("=== 生成正样本（唤醒词「你好高总」）===")
    generate_positive_tts(pos_dir, args.count)

    print("=== 生成负样本（非唤醒词）===")
    generate_negative(neg_dir, args.count)

    print("\n数据生成完成！")
    print(f"  正样本: {pos_dir} ({args.count} 个)")
    print(f"  负样本: {neg_dir} ({args.count} 个)")
    print("下一步: python train.py")


if __name__ == "__main__":
    main()
