# 宏月 LunaMacro 软件全体报告

> 生成日期：2026-10-24
> 版本：0.6.28（versionCode 38）
> 用途：软件整体分析

---

## 一、项目概述

| 项 | 内容 |
|---|---|
| 产品名 | 宏月（品牌英文名 LunaMacro） |
| 包名 | `com.nexus.agent` |
| 定位 | Android 全能自动化助手（语音 + 视觉 + 触控） |
| 当前版本 | 0.6.28（versionCode 38） |
| 平台 | Android（minSdk 24 / targetSdk 36 / compileSdk 36） |
| 开发语言 | Kotlin（108 文件 / 约 19604 行）+ C++（26 文件，JNI） |
| 版本控制 | Git（140 次提交，16 个 tag） |
| UI 框架 | Jetpack Compose（Material 3，已定制为低饱和几何色块风） |

**核心定位**：从「游戏自动化助手」升级为「全能助手」。用户通过语音/文字下达指令，App 通过视觉识别 + 触控注入自动完成任务，覆盖游戏、日常应用操作等场景。

---

## 二、版本演进史

| 阶段 | 版本 | 主要内容 |
|---|---|---|
| 起步 | 0.1.0 ~ 0.3.x | 基础框架、Qwen-VL 视觉接入、Shizuku、语音链路（录音+ASR+TTS） |
| 稳定 | 0.4.0 ~ 0.5.x | Stellar 替代 Shizuku、内嵌部署向导、多轮上下文、SafetyPolicy、语音状态机 |
| 采集/训练 | 0.6.0 ~ 0.6.21 | 触摸采集、分步向导、众包入口、断点续采、批次管理、标注脚本 |
| 模型本地化 | 0.6.22 ~ 0.6.24 | MNN 本地模型（1.3GB 打进 APK）、语音本地化（SenseVoice + sherpa KWS） |
| 全能助手 | 0.6.25 | 日常收集器 + Qwen3-20B 标注蒸馏 |
| UI/稳定性 | 0.6.26 ~ 0.6.28 | UI 大改版（几何色块风）、换图标、模型懒加载、语音助手修复 |

---

## 三、系统架构

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
    │  会话历史 SQLite / 游戏知识库 / 采集数据集          │
    └─────────────────────────────────────────────────┘
