# 宏月 LunaMacro · 工程交接与体检报告

> **适用版本**：0.7.6（versionCode 55）
> **代码基线**：`013512a`（master）
> **生成时间**：2026-10-30 23:45
> **文档读者**：接手的 AI / 人类开发者 —— 目的是让你在不读完整仓 2.6 万行代码的前提下，能直接规划并实施后续工作。

---

## 目录

| 章 | 内容 | 谁该读 |
|---|---|---|
| 0 | 怎么读这份文档 | 所有人，先读 |
| 1 | 项目定位与边界 | 想理解"这软件到底干嘛" |
| 2 | 一分钟速览 | 想快速建立全局印象 |
| 3 | 构建环境与命令 | 要动手编译/发版的人 |
| 4 | 代码地图 | 要改代码的人 |
| 5 | 运行时结构 | 要理解组件关系的人 |
| 6 | 核心链路详解 | 要改功能/修链路 bug 的人 |
| 7 | 关键契约与数据格式 | 要接新工具/新模型的人 |
| 8 | 存储与数据 | 要排查数据问题的人 |
| 9 | 权限与部署 | 要处理"用不了"反馈的人 |
| 10 | 发布流程 | 要发版的人 |
| 11 | 体检结果 | 要规划技术债的人 |
| 12 | 未验证项与待办 | 要接续工作的人 |
| 13 | 规划建议与排障手册 | **规划后续流程的 AI 重点看这章** |
| 14 | 术语表 | 遇到看不懂的缩写 |

---

## 0. 怎么读这份文档

**如果你的任务是"规划后续流程"**，按这个顺序读：

1. 第 2 章速览 —— 建立事实基线（20 个关键数字）
2. 第 11 章体检 —— 知道哪里有病、病多重
3. 第 13.1 章硬约束 —— **这些是雷区，不知道就会踩**
4. 第 13.2~13.3 章优先级与路线 —— 直接给你排好序的选项
5. 需要动具体功能时，再回查第 6 章的对应链路

**如果你要直接改代码**，还必须读第 3 章（否则编不出来）和第 4 章（否则找不到地方）。

> ⚠️ 本仓根目录已有若干历史文档（`PROJECT_STATUS.md`、`LunaMacro软件全体报告.md` 等），其中 **`PROJECT_STATUS.md` 已严重过期**（写的是 0.6.2 / 12,000 行），不要引用它的数据。以本文档为准。

---

## 1. 项目定位与边界

### 1.1 一句话定义

**一个本地优先（local-first）的 Android 全能自动化助手**：用户用语音或文字下指令，App 通过本地视觉模型理解屏幕、通过无障碍服务或 Shizuku 注入触控，替用户完成应用操作、游戏托管等任务；全链路除"大模型对话"外不依赖云端。

品牌：中文「宏月」，英文 `LunaMacro`。
包名：`com.nexus.agent`（**历史原因保持不改**，类名仍是 `Nexus*`，不要因为改名去重构包结构）。

### 1.2 功能边界：做什么 / 不做什么

| 做 | 说明 |
|---|---|
| 语音唤醒 + 离线识别 | 本地 KWS（sherpa-onnx zipformer）+ 本地 ASR（SenseVoice） |
| 屏幕理解 | 本地 Qwen2-VL-2B（MNN）识别屏幕内容与坐标 |
| 语义化操作 | 15 个工具（点击文本/ID、打开应用、滑动、长按、拖拽、多指、宏回放…） |
| 多步任务 | 云端 Planner 拆子目标 + 本地 Executor 自主循环 |
| 副驾驶 | 悬浮灵动岛，随时提问、看屏回答、代操作 |
| 游戏托管 | 规则引擎 + 状态机 + 知识库（原神 / 星穹铁道） |
| 数据采集 | 触控采集、场景标注、数据集导出（供本地模型微调） |
| 宏录制回放 | 录制动作序列并回放 |

| 不做 / 明确拒绝 | 原因 |
|---|---|
| 全云端方案（截屏上传 + 云端 Agent） | 用户硬性要求隐私与离线闭环 |
| 云端 ASR 作为主路径 | 仅在本地模型缺失时可作为回退，不作为默认 |
| 商业用途 / 破解 / 外挂式作弊 | 免责声明与许可约束 |
| 支付类界面代操作 | `SafetyPolicy` 硬拦截（见 6.3） |

### 1.3 与云端的真实关系（重要，别搞错）

虽然定位是"本地优先"，但**用户明确接受了两个云端例外**：

1. **大模型对话 / 规划**：云端 OpenAI 兼容 API（DeepSeek、Qwen 等，用户自带 Key）。本地 2B 模型能力不足以做规划，所以 `Planner` 依赖云端。**主 provider 不支持视觉时降级纯文本**，且必须显式告知用户"本轮未读取屏幕"。
2. **模型包下载**：1.3GB 视觉模型 + 228MB 语音模型通过 GitHub Release + 国内镜像分发。

除这两点，所有识别、注入、执行都是本地完成。

### 1.4 合规与免责（改代码时别删）

- 首启有**免责声明强制确认**（`ui/CopilotDisclaimerDialog.kt`，35 秒倒计时不可跳过），文案必须含三条：读屏隐私 / AI 内容免责 / 禁作弊与支付。
- `README.md` 与 `LICENSE`（GPL-3.0）已声明"开源≠授权商用"。
- 涉及自动化的功能（游戏托管）在 App 内需用户自行承担风险提示。

---

## 2. 一分钟速览

| 维度 | 事实 |
|---|---|
| 版本 | `0.7.6` / versionCode `55` |
| SDK | compileSdk 36 · targetSdk 36 · minSdk 24 |
| 语言/UI | Kotlin 2.0.21 · Jetpack Compose（Material3） |
| 构建 | AGP 8.7.3 · Gradle 8.13 · JDK 17 |
| 自有代码 | **22,656 行 / 103 个 .kt**（另有 sherpa 封装 4,288 行不许动） |
| 包结构 | 16 个功能包 + 根文件 |
| 最大单文件 | `ui/MainActivity.kt` 2,037 行（上帝类） |
| 后台常驻 | 1 个前台服务 + 1 个无障碍服务 + 1 个开机接收器 |
| 权限 | 12 项（含 1 项无效声明） |
| 工具数 | 15 个语义化工具 |
| 本地模型 | 视觉 Qwen2-VL-2B（约 1.3GB）+ 语音 SenseVoice（约 229MB） |
| APK | 46,513,940 B ≈ 44.4 MiB（瘦身版，不含模型） |
| 模型分发 | GitHub Release `models` tag + ghfast.top 镜像 |
| 自动更新 | 拉 `v*` tag Release 的 APK 资产 |
| 公告 | `announcement.json` 热更新，改文件即生效 |
| 提交历史 | 186 commits / 24 条 CHANGELOG |
| 自动化测试 | **0**（无 test / androidTest 目录） |
| 崩溃上报 | **无**（无 `UncaughtExceptionHandler`） |
| 构建状态 | ✅ 当前可编译（`compileDebugKotlin` 0 错误） |

---

## 3. 构建环境与命令

### 3.1 版本矩阵

