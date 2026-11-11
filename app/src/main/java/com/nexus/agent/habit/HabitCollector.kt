package com.nexus.agent.habit

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.nexus.agent.copilot.CopilotConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自我学习的采集端（0.9.11）。
 *
 * 数据来源只有一个：系统无障碍回调。所以这个功能**必须**在无障碍开启时才工作，
 * 没开就静默什么都不做 —— 绝不去另找后门（比如 UsageStats，那是另一套权限，
 * 用户根本没同意过）。
 *
 * 线程模型（整个功能最需要小心的地方）：
 *  - `onAccessibilityEvent` 在主线程，`event.source` 在回调返回后就会失效，
 *    所以**所有节点字段必须当场读完**，绝不能把 event 塞进队列稍后再读。
 *  - 主线程只做「读字段 + 廉价判定」，写完立刻返回：不查 PackageManager、不碰磁盘。
 *  - 真正的落盘在 [writer] 单线程里串行做，保证 JSONL 行不会交错。
 *
 * 排查：`adb logcat -s HabitDebug`。每条被拒的记录都会写明拒在哪一道闸。
 */
object HabitCollector {

    /** 统一排查 TAG —— 和视觉那套 VisionDebug 一个思路，一站一痕 */
    const val DEBUG_TAG = "HabitDebug"

    private const val TAG = "HabitCollector"

    // ---- 节流参数 ----
    /** 同一个应用的开屏，这段时间内只记一次（来回切换不算新行为） */
    private const val OPEN_DEDUP_MS = 5_000L

    /** 同一个控件连点，只记一次（手抖 / 连点） */
    private const val TAP_DEDUP_MS = 3_000L

