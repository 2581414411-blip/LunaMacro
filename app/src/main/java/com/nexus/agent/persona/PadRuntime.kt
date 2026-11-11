package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.copilot.PresenceSense
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.PadState
import com.nexus.agent.data.db.PadStateDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * PAD 运行时（0.9.15 阶段 1）—— 把 [PadDynamics] 的纯数学接进真实对话。
 *
 * ## 三层分工
 *
 * | 层 | 文件 | 干什么 |
 * |---|---|---|
 * | 数学 | `PadState.kt` | 衰减、EMA、基线、八分区、提示词段落，全是纯函数 |
 * | 判定 | `PadAppraisal.kt` | 一句话 + 情境 → PAD 增量，纯函数 |
 * | 状态 | **本文件** | 持有当前坐标、读写 `pad_state`、推进一轮、写回心情标签 |
 *
 * ## 三个必须守住的东西
 *
 * 1. **推进只发生在 `LocalDigitalBeing.propagateTurn`**。0.9.10 踩过的坑：情绪推进
 *    写在多个入口，好感度双倍增长。PAD 同理，别在 `respond` / `recordTurn` 里再加一次。
 * 2. **`current()` 是纯读**：它把「距上次推进过去了多久」的衰减临时算进去，
 *    但不改内存、不写库。提示词随时可以问，问一百次也不会让状态漂移。
 * 3. **没初始化就不说话**：`promptBlock()` 在 [init] 之前返回空串。
 *    否则纯 JVM 单测里的 `buildReplyPrompt` 会凭空多出一段情感描述，把老断言全打红。
 *
 * ## 冷启动怎么补
 *
 * `pad_state` 里存着 `lastDecayAt`。下次开机第一次 `current()` 时按这个时间差衰减 ——
 * 隔一夜再打开，快变量已经落回基线（半衰期 20 分钟），慢变量也淡了一半（6 小时），
 * 这就是「睡一觉气就消了」在数值上的样子。不需要任何定时任务。
 */
object PadRuntime {

    /** 排障统一标签：`adb logcat -s PadDebug` */
    private const val TAG = "PadDebug"

