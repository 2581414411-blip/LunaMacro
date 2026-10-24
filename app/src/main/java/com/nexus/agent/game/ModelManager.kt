package com.nexus.agent.game

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 模型下载管理（瘦身版 APK 用）。
 *
 * 瘦身版 APK 不内置本地大模型（视觉 Qwen2-VL-2B 1.3GB + SenseVoice 229MB），
 * 首次需要时从 GitHub Releases 下载到外部存储，之后直接加载。
 *
 * 模型目录结构（外部存储）：
 *   models/      视觉模型（llm.mnn 等）
 *   sensevoice/  SenseVoice 语音识别（model.int8.onnx 等）
 *
 * 完整版 APK（assets 内置模型）不受影响：assets 解压逻辑仍走 LocalScreenRecognizer 原有路径。
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** GitHub Releases 模型下载源（release tag = "models"） */
    private const val RELEASE_BASE =
        "https://github.com/2581414411-blip/LunaMacro/releases/download/models"

    private const val VISION_ZIP = "models.zip"
    private const val SENSEVOICE_ZIP = "sensevoice.zip"

    const val VISION_DIR = "models"
    const val SENSEVOICE_DIR = "sensevoice"

    /** 视觉模型必需文件 */
    private val VISION_FILES = listOf(
        "llm.mnn", "llm.mnn.weight",
        "visual.mnn", "visual.mnn.weight",
        "tokenizer.mtok", "config.json"
    )

    /** SenseVoice 必需文件 */
    private val SENSEVOICE_FILES = listOf("model.int8.onnx", "tokens.txt")

    @Volatile
    var downloading = false
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 外部存储模型根目录 */
    fun modelRoot(context: Context): File =
        File(context.getExternalFilesDir(null), "")

    fun visionDir(context: Context): File =
        File(context.getExternalFilesDir(null), VISION_DIR)

    fun senseVoiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), SENSEVOICE_DIR)

    /** 视觉模型是否就绪 */
    fun isVisionModelReady(context: Context): Boolean {
        val dir = visionDir(context)
        return VISION_FILES.all { f ->
            File(dir, f).exists() && File(dir, f).length() > 0
        }
    }

    /** SenseVoice 是否就绪 */
    fun isSenseVoiceReady(context: Context): Boolean {
        val dir = senseVoiceDir(context)
        return SENSEVOICE_FILES.all { f ->
            File(dir, f).exists() && File(dir, f).length() > 0
        }
    }

    /**
     * 下载缺失的模型（视觉 + 语音）。
     *
     * @param onProgress 进度回调（0~1 总进度，stage 当前阶段文字）
     * @return 是否全部就绪
     */
    suspend fun ensureModels(
        context: Context,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        if (downloading) return@withContext false
        downloading = true
        try {
            val needVision = !isVisionModelReady(context)
            val needSenseVoice = !isSenseVoiceReady(context)
            if (!needVision && !needSenseVoice) {
                onProgress(1f, "模型已就绪")
                return@withContext true
            }
            var ok = true
            if (needVision) {
                ok = ok && downloadAndExtract(
                    context, VISION_ZIP, visionDir(context),
                    onProgress = { p -> onProgress(p * 0.7f, "下载视觉模型 ${(p * 100).toInt()}%") }
                )
            }
            if (ok && needSenseVoice) {
                ok = ok && downloadAndExtract(
                    context, SENSEVOICE_ZIP, senseVoiceDir(context),
                    onProgress = { p -> onProgress(0.7f + p * 0.3f, "下载语音模型 ${(p * 100).toInt()}%") }
                )
            }
            if (ok) onProgress(1f, "模型就绪")
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "模型下载失败", e)
            false
        } finally {
            downloading = false
        }
    }

    /**
     * 下载一个 zip 并解压到目标目录。
     */
    private fun downloadAndExtract(
        context: Context,
        zipName: String,
        targetDir: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val url = "$RELEASE_BASE/$zipName"
        targetDir.mkdirs()
        val tmp = File(context.cacheDir, zipName)
        return try {
            // 1. 下载
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "下载失败 HTTP ${resp.code}: $url")
                    return false
                }
                val body = resp.body ?: return false
                val total = body.contentLength()
                var done = 0L
                FileOutputStream(tmp).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            Log.i(TAG, "下载完成 $zipName (${tmp.length() / 1024 / 1024} MB)")
            // 2. 解压
            extractZip(tmp, targetDir)
            tmp.delete()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "下载/解压失败 $zipName", e)
            tmp.delete()
            false
        }
    }

    /** 解压 zip 到目标目录（zip 根目录文件直接落 targetDir） */
    private fun extractZip(zip: File, targetDir: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val outFile = File(targetDir, entry.name)
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { out ->
                    zis.copyTo(out)
                }
                zis.closeEntry()
            }
        }
    }
}
