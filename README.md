# 宏月 LunaMacro

> **Android 全能自动化助手** —— 语音唤醒 · 离线视觉 · Shizuku 注入 · 游戏托管

一款本地优先的 Android 自动化助手。通过语音或文字下达指令，App 利用本地视觉模型识别屏幕元素、通过无障碍/Shizuku 注入触控，自动完成游戏托管、应用操作等任务，并内置训练数据采集体系。

---

## ⚠️ 免责声明（重要，请先阅读）

**本项目仅供学习交流使用，严禁用于任何商业用途。**

本软件可能涉及自动化操作、游戏托管等行为，请务必遵守相关平台的服务条款与法律法规。使用本软件产生的一切后果由使用者自行承担，作者不承担任何责任。

> 本项目代码以 GPL-3.0 协议开源，但「开源」不等于「授权商用」，更不等于「授权违规使用」。

---

## ✨ 核心特性

| 特性 | 说明 |
|---|---|
| 🎙️ 语音唤醒 | 本地离线唤醒词（sherpa-onnx KWS），可自定义、阈值可调 |
| 🧠 离线语音识别 | SenseVoice 本地识别，彻底摆脱云端 ASR |
| 👁️ 离线视觉识别 | Qwen2-VL-2B 本地模型（MNN 部署）识别屏幕元素与坐标 |
| 🖱️ 触控注入 | Shizuku / Stellar 系统级注入 + 无障碍服务双通道 |
| 🎮 游戏托管 | 云端规划 + 本地感知，自动执行游戏任务（原神/星穹铁道） |
| 🤖 18 个操作工具 | 点击/滑动/长按/打开应用/宏/游戏托管等 |
| 📊 数据采集 | 触摸采集（分步向导）+ 日常收集，为训练攒数据 |
| 🎨 极简几何 UI | 低饱和抽象色块风，Material 3 深度定制 |

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────┐
│                    用户交互层                          │
│  语音（唤醒词→ASR） / 文字输入 / 悬浮窗 / 运行灯        │
└────────────────────────┬────────────────────────────┘
                         │ 指令
┌────────────────────────▼────────────────────────────┐
│                   Agent 执行层                        │
│  Planner（规划）→ Executor（执行）→ AgentRunner（编排） │
│  18 个工具：点击/滑动/打开应用/宏/游戏托管…             │
└──────────────┬──────────────────────────┬───────────┘
               │                          │
    ┌──────────▼─────────┐     ┌──────────▼─────────┐
    │  感知层（视觉）      │     │  执行层（触控注入）  │
    │  本地 MNN 模型       │     │  Shizuku/Stellar    │
    │  Qwen2-VL-2B 检测    │     │  无障碍服务          │
    │  云端 Qwen-VL 兜底   │     │  悬浮窗             │
    └──────────┬─────────┘     └─────────────────────┘
               │
    ┌──────────▼─────────────────────────────────────┐
    │              数据 / 记忆层                        │
    │  会话历史 / 游戏知识库 / 采集数据集                │
    └─────────────────────────────────────────────────┘
```

**核心设计**：云端大模型负责「任务规划」，本地模型负责「画面元素检测」（识别按钮+坐标），各司其职。

---

## 🚀 编译与运行

### 环境要求

| 工具 | 版本 |
|---|---|
| Android Studio | Ladybug 及以上 |
| JDK | 17 |
| Android SDK | compileSdk 36 |
| NDK | 29.0.14206865（编译 JNI） |
| CMake | 3.30+ |
| Gradle | 8.13 |

### 编译步骤

1. 用 Android Studio 打开项目根目录
2. 等待 Gradle 同步完成
3. `Build → Make Project` 或命令行 `./gradlew assembleDebug`

> 注意：本地模型（Qwen2-VL-2B 1.3GB + SenseVoice 229MB）**不在仓库内**（体积过大）。如不需要本地模型，直接编译即可——运行时缺失会自动降级到云端。

### 配置 LLM API Key（首次运行必需）

App 的「规划/视觉识别兜底」需要云端大模型，支持：

- **DeepSeek**（推荐，成本低）
- **通义千问 DashScope**

首次启动会在引导页引导你填写，或进入「设置 → 配置 LLM」填写。API Key 通过 Android Keystore 加密存储，不会硬编码在代码里。

### 权限说明

| 权限 | 用途 |
|---|---|
| 无障碍服务 | 触控注入 |
| 悬浮窗 | 悬浮提示/运行灯 |
| 录音 | 语音唤醒/识别 |
| Shizuku/Stellar | 系统级触控注入（可选，非 root） |

---

## 📦 本地模型（可选，单独下载）

本地视觉/语音模型因体积过大未纳入仓库，如需启用：

| 模型 | 大小 | 放置目录 |
|---|---|---|
| Qwen2-VL-2B（MNN 4bit） | 1.3GB | `app/src/main/assets/models/` |
| SenseVoice INT8 | 229MB | `app/src/main/assets/sensevoice/` |

模型从 Release 页面单独下载（如未发布，请联系维护者）。

---

## 🤝 参与贡献

欢迎提 Issue 和 Pull Request！

- 国内更新或软件交流反馈也可进群QQ：1129513838
- 🐛 提 Bug → 描述清楚复现步骤 + 手机型号 + 日志
- 💡 提功能建议 → 说明使用场景
- 🔧 提交代码 → 见 [CONTRIBUTING.md](CONTRIBUTING.md)

**请务必阅读 [CONTRIBUTING.md](CONTRIBUTING.md) 后再提交 PR。**

---

## 🙏 致谢

本项目使用了以下优秀的开源项目，特此感谢：

- [Shizuku](https://github.com/RikkaApps/Shizuku) — 系统级 API 调用
- [Stellar](https://github.com/sunnysidesoundsok/Stellar) — Shizuku 的免 root 实现
- [MNN](https://github.com/alibaba/MNN) / [mnn-llm](https://github.com/wangzhaode/mnn-llm) — 端侧大模型推理
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 本地语音识别/唤醒
- [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) — 语音识别模型
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — UI 框架
- [Qwen](https://github.com/QwenLM) — 视觉语言模型

---

## 📄 许可证

本项目采用 [GPL-3.0](LICENSE) 许可证。

Copyright (c) 2026 LunaMacro

> 简单说：你可以自由使用、修改、再分发，但**分发修改后的版本必须同样以 GPL-3.0 开源**，不能拿去闭源卖钱。
