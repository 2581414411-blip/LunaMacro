# 宏月 LunaMacro · 软件当前完整报告

> **报告生成**：2026-10-25 10:12
> **对应版本**：0.6.38（versionCode 48）
> **代码基线**：`master` @ `9ad13c9`
> **数据来源**：实际代码统计、`git` 历史、构建产物拆包、GitHub API 实测。凡标「实测」的均为本轮跑命令得到的数字；沿用历史结论的会明确标注。

---

## 一、总体状态一句话

**软件处于「可日常使用」状态**：核心链路（语音唤醒 → 离线识别 → 规划 → 触控执行 → 播报）已在真机跑通，安装包瘦身到 45MB，更新与公告走 GitHub 自动下发；当前 **BUG.txt 无未完成项**，遗留问题集中在「真机识别效果」这类需要人肉体验的验证上，而非崩溃类故障。

| 维度 | 状态 | 依据 |
|---|---|---|
| 编译 | ✅ 通过 | `assembleRelease` BUILD SUCCESSFUL，APK 45MB |
| 签名 | ✅ 正常 | v2 签名，CN=Nexus Agent，与历史版本同证书 |
| 语音助手稳定性 | ✅ 已修复并真机验证 | 0.6.37 真机连续运行 6 分 18 秒、`logcat -b crash` 0 条 |
| 更新检查 | ✅ 生效 | GitHub Release `v0.6.38` 已建、APK 已上传，接口实测命中 |
| 每日公告 | ⚠️ 代码完成，待真机首次启动验证 | 两个拉取源桌面 curl 实测 200 |
| 未完成 BUG | ✅ 0 条 | `BUG.txt` 仅剩标题行 |

---

## 二、基本信息

| 项 | 内容 |
|---|---|
| 产品名 | 宏月（英文品牌名 **LunaMacro**） |
| 包名 / namespace | `com.nexus.agent`（历史原因未改） |
| 定位 | Android 全能自动化助手：语音 + 视觉 + 触控注入 |
| 版本 | 0.6.38 / versionCode 48 |
| SDK | minSdk 24 · targetSdk 36 · compileSdk 36 |
| ABI | 仅 `arm64-v8a` |
| 语言 | Kotlin 2.0.21 + 少量 C++（JNI 27 文件） |
| UI | Jetpack Compose（Material 3，深度定制为低饱和几何块风、0dp 圆角、无阴影、无 ripple） |
| 构建 | Gradle 8.13 · AGP · NDK 29.0.14206865 · CMake 3.30.5 · JDK 17 |
| 仓库 | `github.com/2581414411-blip/LunaMacro`，默认分支 **`master`** |
| 许可 | GPL-3.0（代码开源，非商用授权） |
| 版本规则 | 见 `VERSION_RULES.md`：默认只递增修订号，minor/major 需明确指令 |

---

## 三、版本与发布历史（GitHub 实测）

当前对外可下载的 Release（`/releases` 接口实测）：

| Tag | 名称 | 发布日 | APK | 主要内容 |
|---|---|---|---|---|
| `v0.6.38` | v0.6.38 每日公告 | 2026-10-25 | 46,359,443 B | 每日公告机制 |
| `v0.6.37` | LunaMacro 0.6.37 | 2026-10-25 | 46,359,443 B | 修 sherpa `getResult` 类型不匹配导致的 SIGSEGV |
| `v0.6.34` | LunaMacro 0.6.34 | 2026-10-25 | 46,359,443 B | KWS 未判 `isReady` 就 `decode` → 进程被杀 |
| `v0.6.32` | LunaMacro 0.6.32 | 2026-10-24 | 46,359,443 B | 采集悬浮窗不显示 |
| `models` | 本地模型包 | 2026-10-24 | — | 1.3GB 视觉 + 229MB SenseVoice，供应用内下载 |

> 三个 APK 字节数完全相同是 zipalign 对齐+填充的结果，sha256 各不相同（0.6.38 = `4cbef812…`，0.6.37 = `9044e094…`），不是同一个包。

**版本演进主线**