    /**
     * 一个时刻的完整情感状态快照（已含衰减）。
     *
     * [base] 每次都是现算的（由性格三维 + 好感度推出），所以用户在设置页拖一下
     * 性格滑杆，她的底色当场就变，不用等下一轮对话。
     */
    data class Snapshot(val quick: PAD, val slow: PAD, val base: PAD, val at: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行化所有推进：两轮几乎同时进来时不能各自读旧值再各自写回 */
    private val mutex = Mutex()

    /** 内存副本；null = 还没从库里读出来 */
    @Volatile
    private var state: PadState? = null

    @Volatile
    private var dao: PadStateDao? = null

    @Volatile
    private var ctx: Context? = null

    /** [init] 调用过没有 —— 只有 true 时提示词才会拿到内容 */
    @Volatile
    private var ready = false

    /**
     * 仅供单测：假时钟。
     *
     * 衰减曲线全靠时间差，没有它就只能 `Thread.sleep` 或者用 `@Ignore` 跳掉
     * 「隔 20 分钟衰减一半」这种最关键的断言。
     */
    @Volatile
    internal var clockForTest: (() -> Long)? = null

    /** 仅供单测：固定「现在是不是深夜」，不然晚上 11 点跑测试会和下午跑出两个结果 */
    @Volatile
    internal var lateNightForTest: Boolean? = null

    /**
     * 单调递增的推进计数。
     *
     * 只给单测用：`onTurn` 是异步的，测试必须能等到这一轮真的算完，
     * 否则断言会读到上一轮的状态（偶发、难复现的那种红）。
     */
    @Volatile
    internal var advances: Int = 0
        private set

    // ---------------------------------------------------------------- 初始化

    /**
     * 注入上下文并异步读回存档。**幂等**（PersonaTab 每次进入都会调）。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        ctx = app
        ready = true
        if (state != null) return
        scope.launch {
            mutex.withLock {
                if (state != null) return@withLock
                val base = baselineNow()
                val row = runCatching { daoOf(app)?.get() }.getOrNull()
                state = row ?: fresh(base)
                Log.i(
                    TAG,
                    if (row == null) "PAD 首次创建，基线=${fmt(base)}"
                    else "PAD 已载入：快=${fmt(row.quickPAD())} 慢=${fmt(row.slowPAD())} " +
                        "基线=${fmt(row.basePAD())} 上次=${stamp(row.lastDecayAt)}"
                )
            }
        }
    }

    // ---------------------------------------------------------------- 读

    /**
     * 确保状态已从库里读出来，再返回快照（0.9.15 阶段 3）。
     *
     * [init] 是**异步**的（它跑在 IO 线程里），夜间任务不能等它 —— 凌晨三点
     * 没有人会先打开一次 App。这里同步读一次，语义上等价于「确保已加载」。
     *
     * 只给 `DiaryWriter` 用：它跑在 WorkManager 的协程里，阻塞几百微秒无所谓。
     * 界面路径一律走 [current]，别在 UI 线程上调这个。
     */
    suspend fun ensureLoaded(context: Context): Snapshot {
        val app = context.applicationContext
        ctx = app
        ready = true
        mutex.withLock {
            if (state == null) {
                val base = baselineNow()
                state = runCatching { daoOf(app)?.get() }.getOrNull() ?: fresh(base)
            }
        }
        return current()
    }

    /**
     * 当前状态（已按时间衰减）。
     *
     * 返回的 `quick/slow` 是**算出来的**，不是内存里存的那份 —— 内存那份是上次推进
     * 结束时的瞬时值，直接拿去拼提示词会出现「三小时前被骂了一句，现在还在气头上」。
     */
    fun current(): Snapshot {
        val base = baselineNow()
        val now = nowMs()
        val s = state ?: return Snapshot(base, base, base, now)
        val dt = elapsedOf(s, now)
        return Snapshot(
            quick = PadDynamics.decayToward(s.quickPAD(), base, dt, PAD.QUICK_HALF_LIFE_MS),
            slow = PadDynamics.decayToward(s.slowPAD(), base, dt, PAD.SLOW_HALF_LIFE_MS),
            base = base,
            at = now
        )
    }

    /**
     * 提示词里的情感段落。空串 = 不注入（没初始化 / 开关关着）。
     *
     * 开关的判断放在这里而不是调用方，是为了让 `buildReplyPrompt` 只写一行 `append`，
     * 以后再加「日记」这类段落时不会在提示词函数里堆一排 if。
     */
    fun promptBlock(): String {
        if (!ready) return ""
        val app = ctx
        if (app != null && !CopilotConfig.isPadEnabled(app)) return ""
        val s = current()
        return PadDynamics.promptBlock(s.quick, s.slow)
    }

    /** 写进 `PersonaProfile.mood` 的标签（羁绊面板的表情靠它） */
    fun moodLabel(): String {
        val s = current()
        return PadDynamics.moodLabelOf(s.quick, s.slow)
    }

    /**
     * 羁绊面板那一行（0.9.15 阶段 4）：`此刻：淡淡的雀跃 | 底色：平静`。
     *
     * 「此刻」是快变量、「底色」是慢变量 —— 这两个词就是双速动力学在界面上的翻译。
     * 开关关着时返回空串，那一条整行不显示（比显示一份不再更新的旧数值诚实）。
     */
    fun displayLine(): String {
        val app = ctx
        if (app != null && !CopilotConfig.isPadEnabled(app)) return ""
        val s = current()
        return "此刻：${PadDynamics.describeOf(s.quick)} | 底色：${PadDynamics.describeOf(s.slow)}"
    }

    /**
     * 归零（跟着 [PersonaEngine.clearAll] 走）。
     *
     * 用户点「清空聊天记录和记忆」时，如果她还带着昨天那股气，用户会觉得根本没清干净 ——
     * 情绪也是一种记忆，一起清才叫清。
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        ctx = app
        ready = true
        // 先把内存置空：界面上那一秒就已经是「平静」，不用等磁盘
        state = null
        scope.launch {
            mutex.withLock {
                runCatching { daoOf(app)?.clear() }
                    .onFailure { Log.w(TAG, "清空 pad_state 失败：${it.javaClass.simpleName}") }
                state = fresh(baselineNow())
                Log.i(TAG, "PAD 已归零：${fmt(state!!.quickPAD())}")
            }
        }
    }

    // ---------------------------------------------------------------- 推进

    /**
     * 一轮对话结束后的推进。**只由 [LocalDigitalBeing.propagateTurn] 调用。**
     *
     * 异步执行、异常吞掉：情绪是最不该拖慢回复的东西，算不出来就当这轮没发生。
     */
    fun onTurn(context: Context?, userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val app = context?.applicationContext ?: ctx
        if (app != null && !CopilotConfig.isPadEnabled(app)) return
        scope.launch {
            mutex.withLock {
                runCatching { advance(app, text) }
                    .onFailure {
                        Log.w(TAG, "PAD 推进失败：${it.javaClass.simpleName} ${it.message}")
                    }
            }
        }
    }

    /**
     * 真正算一轮。
     *
     * 顺序写死：**先用旧值算衰减，再加本轮的刺激**（[PadDynamics.evolveQuick] 内部保证）。
     * 如果反过来，一句「隔了三小时才回」会把刚发生的那句话也按三小时衰减掉。
     */
    private suspend fun advance(app: Context?, userText: String) {
        val now = nowMs()
        val profile = PersonaEngine.profileSnapshot()
        val base = baselineOf(profile)
        val prev = state ?: runCatching { daoOf(app)?.get() }.getOrNull() ?: fresh(base)
        val dt = elapsedOf(prev, now)

        val event = PadAppraisal.Event(
            text = userText,
            // 距上一轮多久：「被晾着」判定的唯一依据。首次（lastDecayAt=0）传 0，
            // 免得全新安装第一句话就被算成「晾了我两次」。
            idleMs = dt,
            isLateNight = lateNightOf(app)
        )
        val result = PadAppraisal.appraise(event)
        val quick = PadDynamics.evolveQuick(
            prev.quickPAD(), base, result.delta, dt, profile.sensibility
        )
        val slow = PadDynamics.evolveSlow(prev.slowPAD(), quick, base, dt)
        val next = PadState(
            quickP = quick.p, quickA = quick.a, quickD = quick.d,
            slowP = slow.p, slowA = slow.a, slowD = slow.d,
            baseP = base.p, baseA = base.a, baseD = base.d,
            updatedAt = now, lastDecayAt = now
        )
        state = next
        advances++
        runCatching { daoOf(app)?.upsert(next) }

        // 心情标签：羁绊面板的表情、以及 PAD 关掉时的降级路径都读它
        val label = PadDynamics.moodLabelOf(quick, slow)
        if (label != profile.mood) {
            runCatching { PersonaEngine.updateMood(label) }
        }
        // 羁绊面板那行「此刻 / 底色」要跟着这一轮动起来
        runCatching { PersonaEngine.notifyEmotionChanged() }

        Log.i(
            TAG,
            "快=${fmt(quick)} 慢=${fmt(slow)} 基线=${fmt(base)} " +
                "信号=${result.signals.joinToString("/") { it.zh }.ifEmpty { "无" }} " +
                "Δ=${fmt(result.delta)} dt=${minutes(dt)} " +
                "分区=${PadDynamics.octantOf(quick).zh}/${PadDynamics.octantOf(slow).zh} " +
                "强度=${"%.2f".format(Locale.US, quick.normalized())} " +
                "表里不一=${PadDynamics.isMasked(quick, slow)} 心情=$label"
        )
    }

    // ---------------------------------------------------------------- 内部

    /** 距上次推进过去了多久；`lastDecayAt <= 0`（首次 / 脏数据）当作 0 */
    private fun elapsedOf(s: PadState, now: Long): Long =
        if (s.lastDecayAt <= 0L) 0L else (now - s.lastDecayAt).coerceAtLeast(0L)

    private fun baselineNow(): PAD = baselineOf(PersonaEngine.profileSnapshot())

    private fun baselineOf(p: com.nexus.agent.data.db.ProfileSnapshot): PAD =
        PadDynamics.baselineOf(p.extroversion, p.sensibility, p.rebelliousness, p.affinity)

    /** 全新安装：没有情绪，一切等于基线 */
    private fun fresh(base: PAD): PadState = PadState(
        quickP = base.p, quickA = base.a, quickD = base.d,
        slowP = base.p, slowA = base.a, slowD = base.d,
        baseP = base.p, baseA = base.a, baseD = base.d,
        updatedAt = 0L, lastDecayAt = 0L
    )

    private suspend fun daoOf(app: Context?): PadStateDao? {
        dao?.let { return it }
        if (app == null) return null
        return runCatching { AppDatabase.get(app).padStateDao() }
            .onFailure { Log.w(TAG, "打开 pad_state 失败：${it.javaClass.simpleName}") }
            .getOrNull()
            ?.also { dao = it }
    }

    private fun lateNightOf(app: Context?): Boolean =
        lateNightForTest
            ?: runCatching { PresenceSense.now(app).isLateNight }.getOrDefault(false)

    private fun nowMs(): Long = clockForTest?.invoke() ?: System.currentTimeMillis()

    // ---------------------------------------------------------------- 测试与排障

    /** 仅供单测：直接塞一份状态，不碰数据库 */
    internal fun useStateForTest(s: PadState) {
        state = s
        ready = true
        advances = 0
    }

    /** 仅供单测：清空内存态（数据库那行留着，不归测试管） */
    internal fun resetForTest() {
        state = null
        ready = false
        advances = 0
        ctx = null
        clockForTest = null
        lateNightForTest = null
    }

    /** 仅供单测：同步跑一轮（绕过协程，免得测试要等调度） */
    internal fun advanceForTest(app: Context?, userText: String) {
        kotlinx.coroutines.runBlocking { mutex.withLock { advance(app, userText) } }
    }

    private fun fmt(v: PAD): String =
        "(%+.2f,%+.2f,%+.2f)".format(Locale.US, v.p, v.a, v.d)

    private fun minutes(ms: Long): String =
        if (ms >= 60_000L) "${ms / 60_000L}m" else "${ms / 1000L}s"

    private fun stamp(at: Long): String =
        if (at <= 0L) "无" else {
            java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(at))
        }
}
