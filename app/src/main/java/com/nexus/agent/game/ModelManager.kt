package com.nexus.agent.game

import android.content.Context
import android.os.StatFs
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

    /** 各 zip 大致体积（字节，用于下载前空间预检） */
    private const val VISION_ZIP_BYTES = 1_389_000_000L      // ≈1.29GB
    private const val SENSEVOICE_ZIP_BYTES = 239_600_000L    // ≈228MB

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

    /** 当前阶段文字（供 UI 在重进页面时回显） */
    @Volatile
    var lastStage: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun visionDir(context: Context): File =
        File(context.getExternalFilesDir(null), VISION_DIR)

    fun senseVoiceDir(context: Context): File =
        File(context.getExternalFilesDir(null), SENSEVOICE_DIR)

    /**
     * 临时下载目录：放在外部存储（与解压目标同一存储卷）。
     *
     * 不用 cacheDir：① 内部存储通常只有几 GB，1.3GB zip + 1.3GB 解压会直接写满；
     * ② cacheDir 会被系统在低存储时自动清理，下载到一半被清掉就永远续不上。
     */
    private fun tmpDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ".download").apply { mkdirs() }
    }

    /** 目标存储卷可用空间（字节） */
    private fun freeBytes(dir: File): Long = try {
        val st = StatFs(dir.absolutePath)
        st.availableBytes
    } catch (e: Throwable) {
        Log.w(TAG, "读取可用空间失败", e)
        -1L
    }

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
                emit(onProgress, 1f, "模型已就绪")
                return@withContext true
            }

            // 空间预检：zip + 解压后 ≈ 2 倍体积（已存在的 .part 可抵扣）
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            base.mkdirs()
            val available = freeBytes(base)
            var need = 0L
            if (needVision) {
                val part = File(tmpDir(context), VISION_ZIP + ".part").length()
                need += (VISION_ZIP_BYTES - part).coerceAtLeast(0) + VISION_ZIP_BYTES
            }
            if (needSenseVoice) {
                val part = File(tmpDir(context), SENSEVOICE_ZIP + ".part").length()
                need += (SENSEVOICE_ZIP_BYTES - part).coerceAtLeast(0) + SENSEVOICE_ZIP_BYTES
            }
            if (available in 0 until need) {
                val msg = "存储空间不足：还需约 ${need / 1024 / 1024 / 1024}GB，" +
                    "当前可用 ${available / 1024 / 1024 / 1024}GB"
                Log.e(TAG, msg)
                emit(onProgress, -1f, msg)
                return@withContext false
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
            if (ok) emit(onProgress, 1f, "模型就绪") else emit(onProgress, -1f, lastStage)
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "模型下载失败", e)
            emit(onProgress, -1f, "下载失败：${e.message ?: e.javaClass.simpleName}")
            false
        } finally {
            downloading = false
        }
    }

    private fun emit(onProgress: (Float, String) -> Unit, p: Float, stage: String) {
        lastStage = stage
        try {
            onProgress(p, stage)
        } catch (e: Throwable) {
            Log.w(TAG, "进度回调异常", e)
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
        val tmp = File(tmpDir(context), "$zipName.part")
        val sources = MIRRORS.map { it + GITHUB_RELEASE + "/" + zipName } +
            (GITHUB_RELEASE + "/" + zipName)

        var downloaded = false
        for ((i, url) in sources.withIndex()) {
            val label = if (i < MIRRORS.size) "镜像${i + 1}" else "官方源"
            Log.i(TAG, "尝试下载 [$label] $url")
            emit(onProgress, -1f, "连接$label…")
            val ok = downloadOne(url, tmp, label) { p, s ->
                onProgress(p, s)
            }
            if (ok) {
                downloaded = true
                break
            }
            // 该源彻底失败且没下到任何字节才丢掉 .part（下到一半的保留以便续传）
            if (tmp.exists() && tmp.length() == 0L) tmp.delete()
        }
        if (!downloaded) {
            Log.e(TAG, "所有下载源均失败: $zipName")
            emit(onProgress, -1f, "所有下载源均失败（已下载部分已保留，可重试续传）")
            return false
        }

        return try {
            emit(onProgress, 0.95f, "解压$zipName…")
            extractZip(tmp, targetDir)
            tmp.delete()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "解压失败 $zipName", e)
            tmp.delete()
            emit(onProgress, -1f, "解压失败：${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * 从单个源下载（支持断点续传）。
     *
     * 进度回调做了节流：只有百分比整数变化才上报，避免 1.3GB 文件触发上万次
     * Compose 重组把 UI 卡住（这是「下载时界面像卡死」的主因）。
     */
    private fun downloadOne(
        url: String,
        tmp: File,
        label: String,
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
                    emit(onProgress, -1f, "[$label] HTTP $code")
                    return false
                }
                // 206=按断点续传追加；200=服务端忽略 Range，必须从头覆盖写
                val append = code == 206
                val body = resp.body ?: return false
                val total = if (append) {
                    (body.contentLength() + startOffset).takeIf { it > 0 }
                } else {
                    body.contentLength().takeIf { it > 0 }
                }
                var done = if (append) startOffset else 0L
                var lastPct = -1
                var lastEmitAt = 0L
                FileOutputStream(tmp, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val pct = if (total != null && total > 0) {
                                (done * 100 / total).toInt()
                            } else {
                                -1
                            }
                            val now = System.currentTimeMillis()
                            val changed = pct >= 0 && pct != lastPct
                            if (changed && (now - lastEmitAt >= 250L || pct == 100)) {
                                lastPct = pct
                                lastEmitAt = now
                                val s = if (total != null && total > 0) {
                                    "[$label] $pct% · ${done / 1024 / 1024}MB / " +
                                        "${total / 1024 / 1024}MB"
                                } else {
                                    "[$label] ${done / 1024 / 1024}MB"
                                }
                                onProgress(
                                    if (total != null && total > 0) done.toFloat() / total else -1f,
                                    s
                                )
                            }
                        }
                    }
                }
                Log.i(TAG, "下载完成: $url (${tmp.length() / 1024 / 1024} MB)")
                emit(onProgress, 1f, "[$label] 下载完成")
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "下载异常 $url", e)
            val kept = if (tmp.exists()) tmp.length() / 1024 / 1024 else 0L
            emit(onProgress, -1f, "[$label] 中断（已缓存 ${kept}MB，可续传）")
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