| 阶段 | 版本区间 | 关键变化 |
|---|---|---|
| 起步 | 0.1.0 ~ 0.3.x | 基础框架、Qwen-VL 视觉、Shizuku、语音链路 |
| 稳定 | 0.4.0 ~ 0.5.x | Stellar 替代 Shizuku、内嵌部署向导、SafetyPolicy |
| 采集/训练 | 0.6.0 ~ 0.6.21 | 触摸采集、分步向导、批次管理、标注脚本 |
| 模型本地化 | 0.6.22 ~ 0.6.24 | MNN 视觉模型、SenseVoice + sherpa KWS |
| 瘦身与自更新 | 0.6.29 ~ 0.6.32 | APK 从 1.5GB 瘦到 45MB、应用内模型下载、检查更新、采集浮窗修复 |
| 崩溃攻坚 | 0.6.33 ~ 0.6.37 | 三轮定位「语音助手一开就回桌面」，最终查明 sherpa 版本错配 |
| 运营能力 | 0.6.38 | 每日公告（GitHub 热更新，不发版） |

---

## 四、代码与工程规模（实测）

| 指标 | 数值 |
|---|---|
| Kotlin 文件总数 | **111** |
| Kotlin 总行数 | **20,730**（含 `com/k2fsa/sherpa/onnx/` 封装 24 文件） |
| 业务代码（`com/nexus/agent`） | 86 文件 / 约 16,450 行 |
| C++ 文件（JNI 桥接） | 27 |
| 提交数 | 157 |
| 仓库首个提交 | 2026-10-02 |

**各包规模（实测）**

| 包 | 文件 | 行数 | 职责 |
|---|---:|---:|---|
| `ui/`（含 components、theme） | 10 | 4,043 | 5 个 Tab + 引导页 + 标注页 + 定制组件 |
| `agent/`（含 18 个 tools） | 25 | 2,927 | 规划/执行/编排 + 18 个操作工具 |
| `game/` | 21 | 2,667 | 游戏托管、本地识别、数据采集、模型管理 |
| `speech/` | 8 | 1,557 | KWS / ASR / TTS / 状态机 / 上下文 |
| `overlay/` | 2 | 1,255 | 思考浮窗（含采集浮窗）、运行指示灯 |
| `input/` | 2 | 972 | Shizuku/Stellar 注入 |
| `system/` | 4 | 725 | 开机自启、电池优化、日志导出、Stellar 部署 |
| `macro/` | 4 | 594 | 宏录制/回放 |
| `accessibility/` | 2 | 446 | 无障碍服务 + UI 树解析 |
| `audit/` | 2 | 426 | 任务审计日志 |
| `util/` | 3 | 365 | 更新检查、公告检查、JSON 适配 |
| `userservice/` | 2 | 281 | 前台服务 + Shizuku Binder |
| `safety/` | 1 | 94 | 危险操作拦截 |

**体积最大的 5 个文件**（也是后续重构的主要目标）：

| 文件 | 行数 |
|---|---:|
| `ui/MainActivity.kt` | 1,550 |
| `overlay/ThinkingOverlay.kt` | 1,017 |
| `ui/GameTab.kt` | 628 |
| `speech/VoiceAssistant.kt` | 619 |
| `input/ShizukuManager.kt` | 528 |

---

## 五、系统架构

```
┌──────────────────────────────────────────────────────┐
│                      用户交互层                        │
│  语音（唤醒词 → 离线 ASR） / 文字 / 悬浮窗 / 运行灯       │
└────────────────────────┬─────────────────────────────┘
                         │ 指令
┌────────────────────────▼─────────────────────────────┐
│                    Agent 执行层                        │
│  AgentRunner（编排）→ Planner（规划）→ Executor（执行） │
│  18 个工具：点击/长按/滑动/多指/打开应用/宏/游戏托管…    │
└──────────┬───────────────────────────┬───────────────┘
           │                           │
┌──────────▼───────────┐   ┌───────────▼───────────────┐
│   感知层（视觉）       │   │   执行层（触控注入）        │
│  本地 Qwen2-VL-2B     │   │  Shizuku / Stellar（binder）│
│  （MNN 4bit，检测元素）│   │  无障碍服务（双通道兜底）    │
│  云端 Qwen-VL 兜底     │   │  悬浮窗状态反馈             │
└──────────┬───────────┘   └───────────────────────────┘
           │
┌──────────▼───────────────────────────────────────────┐
│                    数据 / 记忆层                       │
│  会话历史 · 游戏知识库(原神/星穹铁道) · 采集数据集 · 审计日志 │
└──────────────────────────────────────────────────────┘
```

