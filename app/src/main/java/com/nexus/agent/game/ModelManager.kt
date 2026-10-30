package com.nexus.agent.game

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
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
 *  - 外部下载器兜底：内置下载太慢时，用户可用 IDM/迅雷/aria2 下好 zip，
 *    再用 [importZip] / [importFolder] 从本地直接导入（不走网络）
 */
object ModelManager {

    private const val TAG = "ModelManager"

    /** GitHub Release 下载源（release tag = "models"） */
    private const val GITHUB_RELEASE =
        "https://github.com/2581414411-blip/LunaMacro/releases/download/models"

    /** 视觉模型包名（给 UI / 外部下载器展示） */
    const val VISION_ZIP_NAME = "models.zip"

    /** 语音模型包名 */
    const val SENSEVOICE_ZIP_NAME = "sensevoice.zip"

    /**
     * 手动下载地址（供外部下载器使用）：镜像在前，官方源兜底。
     * @return zip 文件名 → 候选 URL 列表（第一项通常是国内最快的镜像）
     */
    fun manualUrls(zipName: String = VISION_ZIP_NAME): List<String> =
        MIRRORS.map { it + GITHUB_RELEASE + "/" + zipName } + (GITHUB_RELEASE + "/" + zipName)

    /** 官方直链（GitHub），给电脑端下载器用 */
    fun officialUrl(zipName: String): String = GITHUB_RELEASE + "/" + zipName

