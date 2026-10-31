package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地屏幕识别器（Qwen2-VL-2B，MNN 部署）。
 *
 * 用本地模型识别游戏截图中的「按钮/可交互元素 + 坐标」（元素检测，而非界面分类）。
 * 模型从 assets 解压到外部存储（瘦身版由 [ModelManager] 下载），通过 JNI 推理。
 *
 * ## 加载状态三态（供副驾驶 UI 显示）
 *
 * - [State.NOT_DOWNLOADED]：模型文件不全，需要用户去「设置 → 下载模型」
 * - [State.LOADING]：正在解压 / 装载进内存（1.3GB，首次可达数十秒）
 * - [State.READY]：可以推理
 * - [State.FAILED]：文件在但 native 装载失败（看 [lastError]）
 *
 * 之前加载失败只打一行日志、UI 完全无感知，表现为「副驾驶问话永远没反应」；
 * 现在状态对外可查，[CopilotEngine][com.nexus.agent.copilot.CopilotEngine] 据此提示用户。
 */
object LocalScreenRecognizer {

    private const val TAG = "LocalScreenRecognizer"
    private const val MODEL_DIR = "models"

    /** 视觉模型装载所需的 6 个文件（缺一个都不装载，判定与排障报告共用同一份清单） */
    private val REQUIRED_FILES = listOf(
        "llm.mnn", "llm.mnn.weight",
        "visual.mnn", "visual.mnn.weight",
        "tokenizer.mtok", "config.json"
    )

    /**
     * JNI 侧依赖的 5 个 so（顺序即加载顺序）。
     *
     * 0.8.1 补上 `libMNNOpenCV.so`：`libllm.so` 的 `DT_NEEDED` 里有它，且真的调用了
     * `MNN::CV::imread` / `resize`。缺这一个，`dlopen("libllm.so")` 直接失败。
     */
    private val REQUIRED_LIBS = listOf(
        "libMNN.so", "libMNN_Express.so", "libMNNOpenCV.so", "libllm.so", "libmnn_recognizer.so"
    )

    /** 排障报告落盘位置：filesDir/crash/vision_init_error.txt（与 CrashHandler 同目录） */
    private const val ERROR_DIR = "crash"
    private const val ERROR_FILE = "vision_init_error.txt"

    /**
     * 官方模型包（Release 的 `models.zip`）解压后的**精确字节数**。
     *
     * 用途只有一个：揪出「没下完 / 没解压完」的残缺文件。
     *
     * 为什么这条检查值得单独写：截断的 `.weight` 会被 MNN 直接 mmap，
     * 读越过文件尾时内核发 **SIGBUS**，而 SIGBUS 不走 Java 的 uncaughtException ——
     * App 当场消失，CrashHandler 一个字节都写不出来。
     * 用户看到的就是「一点测试识图就闪退，还没有日志」，正是 0.8.3 报上来的症状。
     *
     * 数字来自本仓库 `app/src/main/assets/models/`（与 `models` tag 的 zip 同源）：
     * 6 个必需文件合计 1,388,709,309 B，加 llm_config.json 正好对上 zip 的 1,388,711,751 B。
     */
    private val EXPECTED_SIZES = mapOf(
        "config.json" to 661L,
        "llm.mnn" to 453_608L,
        "llm.mnn.weight" to 965_665_010L,
        "tokenizer.mtok" to 4_108_014L,
        "visual.mnn" to 679_296L,
        "visual.mnn.weight" to 417_802_720L
    )

    /**
     * 装载视觉模型要求的最小**系统可用内存**（MB）。
     *
     * 1.4GB 权重之外，MNN 还要 KV cache 与视觉编码器的中间张量。
     * 低于这个数硬上，大概率被系统的 OOM Killer 直接杀进程 ——
     * 那同样不会留下 Java 堆栈，用户看到的是「点了就闪退」。
     */
    private const val MIN_AVAIL_RAM_MB = 1800L

    private const val PROMPT =
        "请识别这张游戏截图中的所有按钮和可交互元素，输出每个元素的名称和归一化坐标（x,y 都在 0 到 1 之间）。"

    /** 本地模型加载状态 */
    enum class State {
        /** 模型文件不全（未下载） */
        NOT_DOWNLOADED,

        /** 正在解压 / 装载 */
        LOADING,

        /** 就绪，可推理 */
        READY,

        /** 文件存在但装载失败 */
        FAILED
    }

    @Volatile
    private var initialized = false