**核心设计分工**：云端大模型只做「任务规划」（拆步骤），本地模型只做「画面元素检测」（有什么按钮、坐标多少）。这样既避免把 1.3GB 模型用于需要频繁调用的分类任务，也保证离线时仍有感知能力。

---

## 六、功能模块现状

### 6.1 五个主 Tab

| Tab | 现有能力 |
|---|---|
| **首页** | 权限总览（无障碍/悬浮窗/录音/Shizuku，每 2 秒刷新）、无障碍绑定的双维状态（开关 vs 实际绑定）、Stellar 熔断提示、语音助手开关、唤醒词与阈值调节、日常收集开关、开始对话入口 |
| **任务** | 指令输入、逐步执行日志（思考浮窗同步）、历史记录（最近 10 条）、审计详情与导出 |
| **设置** | LLM 配置（OpenAI / DeepSeek / 通义千问）、ASR Key、唤醒词自定义、权限跳转、电池/自启动优化、安全策略开关、模型下载与进度、检查更新、关于 |
| **宏** | 宏录制/编辑/回放，步骤类型：`TAP` `LONG_PRESS` `SWIPE` `MULTI_TOUCH` `WAIT` `SCREENSHOT`（`CONDITION` 条件分支尚未实现） |
| **游戏** | 零配置托管（一句话启动）、游戏 Profile、触摸采集、日常收集、内置标注、数据集导出 |

### 6.2 语音助手（本地闭环）

```
唤醒词（sherpa-onnx KWS）→ 录音（自适应端点）→ ASR（SenseVoice INT8 离线）
→ 指令澄清（多轮上下文）→ 执行（Agent）→ TTS 播报 → 追问 / 回待机
```

| 项 | 现状 |
|---|---|
| 唤醒词源 | `assets/kws/`（zipformer 三件套 + tokens + keywords），4.6MB |
| 预设唤醒词 | 你好高总 / 小爱同学 / 小艺小艺 / 你好军哥（可换音素串） |
| 默认阈值 | `0.6f`（UI 可调，用户机当前手动设过 0.375） |
| 状态机 | IDLE → LISTENING → TRANSCRIBING → CLARIFYING → EXECUTING → SPEAKING → FOLLOW_UP |
| 可靠性措施 | 单飞锁、协程异常兜底、崩溃循环熔断（2 分钟内 ≥3 次自动关闭自恢复） |
| 实测 | 连续运行 6 分 18 秒、连续解码 100+ 帧、0 崩溃（0.6.37） |

### 6.3 Agent 执行

- **18 个工具**：`click_text`、`click_id`、`long_press`、`double_tap`、`drag`、`scroll`、`multi_touch`、`open_app`、`press_home`、`press_back`、`wait`、`list_macros`、`play_macro`、`start_game_auto`、`stop_game_auto` 等。
- **LLM 提供商**：`openai`（gpt-4o）/ `deepseek`（deepseek-chat，默认）/ `qwen`（qwen-plus，DashScope 兼容模式）。Key 用 Android Keystore 加密存。
- **安全策略**：命中「卸载/删除/格式化/转账/支付/贷款/改密」等关键词直接拦截；`open_app` 目标为银行/支付/证券/理财类应用一律拦截；可在设置里关闭（需二次确认）。

### 6.4 游戏托管与数据采集

| 能力 | 说明 |
|---|---|
| 零配置托管 | 一句话识别游戏 → 加载知识库 → 规划 → 循环执行 |
| 游戏知识库 | 内置 `genshin.json`、`starrail.json` |
| 触摸采集 | 分步向导、断点续采、批次管理、上限 2000 张、记录归一化坐标（触摸面板 0-12599 / 0-27999） |
| 日常收集 | 后台静默截图、无上限、记录前台包名 |
| 标注 | 内置标注页 + PC 端 DashScope Qwen-VL 批量标注 → SFT 格式 |

