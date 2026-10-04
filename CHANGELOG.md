# Changelog

## [0.6.0-alpha] - 2026-10-XX

### Added
- Macro 系统：录制、存储、回放、管理。
- play_macro 和 list_macros 工具。
- 宏管理界面（列表、编辑、录制入口）。

### Changed
- PromptBuilder 增加宏使用引导。

## [0.5.1] - 2026-10-XX

### Added
- 多轮上下文（ConversationContext）：续听时记住最近 5 轮指令和结果。
- 指令澄清：含指代词时自动改写为完整指令。
- ASR 独立 Key 配置。
- KWS 能量跳变检测 + 连续帧确认。
- 语音状态机枚举化（VoiceState）。

### Fixed
- SafetyPolicy 拦截时补全 TTS 播报和结构化日志。
- ASR 不再强耦合 qwen Key，未配置时给出明确提示。

## [0.5.1-beta] - 2026-10-XX

### Added
- 首次外部测试版。
- 危险操作拦截层（应用黑名单 + 敏感文本黑名单）：`SafetyPolicy`，任务入口文本级拦截 + 工具级拦截，默认开启，设置中可关闭（需二次确认）。
- 内建日志导出功能（设置 → 一键导出日志）：`LogExporter` 收集近 30 分钟关键 tag 日志、自动脱敏 API Key、打包 zip、调起系统分享。

### Changed
- 测试用例增加量化标准（预期耗时 / 允许上限）。
- 新增 T12（无障碍黑名单恢复）/ T13（首次引导流程）/ T14（危险操作拦截）。

## [0.3.3] - 2026-10-02

### Changed
- 唤醒后零空窗衔接：停 KWS 后立即录音，提示音并行播放不阻塞，指令开头不再被截断。
- 自适应端点检测：前 500ms 噪声采样，动态阈值（noiseFloor × 2.5，clamp 300~2000），抗嘈杂环境。
- TTS 与 KWS 严格互斥：播报前 pause、播报后延迟 1.5s resume，消除自我唤醒。
- 增加识别结果确认显示（"你说的是：xxx"，可配置开关/时长）。
- 执行中可打断：悬浮窗「取消」按钮 + currentJob 取消机制。
- 支持连续对话：执行完进入续听模式（默认 5 秒），无需重复喊唤醒词。

### Added
- `WakeWordDetector.pause()/resume()`：保留模型状态、仅释放/恢复麦克风。
- 语音配置项：`voice_confirm_enabled`、`voice_confirm_duration_ms`、`voice_continuous_enabled`、`voice_continuous_duration_ms`。

## [0.3.0] - 2026-10-02

### Added
- 语音助手完整链路：唤醒词「你好高总」→ 录音（静音端点检测）→ DashScope ASR 转文字 → 执行任务 → TTS 播报结果 → 重启唤醒。
- `VoiceCommandRecorder`：录音 + 静音端点检测（静音 900ms 判定说完，8s 超时保护）。
- `AsrClient`：DashScope paraformer-realtime-v2 WebSocket 语音识别。
- `TtsSpeaker`：Android 自带 TextToSpeech 离线中文播报。
- `VoiceAssistant`：语音状态机单例，串接整条链路；前台服务进程重建后自动恢复唤醒。
- 唤醒提示音（ToneGenerator beep）。
- 语音状态实时显示到悬浮窗（postStatus 强制可见）。

### Fixed
- 修复唤醒 `stop()` 与 `detectFrame()` 竞态导致的 NPE 崩溃（cancel 后 join + env 空检查）。
- 修复 LLM 配置切换 provider 时 baseUrl/model 不同步、漏 saveProvider。
- 修复 qwen baseUrl 被历史 bug 污染的旧值（运行时自愈）。
- 修复无障碍「开了开关却提示未开启」（崩溃黑名单 + 启动时序三态检测）。
- 修复 Shizuku binder 断开 30s 内不重连的问题（binder 恢复即重绑）。

### Changed
- API Key 改用 Android Keystore AES/GCM 加密存储（向后兼容明文旧数据）。
- 无障碍首页状态卡三态显示（未开启 / 连接中 / 已开启）。
- 版本号规范：修 bug → patch +1，加功能 → minor +1。

### Removed
- 删除唤醒后跳转 MainActivity 的残留逻辑。

## [0.2.0] - 2026-10-02

### Fixed（P0 + P1 修复项）

- 视觉模式坐标换算：LLM 在降采样截图（最长边 1280px）上推算的坐标，现在会按比例换算回真实屏幕坐标，修复点击位置系统性偏移。
- 视觉模式配置引导：qwen-vl 未配置 API Key 时给出明确提示，而非静默失败。
- 任务停止机制：悬浮窗「停止」按钮通过回调取消 MainActivity 的 collect 协程，停止更可靠。
- 唤醒后任务流程：唤醒后自动切换到「任务」Tab，缩短「唤醒→执行」路径。
- 注入反射降级：Shizuku 注入失败/反射拿不到 injectInputEvent 时，降级到无障碍 performClick，避免静默失败误报成功。
- 唤醒性能：将直接 DFT 替换为 radix-2 FFT（O(n²)→O(n log n)，约 28 倍提速），MFCC 推理延迟从 ~2.6s 降到 ~0.1s；并预计算 Hann 窗/mel 滤波器/FFT。
- Shizuku binder 恢复自动重连：binder 断开后重新到达时自动重新绑定 UserService，消除 30s 瘫痪窗口。

### Changed（P2 + P3 优化项）

- AudioRecord.startRecording() 增加异常保护，失败时释放资源并返回 false。
- ShizukuManager.buildArgs() 移除 `!!` 强制解包，改为明确的 IllegalStateException。
- UiTreeParser 不再 recycle parent 节点，避免破坏 UI 树遍历。
- OnboardingActivity 状态检测改用 LocalContext，消除 return@LaunchedEffect 边界隐患。
- 悬浮窗标题/按钮文案提取到 strings.xml。
- 删除未使用的 import（kotlin.math.ln）。

### Removed（死代码、未使用依赖等）

- 删除 PromptBuilder.build()（旧 AgentController 专用，无调用方）。
- 删除 LlmClientFactory.buildConfigApiKey()（固定返回 null 的占位方法）。
- Executor 的 client 改为在非视觉分支内创建，避免视觉模式下无效创建。
- （历史提交）删除 AgentController.kt / ActionParser.kt / ActionExecutor.kt 三个旧死代码文件（591 行）。
- （历史提交）移除未使用的 Room 依赖与 ksp 插件。

### Known Issues（暂缓项及原因）

- API Key 明文存 SharedPreferences：本地离线 app 风险可接受，Keystore 加密留待后续。
- 截图降采样 1280px：是视觉识别的合理分辨率权衡，进一步降低会影响识别准确率。
- 进程被杀后语音唤醒不自动重启：需将唤醒从 Activity 迁移到 Service，属较大重构，留待后续。