    /** 每分钟最多记多少条 —— 滑列表会刷出大量 CLICKED，不拦会把画像带偏 */
    private const val MAX_PER_MINUTE = 30

    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "habit-writer").apply { isDaemon = true }
    }

    /** 每分钟计数窗口 */
    private var windowStart = 0L
    private val windowCount = AtomicInteger(0)

    @Volatile
    private var lastOpenKey = ""
    @Volatile
    private var lastOpenAt = 0L
    @Volatile
    private var lastTapKey = ""
    @Volatile
    private var lastTapAt = 0L

    /** 应用显示名缓存（包名 → 名字），避免每条事件都查一次 PackageManager */
    private val labelCache = HashMap<String, String>(32)

    /** 最近一次被拒的原因（设置页可以展示，让用户知道「为什么没学到」） */
    @Volatile
    var lastRejectReason: String? = null
        private set

    /** 本次进程内累计写入条数 */
    @Volatile
    var collectedInSession: Int = 0
        private set

    /**
     * 无障碍事件入口，由 [com.nexus.agent.accessibility.NexusAccessibilityService] 调用。
     *
     * 传进来的 event 必须还在回调栈上 —— 调用方不要转手异步。
     */
    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        // 第一道：开关。每次都现读，保证关掉之后下一条事件立刻停住 ——
        // 用户点了「关闭」，不能还有几条在管道里慢慢流进去。
        if (!CopilotConfig.isHabitLearningEnabled(context)) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) return
        if (pkg == context.packageName) return // 她自己的界面不学

        val now = System.currentTimeMillis()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == lastOpenKey && now - lastOpenAt < OPEN_DEDUP_MS) return
                lastOpenKey = pkg
                lastOpenAt = now
                collect(context, pkg, HabitEvent.KIND_OPEN, screenTitleOf(event), null, false, 0, now, null)
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val node = event.source ?: return
                var label: String? = null
                var isPassword = false
                var inputType = 0
                var cls: String? = null
                try {
                    cls = node.className?.toString()
                    isPassword = node.isPassword
                    inputType = node.inputType
                    // 只读「控件自己的字」，绝不读它所在界面的正文
                    label = node.text?.toString()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()
                } catch (e: Throwable) {
                    Log.w(TAG, "读节点失败：${e.javaClass.simpleName}")
                } finally {
                    try {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    } catch (_: Throwable) {
                    }
                }
                // 密码框：连「点了输入框」这件事都不留，别给反推留任何口子
                if (HabitPrivacy.isPasswordField(isPassword, inputType, cls)) {
                    lastRejectReason = "密码输入框，整条丢弃"
                    Log.d(DEBUG_TAG, "✗ $pkg 命中密码框（isPassword=$isPassword），丢弃")
                    return
                }
                val key = pkg + "\u0000" + label.orEmpty()
                if (key == lastTapKey && now - lastTapAt < TAP_DEDUP_MS) return
                lastTapKey = key
                lastTapAt = now
                collect(context, pkg, HabitEvent.KIND_TAP, null, label, isPassword, inputType, now, cls)
            }
        }
    }

    /**
     * WINDOW_STATE_CHANGED 的标题只从 `event.text` 取。
     *
     * 通知栏、聊天列表这类窗口事件会把正文塞进 text，所以这里先按长度卡一道：
     * 超过标题长度的直接当正文丢掉，宁可没标题也不要记下别人发的消息。
     */
    private fun screenTitleOf(event: AccessibilityEvent): String? {
        val raw = try {
            event.text?.joinToString(" ")?.trim()
        } catch (e: Throwable) {
            null
        }
        if (raw.isNullOrBlank()) return null
        if (raw.length > HabitPrivacy.SCREEN_MAX_LEN) return null
        return raw
    }

    /** 主线程：廉价判定 → 入队 → 立刻返回 */
    private fun collect(
        context: Context,
        pkg: String,
        kind: String,
        screen: String?,
        label: String?,
        isPassword: Boolean,
        inputType: Int,
        at: Long,
        className: String?
    ) {
        // 主线程能做的判定（不查 PackageManager）：包名免采 + 敏感界面
        if (!HabitPrivacy.isPackageAllowed(pkg)) {
            lastRejectReason = "应用在免采名单（金融 / 密码 / 系统面板）"
            Log.d(DEBUG_TAG, "✗ $pkg 在免采名单，跳过")
            return
        }
        if (HabitPrivacy.isScreenSensitive(screen, className)) {
            lastRejectReason = "界面属于密码 / 支付 / 登录类，整屏跳过"
            Log.d(DEBUG_TAG, "✗ $pkg 界面敏感（screen=$screen），跳过")
            return
        }
        if (!allowByRate(at)) {
            Log.d(DEBUG_TAG, "✗ $pkg 超出每分钟上限（$MAX_PER_MINUTE 条），丢弃")
            return
        }

        // 只把纯数据传下去，别让 event / node 跟着出回调栈
        val appCtx = context.applicationContext
        writer.execute {
            try {
                val appLabel = labelOf(appCtx, pkg)
                val clean = HabitPrivacy.screen(
                    at = at,
                    pkg = pkg,
                    appLabel = appLabel,
                    kind = kind,
                    screenTitle = screen,
                    className = className,
                    label = label,
                    isPassword = isPassword,
                    inputType = inputType
                )
                if (clean == null) {
                    lastRejectReason = "二次判定被拒（应用名或标签命中免采词）"
                    Log.d(DEBUG_TAG, "✗ $pkg 二次判定被拒（app=$appLabel）")
                    return@execute
                }
                if (HabitStore.append(appCtx, listOf(clean)) > 0) {
                    collectedInSession++
                    Log.d(
                        DEBUG_TAG,
                        "✓ ${clean.app} ${clean.kind}" +
                            (if (clean.screen.isNotEmpty()) " 界面「${clean.screen}」" else "") +
                            (if (clean.label.isNotEmpty()) " 点「${clean.label}」" else "")
                    )
                }
            } catch (e: Throwable) {
                Log.w(TAG, "落盘失败：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 查应用显示名（带缓存），必须在后台线程调用 */
    private fun labelOf(context: Context, pkg: String): String? {
        synchronized(labelCache) { labelCache[pkg] }?.let { return it }
        val name = try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            null
        }
        if (name != null) synchronized(labelCache) { labelCache[pkg] = name }
        return name
    }

    /** 每分钟配额 */
    private fun allowByRate(now: Long): Boolean = synchronized(this) {
        if (now - windowStart > 60_000L) {
            windowStart = now
            windowCount.set(0)
        }
        if (windowCount.get() >= MAX_PER_MINUTE) return@synchronized false
        windowCount.incrementAndGet()
        true
    }

    /**
     * 立刻学一次（设置页「现在学一下」/ 每日定时任务都走这里）。
     *
     * 同步执行，调用方要放在后台线程。
     */
    fun learnNow(context: Context): HabitProfile {
        val appCtx = context.applicationContext
        val events = HabitStore.readRecent(appCtx)
        val profile = HabitLearner.learn(events)
        if (!profile.isEmpty) HabitStore.saveBrief(appCtx, profile)
        HabitStore.prune(appCtx)
        Log.i(TAG, "学习完成：${events.size} 条记录 → ${profile.brief.length} 字画像")
        return profile
    }

    /**
     * 每天最多聚合一次（冷启动时顺手检查，不额外挂闹钟）。
     *
     * 为什么不用 WorkManager：这个任务只依赖「手机被用过」，
     * 冷启动就是最可靠的触发点；为它单独挂一个周期任务，
     * 省电白名单上又要多一条解释成本，不划算。
     *
     * 开关关着 ⇒ 直接返回，连上次学习时间都不读。
     */
    fun maybeLearnDaily(context: Context) {
        val appCtx = context.applicationContext
        if (!CopilotConfig.isHabitLearningEnabled(appCtx)) return
        val last = CopilotConfig.habitLastLearnedAt(appCtx)
        if (System.currentTimeMillis() - last < DAY_MS) return
        writer.execute {
            try {
                val p = learnNow(appCtx)
                CopilotConfig.setHabitLastLearnedAt(appCtx, System.currentTimeMillis())
                HabitMemory.reload(appCtx)
                Log.i(TAG, "每日聚合完成：${p.brief.length} 字")
            } catch (e: Throwable) {
                Log.w(TAG, "每日聚合失败：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 清空全部学习数据（流水 + 结论 + 目录） */
    fun clearAll(context: Context) {
        HabitStore.clear(context.applicationContext)
        collectedInSession = 0
        lastRejectReason = null
        HabitMemory.clear()
        synchronized(labelCache) { labelCache.clear() }
        Log.i(TAG, "学习数据已清空")
    }

    /** 一天（毫秒） */
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