    @Volatile
    var state: State = State.NOT_DOWNLOADED
        private set

    /** 最近一次失败原因（装载失败时给 UI 用） */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 最近一次**推理**失败的原因（0.8.2）。
     *
     * [lastError] 说的是「模型没起来」，这个说的是「模型起来了但这一句没答上来」。
     * 设置页的「测试视觉模型」靠它把「显存不足 / native 内部错误 / 返回空」
     * 这三种表现一模一样、修法完全不同的情况区分开。
     */
    @Volatile
    var lastInferError: String? = null
        private set

    /** 本地模型推理互斥锁（陪玩循环与副驾驶对话共用同一个 native 实例） */
    private val inferMutex = Mutex()

    /** 装载互斥锁（避免陪玩循环与对话同时触发 import 1.3GB 模型） */
    private val loadMutex = Mutex()

    @Volatile
    private var modelAvailable = false

    private val buttonRegex =
        Regex("([\\u4e00-\\u9fa5A-Za-z0-9]+)\\s*[（(]\\s*([0-9.]+)\\s*[,，]\\s*([0-9.]+)\\s*[)）]")

    /** 模型是否已就绪（可在任意线程调用） */
    fun isReady(): Boolean = state == State.READY

    /**
     * 模型文件是否齐全（不装载，只 stat 六个文件）。
     *
     * 用来判断「值不值得等加载」：文件在就显示「加载中」并等它；
     * 文件不在就直接走云端，不拿一句「加载中」骗用户。
     */
    fun modelFilesPresent(context: Context? = null): Boolean {
        val ctx = context ?: ShizukuManager.appContext() ?: return false
        return isModelReady(File(ctx.getExternalFilesDir(null), MODEL_DIR))
    }

    /**
     * 确保模型已装载（幂等、并发安全）。
     *
     * 已在内存中直接返回 [State.READY]；否则走一次装载流程。
     * 装载耗时可达数十秒（1.3GB），调用方应在 IO 上下文里等它，
     * 期间把状态显示给用户（不要静默放弃）。
     *
     * @param context 用于定位 `getExternalFilesDir()/models`；传 null 则用
     *                [ShizukuManager.appContext]
     */
    suspend fun ensureLoaded(context: Context? = null): State = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (state == State.READY) return@withContext State.READY

