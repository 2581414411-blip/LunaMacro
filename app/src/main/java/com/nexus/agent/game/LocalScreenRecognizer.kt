package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
                lastError = "模型文件不全（$MODEL_DIR 目录）"
                Log.i(TAG, "本地模型未下载，识别与副驾驶对话将走云端")
                return
            }

            modelAvailable = true
            state = State.LOADING
            lastError = null
            if (LocalRecognizerNative.ensureLoaded()) {
                initialized = LocalRecognizerNative.init(targetDir.absolutePath)
            } else {
                initialized = false
                lastError = "native 库加载失败"
            }
            state = if (initialized) State.READY else State.FAILED
            if (!initialized && lastError == null) lastError = "native init 返回 false"
        } catch (e: Throwable) {
            Log.e(TAG, "模型初始化失败", e)
            initialized = false
            state = State.FAILED
            lastError = "${e.javaClass.simpleName}: ${e.message}"
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
            val result = LocalRecognizerNative.recognize(imgFile.absolutePath, PROMPT)
            parseResult(result)
        } catch (e: Throwable) {
            Log.e(TAG, "本地识别失败", e)
            null
        } finally {
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
            if (!initialized) return@withContext null

            val imgFile = saveBitmapToTemp(bitmap) ?: return@withContext null
            try {
                LocalRecognizerNative.recognize(imgFile.absolutePath, prompt)
            } catch (e: Throwable) {
                Log.e(TAG, "本地推理失败", e)
                null
            } finally {
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
    private fun isModelReady(dir: File): Boolean {
        val required = listOf(
            "llm.mnn", "llm.mnn.weight",
            "visual.mnn", "visual.mnn.weight",
            "tokenizer.mtok", "config.json"
        )
        return required.all { name ->
            val f = File(dir, name)
            f.exists() && f.length() > 0
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