| 组件 | 版本 | 备注 |
|---|---|---|
| compileSdk / targetSdk | 36 | Android 16 |
| minSdk | 24 | Android 7.0 |
| AGP | 8.7.3 | 定义在根 `build.gradle.kts` |
| Kotlin | 2.0.21 | 含 `kotlin.plugin.compose` |
| Gradle | 8.13 | 本机缓存在 `~/.gradle/wrapper/dists/gradle-8.13-bin/...` |
| JDK | **17** | `.jdks/jbr-17.0.14` |
| Compose BOM | 2024.09.00 | ⚠️ 相对 2026-10 已落后约两年 |
| Shizuku API | 13.1.5 | `dev.rikka.shizuku:api` + `provider` |
| OkHttp | 4.12.0 | LLM / 下载 |
| Moshi | 1.15.0 | JSON |
| Coroutines | 1.8.0 | |

### 3.2 环境变量（**最容易卡住的地方**）

```bash
# 必须两个都设：JAVA_HOME 用 Windows 风格，PATH 用 unix 风格
export JAVA_HOME="C:\Users\25814\.jdks\jbr-17.0.14"
export PATH="/c/Users/25814/.jdks/jbr-17.0.14/bin:$PATH"
```

**踩过的三个坑：**

1. `JAVA_HOME` 重启后可能为空 → 编译卡住无输出。
2. Android Studio 自带 JBR 已升级到 **JDK 25**，Gradle 8.13 **不认**，报错只有莫名其妙的一行 `* What went wrong: 25.0.3`。
3. 只设 `JAVA_HOME` 不设 `PATH` → `java: command not found` / `apksigner` 无输出。

备用 JDK：`C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot`、`C:\Program Files\Zulu\zulu-17`。

### 3.3 常用命令

Gradle 不在 PATH 里，用绝对路径：

```bash
GRADLE=/c/Users/25814/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle.bat

# 只查语法（快，约 1 分钟）
$GRADLE :app:compileDebugKotlin --offline -q

# 打 debug 包
$GRADLE assembleDebug --offline

# 打 release 包（需先做瘦身，见 3.4）
$GRADLE assembleRelease --offline
```

一律加 `--offline`：本机依赖已缓存，联网反而会因为镜像拉不到而失败（lint-gradle 尤其）。

### 3.4 瘦身构建（**必须严格遵守，否则打出 1.5GB 的包**）

`app/src/main/assets/` 里有 1.6GB 模型，打 release 前**必须物理移出**（改后缀 `.bak` 没用，照样打包）：

```bash
cd NexusAgent

# 1) 移出（移到 app/ 下，不能留在 assets 里）
mv app/src/main/assets/models      app/_hold_models
mv app/src/main/assets/sensevoice  app/_hold_sensevoice

# 2) 构建
$GRADLE assembleRelease --offline

# 3) 立刻移回（忘了移回，下次构建会缺模型；git 状态也会脏）
mv app/_hold_models      app/src/main/assets/models
mv app/_hold_sensevoice  app/src/main/assets/sensevoice
```

产物：`app/build/outputs/apk/release/app-release.apk`（约 46MB）。

### 3.5 签名与产物校验

| 项 | 值 |
|---|---|
| Keystore | `nexus-release.keystore`（项目根，**已 gitignore**） |
| 密码文件 | `keystore.properties`（**已 gitignore**） |
| alias | `nexus` |
| 证书 | `CN=Nexus Agent` |
| 签名方案 | v2 |

校验命令：

