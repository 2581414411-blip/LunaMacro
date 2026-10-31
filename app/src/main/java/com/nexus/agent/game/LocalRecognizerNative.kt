package com.nexus.agent.game

import android.util.Log

/**
 * MNN 本地识别器的 JNI 绑定。
 *
 * 对应 cpp/mnn_recognizer.cpp 的 native 方法。
 *
 * ## 加载顺序（不能改）
 *
 * `MNN → MNN_Express → MNNOpenCV → llm → mnn_recognizer`
 *
 * 后一个依赖前一个：`libmnn_recognizer.so` 依赖 `libllm.so`，
 * `libllm.so` 依赖 `libMNNOpenCV.so` + `libMNN_Express.so`，
 * 而 `libMNN_Express.so` 依赖 `libMNN.so`。
 *
 * ## 为什么 `MNNOpenCV` 排在 `llm` 前面（0.8.1 的教训）
 *
 * `libllm.so` 的 ELF 头里写死了 `DT_NEEDED libMNNOpenCV.so`，并且源码里**真的调用了**
 * `MNN::CV::imread` 和 `MNN::CV::resize` —— 这是 MNN 自带的轻量图像库（不是 OpenCV）。
 *
 * 0.6.22 集成时只拷了 MNN / MNN_Express / llm 三个 so，漏了这一个。
 * 结果 Android 的 linker 在 `dlopen("libllm.so")` 时就直接失败：
 * `UnsatisfiedLinkError: dlopen failed: library "libMNNOpenCV.so" not found`，
 * 后面两个库也跟着起不来，视觉模型从 0.6.22 到 0.8.0 一次都没成功装载过。
 *
 * 更坑的是：排障报告当时按 `nativeLibraryDir` 里「文件是否存在」来判定，
 * 而本工程是 `extractNativeLibs=false`（so 直接 mmap 自 APK，不解压到磁盘），
 * 那个目录本来就读不到这些文件 —— 于是「一个依赖没找到」被报成了「4 个 so 全缺失」，
 * 排查方向被带偏了好几轮。现在 [loadReport] 会逐条记录每个库的真实结果。
 */
object LocalRecognizerNative {

    private const val TAG = "LocalRecognizerNative"

    /** 加载顺序 = 依赖顺序，不要调换（见类注释） */
    private val LOAD_ORDER = listOf(
        "MNN",
        "MNN_Express",
        "MNNOpenCV",
        "llm",
        "mnn_recognizer"
    )

    @Volatile
    private var loaded = false

    /** 四个 so 是否已全部 loadLibrary 成功（排障报告用） */
    val isLoaded: Boolean get() = loaded

    /**
     * 最近一次加载的逐库结果，例如：
     * ```
     * OK   libMNN.so
     * OK   libMNN_Express.so
     * FAIL libMNNOpenCV.so → UnsatisfiedLinkError: dlopen failed: library "libMNNOpenCV.so" not found
     * ```
     * 排障报告直接贴这段，比「native 库加载失败」有用得多。
     */
    @Volatile
    var loadReport: String = ""
        private set

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        val sb = StringBuilder()
        for (name in LOAD_ORDER) {
            // dlopen 会跑 C++ 静态构造，出问题就是进程级死亡（SIGSEGV/SIGABRT），
            // Java 的 try/catch 完全拦不住。先落个记号，崩了下次启动能查到是哪个库。
            VisionStage.mark("native:loadLibrary:$name")
            try {
                System.loadLibrary(name)
                sb.appendLine("OK   lib$name.so")
            } catch (e: Throwable) {
                val msg = e.message?.take(400) ?: e.javaClass.simpleName
                sb.appendLine("FAIL lib$name.so → ${e.javaClass.simpleName}: $msg")
                loadReport = sb.toString()
                Log.e(TAG, "加载 lib$name.so 失败：$msg")
                VisionStage.clear()
                return false
            }
        }
        loaded = true
        loadReport = sb.toString()
        VisionStage.clear()
        Log.i(TAG, "MNN 库全部加载成功（${LOAD_ORDER.size} 个）")
        return true
    }

    /**
     * 安装 native 崩溃取证（0.8.8）。
     *
     * native 崩溃不走 Java 的 uncaughtException，CrashHandler 写不出任何东西。
     * 装上这个之后，SIGSEGV / SIGBUS / SIGABRT 会把信号、出错地址、调用栈
     * 追加写进 [logPath]，相当于自带一份迷你 tombstone，不用连 adb。
     */
    external fun installCrashHandler(logPath: String)

    /**
     * 只创建模型对象（读 config.json），**不加载权重**。
     *
     * 拆出来是为了定位：崩在这一步 = 配置/模型与 so 版本不匹配；
     * 崩在 [load] 才是权重/内存/指令集的问题。两者修法完全不同。
     *
     * ## 参数必须是 config.json 的路径（0.8.9 的教训）
     *
     * MNN 的 `LlmConfig(path)` 只对 `.json` 后缀做「读文件 + base_dir 取所在目录」，
     * 传目录时会**原样**当 base_dir 且**不补结尾斜杠**，于是
     * `llm_config()` 拼成 `.../modelsllm_config.json` —— 配置读空、模型路径全错。
     * 而这份 `libllm.so` 的 `load()` 不检查文件存在，直接崩在 `Module::load`。
     *
     * 现在 native 侧会做归一化（传目录也能纠正），但调用方请直接传
     * `File(modelDir, "config.json").absolutePath`，别依赖兜底。
     */
    external fun create(configPath: String): Boolean

    /** 加载权重 + 建计算图 */
    external fun load(): Boolean

    /**
     * 初始化模型（modelDir 为模型目录，native 会归一化成 `<dir>/config.json`）。
     *
     * 等价于 [create] + [load]，保留给不需要分段定位的调用方。
     */
    external fun init(modelDir: String): Boolean

    /**
     * 最近一次 native 失败的真实原因（空串 = 上一步成功）。
     *
     * 用来把「create 返回 false」变成「配置文件不存在：/xxx/config.json」这种可读信息，
     * 而不是让 UI 只能写一句「装载失败」。
     */
    external fun lastError(): String?

    /**
     * 识别一帧截图。imagePath 为截图文件路径，prompt 为指令文本。
     * @return 识别结果文本；失败返回 null
     */
    external fun recognize(imagePath: String, prompt: String): String?

    /**
     * 释放模型。
     */
    external fun release()
}