            val ctx = context ?: ShizukuManager.appContext()
            if (ctx == null) {
                state = State.FAILED
                lastError = "缺少 Context，无法定位模型目录"
                Log.e(TAG, lastError!!)
                return@withContext state
            }
            init(ctx)
            state
        }
    }

    /**
     * 初始化：解压模型 → 加载 JNI → 初始化模型。
     *
     * 注意：这是阻塞调用，1.3GB 模型装载期间 UI 线程会被卡住，
     * 务必只在 IO 线程调用（[ensureLoaded] 已处理）。
     */
    fun init(context: Context) {
        val targetDir = File(context.getExternalFilesDir(null), MODEL_DIR)
        try {
            if (!isModelReady(targetDir)) {
                // 完整版 APK 内置模型，从 assets 解压；瘦身版 assets 为空，这里会失败
                state = State.LOADING
                copyModelsFromAssets(context, targetDir)
            }
            if (!isModelReady(targetDir)) {
                modelAvailable = false
                initialized = false
                state = State.NOT_DOWNLOADED
                val missing = missingFiles(targetDir)
                lastError = "模型文件不全（缺：${missing.joinToString("、")}）"
                Log.i(TAG, "本地模型未下载，识别与副驾驶对话将走云端；缺 ${missing.size} 个文件")
                // 0.7.12：文件不全是真机上最高频的「视觉没反应」原因，
                // 光打 Log 用户抓不到，落一份报告让设置页能一键导出。
                writeErrorReport(context, "init:模型文件不全")
                return
            }

            modelAvailable = true
            state = State.LOADING
            lastError = null

            // ---- 0.8.4 加载前的两道闸 ----
            // ① 文件完整性：比官方包小的 .weight 一定是没下完，加载必崩 native
            val sizeProblem = describeSizeProblem(targetDir)
            if (sizeProblem != null) {
                modelAvailable = false
                initialized = false
                state = State.FAILED
                lastError = sizeProblem
                Log.e(TAG, sizeProblem)
                writeErrorReport(context, "init:模型文件不完整")
                return
            }
            // ② 可用内存：1.4GB 权重 + KV cache + 视觉编码器，低于这个数硬加载会被
            //    系统 OOM Killer 直接杀进程 —— 同样是「无日志闪退」
            val ram = availableRamMb(context)
            if (!ramSufficient(ram)) {
                modelAvailable = false
                initialized = false
                state = State.FAILED
                lastError = "可用内存只有 ${ram}MB，2B 视觉模型至少要 ${MIN_AVAIL_RAM_MB}MB。" +
                    "先关掉后台的大应用（或重启一次）再试。"
                Log.w(TAG, lastError!!)
                writeErrorReport(context, "init:内存不足")
                return
            }

            // ③ 崩溃循环拦截：上次就是死在 native 加载里，别再自动重试把用户 App 打崩
            if (VisionStage.shouldBlockAutoLoad()) {
                modelAvailable = false
                initialized = false
                state = State.FAILED
                lastError = "上次加载时 native 崩溃（停在 ${VisionStage.lastCrashStage}），" +
                    "已暂停自动加载。去 设置 → 测试视觉模型 可手动再试一次。"
                Log.w(TAG, lastError!!)
                writeErrorReport(context, "init:native崩溃后暂停自动加载")
                return
            }

            if (LocalRecognizerNative.ensureLoaded()) {
                // dlopen 里的 C++ 静态构造出错同样是进程级死亡，先落记号
                VisionStage.mark("native:init:${targetDir.absolutePath}")
                initialized = LocalRecognizerNative.init(targetDir.absolutePath)
                VisionStage.clear()
            } else {
                initialized = false
                // 带上第一个失败库的真实异常，别再只说「加载失败」
                val failed = LocalRecognizerNative.loadReport
                    .lineSequence().firstOrNull { it.startsWith("FAIL") }?.trim()
                lastError = if (failed != null) "native 库加载失败：$failed" else "native 库加载失败"
            }
            state = if (initialized) State.READY else State.FAILED
            if (!initialized && lastError == null) lastError = "native init 返回 false"

            if (initialized) {
                // 这次装成功了，之前那份失败报告已经过期，删掉免得导出时误导
                runCatching { errorFile(context).takeIf { it.exists() }?.delete() }
            } else {
                writeErrorReport(
                    context,
                    "init:装载失败",
                    IllegalStateException(lastError ?: "native init 返回 false")
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "模型初始化失败", e)
            initialized = false
            state = State.FAILED
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            writeErrorReport(context, "init:异常", e)
        }
        Log.i(TAG, "本地模型状态=$state${lastError?.let { " 原因=$it" } ?: ""}")
    }

    /**
     * 释放资源。
     */
    fun release() {
        if (initialized) {
            try {
                LocalRecognizerNative.release()
            } catch (e: Throwable) {
                Log.e(TAG, "释放模型失败", e)
            }
        }
        initialized = false
        state = State.NOT_DOWNLOADED
        modelAvailable = false
    }

    /**
     * 识别一帧截图。
     *
     * @return 识别结果；模型未就绪时返回 null（上层降级云端）
     */
    suspend fun recognize(bitmap: Bitmap): ScreenAnalysis? = withContext(Dispatchers.IO) {
        // 懒加载：首次识别时才解压并加载模型（避免启动时加载 1.3GB 导致启动慢/OOM 闪退）
        if (!initialized) {
            ensureLoaded(ShizukuManager.appContext())
        }
        if (!initialized) return@withContext null

        val imgFile = saveBitmapToTemp(bitmap) ?: return@withContext null
        try {
            VisionStage.mark("native:recognize")
            val result = LocalRecognizerNative.recognize(imgFile.absolutePath, PROMPT)
            parseResult(result)
        } catch (e: Throwable) {
            Log.e(TAG, "本地识别失败", e)
            null
        } finally {
            VisionStage.clear()
            imgFile.delete()
        }
    }

    /**
     * 用自定义 prompt 推理一帧（情绪陪玩 / 副驾驶图文问答）。
     *
     * 与 [recognize] 的区别：不解析成 ScreenAnalysis，直接返回模型原始输出文本。
     *
     * 加锁原因：陪玩循环与副驾驶对话可能同时想用同一个本地模型，
     * 并发进 native 层会踩内存（表现为随机 SIGSEGV 或输出乱码），必须串行。
     *
     * 注意：native 接口吃的是**图片文件路径**，不是 Base64。
     * 截图统一落一张临时 JPEG 再传路径（见 [saveBitmapToTemp]）。
     *
     * @return 模型原文；模型未就绪或推理失败返回 null
     */
    suspend fun infer(bitmap: Bitmap, prompt: String): String? = inferMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!initialized) {
                ensureLoaded(ShizukuManager.appContext())
            }
            if (!initialized) {
                lastInferError = "模型未装载（state=$state）"
                return@withContext null
            }

            val imgFile = saveBitmapToTemp(bitmap)
            if (imgFile == null) {
                lastInferError = "截图落盘失败（cacheDir 不可写或空间不足）"
                return@withContext null
            }
            try {
                VisionStage.mark("native:recognize")
                val out = LocalRecognizerNative.recognize(imgFile.absolutePath, prompt)
                // 返回空和抛异常在 UI 上长得一样，但原因差得远：显存不足 / native 内部错误
                // / 模型对这张图没话可说。这里如实记下，供「测试视觉模型」直接展示。
                lastInferError = if (out.isNullOrBlank()) {
                    "推理返回空（可能是显存不足，或 native 内部错误）"
                } else {
                    null
                }
                out
            } catch (e: Throwable) {
                lastInferError = "${e.javaClass.simpleName}: ${e.message?.take(120).orEmpty()}"
                Log.e(TAG, "本地推理失败", e)
                null
            } finally {
                // 正常返回 / Java 异常都会走到这；**native 崩溃走不到**，
                // 记号文件会留在磁盘上，下次启动由 VisionStage.consume() 读出来
                VisionStage.clear()
                imgFile.delete()
            }
        }
    }

    /**
     * 无截图时的纯文本推理。
     *
     * native 接口硬性要求一张图，这里喂一张 64×64 纯色占位图，
     * 并在 prompt 里明确「本轮没有截图」——模型仍能回答数学题、闲聊这类纯文本问题。
     * 副驾驶在截图失败（无障碍/Shizuku 未就绪）时走这条路，而不是直接放弃本地模型。
     */
    suspend fun inferText(prompt: String): String? {
        val placeholder = try {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.BLACK)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "占位图创建失败：${e.message}")
            return null
        }
        return try {
            infer(placeholder, prompt)
        } finally {
            if (!placeholder.isRecycled) placeholder.recycle()
        }
    }

    /**
     * 解析识别文本为 ScreenAnalysis。
     */
    private fun parseResult(text: String?): ScreenAnalysis? {
        if (text.isNullOrBlank()) return null
        val buttons = buttonRegex.findAll(text).mapNotNull { m ->
            val name = m.groupValues[1]
            val x = m.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
            val y = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null
            ButtonInfo(name, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }.toList()
        return ScreenAnalysis(screenType = "未知", buttons = buttons, description = text)
    }

    /**
     * 保存 Bitmap 到临时文件。
     */
    private fun saveBitmapToTemp(bitmap: Bitmap): File? {
        val ctx = ShizukuManager.appContext() ?: return null
        val file = File(ctx.cacheDir, "recognize_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file
        } catch (e: Throwable) {
            Log.e(TAG, "保存截图失败", e)
            null
        }
    }

    /**
     * 检查模型文件是否完整。
     */
    private fun isModelReady(dir: File): Boolean = missingFiles(dir).isEmpty()

    /** 返回缺失（或长度为 0）的文件名，全部齐了返回空列表 */
    private fun missingFiles(dir: File): List<String> = REQUIRED_FILES.filter { name ->
        val f = File(dir, name)
        !(f.exists() && f.length() > 0)
    }

    /**
     * 供「读屏自检」用：列出模型目录里缺哪些文件（不装载模型，只 stat）。
     *
     * @return 缺失文件名；空列表表示齐全
     */
    fun missingModelFiles(context: Context): List<String> =
        missingFiles(File(context.getExternalFilesDir(null), MODEL_DIR))

    /** 单个文件的大小体检结果 */    data class FileCheck(val name: String, val actual: Long, val expected: Long) {
        /** 比官方包小 = 截断（更大多半是用户自己换了量化版本，允许） */
        val ok: Boolean get() = expected <= 0L || actual >= expected
    }

    /**
     * 逐个文件比对官方大小，供「测试视觉模型」和排障报告直接展示。
     *
     * 不做装载、不碰 native，纯 stat，随便调用。
     */
    fun checkModelFiles(context: Context): List<FileCheck> {
        val dir = File(context.getExternalFilesDir(null), MODEL_DIR)
        return REQUIRED_FILES.map { name ->
            FileCheck(name, File(dir, name).length(), EXPECTED_SIZES[name] ?: -1L)
        }
    }

    /**
     * 系统当前可用内存（MB）。读不到返回 -1（调用方跳过检查，不误杀）。
     */
    fun availableRamMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem / 1024 / 1024
    } catch (e: Throwable) {
        Log.w(TAG, "读可用内存失败：${e.message}")
        -1L
    }

    /**
     * 内存够不够装 2B 视觉模型（纯函数，可单测）。
     *
     * `availMb <= 0` 表示读不到，按「够」处理 —— 宁可不拦，也不能因为读不到内存
     * 就把所有人的视觉功能锁死。
     */
    internal fun ramSufficient(availMb: Long, needMb: Long = MIN_AVAIL_RAM_MB): Boolean =
        availMb <= 0L || availMb >= needMb

    /** 人类可读的大小 */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * 文件完整性检查：**只拦「比官方包小」的**。
     *
     * 更小 = 没下完 / 没解压完，MNN mmap 后一读就越界 → SIGBUS，连异常都收不到；
     * 更大 = 用户自己换了别的量化版本，不替用户判断，放行。
     *
     * @return 有问题时返回一句能直接展示给用户的话；null = 都正常
     */
    private fun describeSizeProblem(dir: File): String? {
        val bad = REQUIRED_FILES.mapNotNull { name ->
            val expected = EXPECTED_SIZES[name] ?: return@mapNotNull null
            val actual = File(dir, name).length()
            if (actual in 1L until expected) {
                "$name 只有 ${formatSize(actual)}（应为 ${formatSize(expected)}）"
            } else {
                null
            }
        }
        if (bad.isEmpty()) return null
        return "模型文件不完整，多半没下完或没解压完：${bad.joinToString("；")}。" +
            "残缺的权重文件会让 MNN 读到文件尾之外直接崩 native（无 Java 堆栈），" +
            "去 设置 → 下载模型 重下一次。"
    }

    // ------------------------------------------------------------ 排障报告

    /** 排障报告文件（设置页「导出模型初始化日志」读的就是它） */
    fun errorFile(context: Context): File = File(File(context.filesDir, ERROR_DIR), ERROR_FILE)

    /**
     * 生成一份即时诊断写入 [errorFile]，返回文件；写盘失败返回 null。
     *
     * 报告内容全部是**现场事实**：目录、6 个必需文件的存在与大小、4 个 so 是否在
     * nativeLibraryDir 里、ABI、机型、当前状态与最后一次错误、以及（若有）完整堆栈。
     * 之前只有一行 `Log.e("MNN 库加载失败")`，用户截图只能给一句话，定位全靠猜。
     *
     * @param stage 触发点，便于区分「解压后仍缺文件」和「native 装载失败」
     * @param tr    捕获到的异常（native init 返回 false 这类没有异常，传一个合成异常进去）
     */
    fun writeErrorReport(context: Context?, stage: String, tr: Throwable? = null): File? {
        val ctx = context ?: runCatching { ShizukuManager.appContext() }.getOrNull() ?: return null
        return try {
            val dir = File(ctx.filesDir, ERROR_DIR).apply { mkdirs() }
            val file = File(dir, ERROR_FILE)
            file.writeText(buildReportText(ctx, stage, tr), Charsets.UTF_8)
            Log.i(TAG, "视觉排障报告已写入 ${file.absolutePath}")
            file
        } catch (e: Throwable) {
            Log.e(TAG, "写入视觉排障报告失败", e)
            null
        }
    }

    /**
     * 手动探测：不装载模型（1.3GB 太重），只跑文件与依赖检查并落盘。
     *
     * 设置页的「导出模型初始化日志」走这里 —— 用户即使没触发过装载失败，
     * 点一下也能拿到一份「它到底缺什么」的现场。
     */
    fun probe(context: Context?): File? = writeErrorReport(context, "manual:手动探测")

    private fun buildReportText(ctx: Context, stage: String, tr: Throwable?): String {
        val extDir = ctx.getExternalFilesDir(null)
        val modelDir = File(extDir, MODEL_DIR)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("===== 宏月 · 视觉模型初始化报告 =====")
            appendLine("时间：$stamp")
            appendLine("阶段：$stage")
            appendLine("状态：state=$state  initialized=$initialized  modelAvailable=$modelAvailable")
            appendLine("原因：${lastError ?: "-"}")
            appendLine()
            appendLine("--- 环境 ---")
            appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.SDK_INT}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("externalFilesDir：${extDir?.absolutePath ?: "null（外部存储不可用）"}")
            appendLine("模型目录：${modelDir.absolutePath}")
            appendLine("模型目录存在：${modelDir.exists()} 可读：${modelDir.canRead()}")
            appendLine()
            appendLine("--- 必需文件（${REQUIRED_FILES.size} 个，缺一个就不装载）---")
            REQUIRED_FILES.forEach { name ->
                val f = File(modelDir, name)
                val ok = f.exists() && f.length() > 0
                val expected = EXPECTED_SIZES[name] ?: -1L
                // 0.8.4：把官方大小一起列出来 —— 截断的 .weight 是 native 崩溃的头号嫌疑，
                // 只写实际大小看不出「差了多少」
                val sizeText = when {
                    !f.exists() -> "-"
                    expected > 0 -> "${formatSize(f.length())} / 官方 ${formatSize(expected)}" +
                        if (f.length() in 1L until expected) "  ← 偏小，疑似没下完" else ""
                    else -> formatSize(f.length())
                }
                appendLine("${if (ok) "OK  " else "MISS"}  ${name.padEnd(18)} $sizeText")
            }
            appendLine()
            appendLine("--- 上次 native 调用（Java 抓不到 native 崩溃，靠这个定位）---")
            appendLine(VisionStage.describe())
            appendLine()
            appendLine("--- native 库 ---")
            val extractNative = (ctx.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0
            appendLine("extractNativeLibs：$extractNative")
            appendLine("nativeLibraryDir：${ctx.applicationInfo.nativeLibraryDir}")
            appendLine("已加载：${LocalRecognizerNative.isLoaded}")
            // 关键：extractNativeLibs=false 时 so 直接从 APK 内 mmap，**不会解压到磁盘**，
            // nativeLibraryDir 里是空的。按「文件是否存在」判定会把「包内齐全」误报成
            // 「全部缺失」——0.8.0 之前就是这么把排查带偏的。这里直接读 APK 清单。
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val inApk = runCatching {
                java.util.zip.ZipFile(ctx.applicationInfo.sourceDir).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { it.startsWith("lib/") && it.endsWith(".so") }
                        .toSet()
                }
            }.getOrElse {
                appendLine("APK 内 lib 读取失败：${it.message}")
                emptySet()
            }
            val libPrefix = "lib/$abi/"
            REQUIRED_LIBS.forEach { name ->
                val ok = inApk.contains(libPrefix + name)
                appendLine(
                    "${if (ok) "OK  " else "MISS"}  ${name.padEnd(24)} " +
                        if (ok) "在 APK 内（$libPrefix$name）" else "APK 内不存在"
                )
            }
            val extra = inApk.filter { it.startsWith(libPrefix) }.map { it.removePrefix(libPrefix) }
                .filterNot { it in REQUIRED_LIBS }
            if (extra.isNotEmpty()) appendLine("其他随包 so：${extra.joinToString(", ")}")
            appendLine()
            appendLine("--- 加载结果（真实异常在这里）---")
            appendLine(LocalRecognizerNative.loadReport.ifBlank { "(尚未尝试加载)" })
            appendLine()
            appendLine("--- 可用空间 ---")
            runCatching {
                val st = android.os.StatFs(extDir?.absolutePath ?: ctx.filesDir.absolutePath)
                appendLine("剩余：${st.availableBytes / 1024 / 1024} MB")
            }.onFailure { appendLine("剩余：读取失败（${it.message}）") }

            if (tr != null) {
                appendLine()
                appendLine("--- 堆栈 ---")
                appendLine(Log.getStackTraceString(tr))
            }
            appendLine()
            appendLine("===== 报告结束 =====")
        }
    }

    /**
     * 从 assets 解压模型到外部存储。
     */
    private fun copyModelsFromAssets(context: Context, targetDir: File) {
        val assets = context.assets.list(MODEL_DIR) ?: return
        targetDir.mkdirs()
        assets.forEach { name ->
            val target = File(targetDir, name)
            if (target.exists() && target.length() > 0) return@forEach
            try {
                context.assets.open("$MODEL_DIR/$name").use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "解压模型文件：$name (${target.length() / 1024 / 1024} MB)")
            } catch (e: Throwable) {
                Log.e(TAG, "解压模型文件失败：$name", e)
            }
        }
    }
}