```bash
BT="$HOME/AppData/Local/Android/Sdk/build-tools/37.0.0"
"$BT/aapt2.exe" dump badging app/build/outputs/apk/release/app-release.apk | head -1
"$BT/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

### 3.6 磁盘体积分布（决定构建速度）

| 目录 | 体积 | 是否入 git |
|---|---|---|
| `app/src/main/assets/models` | 1.3 GB | ❌ 否 |
| `app/src/main/assets/sensevoice` | 229 MB | ❌ 否 |
| `app/src/main/jniLibs` | 100 MB | ✅ 是（so 必须入库） |
| `app/src/main/assets/kws` | 4.6 MB | ✅ 是 |
| `app/src/main/assets/stellar.apk` | 4.7 MB | ✅ 是 |
| `app/src/main/res` | 142 KB | ✅ 是 |
| `.git` | 38 MB | — |

---

## 4. 代码地图

### 4.1 包结构总表

包根：`app/src/main/java/com/nexus/agent/`

| 包 | 行数 | 文件数 | 职责 | 关键文件 |
|---|---|---|---|---|
| `ui/` | 5,421 | 15 | 所有界面（Compose） | `MainActivity.kt`(2037)、`GameTab.kt`(630)、`MacroTab.kt`(485, 死代码)、`CopilotTab.kt`(305) |
| `agent/` | 4,543 | 27 | 云端规划 + 本地执行 + 工具层 + 状态机 | `Executor.kt`(804)、`Planner.kt`(323)、`AgentRunner.kt`(292)、`tools/*` |
| `game/` | 3,098 | 22 | 游戏自动化 + 本地视觉 + 模型管理 | `ModelManager.kt`(603)、`LocalScreenRecognizer.kt`(323)、`TouchDatasetCollector.kt`(379) |
| `overlay/` | 1,997 | 3 | 悬浮窗 | `ThinkingOverlay.kt`(1017)、`CopilotIslandOverlay.kt`(742)、`RunningIndicator.kt`(238) |
| `speech/` | 1,674 | 8 | 唤醒 + 录音 + 识别 + TTS | `VoiceAssistant.kt`(690)、`LocalKeywordSpotter.kt`(287)、`VoiceCommandRecorder.kt`(276) |
| `copilot/` | 1,491 | 5 | 副驾驶大脑 | `CopilotEngine.kt`(697)、`CopilotVision.kt`(381)、`CopilotIntent.kt`(252) |
| `input/` | 972 | 2 | Shizuku / Stellar 注入 | `ShizukuManager.kt`(528)、`ShizukuInputInjector.kt`(444) |
| `system/` | 725 | 4 | 开机、稳定性、日志导出、Stellar 安装 | `StabilityHelper.kt`(450)、`LogExporter.kt`(145) |
| `util/` | 688 | 4 | 公告、更新、JSON | `AppUpdater.kt`(283)、`AnnouncementChecker.kt`(231) |
| `macro/` | 594 | 4 | 宏录制回放 | `MacroRecorder.kt`(239)、`MacroPlayer.kt`(218) |
| `accessibility/` | 453 | 2 | 无障碍服务 + UI 树解析 | `NexusAccessibilityService.kt`(293)、`UiTreeParser.kt`(160) |
| `audit/` | 426 | 2 | 任务审计落盘 | `AuditLogger.kt`(391) |
| `userservice/` | 312 | 2 | 前台服务 + Shizuku UserService | `NexusForegroundService.kt`(204) |
| `safety/` | 190 | 1 | 安全策略三道闸 | `SafetyPolicy.kt`(190) |
| `com/k2fsa/sherpa/onnx/` | 4,288 | 6 | **三方封装，禁止修改** | 见 4.3 |

另有根文件 `NexusApplication.kt`(72)。

### 4.2 必读的 12 个文件（读完能懂 80%）

| 序 | 文件 | 为什么读 |
|---|---|---|
| 1 | `ui/MainActivity.kt` | 所有入口：Tab、权限申请、语音/副驾驶接线。**但别一口气读完 2037 行**，按第 6 章链路跳读 |
| 2 | `copilot/CopilotEngine.kt` | 副驾驶唯一入口 `ask()`，所有对话/动作/任务都从这里进 |
| 3 | `copilot/CopilotVision.kt` | 模型选择与降级逻辑（本地/云端/provider 回退都在这里） |
| 4 | `copilot/CopilotIntent.kt` | 三级意图解析（JSON → 正则 → 当聊天） |
| 5 | `agent/AgentRunner.kt` | 多步任务主循环：Planner → Executor → 重规划 |
| 6 | `agent/Executor.kt` | 子目标内的自主执行循环（含截图、工具调用、状态机） |
| 7 | `agent/tools/ToolRegistry.kt` | 15 个工具的唯一注册点，加工具只改这里 |
| 8 | `speech/VoiceAssistant.kt` | 语音状态机与全流程，坑最多的地方 |
| 9 | `speech/LocalKeywordSpotter.kt` | 唤醒实现，sherpa API 用法极敏感 |
| 10 | `game/ModelManager.kt` | 模型下载/导入/校验，用户反馈"装不上"都在这 |
| 11 | `safety/SafetyPolicy.kt` | 三道安全闸，改执行链必看 |
| 12 | `userservice/NexusForegroundService.kt` | 后台存活与麦克风权限的类型声明 |

### 4.3 三方代码：`com/k2fsa/sherpa/onnx/`（**绝对不要改**）

| 文件 | 行数 |
|---|---|
| `OfflineRecognizer.kt` | 1,544 |
| `OnlineRecognizer.kt` | 808 |
| `Tts.kt` | 497 |
| 其他 | 约 1,400 |

**为什么不能改：** 这套 Kotlin 封装必须与 `jniLibs` 里的 `libsherpa-onnx-jni.so` **版本严格对齐**。当前 so 是 **v1.12.11**。历史上曾从更新版本拷入封装，导致 `getResult` 声明返回对象而 native 实际返回 `Object[]` → Java 层对数组虚派发 → **SIGSEGV 随机杀进程**（崩溃栈落在 RenderThread/hwuiTask，极难定位）。

**核对方法：**

```bash
grep -ao "Java_com_k2fsa_sherpa_onnx_[A-Za-z]*_[A-Za-z]*" app/src/main/jniLibs/*/libsherpa-onnx-jni.so
```

**v1.12.11 的返回值约定（写代码时按这个用）：**

| API | 返回 |
|---|---|
| `KeywordSpotter.getResult` | `Object[3]` = keyword / tokens / timestamps |
| `OfflineRecognizer.getResult` | `Object[7]` = text / tokens / timestamps / lang / emotion / event / durations |
| `OnlineRecognizer.getResult` | `Object[3]` |

---

## 5. 运行时结构

### 5.1 组件清单

| 类型 | 类 | exported | 职责 |
|---|---|---|---|
| Application | `.NexusApplication` | true | 初始化、通道、全局兜底 |
| Activity | `.ui.MainActivity` | true | 主界面（4 Tab）+ 权限 + 语音接线 |
| Activity | `.ui.OnboardingActivity` | false | 首启引导 |
| Service | `.userservice.NexusForegroundService` | **false** | 常驻前台服务，`specialUse\|microphone` |
| Service | `.accessibility.NexusAccessibilityService` | true | 无障碍：读 UI 树、点击、截图 |
| Service | `.userservice.UserServiceImpl` | true | Shizuku UserService（系统权限执行端） |
| Provider | `rikka.shizuku.ShizukuProvider` | true | Shizuku 权限桥 |
| Receiver | `.system.BootReceiver` | true | 开机 / 应用更新后重启前台服务（白名单 3 个 action） |

### 5.2 进程内常驻对象（单例全景，共 74 个 `object`）

```
NexusApplication
 ├─ NexusForegroundService ── 通知栏常驻，保持进程优先级
 ├─ VoiceAssistant ── 语音状态机（KWS 常驻麦克风）
 │    ├─ LocalKeywordSpotter ── sherpa KWS + AudioRecord
 │    ├─ VoiceCommandRecorder ── 录音（静音端点）
 │    ├─ LocalAsr ── SenseVoice 识别
 │    └─ TtsSpeaker ── 播报
 ├─ CopilotEngine ── 副驾驶大脑（busy 锁 + 协程）
 │    ├─ CopilotVision ── 本地/云端模型选择
 │    ├─ CopilotIntentParser ── 意图解析
 │    ├─ CopilotIslandOverlay ── 灵动岛 UI（回调靠静态字段接线）
 │    └─ AgentRunner ── 多步任务
 │         ├─ Planner（云端）
 │         ├─ Executor（本地循环）
 │         ├─ TaskStateMachine ── 状态机
 │         └─ ToolRegistry ── 15 个工具
 ├─ ShizukuManager / ShizukuInputInjector ── 系统级注入
 ├─ NexusAccessibilityService ── 无障碍通道
 ├─ ModelManager ── 模型下载/导入
 ├─ LocalScreenRecognizer ── 本地视觉推理（Mutex 串行）
 └─ AuditLogger / StabilityHelper / AnnouncementChecker / AppUpdater
```

### 5.3 四层能力底座

| 层 | 实现 | 缺失时的表现 |
|---|---|---|
| **读**（看屏幕） | 无障碍 `UiTreeParser` 拿控件树 + `ScreenCapture` 拿位图 | 无法截图 → 副驾驶降级纯文本；Agent 无法决策 |
| **写**（点屏幕） | ① Shizuku/Stellar 注入 ② 无障碍 `performAction` | 两个都没 → 只说不做 |
| **听**（语音输入） | KWS 唤醒 + SenseVoice 识别 | 唤醒无反应 / 识别为空 |
| **想**（决策） | 本地 Qwen2-VL-2B（快、弱） + 云端 LLM（慢、强） | 全无 → 只会回"模型没接上" |

### 5.4 冷启动时序

```
进程启动
 → NexusApplication.onCreate：建通知渠道、初始化 Shizuku 上下文
 → MainActivity.onCreate/onResume
     ├─ CopilotEngine.bindIsland(this)        ← 必须调，否则灵动岛是哑的
     ├─ CopilotEngine.start(this)             ← 若配置开启陪玩
     ├─ VoiceAssistant.autoRestore(this)      ← 若上次是开启状态
     └─ AnnouncementChecker.fetchIfNeeded()   ← 每天首次拉公告
 → NexusForegroundService 常驻
 → 用户开启唤醒 → VoiceAssistant.start(ctx) → 拉起前台服务(升级 serviceType) → KWS 监听
```

---

## 6. 核心链路详解

### 6.1 语音链路（唤醒 → 播报）

#### 状态机

`speech/VoiceState.kt` 定义了 8 个状态，由 `VoiceAssistant.transitionTo()` 校验合法性：

| 状态 | 含义 | 出口 |
|---|---|---|
| `IDLE` | 待机，KWS 监听中 | 唤醒词 / 手动点击 |
| `LISTENING` | 录音中 | 静音端点 / 超时 |
| `TRANSCRIBING` | ASR 识别中 | 识别成功 / 重试 |
| `CLARIFYING` | 指代澄清 | 澄清完成 |
| `EXECUTING` | Agent 执行中 | `reportExecuteResult()` |
| `SPEAKING` | TTS 播报中 | 播完 |
| `FOLLOW_UP` | 续听窗口 | 超时回 IDLE |
| `ERROR` | 错误 | 复位 |

#### 完整流程（8 步）

```
① KWS 检测到唤醒词（LocalKeywordSpotter.onWakeWordDetected）
      ↓ 回调 IO 线程
② VoiceAssistant.handleWakeWord()
      ├─ 状态必须是 IDLE，否则忽略（防重复唤醒）
      ├─ ❗先检查 LocalAsr.isModelAvailable()，缺模型直接播报"去导入模型"
      ├─ wakeWordDetector.pause()   ← 只放麦克风，保留 zipformer 模型
      └─ 立刻 startRecording()（零空窗，不等提示音）
