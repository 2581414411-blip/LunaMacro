# Changelog

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