### 6.5 运营能力（0.6.29 起陆续加入）

| 能力 | 机制 |
|---|---|
| **应用内模型下载** | 瘦身版 APK 不含模型；从 `models` Release 下载，临时文件在外部存储，Range 断点续传，进度节流（百分比变化或 ≥250ms），下载前 StatFs 空间预检，多镜像（`ghfast.top` / `gh-proxy.com` / `ghproxy.net` / 官方源） |
| **检查更新** | `api.github.com/releases`，只认 `v*` tag 且带 `.apk` 的 Release；跳 `models` tag |
| **每日公告** | 每天首次进前台拉仓库根目录 `announcement.json`，弹窗一个「确定」按钮；`enabled:false` 或正文空则不弹；改 json 即热更新，**不用发版** |

---

## 七、技术栈与依赖（实测 `app/build.gradle.kts`）

| 类别 | 依赖 | 版本 |
|---|---|---|
| 语言/插件 | Kotlin / compose plugin | 2.0.21 |
| Shizuku | `dev.rikka.shizuku:api` / `:provider` | 13.1.5 |
| Compose | `compose-bom` | 2024.09.00 |
| Activity | `activity-compose` | 1.9.0 |
| 协程 | `kotlinx-coroutines-android` | 1.8.0 |
| 网络 | `okhttp3` | 4.12.0 |
| JSON | `moshi` / `moshi-kotlin` | 1.15.0 |
| 基础 | `core-ktx` / `lifecycle-runtime-ktx` | 1.13.1 / 2.8.4 |

**本地推理库（`jniLibs/arm64-v8a`，解压后 100MB）**

| 库 | 大小 | 用途 |
|---|---|---|
| `libMNN.so` | 48M | MNN 推理核心 |
| `libMNN_Express.so` | 18M | MNN 表达式层 |
| `libllm.so` | 9.6M | mnn-llm 封装 |
| `libonnxruntime.so` | 16M | sherpa-onnx 依赖 |
| `libsherpa-onnx-jni.so` + c-api + cxx-api | 约 9.3M | 语音 KWS/ASR |

> ⚠️ **版本红线**：`jniLibs` 为 **sherpa-onnx v1.12.11**，`com/k2fsa/sherpa/onnx/*.kt` 封装必须与之严格对齐。曾经因为拷入更新版本的 Kotlin 封装，`getResult` 声明返回对象而 native 实际返回 `Object[]`，虚派发直接 SIGSEGV —— 编译能过、运行必崩，且崩溃栈落在渲染线程，极难定位。

---

## 八、体积构成

### 8.1 分发用 APK（瘦身版）：45MB

| 组成 | 大小（APK 内） |
|---|---|
| `lib/arm64-v8a` | 28.7MB |
| `classes.dex` + `classes2.dex` | 21.0MB |
| `assets/stellar.apk`（内嵌 Stellar 部署包） | 4.6MB |
| `assets/kws`（唤醒词模型） | 4.5MB |
| `assets/nexus_kws.onnx` | 0.1MB |
| 资源 / 清单 / 其他 | 约 0.5MB |

模型不进 APK，首次使用时按需下载：

| 模型 | 体积 | 存放（运行期） |
|---|---|---|
| Qwen2-VL-2B（MNN 4bit）：`llm.mnn`+weight 921M、`visual.mnn`+weight 399M、`tokenizer.mtok` 4M | ≈1.3GB | `getExternalFilesDir()/models` |
| SenseVoice INT8：`model.int8.onnx` + `tokens.txt` | 229MB | `getExternalFilesDir()/sensevoice` |

### 8.2 仓库内资源（不进 APK，构建时移出）

| 目录 | 大小 |
|---|---|
| `app/src/main/assets/models` | 1.3G |
| `app/src/main/assets/sensevoice` | 229M |
| `app/src/main/assets/kws` | 4.6M |
| `app/src/main/assets/stellar.apk` | 4.7M |