③ 录音：VoiceCommandRecorder，静音端点 / 超时自动结束
      ↓ onPcmReady(pcm, reason)
④ 丢弃语义：suppressPcm 为真（用户点了取消）→ 直接 return
⑤ 本地识别：LocalAsr.recognize(FloatArray) → SenseVoice
      ├─ 空 / 少于 2 字 → handleRecognizeRetry()（最多 2 次）
      └─ ❗再查一次 isModelAvailable()（运行中可能被删）
⑥ 确认显示（可配开关 + 时长）→ 指代澄清 clarifyIfNeeded()
⑦ 下发：onCommand?.invoke(text) → MainActivity 转给 CopilotEngine.ask()
      └─ 兜底：30 秒没收到 reportExecuteResult → 强制 restartWake()
⑧ 播报：CopilotEngine 的 onDone → VoiceAssistant.reportExecuteResult()
      → TtsSpeaker 播报 → 恢复 KWS 监听
```

#### 五个必须知道的坑

| # | 坑 | 后果 | 现状 |
|---|---|---|---|
| 1 | 前台服务缺 `microphone` 类型 | 后台麦克风读到全 0，唤醒永不触发；App 前台却正常 | **0.7.6 已修**（`specialUse\|microphone`） |
| 2 | 唤醒用 `stop()` 而非 `pause()` | 每次唤醒重载 zipformer 1~3 秒，期间喊话无反应 | 已修 |
| 3 | 取消后 `stop()` 仍触发 `onPcmReady` | 半句话被当完整指令执行 | 已修（`suppressPcm`） |
| 4 | **无条件 `decode()`** | `features.cc` 里 `frame_index + n > NumFramesReady()` → `SHERPA_ONNX_EXIT(-1)` → **直接 exit() 杀进程**，`try/catch` 拦不住 | 已修，必须 `while (isReady) { decode }` |
| 5 | 只喂本次读到的样本数 | 残留样本导致帧数算错 | 已修（`read < buffer.size` 时 `copyOf(read)`） |

> 唤醒词默认「你好高总」，可配置：`speech/WakeWordConfig.kt`（prefs `voice_assistant`）。

### 6.2 副驾驶链路（看 → 想 → 说/做）

入口唯一：`CopilotEngine.ask(context, text, onReply, onDone)`。

```
① 门禁：isBusy 为真 → 直接回"我还在忙，点一下灵动岛可以中断"
② 取前台包名 → 判断是否敏感应用（支付宝/银行/微信支付等）
      └─ 敏感：不截图（隐私熔断优先于"看得见"）
      └─ 非敏感：ScreenCapture.captureBitmap(900 长边) → JPEG q78 → Base64
③ 装配三个提示词
      ├─ systemPrompt（云端）：含 15 工具 JSON Schema + 前台应用名
      ├─ localPrompt（本地视觉）：工具一行一条精简清单
      └─ localTextPrompt（本地纯文本）：无图时用
④ 超时预算：模型调用阶段包 withTimeoutOrNull
      ├─ 本地 LOADING 中 → 180 秒（装载 1.3GB 不算"网络慢"）
      └─ 其他 → 90 秒
⑤ CopilotVision.ask() 决策：
      本地 Qwen2-VL 可用 → 本地（快）
      ↓ 失败/不可用
      视觉 provider 有 Key → 用它
      ↓ 没有
      主 provider 有 Key → 纯文本（必须带 TEXT_ONLY_NOTICE）
      ↓ 没有
      返回失败 + 四分类错误（NoKey / AuthError 401 / NetworkError / EmptyReply）
⑥ 意图解析：CopilotIntentParser.parse()  三级兜底
      严格 JSON（容忍 ```json 围栏）→ 正则识别动作 → 整段当聊天
⑦ 本地输出像聊天但用户明显要动作 → 升云端再问一次（looksLikeActionRequest）
⑧ 分派：
      Chat   → 只说话
      Action → 先说话，再 executeAction()（过 SafetyPolicy 三道闸），再播报结果
      Task   → 先说话，再 runAgentTask() 走 AgentRunner
⑨ finally：回收 Bitmap、清 isBusy、endThinking、onDone 回调
```

#### Provider 回退矩阵

| 场景 | 行为 | 用户看到 |
|---|---|---|
| 本地模型 READY + 云端有 Key | **本地优先** | 正常回答 |
| 本地没下 / 加载失败 + 主 provider 支持视觉 | 云端图文 | 正常回答 |
| 本地没下 + 主 provider 不支持视觉（如 DeepSeek） | **云端纯文本** | 回答 + "（注意：当前模型不支持视觉，本轮未读取屏幕，仅纯文本回复）" |
| 全都没有 | 失败 | 按四态分别说人话（"本地模型没下载" / "Key 没配" / "授权失败 401" / "网络不通"） |
| 敏感界面 | 不截图 | "这个界面比较敏感，我就不看了" |

#### 一条历史教训（改这块前必须知道）

0.7.4 之前的 P0 根因：`CopilotVision.askCloud` 硬读 `qwen-vl_api_key`，而设置页只写 `${主provider}_api_key` → Key 判空 → **HTTP 请求压根没构造**，上层却报"都没接上"。

> **排查铁律：凡是"用户说配了却没用"，先查 key 名字是否对得上写入点，再查是否 `isBlank()` 直接 return 把异常吞成一句话。**

### 6.3 Agent 多步任务链路

三层架构（`AGENT_ARCH_V2.md` 有更详细设计）：

```
用户任务文本
   ↓
AgentRunner.runTask()  ← 返回 Flow<LogEvent>，逐条吐进度
   ├─ 闸 0：SafetyPolicy.checkTaskText()  ← 文本级硬拦截
   ├─ 挂状态机：TaskStateMachine.attach(ctx, fgPkg, task)  （匹配不到则旁路）
   ├─ observeInitial()  拿初始屏幕状态
   ├─ Planner.planSubGoals()  ← 云端只出 1~5 个子目标（"要什么"，不说"怎么点"）
   └─ 逐个子目标循环：
        Executor.execute(step, subGoal)
          ├─ 每次循环：observe() 截图 + UI 树 → 拼思考内容 → 问模型下一步
          ├─ parseToolCall() → 校验工具名 → SafetyPolicy.checkCopilotAction() → executeTool()
          ├─ 状态机判定：识别当前状态、白名单校验、stuck 检测
          └─ 返回 SUCCESS / FAILED / NEED_REPLAN
        SUCCESS → 下一个子目标
        FAILED  → 配置类错误（LLM/API Key）直接中止；否则 replan（上限 MAX_REPLANS）
   ↓
终止条件：全部完成 / 全局动作数超限 / 重规划超限 / 被中断
```

**三道安全闸**（`safety/SafetyPolicy.kt`）：

| 闸 | 时机 | 拦什么 |
|---|---|---|
| 任务文本 | 任务开始前 | 明显违规请求（刷单、支付、破解） |
| 敏感界面 | 每次动作前 | 前台是支付/银行类 → 拒绝 |
| 危险参数 | 每次工具调用前 | 危险包名、危险坐标/参数 |

固定文案：`SENSITIVE_UI_MSG` = "检测到敏感支付界面，我无法代为操作，请手动完成"。

### 6.4 工具层（15 个语义化工具）

设计原则：**LLM 不说坐标，只说意图**。坐标由 `ToolSupport` 从 UI 树自动解析。

