# 触摸采集数据训练流程

把手机端触摸采集的数据，在 PC 端批量打标签并转换为 Qwen2-VL SFT 格式。

## 环境要求

- Python 3.10+
- dashscope SDK
- DASHSCOPE_API_KEY（DashScope 平台申请）

## 使用流程

### 1. 手机端采集

1. 打开「宏月」→ 游戏 Tab → 打开「触摸采集模式」
2. 悬浮窗显示「● 触摸采集中（N 张）」
3. 正常玩一遍游戏（原神等），每次点击会自动截图 + 记录坐标
4. 玩完关闭采集，导出 zip（`touch_dataset_{timestamp}.zip`）

### 2. PC 解压

把导出的 zip 解压到 `train/` 目录下，确保结构为：

```
train/
├── dataset/
│   ├── images/
│   │   └── manual_*.jpg
│   └── labels/
│       └── manual_*.json   # 含 annotated=false、touchX/Y、screenshot
├── annotate_dataset.py
├── convert_to_sft.py
└── README.md
```

### 3. 安装依赖

```bash
pip install dashscope
```

### 4. 设置 API Key

```bash
# Windows PowerShell
$env:DASHSCOPE_API_KEY="sk-xxx"

# Linux / macOS
export DASHSCOPE_API_KEY=sk-xxx
```

### 5. 批量标注

```bash
python annotate_dataset.py
```

- 对每张未标注的图调用 Qwen-VL-Max 识别界面类型 + 按钮
- 结果写回 `labels/*.json`（annotated=true + screenType + buttons）
- 支持断点续传（已标注的跳过）和 3 并发
- 输出汇总：总数、成功、失败、界面分布

### 6. 转换 SFT 格式

```bash
python convert_to_sft.py
```

得到：

```
train/output/
├── dataset.jsonl    # 每行一条 {image, conversations}
└── images/          # 对应截图
```

### 7. 后续

`train/output/dataset.jsonl` + `images/` 可直接用于 Qwen2-VL-1.5B 的 LoRA 微调。

## 已知限制

- getevent 需要 Shizuku shell 权限，不同设备的 event 节点不同（首次自动探测）
- 每次触摸都截图，2000 张约 200MB
- 云端 Qwen-VL 批量标注有 API 费用（2000 张约 20-50 元）