> **打包流程**：构建瘦身版前必须把 `assets/models`、`assets/sensevoice` 移到 `app/` 下（留在 assets 里改名成 `.bak` 也会被打包，漏移会打出 1.5GB 的包），`assembleRelease` 后再移回。

---

## 九、权限与系统组件（实测 `AndroidManifest.xml`）

### 权限

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 思考浮窗 / 采集浮窗 / 运行灯 |
| `RECORD_AUDIO` | 唤醒词监听与语音识别 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 前台服务保活 |
| `POST_NOTIFICATIONS` | 前台服务通知 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 后台存活 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `INTERNET` | 云端 LLM / 更新检查 / 公告 / 模型下载 |
| `QUERY_ALL_PACKAGES` | 游戏与应用检测 |
| `REQUEST_INSTALL_PACKAGES` | 内嵌 Stellar 部署 |
| `INTERACT_ACROSS_USERS_FULL` | 系统级注入辅助 |

### 组件

| 组件 | 职责 |
|---|---|
| `.ui.MainActivity` | 主界面（5 Tab） |
| `.ui.OnboardingActivity` | 首次引导（权限 + LLM Key 配置） |
| `.userservice.NexusForegroundService` | 前台服务保活 |
| `com.google.android.accessibility.selecttospeak.SelectToSpeakService` | 无障碍服务（对外用「朗读」类名规避厂商清理） |
| `rikka.shizuku.ShizukuProvider` | Shizuku Provider |
| `androidx.core.content.FileProvider` | 日志/数据集导出分享 |
| `.system.BootReceiver` | 开机自启 |

---

## 十、关键技术决策与踩坑（可复用经验）

| # | 决策/坑 | 结论 |
|---|---|---|
| 1 | **sherpa 封装与 native 版本错配** | 升级 `jniLibs` 必须同版本更新 `com/k2fsa/sherpa/onnx/` 全套；核对法：`grep -ao "Java_com_k2fsa_sherpa_onnx_Xxx_[A-Za-z]*" libsherpa-onnx-jni.so` + 扫 so 内版本串 |
| 2 | **不能无条件 `decode()`** | 必须先 `isReady()`，否则 `features.cc` 里 `SHERPA_ONNX_EXIT(-1)` 直接杀进程，`try/catch` 拦不住（日志特征 `0 + 45 > 9`） |
| 3 | **只喂本次读到的样本** | `read < buffer.size` 时 `copyOf(read)`，否则残留样本导致帧数算错 |
| 4 | **瘦身而非内嵌** | 1.5GB APK 分发几乎不可用；45MB + 按需下载是当前最优解 |
| 5 | **vivo/iQOO 无障碍黑名单** | 被 force-stop 后系统会拉黑：清 `enabled_accessibility_services` → **等 ≥3 秒** → 恢复 → 启动 App |
| 6 | **Stellar 重启必失效** | 无线调试配对 + 看门狗/开机自启；USB 激活重启即失效 |
| 7 | **GitHub 源可达性** | 国内 `raw.githubusercontent.com` 直连超时（000）；`api.github.com` 与 `ghfast.top` 通。公告/更新/模型下载全部按此排源顺序 |
| 8 | **仓库默认分支是 `master`** | 写成 `main` 全源 404，公告永不弹 |
| 9 | **版本号规则** | 默认只 +patch，minor/major 需明确指令；APK 命名统一 `LunaMacro-{version}.apk` |

---

## 十一、稳定性与验证状态

### 已验证（真机 / 实测）

| 项 | 证据 |
|---|---|
| 语音助手不再「一开就回桌面」 | 0.6.37 真机连续 6 分 18 秒、`logcat -b crash` 0 条、连续解码 100+ 帧 |
| 采集悬浮窗正常显示 | 用户 2026-10-25 确认 |
| 模型下载有进度 | 临时文件外部存储 + 进度条 + 空间预检，0.6.31 上线 |
| 检查更新能命中新版本 | 0.6.38 Release + asset 实测，`releases` 接口返回 tag 与 apkUrl |
| 公告文件可拉取 | `api.github.com` 200 / 0.7s，`ghfast.top` 镜像 200 |
| APK 完整性与签名 | sha256 本地与 GitHub asset 一致；v2 签名 CN=Nexus Agent |