| 工具名 | 用途 | 关键参数 |
|---|---|---|
| `click_text` | 按文本点击控件 | `text` |
| `click_id` | 按资源 ID 精确点击 | `resource_id` |
| `open_app` | 按应用名启动 | `app_name` |
| `press_back` | 返回键 | — |
| `press_home` | 回桌面 | — |
| `scroll` | 方向滑动 | `direction`(up/down/left/right) |
| `wait` | 等待毫秒 | `ms` |
| `long_press` | 长按控件/坐标 | `text` 或 `x,y` |
| `drag` | 精确拖拽 | 起点/终点 |
| `double_tap` | 双击 | 同上 |
| `multi_touch` | 多指手势（缩放） | 手势描述 |
| `play_macro` | 回放已存宏 | `name` |
| `list_macros` | 列出宏 | — |
| `start_game_auto` | 启动游戏自动化 | `profile` |
| `stop_game_auto` | 停止 | — |

**加新工具的正确做法**：在 `agent/tools/` 新建类实现 `Tool` 接口 → 在 `ToolRegistry.all` 列表加一行 → 在 `describe()` 加一句描述。Schema 自动并入提示词，无需改别处。

### 6.5 游戏自动化链路

```
GameTab（UI）
 ├─ GameProfileStore ── 配置（按包名匹配）
 ├─ GameAutoPilot ── 主循环
 │    ├─ GamePerceptionLoop ── 高频截图（1.2s 采样 720p）
 │    ├─ LocalScreenRecognizer ── Qwen2-VL 识别画面（Mutex 串行）
 │    ├─ GameStateRecognizer ── 状态判定
 │    ├─ RuleEngine / GameRuleRunner ── 规则匹配
 │    └─ GameKnowledge ── 游戏知识库（原神/星穹铁道）
 └─ 数据采集：TouchEventMonitor → TouchDatasetCollector → DatasetExporter
```

状态机配置放在 `assets/state_profiles/*.json`，运行时可用 `filesDir` 下的同名文件覆盖（`StateMachineStore` 按包名前缀匹配）。

### 6.6 模型管理链路

```
ModelManager
 ├─ 下载：ensureModels(ctx, onProgress) ── 多镜像 + 断点续传（.part）
 ├─ 导入 zip：importZip(uri) ── SAF 选文件 → 解到 .download/import_staging
 ├─ 导入文件夹：importFolder(treeUri) ── DocumentsContract 手写递归
 └─ 归类：classifyAndInstall() 按 basename 搬进 models/ 与 sensevoice/
```

**判定就绪的必需文件（不可放宽）：**

| 模型 | 文件 |
|---|---|
| 视觉 Qwen2-VL | `llm.mnn`、`llm.mnn.weight`、`visual.mnn`、`visual.mnn.weight`、`tokenizer.mtok`、`config.json` |
| 语音 SenseVoice | `model.int8.onnx`、`tokens.txt` |

目录：`getExternalFilesDir(null)/models` 与 `/sensevoice`。

**镜像实测（2026-10-30，国内直连）：**

| 源 | 结果 |
|---|---|
| `ghfast.top` | ✅ 唯一好用，206、可续传、≈1.2MB/s |
| `gh-proxy.com` | ⚠️ 能通但慢，偶尔忽略 Range |
| `ghproxy.net` | ⚠️ 握手 15s |
| GitHub 官方直连 | ❌ 1.3GB 必超时 |
| `gh.llkk.cc` / `github.moeyy.xyz` / `gh.xxooo.cf` / `ghps.cc` / `gh.ddlc.top` | ❌ 全失效 |

---

## 7. 关键契约

### 7.1 Tool 接口

```kotlin
interface Tool {
    val name: String                 // 如 "click_text"
    val schema: JSONObject           // 参数 JSON Schema
    fun execute(args: Map<String, Any?>): String   // 返回结果描述（成功含坐标，失败含原因）
}
```

### 7.2 副驾驶意图 JSON（模型必须输出这个结构）

```json
{"type": "chat",   "reply": "直接回答用户的话"}
{"type": "action", "reply": "我这就打开微信", "tool": "open_app", "params": {"app_name": "微信"}}
{"type": "task",   "reply": "我来完成", "goal": "在设置里把蓝牙打开"}
```

