package com.nexus.agent.system

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nexus.agent.game.LocalScreenRecognizer
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 一键日志提取。
 *
 * 点击「设置 → 导出日志」后：
 * 1. 读取 logcat 最近 30 分钟日志
 * 2. 过滤关键 tag（Agent 执行 / 语音 / 注入链路）
 * 3. 自动脱敏 API Key 等敏感信息
 * 4. 打包为 zip
 * 5. 调起系统分享面板（微信 / 邮件 / 文件管理器）
 *
 * 文件名格式：nexus_log_{时间戳}_{机型}.zip
 */
object LogExporter {

    private const val TAG = "LogExporter"

    /** 关键 tag（日志导出只保留这些，减小体积、聚焦问题） */
    private val KEY_TAGS = listOf(
        "ShizukuHealth", "AgentRunner", "Executor", "WakeWordRaw",
        "VoiceRecorder", "AsrClient", "NexusTool", "NexusVision",
        "UserServiceImpl", "ShizukuInputInjector", "SafetyPolicy", "TtsSpeaker",
        // 0.8.6 补：视觉与灵动岛链路（之前完全没进白名单，出了事导出是空的）
        "VisionWorker", "VisionStage", "VisionSelfCheck", "LocalScreenRecognizer",
        "LocalRecognizerNative", "CopilotEngine", "CopilotIsland", "ScreenCapture",
        "ShizukuManager", "ModelManager", "PersonaEngine", "MainActivity", "NexusAgent"
    )

    /**
     * **崩溃相关 tag —— 0.8.6 新增，不加这个等于白导出。**
     *
     * 之前白名单只有自家 tag，于是 `AndroidRuntime`（Java 崩溃）、`DEBUG`/`libc`
     * （native 崩溃墓碑）、`lmkd`（内存不足被杀）**全部被过滤掉**。
     * 用户报「点测试就回桌面」时导出的日志只有几行健康检查，
     * 真正的死因一个字都看不到 —— 白排查了两轮。
     */
    private val CRASH_TAGS = listOf(
        "AndroidRuntime", "DEBUG", "libc", "lmkd", "lowmemorykiller",
        "CrashHandler", "ANR in", "FATAL EXCEPTION"
    )

    /**
     * 崩溃关键字：不绑 tag，只要行里出现就算。
     *
     * `com.nexus.agent` 用来捞 ActivityManager 里「Killing / Force stopping 我们的进程」，
     * 比整个 AMS tag 全收下来干净得多。
     */
    private val CRASH_HINTS = listOf(
        "com.nexus.agent", "SIGSEGV", "SIGBUS", "SIGABRT",
        "Killing", "Force stopping", "Process died", "fatal signal"
    )

    /**
     * 一键导出（后台线程执行，完成后主线程调起分享）。
     */
    fun export(context: Context) {
        Thread {
            try {
                val zipFile = collectToZip(context)
                Handler(Looper.getMainLooper()).post {
                    try {
                        shareZip(context, zipFile)
                    } catch (e: Throwable) {
                        Log.e(TAG, "share failed", e)
                        Toast.makeText(context, "日志已生成：${zipFile.name}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "export failed", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "日志导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * 导出「视觉模型初始化报告」。
     *
     * 与 [export] 的区别：不读 logcat（那里面只有一行 Log.e，看不出缺什么），
     * 而是拿 [LocalScreenRecognizer] 落盘的那份现场报告：模型目录、6 个必需文件的
     * 存在与大小、5 个 so 是否**在 APK 内**（不是 nativeLibraryDir —— 本工程
     * `extractNativeLibs=false`，那个目录本来就查不到）、逐库真实加载结果、ABI、机型、完整堆栈。
     *
     * 文件不存在时**现场跑一次探测**再导，保证这个按钮永远有产出，
     * 不用先「故意让它失败一次」。
     *
     * @return 是否成功调起分享
     */
    fun exportVisionInit(context: Context): Boolean {
        val file = LocalScreenRecognizer.probe(context)
            ?: LocalScreenRecognizer.errorFile(context).takeIf { it.exists() }
            ?: return false
        return try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出模型初始化日志"))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "vision init report share failed", e)
            false
        }
    }

    /**
     * 一行日志该不该留。
     *
     * 三道闸取并集：自家 tag / 崩溃 tag / 崩溃关键字。
     * 抽成函数是为了能单测 —— 之前就是因为只有一道「白名单 tag」闸，
     * 崩溃行全被滤掉，导出的日志看起来「什么都没发生」。
     */
    internal fun keepLine(line: String): Boolean =
        KEY_TAGS.any { line.contains(it) } ||
            CRASH_TAGS.any { line.contains(it) } ||
            CRASH_HINTS.any { line.contains(it) }

    /** 收集日志并打包为 zip，返回文件。 */
    private fun collectToZip(context: Context): File {
        val lines = readRecentLogcat()
        val filtered = lines.filter { keepLine(it) }
        val sanitized = filtered.map { sanitize(it) }
        val content = sanitized.joinToString("\n")

        val model = sanitizeFileName(Build.MODEL ?: "unknown")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(context.cacheDir, "nexus_log_${stamp}_$model.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("nexus.log"))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        Log.i(TAG, "日志已打包：${zipFile.absolutePath}（${sanitized.size} 行）")
        return zipFile
    }

    /** 读取 logcat 最近 30 分钟日志。 */
    private fun readRecentLogcat(minutes: Int = 30): List<String> {
        val lines = try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time"))
            proc.inputStream.bufferedReader().readLines()
        } catch (e: Throwable) {
            Log.e(TAG, "read logcat failed", e)
            return emptyList()
        }
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        return lines.filter { line ->
            val ts = parseTimestamp(line) ?: return@filter false
            ts >= cutoff
        }
    }

    /** 解析 logcat `-v time` 输出的时间戳（MM-DD HH:mm:ss.mmm）。 */
    private fun parseTimestamp(line: String): Long? {
        val m = Regex("(\\d{2})-(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})").find(line) ?: return null
        val (mon, day, h, min, s) = m.destructured
        val cal = Calendar.getInstance().apply {
            set(Calendar.MONTH, mon.toInt() - 1)
            set(Calendar.DAY_OF_MONTH, day.toInt())
            set(Calendar.HOUR_OF_DAY, h.toInt())
            set(Calendar.MINUTE, min.toInt())
            set(Calendar.SECOND, s.toInt())
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 脱敏 API Key / token 等敏感信息。 */
    private fun sanitize(line: String): String {
        var s = line
        s = s.replace(Regex("(sk-[A-Za-z0-9]{6,})"), "sk-***")
        s = s.replace(Regex("(Bearer\\s+)[A-Za-z0-9._\\-]{8,}"), "$1***")
        s = s.replace(
            Regex("(api[_\\-]?key[\"']?\\s*[:=]\\s*)[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE),
            "$1***"
        )
        return s
    }

    /** 文件名安全化（去掉机型名里的空格/斜杠等）。 */
    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")

    /** 通过系统分享面板分享 zip。 */
    private fun shareZip(context: Context, zipFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享日志"))
    }
}
