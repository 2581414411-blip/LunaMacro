package com.nexus.agent.game

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 原生调用阶段的「黑匣子」。
 *
 * ## 为什么需要它
 *
 * native 层崩溃（SIGSEGV / SIGBUS / SIGABRT）**不会走 Java 的 uncaughtException**，
 * 所以 [com.nexus.agent.system.CrashHandler] 一个字节都写不出来 ——
 * 用户看到的就是「一点就闪退，没有任何日志」。
 *
 * 而视觉这边最容易 native 崩的只有三处：
 *  1. `System.loadLibrary()` —— dlopen 会执行 C++ 静态构造，里面出错就是进程级死亡；
 *  2. `LocalRecognizerNative.init()` —— 2B 模型 mmap + 加载；
 *  3. `LocalRecognizerNative.recognize()` —— 真推理。
 *
 * 做法很土但有效：**每次进 native 之前先把「我要干什么」写进一个小文件**，
 * 正常返回就清掉。进程被 native 打死时文件留在那儿，
 * 下次启动读出来就知道「上一次死在哪一步」——比墓碑还便宜。
 *
 * ## 还有一个作用：阻止崩溃循环
 *
 * 如果上次死在某一步，[consume] 会把它记进 SharedPreferences 并返回；
 * [LocalScreenRecognizer.init] 看到这个标记就**不再自动加载**，
 * 只在用户主动点「测试视觉模型」时才重试一次。
 * 不然用户每开一次 App 就崩一次，那是灾难。
 */
object VisionStage {

    private const val TAG = "VisionStage"

    private const val STAGE_FILE = "vision_stage.txt"

    /** 记录「上次 native 崩在哪」的 prefs（跨进程重启保留） */
    private const val PREFS = "luna_vision_guard"
    private const val KEY_CRASH_STAGE = "native_crash_stage"
    private const val KEY_CRASH_AT = "native_crash_at"

    @Volatile
    private var app: Context? = null

    /** 上一次 native 崩溃停在哪个阶段（内存缓存，UI 与报告都读它） */
    @Volatile
    var lastCrashStage: String? = null
        private set

    @Volatile
    var lastCrashAt: Long = 0L
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun ctx(): Context? = app

    private fun file(): File? {
        val c = ctx() ?: return null
        return File(c.filesDir, STAGE_FILE)
    }

    /**
     * 进入 native 调用前打个记号。
     *
     * 只做一次小文件写（几十字节），比任何日志框架都轻；
     * 用 `runCatching` 兜住 IO 异常 —— 取证工具本身绝不能成为崩溃源。
     */
    fun mark(stage: String) {
        runCatching { file()?.writeText(stage, Charsets.UTF_8) }
    }

    /** 正常返回后清掉记号 */
    fun clear() {
        runCatching { file()?.takeIf { it.exists() }?.delete() }
    }

    /**
     * 启动时调用：有残留记号 = 上次死在中途。
     *
     * 把内容记进 prefs（给后面的报告/弹窗用），并从磁盘清掉，
     * 避免同一次崩溃被反复上报。
     *
     * @return 上次死在哪一步；null = 上次是正常退出
     */
    fun consume(): String? {
        val f = file() ?: return lastCrashStage
        if (!f.exists()) return lastCrashStage
        val stage = runCatching { f.readText(Charsets.UTF_8).trim() }.getOrNull()
        clear()
        if (stage.isNullOrBlank()) return lastCrashStage
        lastCrashStage = stage
        lastCrashAt = System.currentTimeMillis()
        runCatching {
            ctx()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.putString(KEY_CRASH_STAGE, stage)
                ?.putLong(KEY_CRASH_AT, lastCrashAt)
                ?.apply()
        }
        Log.w(TAG, "检测到上次 native 调用异常终止：$stage（Java 层没有堆栈，这是唯一线索）")
        return stage
    }

    /** 从 prefs 读回历史上记录的崩溃阶段（冷启动后仍能显示） */
    fun loadRecordedCrash() {
        val p = runCatching { ctx()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
            ?: return
        val stage = p.getString(KEY_CRASH_STAGE, null)
        if (!stage.isNullOrBlank() && lastCrashStage == null) {
            lastCrashStage = stage
            lastCrashAt = p.getLong(KEY_CRASH_AT, 0L)
        }
    }

    fun shouldBlockAutoLoad(): Boolean = blocksAutoLoad(lastCrashStage)

    /**
     * 纯函数版判定（可单测）：哪些阶段崩了要停止自动加载。
     *
     * 拦「加载阶段」全部四步：`native:loadLibrary`（dlopen）、`native:create`（建对象）、
     * `native:init`（老的两步合一）、`native:load`（加载权重）。
     * 推理阶段（`native:recognize`）崩了说明模型能起来，只是这一帧没算完 ——
     * 那种情况自动重试一次很合理，一崩就永久禁用反而让用户以为功能坏了。
     */
    internal fun blocksAutoLoad(stage: String?): Boolean {
        if (stage.isNullOrBlank()) return false
        return stage.startsWith("native:loadLibrary") ||
            stage.startsWith("native:init") ||
            stage.startsWith("native:create") ||
            stage.startsWith("native:load")
    }

    /** 用户主动重试（点了「测试视觉模型」）时调用：清掉标记，允许再试一次 */
    fun clearCrashRecord() {
        lastCrashStage = null
        lastCrashAt = 0L
        runCatching {
            ctx()?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
                ?.remove(KEY_CRASH_STAGE)
                ?.remove(KEY_CRASH_AT)
                ?.apply()
        }
    }

    /** 给排障报告/自检用的一句话 */
    fun describe(): String {
        val stage = lastCrashStage ?: return "无（上次 native 调用正常结束）"
        val stamp = lastCrashAt.takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(it))
        } ?: "时间未知"
        return "$stage（$stamp）"
    }
}