解析容错顺序：严格 JSON（剥 ```json 围栏）→ 正则识别动作动词 → 整段当 chat。

### 7.3 Executor 返回

```kotlin
enum class Status { SUCCESS, FAILED, NEED_REPLAN }
// SUCCESS：子目标达成，带 summary
// FAILED：失败原因（含"LLM"/"API Key"字样的直接中止，不重规划）
// NEED_REPLAN：需要 Planner 重新拆解
```

### 7.4 StateProfile（状态机配置）

```json
{
  "name": "原神-每日",
  "packagePrefix": "com.miHoYo",
  "states": [
    {"id": "map", "keywords": ["传送", "地图"], "antiKeywords": ["加载中"], "allowedTools": ["click_text"]}
  ]
}
```

### 7.5 公告格式（`announcement.json`）

```json
{"id":"2026-10-30-02","title":"标题","content":"正文（支持换行）","date":"2026-10-30","enabled":true}
```

> **改这个文件即热更新，不用发版**。字段 `id` 变了才会重新弹。仓库默认分支是 **`master`**，写错分支 → 全源 404 → 公告永不弹。

### 7.6 日志标签（排障时按这个过滤）

| TAG | 作用 |
|---|---|
| `CopilotEngine` | 副驾驶全流程，含 `[Copilot] 收到指令=…, 模型选择=…, 耗时=…ms, 状态=…` |
| `CopilotVision` | 模型选择、云端请求、错误分类 |
| `VoiceAssistant` | 语音状态流转 |
| `LocalKWS` | 唤醒；含"麦克风连续 5 秒全为静音"自检 |
| `LocalAsr` | 识别 |
| `ModelManager` | 下载/导入/校验 |
| `AgentRunner` / `Executor` / `Planner` | 多步任务 |
| `ToolRegistry` / `ToolSupport` | 工具执行 |
| `NexusAccessibilityService` | 无障碍通道 |
| `ShizukuManager` | 注入通道 |

---

## 8. 存储与数据

### 8.1 SharedPreferences（按用途）

| 文件 | 内容 | 风险 |
|---|---|---|
| `nexus_llm_config` | LLM provider / Key（**AES-GCM 加密后存**）/ baseUrl / model | 清数据即丢 Key |
| `voice_assistant` | 唤醒开关、唤醒词、阈值、确认时长、续听 | 影响语音是否自启 |
| `luna_copilot` | 免责同意、岛开关、陪玩开关、最近弹幕 | 清了要重新同意免责 |
| `luna_announcement` | 最后检查日期、已读公告 id | — |
| `nexus_safety` | 安全策略开关 | 关掉就没保护了 |
| `stability` | 崩溃循环计数 | 影响自恢复判定 |
| `task_history` | 任务归档 | — |
| `game_regions` / `collect_progress` | 游戏区域、采集进度 | — |
| `nexus_prefs` | 引导完成标记 | 清了会重走引导 |

### 8.2 目录

| 路径 | 内容 |
|---|---|
| `getExternalFilesDir(null)/models` | 视觉模型（必需 6 文件） |
| `getExternalFilesDir(null)/sensevoice` | 语音模型（必需 2 文件） |
| `getExternalFilesDir(null)/.download` | 下载临时文件、`import_staging` |
| `getExternalFilesDir("audit")/{taskId}/` | 审计：`task.json`、`task.md`、`screenshots/` |
| `cacheDir/nexus_log_*.zip` | 日志导出包（`LogExporter`） |
| `filesDir/state_profiles/` | 状态机覆盖配置 |

---

## 9. 权限与部署

### 9.1 静态权限（12 项）

| 权限 | 用途 | 备注 |
|---|---|---|
| `INTERNET` | LLM / 下载 | — |
| `RECORD_AUDIO` | 唤醒 + 录音 | 必需 |
| `FOREGROUND_SERVICE` | 常驻服务 | 必需 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 类型 | 必需 |
| `FOREGROUND_SERVICE_MICROPHONE` | **后台麦克风** | 0.7.6 新加，缺了唤醒必死 |
| `SYSTEM_ALERT_WINDOW` | 灵动岛 / 悬浮窗 | 必需 |
| `POST_NOTIFICATIONS` | 前台服务通知 | Android 13+ |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | — |
| `QUERY_ALL_PACKAGES` | 按名查应用 | 商店重点审查项 |
| `REQUEST_INSTALL_PACKAGES` | App 内更新安装 | 商店重点审查项 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 防后台被杀 | — |
| `INTERACT_ACROSS_USERS_FULL` | **签名级权限，普通应用拿不到** | ⚠️ 纯无效声明，建议删 |

### 9.2 四个运行时开关（缺一功能就废）

| 开关 | 位置 | 缺失表现 |
|---|---|---|
| 无障碍服务 | 设置 → 无障碍 → 宏月 | 无法读屏/点击/截图 |
| 悬浮窗 | 设置 → 应用 → 显示在其他应用上层 | 灵动岛不显示、静默失败 |
| 录音 | 首次开启唤醒时申请 | 唤醒无反应 |
| Shizuku / Stellar | 靠无线调试或 USB 激活 | 只能走无障碍，能力受限 |

### 9.3 vivo / iQOO 无障碍被拉黑（**实测高频问题**）

症状：设置显示"已开启"，但实际不绑定。

```bash
# 判断：Enabled 与 Crashed 同时列出 = 进了黑名单
adb shell dumpsys accessibility | grep -i "nexus\|crashed"
```

清除法（顺序不能错，**必须等 ≥3 秒**）：

```bash
adb shell am force-stop com.nexus.agent
adb shell settings put secure enabled_accessibility_services ""
# 等 3 秒以上（1 秒实测无效）
adb shell settings put secure enabled_accessibility_services com.nexus.agent/com.nexus.agent.accessibility.NexusAccessibilityService
adb shell am start -n com.nexus.agent/.ui.MainActivity
# 验证：Bound services 出现 + Crashed services:{} 即成功
```

### 9.4 Shizuku / Stellar

- Shizuku 非 root 模式**重启后服务必停**（Stellar 可免电脑，靠无线调试一次性配对）。
- Stellar（`roro.stellar.manager`）已替代原版 Shizuku，USB 激活：

```bash
adb shell <nativeLibraryDir>/libstellar.so --apk=<base.apk>
```

- 开机自启需在 Stellar 内做无线调试配对 + 开看门狗。

### 9.5 前台服务类型（0.7.6 关键修复，别回退）

```xml
<service
    android:name=".userservice.NexusForegroundService"
    android:foregroundServiceType="specialUse|microphone">
```

`startForegroundCompat()` 按版本传 type：Android 14+ 传 `SPECIAL_USE|MICROPHONE`，29~33 传 `MICROPHONE`，没录音权限则降级为 `SPECIAL_USE`（不崩）。

**为什么重要**：Android 11+ 后台访问麦克风必须在带 `microphone` 类型的前台服务里。否则 `AudioRecord` 能创建、`startRecording()` 不报错，但**读到的缓冲区全是 0**。

---

## 10. 发布流程

### 10.1 版本规则（`VERSION_RULES.md`）

| 规则 | 内容 |
|---|---|
| 默认 | **每次只改修订号**：patch +1 且 versionCode +1（无论加功能还是修 bug） |
| 例外 | minor / major 必须用户明确指令（"升到 0.8.0"/"升到 1.0.0"） |
| 禁止 | 未经指令跳 minor/major、复用 versionCode、回退版本号 |
| APK 命名 | `LunaMacro-{versionName}.apk`（品牌英文名，不是中文名） |

### 10.2 发布七步

```bash
# 1) 改版本号
sed -i 's/versionCode = 55/versionCode = 56/; s/versionName = "0.7.6"/versionName = "0.7.7"/' app/build.gradle.kts

# 2) 更新 CHANGELOG.md（顶部新增 ## [0.7.7] 段落）

# 3) 提交
git add -A && git commit -m "fix: ...（0.7.7）"

# 4) 瘦身构建（见 3.4，三步不能少）

# 5) 落盘 + 校验
cp app/build/outputs/apk/release/app-release.apk "/c/Users/25814/Desktop/LunaMacro-0.7.7.apk"
sha256sum "/c/Users/25814/Desktop/LunaMacro-0.7.7.apk"
"$BT/aapt2.exe" dump badging .../app-release.apk | head -1

# 6) 推送 + 打 tag
git push origin master
git tag -f v0.7.7 <commit>
git push origin v0.7.7

# 7) 建 Release（v* tag，附 APK 资产）
```

### 10.3 Release 的硬要求

> **App 内"检查更新"只认 `v*` tag 的 `.apk` 资产。** 没有 v 标签 Release 就会永远显示"已是最新"。
> `models` 标签的 Release 专用于模型包下载，**不要删**。

REST API 建 Release（直连比走代理快约 15 倍）：

```bash
TOK=$(printf "protocol=https\nhost=github.com\n\n" | git credential fill | grep '^password=' | cut -d= -f2)
# POST /repos/{owner}/{repo}/releases  → 拿 id
# POST https://uploads.github.com/repos/{owner}/{repo}/releases/{id}/assets?name=...
```

### 10.4 git 推送的代理坑（**必看，会浪费半小时**）

- 全局配置有 `http.proxy=http://127.0.0.1:7993`（Clash）。**Clash 一关，git 就卡死在这个死端口** —— 是配置问题不是网络问题。
- 仓库已加 `git config --local http.proxy ""` 覆盖为直连。
- 直连不通时（`Connection was reset` / 21s 超时），**把代理加回来最省事**：

```bash
git -c http.proxy=http://127.0.0.1:7993 push origin master
```

- WorkBuddy 自带的 `127.0.0.1:56288` 对 git 报 502，不能用。
- 注意：`curl --noproxy '*'` 直连 api.github.com **一直是通的**，只有 git 到 github.com:443 会挂。

### 10.5 公告热更新

改根目录 `announcement.json` → 提交推送 → App 次日（或当日首次进前台）自动拉取。**不用发版。**

拉取源顺序：`api.github.com` contents（raw）→ `ghfast.top` → `gh-proxy.com` → `ghproxy.net` → `raw` 直连兜底。

---

## 11. 体检结果

### 11.1 分项评分（综合 69/100）

