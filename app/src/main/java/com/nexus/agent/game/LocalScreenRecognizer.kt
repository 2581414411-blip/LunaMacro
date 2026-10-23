package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import java.io.File
import java.io.FileOutputStream

/**
 * 本地屏幕识别器（Qwen2-VL-2B，MNN 部署）。
 *
 * 用本地模型识别游戏截图中的「按钮/可交互元素 + 坐标」（元素检测，而非界面分类）。
 * 模型从 assets 解压到外部存储，通过 JNI（LocalRecognizerNative）推理。
 * 模型缺失时返回 null，由上层降级到云端 Qwen-VL。
 */
object LocalScreenRecognizer {

    private const val TAG = "LocalScreenRecognizer"
    private const val MODEL_DIR = "models"

    private const val PROMPT =
        "请识别这张游戏截图中的所有按钮和可交互元素，输出每个元素的名称和归一化坐标（x,y 都在 0 到 1 之间）。"

    @Volatile
    private var initialized = false

    @Volatile
    private var modelAvailable = false

    private val buttonRegex =
        Regex("([\\u4e00-\\u9fa5A-Za-z0-9]+)\\s*[（(]\\s*([0-9.]+)\\s*[,，]\\s*([0-9.]+)\\s*[)）]")

    /**
     * 初始化：解压模型 → 加载 JNI → 初始化模型。
     */
    fun init(context: Context) {
        val targetDir = File(context.getExternalFilesDir(null), MODEL_DIR)
        try {
            if (!isModelReady(targetDir)) {
                copyModelsFromAssets(context, targetDir)
            }
            modelAvailable = isModelReady(targetDir)
            if (modelAvailable) {
                if (LocalRecognizerNative.ensureLoaded()) {
                    initialized = LocalRecognizerNative.init(targetDir.absolutePath)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "模型初始化失败", e)
            initialized = false
        }
        Log.i(TAG, "本地模型${if (initialized) "就绪" else "不可用，识别将降级云端 Qwen-VL"}")
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
    }

    /**
     * 识别一帧截图。
     *
     * @return 识别结果；模型未就绪时返回 null（上层降级云端）
     */
    suspend fun recognize(bitmap: Bitmap): ScreenAnalysis? {
        if (!initialized) return null

        val imgFile = saveBitmapToTemp(bitmap) ?: return null
        return try {
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
