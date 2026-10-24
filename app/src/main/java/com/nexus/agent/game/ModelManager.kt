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
 * 下载策略：
 *  - 多源尝试：GitHub 加速镜像（国内快）→ GitHub 直连兜底
 *  - 断点续传：下载中断保留 .part，重试时用 Range 从断点继续
 *  - 长超时：大文件下载 readTimeout 300s，避免慢网络被误判超时
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** GitHub Release 下载源（release tag = "models"） */
    private const val GITHUB_RELEASE =
        "https://github.com/2581414411-blip/LunaMacro/releases/download/models"

    /** GitHub 加速镜像前缀（国内下载更快，逐个尝试） */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

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
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun visionDir(context: Context): File =
        File(context.getExternalFilesDir(null), VISION_DIR)

    fun senseVoiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), SENSEVOICE_DIR)

    /** 视觉模型是否就绪 */
    fun isVisionModelReady(context: Context): Boolean {
        val dir = visionDir(context)
        return VISION_FILES.all { f -> File(dir, f).exists() && File(dir, f).length() > 0 }
    }

    /** SenseVoice 是否就绪 */
    fun isSenseVoiceReady(context: Context): Boolean {
        val dir = senseVoiceDir(context)
        return SENSEVOICE_FILES.all { f -> File(dir, f).exists() && File(dir, f).length() > 0 }
    }

    /**
     * 下载缺失的模型（视觉 + 语音）。
     *
     * @param onProgress 进度回调（0~1 总进度，-1 表示未知；stage 为可读阶段文字）
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
                ok = downloadAndExtract(context, VISION_ZIP, visionDir(context)) { p, s ->
                    onProgress(p * 0.7f, s)
                }
            }
            if (ok && needSenseVoice) {
                ok = downloadAndExtract(context, SENSEVOICE_ZIP, senseVoiceDir(context)) { p, s ->
                    onProgress(0.7f + p * 0.3f, s)
                }
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
     * 下载一个 zip（多源 + 断点续传）并解压到目标目录。
     * 进度回调 p 为 0~1（未知时 -1），s 为可读文字。
     */
    private fun downloadAndExtract(
        context: Context,
        zipName: String,
        targetDir: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        targetDir.mkdirs()
        val tmp = File(context.cacheDir, "$zipName.part")
        val sources = MIRRORS.map { it + GITHUB_RELEASE + "/" + zipName } +
            (GITHUB_RELEASE + "/" + zipName)

        var downloaded = false
        for ((i, url) in sources.withIndex()) {
            val label = if (i < MIRRORS.size) "镜像${i + 1}" else "官方源"
            Log.i(TAG, "尝试下载 [$label] $url")
            val ok = downloadOne(url, tmp) { p, s ->
                onProgress(p, "下载$zipName [$label] $s")
            }
            if (ok) {
                downloaded = true
                break
            }
        }
        if (!downloaded) {
            Log.e(TAG, "所有下载源均失败: $zipName")
            return false
        }

        return try {
            extractZip(tmp, targetDir)
            tmp.delete()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "解压失败 $zipName", e)
            tmp.delete()
            false
        }
    }

    /**
     * 从单个源下载（支持断点续传）。进度 p：0~1 或 -1（未知），s 为文字。
     */
    private fun downloadOne(
        url: String,
        tmp: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        val startOffset = if (tmp.exists()) tmp.length() else 0L
        val reqBuilder = Request.Builder().url(url)
        if (startOffset > 0) reqBuilder.header("Range", "bytes=$startOffset-")
        return try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                if (code != 200 && code != 206) {
                    Log.e(TAG, "下载失败 HTTP $code: $url")
                    return false
                }
                val append = code == 206
                val body = resp.body ?: return false
                val total = if (code == 206) {
                    (body.contentLength() + startOffset).takeIf { it > 0 }
                } else {
                    body.contentLength().takeIf { it > 0 }
                }
                var done = startOffset
                FileOutputStream(tmp, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val text = if (total != null && total > 0) {
                                val pct = (done * 100 / total).toInt()
                                "$pct% (${done / 1024 / 1024}MB/${total / 1024 / 1024}MB)"
                            } else {
                                "${done / 1024 / 1024}MB"
                            }
                            onProgress(
                                if (total != null && total > 0) done.toFloat() / total else -1f,
                                text
                            )
                        }
                    }
                }
                Log.i(TAG, "下载完成: $url (${tmp.length() / 1024 / 1024} MB)")
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "下载异常 $url", e)
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
