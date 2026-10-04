# 宏月 唤醒词模型训练

用本地 V100 训练一个极小的 KWS（关键词识别）模型，识别唤醒词「**你好高总**」，导出 ONNX 供 Android 端 `onnxruntime` 实时识别。

## 目录结构

```
kws/
├── generate_data.py    # 合成训练数据（TTS 正样本 + 噪音/其他词负样本）
├── split_recordings.py # 把长录音按静音切分成单条样本（真录音用）
├── train.py            # 训练 CNN + 导出 ONNX
├── requirements.txt    # 依赖
├── raw/                # 原始录音（positive/ negative/ 子目录）
├── data/               # 切分/生成后的数据（正/负样本 wav）
└── output/             # 训练产物 nexus_kws.onnx
```

## 在 V100 机器上的操作步骤

### 1. 装依赖

```bash
# 建议用独立虚拟环境
python -m venv venv
# Windows:
venv\Scripts\activate
# Linux:
# source venv/bin/activate

pip install -r requirements.txt
```

> torch 建议装对应 CUDA 的版本，例如 CUDA 12.x：
> `pip install torch --index-url https://download.pytorch.org/whl/cu121`

### 2. 生成数据

```bash
python generate_data.py --count 300
```

- 会用 edge-tts 合成「你好高总」正样本（需联网一次，合成多种音色）
- 负样本用噪音/音调/静音/其他短语
- 首次建议 `--count 100` 快速跑通，正式训练 `--count 500+`

### 3. 训练并导出 ONNX

```bash
python train.py
```

产物在 `output/nexus_kws.onnx`。

### 4. 把 ONNX 拷回笔记本

把 `output/nexus_kws.onnx` 拷贝到 Android 项目：
```
app/src/main/assets/nexus_kws.onnx
```

---

## 提升精度（用真录音重训，强烈推荐）

TTS 合成的样本和真人发音差距大，**必须补你自己的真录音重训**。用 `split_recordings.py` 按静音自动切分长录音。

### 录长录音（省事）

录 3~5 段，每段独立文件：
- 前 2~4 段：连续说「你好高总」30~50 遍，每遍之间停 1 秒
- 最后 1 段：说日常其他话（负样本），如「帮我打开设置」「今天天气怎么样」「播放音乐」，中间也停顿

放好文件：
```
raw/positive/   <- 唤醒词录音（m4a / wav 都行）
raw/negative/   <- 日常话录音
```

### 切分 + 重训

```bash
python split_recordings.py          # 自动扫 raw/ 目录，切分到 data/
python train.py                     # 用真录音（+ 合成样本）重训
```

> 切分参数（`TOP_DB`/`MIN_SEG`/`MAX_SEG`）在 `split_recordings.py` 顶部，切出来太碎或太整可调。

真录音越多，唤醒准确率越高、误唤醒越少。建议正样本凑够 100+ 条。

## 模型说明

| 项 | 值 |
|---|---|
| 输入 | 16kHz 单声道，40 维 MFCC，61 帧（1000ms） |
| 结构 | 3 层 Conv + BatchNorm + GlobalPool + FC |
| 参数量 | ~10 万，< 100KB |
| 输出 | 2 类 logits（唤醒 / 非唤醒） |
| 推理 | 手机 CPU 实时无压力 |