```

### 模块清单（按包划分）

| 模块 | 文件数 | 职责 |
|---|---|---|
| `agent/` + `agent/tools/` | 7 + 18 | Agent 编排 + 18 个操作工具 |
| `game/` | 20 | 游戏自动化、数据采集、本地识别 |
| `speech/` | 8 | 语音链路（KWS/ASR/TTS/状态机） |
| `ui/`（含 components/theme） | 6 + 2 + 2 | 5 个 Tab 页面 + 定制组件 + 主题 |
| `macro/` | 4 | 宏录制/回放 |
| `overlay/` | 2 | 悬浮窗（思考/采集/运行灯） |
| `accessibility/` | 2 | 无障碍服务 |
| `input/` | 2 | 输入注入（Shizuku） |
| `system/` | 4 | 开机自启、电池、系统工具 |
| `userservice/` | 2 | Shizuku 前台服务 |
| `audit/` | 2 | 任务审计日志 |
| `safety/` | 1 | 安全策略拦截 |

---

## 四、功能模块详解

### 4.1 五个主 Tab

| Tab | 功能 |
|---|---|
| 首页 | 权限状态总览（无障碍/悬浮窗/录音/Shizuku）、开始对话入口、日常收集开关 |
| 任务 | Agent 任务输入、执行日志、历史记录、审计导出 |
| 设置 | LLM 配置、ASR 配置、唤醒词自定义、权限管理、日志导出 |
| 宏 | 宏的录制/编辑/回放（点击/长按/滑动/等待/多点触控） |
| 游戏 | 游戏 Profile 配置、启动/停止、众包数据采集、标注、已支持游戏列表 |

### 4.2 语音助手（全链路离线）

```
唤醒词（sherpa KWS）→ 录音（自适应端点）→ ASR（SenseVoice 离线）
→ 指令澄清（多轮上下文）→ 执行（Agent）→ TTS 播报 → 连续对话/回待命
```

- **唤醒词**：sherpa-onnx KWS（zipformer 3.3M，中文），可自定义（4 个预设），阈值可调
- **ASR**：SenseVoice INT8（229M），完全离线，替换原云端 DashScope
- **状态机**：IDLE → LISTENING → TRANSCRIBING → CLARIFYING → EXECUTING → SPEAKING → FOLLOW_UP
- **特性**：连续对话、识别重试、执行打断、结果确认、前台服务保活

### 4.3 游戏自动化

- **规划**：云端大模型拆解任务步骤（Planner）
- **感知**：本地 MNN 模型（Qwen2-VL-2B）识别画面元素 + 坐标；失败降级云端 Qwen-VL
- **执行**：18 个工具（点击/滑动/打开应用/宏/游戏托管）
- **知识库**：内置原神（genshin.json）、星穹铁道（starrail.json）预设

### 4.4 数据采集（训练数据闭环）

| 模式 | 说明 |
|---|---|
| 触摸采集 | 分步向导、每步手动点开始、断点续采、累计 2000 张、批次管理 |
| 日常收集 | 后台静默截图、无上限、记录前台包名 |
| 标注 | 内置标注页 + PC 端批量标注脚本 + Qwen3-20B 自动蒸馏 |

### 4.5 本地模型（Qwen2-VL-2B，MNN 部署）

| 文件 | 大小 | 用途 |
|---|---|---|
| llm.mnn + weight | 921M | 语言模型（4bit） |
| visual.mnn + weight | 399M | 视觉塔（4bit） |
| tokenizer.mtok | 4.0M | 分词器 |
| config.json | — | 配置 |

- **方案 A**：1.3GB 模型打进 APK（APK 总 1.5GB），首次启动懒加载解压
- **注意**：模型已改懒加载（启动不加载，首次游戏托管识别才加载）

---

## 五、技术栈与依赖

| 类别 | 依赖 | 版本 |
|---|---|---|
| Shizuku | dev.rikka.shizuku:api / provider | 13.1.5 |
| UI | Jetpack Compose BOM | 2024.09.00 |
| Activity | activity-compose | 1.9.0 |
| 协程 | kotlinx-coroutines-android | 1.8.0 |
| 网络 | okhttp3 | 4.12.0 |
| JSON | moshi | 1.15.0 |
| AndroidX | core-ktx / lifecycle-runtime-ktx | 1.13.1 / 2.8.4 |

**本地推理引擎（jniLibs，arm64-v8a）**：

| 库 | 大小 | 用途 |
|---|---|---|
| libMNN.so + libMNN_Express.so + libllm.so | 75M | 本地视觉模型推理 |
| libsherpa-onnx-*.so | 9.6M | 本地语音（KWS + ASR） |
| libonnxruntime.so | 16M | sherpa-onnx 依赖 |

---

## 六、资源与体积构成

APK 总大小 **1.5GB**，构成：

| 组成 | 大小 | 说明 |
|---|---|---|
| 本地模型（assets/models） | 1.3G | Qwen2-VL-2B MNN 4bit |
| SenseVoice（assets/sensevoice） | 229M | 本地 ASR |
| KWS（assets/kws） | 4.6M | 唤醒词模型 |
| 推理库（jniLibs） | 100M | MNN + sherpa-onnx |
| 代码 + 资源 | 数 MB | Kotlin + Compose |
| 内嵌 Stellar（stellar.apk） | 4.8M | 部署向导 |

> ⚠️ **体积风险**：1.5GB APK 安装慢、分发难（QQ/GitHub/网盘都受限）。长期建议切「方案 B」——APK 瘦身到 ~75M，模型单独分发。

---

## 七、权限与系统组件

### 权限清单

| 权限 | 用途 |
|---|---|
| SYSTEM_ALERT_WINDOW | 悬浮窗 |
| RECORD_AUDIO | 语音唤醒/识别 |
| FOREGROUND_SERVICE + SPECIAL_USE | 前台服务保活 |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | 后台运行 |
| RECEIVE_BOOT_COMPLETED | 开机自启 |
| QUERY_ALL_PACKAGES | 应用检测 |
| INTERNET | 云端 LLM 调用 |
| POST_NOTIFICATIONS | 通知 |
| REQUEST_INSTALL_PACKAGES | Stellar 内嵌部署 |

### 系统组件

| 组件 | 职责 |
|---|---|
| MainActivity | 主界面（5 Tab） |
| OnboardingActivity | 引导页 |
| NexusForegroundService | 前台服务保活 |
| SelectToSpeakService | 无障碍服务（触控注入） |
| UserServiceImpl | Shizuku Binder 服务 |
| ShizukuProvider | Shizuku Provider |
| FileProvider | 数据分享 |
| BootReceiver | 开机自启 |

---

## 八、关键技术设计

### 8.1 云端规划 + 本地检测（新架构方向）

```
用户说「帮我做原神每日任务」
  → 云端大模型规划整体步骤
  → 循环执行：截图 → 本地模型识别「画面里有哪些按钮+坐标」→ 找目标 → 点击