| 维度 | 分 | 依据 |
|---|---|---|
| 构建与发布 | 95 | 编译通过、四件套齐全、签名规范；扣分：lint 与混淆全关 |
| 文档与流程 | 88 | 24 条 CHANGELOG、版本规则、公告机制；扣分：旧文档未清理 |
| 安全性 | 72 | Key 加密、无硬编码密钥、无明文 http；扣分：5 个 exported 组件、高危权限、无混淆 |
| 稳定性 | 70 | 有崩溃循环熔断、服务自恢复；扣分：244 处宽 catch、14 处空 catch、无崩溃上报 |
| 代码结构 | 68 | 分包清晰；扣分：MainActivity 2037 行、10 个文件超 600 行、485 行死代码 |
| 性能与体积 | 65 | APK 46MB 已瘦身；扣分：工作区背 1.6GB 模型、无内存监控 |
| 依赖新鲜度 | 60 | AGP/Kotlin 尚可，Compose BOM 落后约两年 |
| 测试覆盖 | 20 | **0 个用例** |

### 11.2 问题清单

#### P0（直接影响排障与交付质量）

| ID | 问题 | 证据 | 影响 | 建议 |
|---|---|---|---|---|
| P0-1 | 无全局崩溃捕获 | 全仓 `UncaughtExceptionHandler` 命中 **0** 处 | 用户报"用不了"时拿不到堆栈，只能真机复现 | 加 handler，栈写 `filesDir/crash/`，设置页加"导出崩溃日志" |
| P0-2 | 零自动化测试 | 无 `app/src/test`、`androidTest` 目录 | 语音/副驾驶是异步多线程链路，每次发版纯人工回归 | 先覆盖纯逻辑（意图解析、安全策略、模型归类） |

#### P1（中短期会咬人）

| ID | 问题 | 证据 | 影响 |
|---|---|---|---|
| P1-1 | Lint 与混淆全关 | `app/build.gradle.kts:83-86` `checkReleaseBuilds=false` `abortOnError=false`；`isMinifyEnabled=false` | 兼容性/权限/资源问题不报警；APK 可反编译读逻辑 |
| P1-2 | 异常大面积静默 | `catch(Throwable)` 244 处，空 catch 14 处 | 与 0.7.4 前"都没接上"同源：失败不发声 |
| P1-3 | MainActivity 上帝类 | 单文件 2,037 行；全仓 10 个文件 > 600 行 | 改一处易连带崩别处 |
| P1-4 | 高危/无效权限 | `QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`INTERACT_ACROSS_USERS_FULL` | 前三个商店重点审查；最后一个是签名级权限，纯无效 |
| P1-5 | 依赖落后 | Compose BOM 2024.09 / coroutines 1.8.0 / okhttp 4.12.0 | targetSdk 36 配老 UI 库有行为差异风险 |

#### P2（整洁度与长期成本）

| ID | 问题 | 证据 | 影响 |
|---|---|---|---|
| P2-1 | 工作区背 1.6GB 模型 | `assets/models` 1.3G + `sensevoice` 229M + `jniLibs` 100M | 未入 git，但构建/同步慢 |
| P2-2 | 485 行死代码 | `ui/MacroTab.kt` 定义 `MacroTab()` 但导航已下线，全仓无调用 | 误导后来者 |
| P2-3 | 文案硬编码 2,001 处 | 中文字面量直接写 Kotlin，未走 `strings.xml` | 多语言/统一改文案等于全仓改 |
| P2-4 | 5 个组件 exported | MainActivity、无障碍、ShizukuProvider、UserServiceImpl、BootReceiver | 均为必需，但无 `networkSecurityConfig` 显式兜底 |
| P2-5 | 旧文档失真 | `PROJECT_STATUS.md` 仍写 0.6.2 / 12,000 行 | 会误导接手者（本文档已纠正） |

### 11.3 健康项（别动）

1. **发版四件套齐全**：versionCode 自增 + CHANGELOG + `v*` tag Release + APK 资产，每次核验 digest。
2. **API Key 真加密**：`AES/GCM/NoPadding`，非明文；全仓无 `sk-` 硬编码。
3. **签名密钥没入库**：`*.keystore`、`keystore.properties` 都在 `.gitignore`。
4. **瘦身工程到位**：1.6GB 模型移出包体，APK 46MB，assets 内确认无 models。
5. **无明文 HTTP**：全仓 `"http://` 命中 0 处。
6. **开机自恢复 + 崩溃熔断**：BootReceiver 只白名单 3 个 action；连续启动失败自动复位。
7. **零 TODO 债**：TODO/FIXME 命中 0。
8. **日志可读性**：0.7.4 起统一 `[Copilot] 收到指令=…, 模型选择=…, 耗时=…, 状态=…` 格式，抓 logcat 能直接定位环节。

### 11.4 代码数据快照（可用于后续对比）

| 指标 | 值 |
|---|---|
| 自有 .kt 文件 / 行数 | 103 / 22,656 |
| sherpa 封装行数 | 4,288 |
| `object` 单例 | 74 |
| `catch(Throwable)` | 244 |
| 空 catch | 14 |
| TODO/FIXME | 0 |
| 硬编码中文字面量 | 2,001 |
| 超 600 行文件 | 10 |
| 权限数 | 12 |
| exported 组件 | 5 |
| CHANGELOG 条目 | 24 |
| Git commits | 186 |

---

## 12. 未验证项与待办

### 12.1 等真机验证（代码已改，效果未确认）

| 项 | 版本 | 验证方法 |
|---|---|---|
| 后台唤醒能否触发 | 0.7.6 | 装包 → 开唤醒 → 回桌面 → 说「你好高总」→ 应有提示音 + "👂 正在听" |
| 后台识别能否出字 | 0.7.6 | 上一步接着说"打开微信" → 应识别并执行 |
| 纯文本降级提示 | 0.7.4 | 只配 DeepSeek，问"我现在在什么软件" → 应带"本轮未读取屏幕" |
| 本地模型接管 | 0.7.4 | 导好本地模型 + 断网，问"1+1" → 应本地回答 |
| Key 错误提示 | 0.7.4 | 故意改错 Key → 应红底报"授权失败（401）" |
| 副驾驶看屏回答 | 0.7.3 | 灵动岛问"我现在在什么软件里" |
| 首页动作执行 | 0.7.3 | 首页输入"帮我打开微信" |
| 支付界面拦截 | 0.7.3 | 支付宝界面说"帮我点一下" → 应被拒 |
| 数据采集悬浮窗 | 0.6.32 | 开启采集 → 悬浮窗应显示 |

### 12.2 用户侧待办

- `C:\Users\25814\Desktop\BUG.txt` 是 BUG 台账，**完成一个删一个**。当前为空（仅标题行）。
- 每次开始任务前应先读该文件。

---

## 13. 给接手 AI 的规划建议

### 13.1 硬约束（雷区，违反会出事故）

| # | 约束 | 原因 |
|---|---|---|
| 1 | **不要动 `com/k2fsa/sherpa/onnx/`** | 与 so 版本严格绑定，改错 = SIGSEGV 随机杀进程 |
| 2 | **KWS 解码前必须 `isReady()`** | 否则 `SHERPA_ONNX_EXIT(-1)` 直接 exit，catch 拦不住 |
| 3 | **瘦身构建必须物理 mv 模型目录** | 改后缀 `.bak` 照样打包 → 1.5GB 包 |
| 4 | **前台服务必须保留 `microphone` 类型** | 掉了就后台静音，且现象极隐蔽 |
| 5 | **发版必须建 `v*` tag Release 并上传 APK** | 否则检查更新永远"已是最新" |
| 6 | **不要删 `models` tag 的 Release** | 模型下载源 |
| 7 | **每次发版只改 patch + versionCode** | minor/major 需用户明确指令 |
| 8 | **不要用 USB 安装**（除非用户明确要求） | 用户自行下载 APK 安装 |
| 9 | **敏感界面不截图** | 隐私熔断是用户硬要求 |
| 10 | **降级纯文本时必须显式告知** | 用户拍板的要求：必须带"本轮未读取屏幕"提示 |
| 11 | **公告写 `master` 分支** | 仓库默认分支不是 main |
| 12 | **push 前先 `git fetch`；直连不通就加代理** | 详见 10.4 |

