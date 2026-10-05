"""用 edge-tts 生成真人声级别的负样本（日常短语，非唤醒词「你好高总」）。

对比旧的 generate_data.py（纯噪音/音调/静音），本脚本生成的是 TTS 人声，
能让模型学到「你好高总 vs 其他说话」的区分，而不是「人声 vs 噪音」。

用法：python generate_negative_tts.py
产物：kws/data/negative/neg_XXXX.wav（16kHz 单声道，覆盖旧的合成负样本）
"""
import os
import asyncio
import subprocess

import edge_tts
import imageio_ffmpeg

SAMPLE_RATE = 16000
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "negative")
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()

# 音色（微软中文普通话 + 地方口音，增加多样性）
VOICES = [
    "zh-CN-XiaoxiaoNeural",        # 晓晓 女
    "zh-CN-YunxiNeural",           # 云希 男
    "zh-CN-YunyangNeural",         # 云扬 男
    "zh-CN-XiaoyiNeural",          # 晓伊 女
    "zh-CN-liaoning-XiaobeiNeural",# 晓北 东北女
    "zh-CN-shaanxi-XiaoniNeural",  # 晓妮 陕西女
]

# 日常短语（绝不能含「你好高总」，否则负样本被污染）
PHRASES = [
    "今天天气怎么样", "帮我打开设置", "播放一首歌", "看一下今天的新闻",
    "定一个闹钟", "给妈妈发个消息", "查一下附近的餐厅", "打开相机",
    "拍一张照片", "计算这个月的开销", "明天早上叫我起床", "打开蓝牙",
    "连接无线网络", "看一下邮件", "翻译这句话", "导航到最近的加油站",
    "搜索这个问题的答案", "播放轻音乐", "设置一个提醒", "打开手电筒",
    "看看明天的行程", "查一下股票", "记一个笔记", "打开地图",
    "搜索附近的电影院", "打电话给朋友", "发一条短信", "打开日历",
    "看看今天星期几", "设置一个倒计时", "打开录音机", "播放有声书",
    "查一下快递", "打开计算器", "看看手机还有多少电", "清理一下缓存",
    "打开文件管理器", "搜索一个视频", "打开浏览器", "读一段文章",
    "播放播客", "设置一个密码", "打开飞行模式", "看看手机内存",
    "搜索一个成语", "打开备忘录", "看看高铁票", "播放睡前故事",
    "设置一个番茄钟", "打开夜间模式", "看看今天走了多少步", "搜索一个菜谱",
    "打开钱包", "查一下航班", "播放相声", "看看汇率",
    "打开护眼模式", "搜索一个地点", "看看空气质量", "打开收音机",
]


async def gen_one(text: str, voice: str, mp3_path: str):
    c = edge_tts.Communicate(text, voice)
    await c.save(mp3_path)


def mp3_to_wav(mp3_path: str, wav_path: str):
    subprocess.run(
        [FFMPEG, "-y", "-loglevel", "error", "-i", mp3_path,
         "-ar", str(SAMPLE_RATE), "-ac", "1", wav_path],
        check=True,
    )


async def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    # 不清空旧样本（避免误删），从 neg_0080 开始编号，与旧合成噪音负样本并存
    idx = 80
    for phrase in PHRASES:
        # 每个短语用 3 个音色 → 约 180 条
        for voice in VOICES[:3]:
            mp3 = os.path.join(OUT_DIR, f"_tmp_{idx}.mp3")
            wav = os.path.join(OUT_DIR, f"neg_{idx:04d}.wav")
            try:
                await gen_one(phrase, voice, mp3)
                mp3_to_wav(mp3, wav)
                # 不删除临时 mp3（沙箱有批量删除保护；tmp mp3 不影响训练，train.py 只读 *.wav）
                idx += 1
            except Exception as e:
                print(f"  失败 [{voice}] {phrase}: {e}")
    print(f"\n生成负样本 {idx - 80} 条 → {OUT_DIR}")


if __name__ == "__main__":
    asyncio.run(main())