```

**核心变化**：本地模型从「界面分类器」转为「元素检测器」——不管"这是什么界面"，只管"画面里有什么按钮、坐标多少"。云端专做规划，本地专做感知。

### 8.2 模型懒加载策略（稳定性关键）

- 本地视觉模型（1.3GB）和 SenseVoice（229M）均懒加载
- 启动/开启语音助手时不加载大模型，避免 OOM 闪退
- 首次真正使用时才加载，失败静默降级云端

### 8.3 本地化 / 离线化底线

- 语音识别：完全离线（SenseVoice），已移除云端 DashScope
- 视觉识别：本地 MNN 优先，云端 Qwen-VL 仅兜底
- 唯一云端依赖：任务规划用的 LLM（用户配置）

---

## 九、当前状态与风险

### 9.1 未完成 BUG（BUG.txt）

| # | 问题 | 状态 |
|---|---|---|
| 1 | 语音换方案已完成（sherpa KWS + SenseVoice，0.6.23），待真机验证效果 | 待验证 |
| 2 | 数据采集悬浮窗不显示 | 未解决 |

### 9.2 近期修复

| 版本 | 修复 |
|---|---|
| 0.6.27 | 启动闪退/慢（模型改懒加载）+ 换图标 |
| 0.6.28 | 开启语音助手崩溃（初始化移 IO 线程 + SenseVoice 懒加载） |

### 9.3 主要风险

| 风险 | 说明 | 建议 |
|---|---|---|
| APK 体积 1.5GB | 安装慢、分发难 | 切方案 B（APK 瘦身） |
| 本地模型精度「一般」 | 366 条数据天花板 | 攒 1000~2000 条重训 |
| visual.mnn 权重警告 | 训练机导出时有 onnx__MatMul 警告 | 需真机验证视觉识别 |
| 语音 native crash 可能 | sherpa-onnx 机型兼容性 | 崩溃需抓 logcat 定位 |
| vivo/iQOO 无障碍黑名单 | 被 force-stop 后需特殊恢复 | 已记录清除法 |

---

## 十、数据流总览

```
用户指令（语音/文字）
  ↓
唤醒/输入 → 语音状态机 / AgentRunner
  ↓
Planner（云端 LLM 拆解步骤）
  ↓
执行循环：截图 → 本地 MNN 检测元素 → Executor 执行工具 → 触控注入
  ↓
结果 → TTS 播报 / 悬浮窗反馈 → 审计日志落库
  ↓（采集模式）
截图 + 场景标签 → 本地数据集 → 众包回传 → 训练机标注 → 重训小模型
```

---

*报告完。本报告基于实际代码、git 历史与构建产物核验生成。*