### 待验证（需要真人体验，无法远程判定）

| 项 | 现状 | 怎么验 |
|---|---|---|
| 唤醒词「你好高总」实际触发 | 代码链路已通，阈值默认 0.6、用户机设 0.375 | 装 0.6.38 后对手机说唤醒词；不行抓 `adb logcat -s LocalKWS` |
| SenseVoice 离线识别准确率 | 封装已按 v1.12.11 修好（此前同样是坏的） | 唤醒后说一句指令，看浮窗识别结果 |
| 本地视觉模型真机识别效果 | MNN 加载与降级逻辑就绪，未做效果评估 | 跑一次游戏托管，看元素定位是否准确 |
| 0.6.38 公告首次弹窗 | 逻辑与源均已验证，仅缺真机 | 装包后首次打开应弹公告 |

### 已知限制

| 限制 | 影响 | 备注 |
|---|---|---|
| 仅 `arm64-v8a` | 32 位机型装不了 | 现在基本不影响 |
| 云端 LLM 是硬依赖（规划层） | 无 Key 时 Agent 不能规划 | 与「本地离线闭环」底线存在张力，是当前唯一云端依赖 |
| 公告/更新/模型下载依赖 GitHub | 网络异常时静默失败 | 已做多源 + 重试，但不保证 |
| 宏的 `CONDITION` 条件分支未实现 | 宏只能线性回放 | 枚举已预留 |
| `MainActivity.kt` 1,550 行 | 维护成本高 | 后续应拆 Tab 文件 |
| 本地视觉模型需下 1.3GB | 首次启用耗时长 | 已做断点续传与进度提示 |

---

## 十二、风险与建议（按优先级）

| 优先级 | 事项 | 建议 |
|---|---|---|
| P0 | 唤醒词与识别效果未真机确认 | 装 0.6.38 实测；阈值不合适就调（0.3~0.5 区间试） |
| P0 | 本地视觉模型效果未评估 | 跑一次托管任务，量化「元素定位成功率」再决定是否重训 |
| P1 | 训练数据量不足（历史结论 ~366 条） | 继续用日常收集攒到 1000~2000 条再重训小模型 |
| P1 | `MainActivity` / `ThinkingOverlay` 过大 | 按 Tab 与浮窗类型拆分，降低回归风险 |
| P2 | 规划层强依赖云端 LLM | 中期可评估小参数本地规划模型（7B 级 4bit）替代 |
| P2 | 无障碍服务被厂商清理 | 已有恢复法 + 熔断，建议再做「自愈提醒」推送 |

---

## 十三、附：关键路径与构建命令

```bash
# 仓库
git clone https://github.com/2581414411-blip/LunaMacro
cd LunaMacro

# 瘦身版 release 打包（务必先移出大模型）
mv app/src/main/assets/models   app/models
mv app/src/main/assets/sensevoice app/sensevoice
/c/Users/<user>/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle.bat assembleRelease --offline
mv app/models   app/src/main/assets/models
mv app/sensevoice app/src/main/assets/sensevoice

# 产物重命名（分发约定）
cp app/build/outputs/apk/release/app-release.apk ~/Desktop/LunaMacro-0.6.38.apk
```

**目录速查**

| 路径 | 内容 |
|---|---|
| `app/src/main/java/com/nexus/agent/agent/` | 编排、规划、执行 + 18 工具 |
| `app/src/main/java/com/nexus/agent/speech/` | 语音全链路 |
| `app/src/main/java/com/nexus/agent/game/` | 游戏托管、采集、本地识别、模型下载 |
| `app/src/main/java/com/nexus/agent/ui/` | Compose 界面 |
| `app/src/main/cpp/` | MNN JNI 桥接 |
| `app/src/main/assets/kws/` | 唤醒词模型与关键词 |
| `announcement.json` | 每日公告（改它即生效） |
| `VERSION_RULES.md` | 版本号规则 |

---

*报告完。本报告基于 2026-10-25 的实际代码、构建产物与 GitHub 接口数据生成；标注「实测」的数字均来自本轮命令输出。*