### 13.2 优先级矩阵（影响 × 成本）

| 任务 | 影响 | 成本 | 建议 |
|---|---|---|---|
| 加全局崩溃捕获 + 日志导出 | 高 | 低（半天） | ⭐ **先做这个** |
| 删 `INTERACT_ACROSS_USERS_FULL` | 低 | 极低 | 顺手做 |
| 14 处空 catch 补日志 | 中 | 低 | 做 |
| 开 Lint 基线扫描（不拦截） | 中 | 低 | 做 |
| 拆分 MainActivity 设置页 | 中 | 中 | 排第二 |
| 补 20 个纯逻辑单测 | 高 | 中 | 排第三 |
| 升级 Compose BOM | 中 | 中高（回归风险） | 缓 |
| 开 minify + keep 规则 | 中 | 中高（易崩反射） | 缓 |
| 删 MacroTab 死代码 | 低 | 极低 | 顺手 |
| 文案抽 strings.xml | 低 | 高 | 除非要多语言 |

### 13.3 三条可选路线

#### 路线 A：稳定性优先（推荐给当前阶段）

**目标**：让"用户说用不了"变成"能一次定位"。

| 步骤 | 交付物 | 验收标准 |
|---|---|---|
| A1 | `CrashHandler` + `filesDir/crash/*.txt` | 故意抛异常，能生成 crash 文件 |
| A2 | 设置页"导出崩溃日志" | 点一下能分享出 zip |
| A3 | 14 处空 catch 补 `Log.w` | 全仓空 catch 为 0 |
| A4 | 清无效权限 + 更新旧文档 | 权限 11 项，`PROJECT_STATUS.md` 已归档或更新 |

#### 路线 B：功能补全

| 候选 | 说明 | 风险 |
|---|---|---|
| 副驾驶多轮上下文 | `ConversationContext.kt` 已存在但未深度接入 ask() | 中（要改 ask 契约） |
| 任务中断后恢复 | 目前中断即丢弃 | 中 |
| 游戏 Profile 可视化编辑 | 目前靠 JSON | 低 |
| 本地模型热切换 | 现在切换要重启 | 中 |

#### 路线 C：架构治理

| 步骤 | 说明 | 风险 |
|---|---|---|
| C1 | MainActivity 按 Tab 拆文件 | 低（纯搬代码） |
| C2 | `CopilotEngine.ask()` 抽成 `BrainPipeline` 类 | 中 |
| C3 | 统一错误模型（去掉分散的 `Log.w + return null`） | 中高 |
| C4 | 依赖升级 + 开 minify | 高（需完整回归） |

### 13.4 排障手册（症状 → 定位 → 根因）

| 症状 | 先看什么 | 常见根因 |
|---|---|---|
| 后台喊唤醒词没反应 | `logcat -s LocalKWS` | ①前台服务缺 microphone 类型 ②录音权限 ③系统隐私麦克风开关 ④KWS 静音自检日志 |
| 唤醒有反应但识别不出字 | `logcat -s LocalAsr` | ①SenseVoice 未导入 ②模型文件不全 ③录音为空 |
| 副驾驶回"模型没接上" | `logcat -s CopilotEngine CopilotVision` | ①Key 名字对不上（`${provider}_api_key`）②本地模型未下 ③provider 不支持视觉未降级 |
| 副驾驶回答但不动手 | 看 `[Copilot] 状态=` 是 Chat 还是 Action | 本地 2B 模型把动作答成闲聊；已加云端重试 |
| 灵动岛不显示 | 检查悬浮窗权限 | 缺权限时曾静默失败 |
| 灵动岛在但打字没反应 | 检查 `CopilotEngine.bindIsland` 是否被调 | 历史上 `onUserMessage` 从未赋值 → 全链路哑 |
| 多步任务卡住 | `logcat -s AgentRunner Executor Planner` | ①动作数超限 ②重规划超限 ③状态机 stuck ④模型返回格式不合契约 |
| 应用装不上/更新失败 | 检查签名的 v2 与 `REQUEST_INSTALL_PACKAGES` | ①签名不一致 ②未授权安装未知来源 |
| 无障碍显示开启但不工作 | `dumpsys accessibility` | vivo/iQOO 黑名单（见 9.3） |
| 下载模型失败 | `logcat -s ModelManager` | 镜像失效；用 `ghfast.top`；或走本地导入 |

### 13.5 反模式（别做这些）

| ❌ 不要 | ✅ 应该 |
|---|---|
| 为了"修好"而整段重写核心链路 | 最小改动，逐段验证（用户明确偏好稳健） |
| 用 `try/catch` 包住然后 `return null` | 分类错误 + 显式日志 + 给用户人话提示 |
| 只看代码就宣布"修好了" | 跑编译、抓日志、看产物（用户要求证据） |
| 一次改多个模块再统一验证 | 一模块一提交，每步可回滚 |
| 用 `GlobalScope` | 用受控 scope（当前全仓 0 处 GlobalScope，保持） |
| 在 `MainActivity` 里继续堆逻辑 | 新功能放独立包，MainActivity 只做接线 |
| 改完不发版不建 Release | 走完第 10 章的七步 |

---

## 14. 术语表

| 术语 | 含义 |
|---|---|
| **KWS** | Keyword Spotting，唤醒词检测（sherpa-onnx zipformer） |
| **ASR** | 语音识别（此处指本地 SenseVoice） |
| **TTS** | 文字转语音 |
| **Shizuku / Stellar** | 借 adb 权限运行的高权限服务；Stellar 是 Shizuku 的衍生版，支持无线调试激活 |
| **Stellar** | `roro.stellar.manager`，已替代原版 Shizuku |
| **MNN** | 阿里推理框架，Qwen2-VL-2B 跑在它上面 |
| **UI 树** | 无障碍 API 拿到的控件层级，工具靠它定位坐标 |
| **子目标（SubGoal）** | Planner 拆出的 1~5 个高层目标，不含具体点击步骤 |
| **语义化工具** | 用"点文本/开应用"这类意图操作，而非像素坐标 |
| **灵动岛** | 顶部胶囊悬浮窗 `CopilotIslandOverlay` |
| **副驾驶** | 看得见屏幕、能动手的 AI 助手（`CopilotEngine`） |
| **瘦身版 APK** | 不含本地模型的 46MB 包，模型靠下载或导入 |
| **宏（Macro）** | 录制下来的动作序列，可回放 |

---

## 15. 文档维护

| 项 | 说明 |
|---|---|
| 本文档位置 | 仓库根 `LunaMacro-工程交接与体检报告.md` |
| 生成依据 | 全仓静态扫描 + `compileDebugKotlin` 验证 + 构建产物核验 + 历史记忆库 |
| 何时更新 | 每次 minor/major 版本、架构调整、链路重构后 |
| 已知过期文档 | `PROJECT_STATUS.md`（0.6.2）、`LunaMacro软件当前报告.md`、`项目现状报告.md` —— 建议归档到 `docs/archive/`，避免误导 |

---

*本文档由 AI 基于实际代码与构建产物生成，所有数字均可复现（命令见第 3 章）。如与代码冲突，以代码为准，并请同步更新本文档。*
