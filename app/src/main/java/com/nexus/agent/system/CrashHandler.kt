package com.nexus.agent.system

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 全局崩溃捕获。
 *
 * 之前的痛点是：用户说「闪退 / 用不了」，但手上没有任何堆栈，只能靠真机复现去猜。
 * 这里接管 [Thread.UncaughtExceptionHandler]，把崩溃现场落盘成可读的 txt：
 *
 * - 时间、线程、**当前页面**（由 Activity 生命周期跟踪）
 * - 应用版本 / versionCode / 包名
 * - 设备型号、系统版本、ABI、内存占用
 * - 完整异常栈（含 Caused by）
 * - 主线程栈（用于判断是卡死还是纯异常）
 *
 * 写完后**必须交还给系统原 handler**，否则应用会「卡在崩溃现场」不退出。
 *
 * 存储：`filesDir/crash/crash_{yyyyMMdd_HHmmss_SSS}.txt`，最多保留 [MAX_FILES] 份。
 */
object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val DIR_NAME = "crash"

    /** 最多保留的崩溃文件数（超了删最旧的） */
    private const val MAX_FILES = 20

    /** 系统原有 handler（写完文件后交还） */
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var appContext: Context? = null

    /** 当前页面（Activity 类名，由生命周期回调维护） */
    @Volatile
    private var currentPage: String = "未知"

    /** 是否在前台 */
    @Volatile
    private var foreground: Boolean = false

    @Volatile
    private var installed = false

    // ------------------------------------------------------------------ 安装

    /**
     * 安装崩溃捕获（幂等）。
     *
     * 同时注册 Activity 生命周期回调，用于记录「崩溃时在哪个页面」——
     * 这是排查用户反馈时最有价值的一条信息。
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        appContext = app.applicationContext

        // 记录页面：onResume 记页面，onPause 记后台
        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    currentPage = activity.javaClass.simpleName.ifBlank { "未知" }
                    foreground = true
                }

                override fun onActivityPaused(activity: Activity) {
                    foreground = false
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
        } catch (e: Throwable) {
            Log.w(TAG, "注册生命周期回调失败（页面信息会缺失）：${e.message}")
        }

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "全局崩溃捕获已安装，目录=${dir(app.applicationContext).absolutePath}")
    }

    // ------------------------------------------------------------------ 捕获

    override fun uncaughtException(t: Thread, e: Throwable) {
        // 任何写文件的异常都不能再抛，否则会二次崩溃（用户看到的还是系统弹窗，但日志全丢）
        try {
            val file = writeCrashFile(t, e)
            if (file != null) Log.e(TAG, "崩溃已记录：${file.name}")
        } catch (inner: Throwable) {
            Log.e(TAG, "记录崩溃失败", inner)
        } finally {
            // 交还给系统原 handler（默认会弹「应用已停止」并退出进程）
            val prev = previousHandler
            if (prev != null && prev !== this) {
                prev.uncaughtException(t, e)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 把崩溃现场写成 txt，返回文件；失败返回 null */
    private fun writeCrashFile(t: Thread, e: Throwable): File? {
        val ctx = appContext ?: return null
        val dir = dir(ctx).apply { mkdirs() }
        if (!dir.exists()) return null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val file = File(dir, "crash_$stamp.txt")

        val content = buildString {
            appendLine("=== 崩溃报告 ===")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("线程: ${t.name} (id=${t.id})")
            appendLine("页面: $currentPage")
            appendLine("应用在前台: $foreground")
            appendLine()

            appendLine("=== 应用 ===")
            appendLine("包名: ${ctx.packageName}")
            appendLine("版本: ${versionName(ctx)} (versionCode ${versionCode(ctx)})")
            appendLine()

            appendLine("=== 设备 ===")
            appendLine("厂商/型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
            val rt = Runtime.getRuntime()
            appendLine(
                "内存: 已用 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB / " +
                    "上限 ${rt.maxMemory() / 1048576}MB"
            )
            appendLine()

            appendLine("=== 异常 ===")
            appendLine(stackTraceOf(e))
            appendLine()

            appendLine("=== 主线程栈 ===")
            try {
                val main = Thread.getAllStackTraces().entries
                    .firstOrNull { it.key.name == "main" }
                if (main != null) {
                    main.value.forEach { appendLine("    at $it") }
                } else {
                    appendLine("（未取到主线程栈）")
                }
            } catch (inner: Throwable) {
                appendLine("（取主线程栈失败：${inner.message}）")
            }
            appendLine()
            appendLine("=== 说明 ===")
            appendLine("此文件由宏月 CrashHandler 自动生成，可在「设置 → 日志 → 导出崩溃日志」打包分享。")
        }

        file.writeText(content, Charsets.UTF_8)
        trimOldFiles(dir)
        return file
    }

    private fun stackTraceOf(e: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        return sw.toString()
    }

    /** 只保留最近 [MAX_FILES] 份 */
    private fun trimOldFiles(dir: File) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
                ?.sortedBy { it.name } ?: return
            if (files.size <= MAX_FILES) return
            files.take(files.size - MAX_FILES).forEach { it.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "清理旧崩溃文件失败：${e.message}")
        }
    }

    // ------------------------------------------------------------------ 读取 / 导出

    /** 崩溃目录 */
    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 所有崩溃文件（新→旧） */
    fun list(context: Context): List<File> {
        val d = dir(context)
        if (!d.exists()) return emptyList()
        return d.listFiles { f -> f.isFile && f.name.startsWith("crash_") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 崩溃文件数量（设置页显示「N 条」） */
    fun count(context: Context): Int = list(context).size

    /**
     * 打包崩溃日志为 zip（放 cacheDir，供 FileProvider 分享）。
     *
     * @return zip 文件；没有崩溃记录时返回 null
     */
    fun exportZip(context: Context): File? {
        val files = list(context)
        if (files.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "luna_crash_$stamp.zip")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            files.forEach { f ->
                try {
                    zip.putNextEntry(ZipEntry(f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                } catch (e: Throwable) {
                    Log.w(TAG, "打包 ${f.name} 失败：${e.message}")
                }
            }
            // 附一份索引，方便快速看有几次崩溃
            zip.putNextEntry(ZipEntry("index.txt"))
            zip.write(
                buildString {
                    appendLine("崩溃记录 ${files.size} 条（新→旧）")
                    files.forEach { appendLine("${it.name}\t${it.length()}B") }
                }.toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        Log.i(TAG, "崩溃日志已打包：${zipFile.absolutePath}（${files.size} 条）")
        return zipFile
    }

    /** 生成 zip 并调起系统分享面板（主线程调用） */
    fun share(context: Context): Boolean {
        val zip = exportZip(context) ?: return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享崩溃日志"))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "分享崩溃日志失败", e)
            false
        }
    }

    /** 清空全部崩溃记录 */
    fun clear(context: Context): Int {
        val files = list(context)
        var n = 0
        files.forEach { if (it.delete()) n++ }
        Log.i(TAG, "已清空 $n 份崩溃记录")
        return n
    }

    // ------------------------------------------------------------------ 工具

    private fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    } catch (e: Throwable) {
        "unknown"
    }

    @Suppress("DEPRECATION")
    private fun versionCode(ctx: Context): Long = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Throwable) {
        -1L
    }
}
