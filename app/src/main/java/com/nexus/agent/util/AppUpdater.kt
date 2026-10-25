package com.nexus.agent.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：下载 APK → 调系统安装器（不跳 GitHub 网页）。
 *
 * 为什么不用浏览器下载：GitHub 的 releases 页面在国内经常打不开或下载中断，
 * 而 api.github.com 与加速镜像是通的 —— 所以下载动作必须由 App 自己做。
 *
 * 下载策略与 [com.nexus.agent.game.ModelManager] 保持一致：
 *  - 多源尝试：加速镜像（国内快）→ GitHub 原始地址兜底
 *  - 断点续传：保留 `.part`，重试时用 Range 从断点继续
 *  - 完整性校验：大小与 Release asset 的 size 对齐，并检查 zip 头 "PK"，
 *    防止镜像返回一段 HTML 错误页被当成功
 *
 * 安装：FileProvider + ACTION_VIEW(package-archive)。Android 8+ 需要
 * 「安装未知应用」授权，未授权时先引导用户去设置页。
 */
object AppUpdater {

    private const val TAG = "AppUpdater"

    /** GitHub 加速镜像前缀（与模型下载共用同一批实测可用的） */
    private val MIRRORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    /** APK 最小合理体积（低于这个基本是错误页/空文件） */
    private const val MIN_APK_BYTES = 1L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 更新包存放目录：外部私有目录（内部存储常被模型占满） */
    fun updateDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "update").apply { mkdirs() }
    }

    fun apkFile(context: Context, version: String): File =
        File(updateDir(context), "LunaMacro-$version.apk")

    /**
     * 下载 APK（多源 + 续传）。
     *
     * @param expectedSize Release 里 asset 的字节数（0 = 未知，跳过大小校验）
     * @param onProgress (进度 0~1，-1 未知; 已下载字节; 总字节)
     * @return 下载好的文件；失败返回 null（可重试续传）
     */
    suspend fun downloadApk(
        context: Context,
        version: String,
        apkUrl: String,
        expectedSize: Long = 0L,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }
    ): File? = withContext(Dispatchers.IO) {
        val dir = updateDir(context)
        val target = File(dir, "LunaMacro-$version.apk")

        // 已经下过完整包：直接复用（用户点了「稍后」再点更新时不再重下 45MB）
        if (target.exists() && target.length() > MIN_APK_BYTES) {
            if (expectedSize <= 0L || target.length() == expectedSize) {
                Log.i(TAG, "复用已下载的安装包 ${target.name}（${target.length()} 字节）")
                onProgress(1f, target.length(), target.length())
                return@withContext target
            }
            Log.w(TAG, "已存在的安装包大小不符（${target.length()} != $expectedSize），删除重下")
            target.delete()
        }

        val part = File(dir, "LunaMacro-$version.apk.part")
        if (expectedSize > 0 && part.exists() && part.length() > expectedSize) part.delete()

        val urls = MIRRORS.map { it + apkUrl } + apkUrl
        for ((i, url) in urls.withIndex()) {
            val label = if (i < MIRRORS.size) "镜像${i + 1}" else "官方源"
            Log.i(TAG, "尝试下载 [$label] $url")
            val ok = downloadOne(url, part, label, onProgress)
            if (!ok) continue

            if (expectedSize > 0 && part.length() != expectedSize) {
                Log.w(TAG, "[$label] 大小不符：期望 $expectedSize，实际 ${part.length()}，换下一个源")
                part.delete()
                continue
            }
            if (!looksLikeApk(part)) {
                Log.w(TAG, "[$label] 下载内容不是 APK（zip 头不对），换下一个源")
                part.delete()
                continue
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            Log.i(TAG, "下载完成：${target.absolutePath}（${target.length()} 字节）")
            onProgress(1f, target.length(), target.length())
            return@withContext target
        }

        Log.e(TAG, "所有下载源均失败（已下载部分保留，可重试续传）")
        null
    }

    /**
     * 单源下载，支持断点续传。
     *
     * 进度节流：百分比整数变化或间隔 ≥250ms 才回调，避免每 64KB 重组一次 UI。
     */
    private fun downloadOne(
        url: String,
        part: File,
        label: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Boolean {
        val start = if (part.exists()) part.length() else 0L
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "LunaMacro-Android")
            .header("Cache-Control", "no-cache")
        if (start > 0) builder.header("Range", "bytes=$start-")

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                if (code != 200 && code != 206) {
                    Log.w(TAG, "[$label] HTTP $code")
                    // 4xx 说明该源对这个地址无效，别浪费时间续传
                    if (code in 400..499) part.delete()
                    return false
                }
                val append = code == 206 && start > 0
                val body = resp.body ?: return false
                val len = body.contentLength()
                val total = if (append) {
                    (len + start).takeIf { it > 0 }
                } else {
                    len.takeIf { it > 0 }
                }
                var done = if (append) start else 0L
                var lastPct = -1
                var lastEmitAt = 0L

                RandomAccessFile(part, "rw").use { raf ->
                    if (!append) raf.setLength(0)
                    raf.seek(if (append) start else 0L)
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            raf.write(buf, 0, n)
                            done += n
                            val pct = if (total != null && total > 0) {
                                (done * 100 / total).toInt()
                            } else -1
                            val now = SystemClock.elapsedRealtime()
                            if (pct != lastPct || now - lastEmitAt >= 250) {
                                lastPct = pct
                                lastEmitAt = now
                                val p = if (total != null && total > 0) {
                                    done.toFloat() / total
                                } else -1f
                                onProgress(p, done, total ?: 0L)
                            }
                        }
                    }
                }
                done > 0
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[$label] 下载异常：${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    /** 真 APK 一定是 zip（前两个字节 "PK"） */
    private fun looksLikeApk(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_APK_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                b0 == 'P'.code && b1 == 'K'.code
            }
        } catch (e: Throwable) {
            false
        }
    }

    /** 是否已被允许安装未知来源应用 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳「安装未知应用」授权页 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Throwable) {
            Log.w(TAG, "打开安装权限设置失败", e)
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Throwable) {
                Log.e(TAG, "连列表页也打不开", e2)
            }
        }
    }

    /**
     * 调系统安装器安装已下载的 APK。
     *
     * @return null = 已成功拉起安装器；非空 = 失败原因（可直接展示给用户）
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() <= 0L) return "安装包不存在，请重新下载"
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return "需要先允许「安装未知应用」，已跳转设置，授权后回来点「安装」"
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "已拉起系统安装器：${apk.name}")
            null
        } catch (e: Throwable) {
            Log.e(TAG, "拉起安装器失败", e)
            "无法启动系统安装器：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 清理旧版本安装包（避免外部私有目录越攒越大） */
    fun cleanOldApks(context: Context, keepVersion: String? = null) {
        try {
            val dir = updateDir(context)
            dir.listFiles()?.forEach { f ->
                val isKeep = keepVersion != null && f.name == "LunaMacro-$keepVersion.apk"
                val isCurrentPart = keepVersion != null && f.name == "LunaMacro-$keepVersion.apk.part"
                if (!isKeep && !isCurrentPart) f.delete()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "清理旧安装包失败", e)
        }
    }
}