    /**
     * GitHub 加速镜像前缀（国内下载更快，逐个尝试）。
     *
     * 2026-10-30 国内直连实测（models.zip 前 8MB）：
     *  - ghfast.top     206 可续传，≈1.2MB/s ← 主力
     *  - gh-proxy.com   206 可续传，但慢（≈100KB/s）且偶发忽略 Range
     *  - ghproxy.net    206 可续传，握手就要 15s ← 最后兜底
     *  - GitHub 官方直连：models.zip 直接超时（1.3GB 压根下不动）
     *  - gh.llkk.cc / github.moeyy.xyz / gh.xxooo.cf / ghps.cc / gh.ddlc.top 均已失效
     * 所以内置下载只当兜底，大文件建议外部下载器 + 本地导入。
     */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
    )

    private const val VISION_ZIP = VISION_ZIP_NAME
    private const val SENSEVOICE_ZIP = SENSEVOICE_ZIP_NAME

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

    /** 本地导入进行中（zip / 文件夹） */
    @Volatile
    var importing = false
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

    // ───────────────────── 本地导入（外部下载器兜底，不走网络） ─────────────────────

    /**
     * 导入用户用外部下载器（IDM / 迅雷 / aria2 / 浏览器）下好的模型包 zip。
     *
     * 流程：全部解到暂存目录 → 按文件名归类搬进 models/ 与 sensevoice/。
     * 所以单包（models.zip）、双包（含 models/ 与 sensevoice/ 两个目录）、
     * 带一层外层目录的包都能吃。
     */
    suspend fun importZip(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        runImport(context, onProgress) { staging, report ->
            val raw = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件，请重新选择（文件可能已被移动或删除）")
            var done = 0L
            var files = 0
            raw.buffered().use { buffered ->
                ZipInputStream(buffered).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val name = entry.name.replace('\\', '/')
                        // 跳过目录、macOS 垃圾文件与路径穿越
                        if (entry.isDirectory || name.startsWith("__MACOSX/") ||
                            name.endsWith(".DS_Store") || name.contains("..")
                        ) {
                            zis.closeEntry()
                            continue
                        }
                        copyStream(zis, File(staging, name)) { n ->
                            done += n
                            report("正在解压：$files 个文件 / ${done / 1024 / 1024}MB")
                        }
                        files++
                        zis.closeEntry()
                    }
                }
            }
            files
        }
    }

    /**
     * 导入已解压的模型文件夹（用户在手机用 MT 管理器 / RAR 解压后直接选目录）。
     * 递归复制目录树，最后同样按文件名归类。
     */
    suspend fun importFolder(
        context: Context,
        treeUri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        runImport(context, onProgress) { staging, report ->
            val counter = IntArray(1)
            var done = 0L
            copyDocTree(context, treeUri, staging, { n ->
                done += n
                report("正在复制：${counter[0]} 个文件 / ${done / 1024 / 1024}MB")
            }, counter)
            counter[0]
        }
    }

    /**
     * 导入骨架：暂存 → 归类 → 校验。extract 返回导入的文件数量。
     */
    private fun runImport(
        context: Context,
        onProgress: (Float, String) -> Unit,
        extract: (staging: File, report: (String) -> Unit) -> Int
    ): Boolean {
        if (importing || downloading) {
            emit(onProgress, -1f, "已有下载或导入任务在进行中，请稍候")
            return false
        }
        importing = true
        val staging = File(tmpDir(context), "import_staging")
        try {
            emit(onProgress, -1f, "准备导入…")
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                emit(onProgress, -1f, "无法创建临时目录，请检查存储空间")
                return false
            }
            val available = freeBytes(context.getExternalFilesDir(null) ?: context.filesDir)
            if (available in 0 until 300_000_000L) {
                emit(onProgress, -1f, "存储空间不足：至少还需 300MB，当前可用 ${available / 1024 / 1024}MB")
                return false
            }

            var lastAt = 0L
            val report = { msg: String ->
                val now = System.currentTimeMillis()
                if (now - lastAt >= 600) {
                    lastAt = now
                    emit(onProgress, -1f, msg)
                }
            }
            val count = extract(staging, report)
            Log.i(TAG, "导入暂存完成：$count 个文件")
            if (count <= 0) {
                emit(onProgress, -1f, "导入失败：所选内容里没有文件")
                return false
            }
            return classifyAndInstall(context, staging, onProgress)
        } catch (e: Throwable) {
            Log.e(TAG, "导入失败", e)
            emit(onProgress, -1f, "导入失败：${e.message ?: e.javaClass.simpleName}")
            return false
        } finally {
            staging.deleteRecursively()
            importing = false
        }
    }

    /** 把暂存目录里的文件按名字归类搬进 models/ 与 sensevoice/，并校验完整性 */
    private fun classifyAndInstall(
        context: Context,
        staging: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        emit(onProgress, -1f, "整理文件…")
        val all = staging.walkTopDown().filter { it.isFile }.toList()
        if (all.isEmpty()) {
            emit(onProgress, -1f, "导入失败：包里没有文件")
            return false
        }
        val hasVision = all.any { it.name in VISION_FILES }
        val hasVoice = all.any { it.name in SENSEVOICE_FILES }
        if (!hasVision && !hasVoice) {
            emit(
                onProgress, -1f,
                "这不是模型包：没找到 llm.mnn / visual.mnn / model.int8.onnx 等文件"
            )
            return false
        }

        val vision = visionDir(context).apply { mkdirs() }
        val voice = senseVoiceDir(context).apply { mkdirs() }
        val both = hasVision && hasVoice

        for (f in all) {
            val rel = f.relativeTo(staging).path.replace('\\', '/')
            val toVoice = when {
                f.name in SENSEVOICE_FILES -> true
                f.name in VISION_FILES -> false
                // 未知文件（tokens.txt 之外的附属文件）：双包按目录前缀判断
                both -> rel.startsWith("$SENSEVOICE_DIR/") || rel.contains("/$SENSEVOICE_DIR/")
                else -> hasVoice && !hasVision
            }
            val dest = File(if (toVoice) voice else vision, f.name)
            dest.parentFile?.mkdirs()
            if (dest.exists()) dest.delete()
            if (!f.renameTo(dest)) {
                f.copyTo(dest, overwrite = true)
                f.delete()
            }
        }

        val vReady = isVisionModelReady(context)
        val sReady = isSenseVoiceReady(context)
        val missing = ArrayList<String>()
        if (hasVision && !vReady) {
            VISION_FILES.filterNot { File(vision, it).exists() }.forEach { missing += "models/$it" }
        }
        if (hasVoice && !sReady) {
            SENSEVOICE_FILES.filterNot { File(voice, it).exists() }.forEach { missing += "sensevoice/$it" }
        }

        val summary = buildString {
            append("视觉模型：")
            append(if (vReady) "● 已就绪" else "○ 未就绪")
            append("；语音模型：")
            append(if (sReady) "● 已就绪" else "○ 未就绪")
        }
        return if (missing.isEmpty()) {
            Log.i(TAG, "导入成功：$summary")
            emit(onProgress, 1f, "导入完成 · $summary")
            true
        } else {
            val msg = "导入不完整，缺少：${missing.joinToString("、")}"
            Log.e(TAG, msg)
            emit(onProgress, -1f, msg)
            false
        }
    }

    /** 递归把 SAF 目录树里的文件复制到暂存目录 */
    private fun copyDocTree(
        context: Context,
        dirUri: Uri,
        outDir: File,
        onChunk: (Long) -> Unit,
        counter: IntArray
    ) {
        val resolver = context.contentResolver
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirUri, DocumentsContract.getDocumentId(dirUri)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, id)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val sub = File(outDir, name).apply { mkdirs() }
                    copyDocTree(context, childUri, sub, onChunk, counter)
                } else {
                    if (name.endsWith(".DS_Store")) continue
                    resolver.openInputStream(childUri)?.use { input ->
                        copyStream(input, File(outDir, name), onChunk)
                    }
                    counter[0]++
                }
            }
        } ?: Log.w(TAG, "无法列出目录：$dirUri")
    }

    /** 流复制（256KB 缓冲），逐块回调字节数用于进度显示 */
    private fun copyStream(input: java.io.InputStream, out: File, onChunk: (Long) -> Unit) {
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                fos.write(buf, 0, n)
                onChunk(n.toLong())
            }
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
