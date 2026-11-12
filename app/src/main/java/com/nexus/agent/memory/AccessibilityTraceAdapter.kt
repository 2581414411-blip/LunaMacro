package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.RawTrace
import com.nexus.agent.habit.HabitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 手动路径的采集适配器 —— 把「用户自己点的那串动作」接进轨迹池（0.9.16）。
 *
 * ## 它替换了什么
 *
 * 0.9.11 起这套东西写在 `habit/` 里：无障碍事件 → JSONL → 每夜挖候选技能。
 * 那条线和 `memory/` 那条线**零代码引用**，两套阈值、两套骨架算法、两段画像
 * 同时进提示词。这一版把「感知」就近收进 `raw_trace`，学习交给已有的夜间巩固，
 * `habit/` 只保留隐私闸（[com.nexus.agent.habit.HabitPrivacy]）、免责声明，
 * 以及「已收下动作」的旧执行通道（批次 B 再下线）。
 *
 * ## 为什么先攒一段再写
 *
 * 一次点击一行没有价值 —— 「点了一下发送」学不出任何可回放的东西。
 * 我们要的最小单位是 **episode**：同一应用、相邻两次操作间隔不超过
 * [SEGMENT_GAP_MS] 的一串点击。所以这里在内存里攒，空闲 [FLUSH_IDLE_MS] 之后
 * 一次性切段落库。
 *
 * 代价是「进程在攒的过程中被杀，这一段就丢了」。可以接受：
 *  - 一段正常操作 10~30 秒就结束，被杀在中间的窗口极小；
 *  - 丢一段的后果只是「这一晚少一个样本」，不影响任何已有记忆。
 *
 * ## 没有意图，就没有 `intent` / `intentKey`
 *
 * 落库时这两列都是空串，**故意的**。用户点「发送」可能是要发消息，
 * 也可能只是在翻记录时手滑，程序无从判断。空意图意味着它进不了
 * `MemoryConsolidator.group` 的 agent 分组（那里按 `指纹|意图` 归组），
 * 只能走 `clusterUserPaths` 按骨架相似度聚类，产出 `candidate` 等用户命名。
 *
 * ## 指纹用哨兵值
 *
 * `pkg#user`。手动路径拿不到「可复现的界面指纹」—— 我们手里只有界面标题和
 * 按钮文字，而路由时的真实指纹是整棵 UI 树的关键节点哈希，两者永远对不上。
 * 与其存一个假装能匹配的哈希，不如存一个一眼就知道「这条没有指纹」的哨兵，
 * 让路由层改用「包名 + 用户给的名字」做闸（见 `MemoryRouter.Scored.eligible`）。
 */
object AccessibilityTraceAdapter {

    private const val TAG = "MemDebug"

    /** 同一应用内两次操作隔这么久，就不算「连续一次操作」了（与 habit 的切分口径一致） */
    const val SEGMENT_GAP_MS = 15_000L

    /** 少于两步不算动作，只能算随手一点 */
    const val MIN_STEPS = 2

    /** 内存里最多攒多少条事件就强制落一次（防止用户长时间连续操作把内存撑大） */
    const val MAX_BUFFER_EVENTS = 240

    /**
     * 空闲多久落一次。
     *
     * 比断档多 3 秒：断档一到其实已经能切段了，多等一点是为了让「最后那一下点击
     * 引发的界面跳转」也进同一段，否则最后一步经常被切到下一段去。
     */
    const val FLUSH_IDLE_MS = SEGMENT_GAP_MS + 3_000L

    /** 手动路径的指纹哨兵后缀 */
    const val FINGERPRINT_SUFFIX = "#user"

    /** 一次连续操作 */
    data class Episode(
        val pkg: String,
        val app: String,
        val startAt: Long,
        val endAt: Long,
        /** 起始界面标题（事件里带得到才有，可能为空） */
        val screen: String,
        /** 按顺序点过的控件文字（已归一、已去重连点） */
        val labels: List<String>
    )

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    private val scheduler by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "habit-trace").apply { isDaemon = true }
        }
    }

    private val lock = Any()

    private val buffer = ArrayList<HabitEvent>(32)

    private var pendingFlush: ScheduledFuture<*>? = null

    // ==================== 纯函数：切段与建行（单测直接钉） ====================

    /**
     * 事件序列 → episode 列表。
     *
     * 切分判据只有两条，都刻意保守：
     *  - 换了应用 → 断；
     *  - 相邻两个事件间隔超过 [SEGMENT_GAP_MS] → 断。
     *
     * 不做「返回键」判断：我们根本没采返回键，凭空推断只会把两条路粘成一条错的。
     *
     * 同一毫秒内的多个事件**保持传入顺序**（`sortedBy` 是稳定排序）——
     * 「先点搜索框再点搜索按钮」这件事，顺序反了整条记忆就废了。
     */
    fun segment(events: List<HabitEvent>): List<Episode> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.filter { it.at > 0 && it.pkg.isNotBlank() }.sortedBy { it.at }
        if (sorted.isEmpty()) return emptyList()

        val out = ArrayList<Episode>(8)
        var pkg = ""
        var app = ""
        var screen = ""
        var startAt = 0L
        var lastAt = 0L
        var labels = ArrayList<String>(8)

        fun flush() {
            if (labels.size >= MIN_STEPS) {
                out.add(
                    Episode(
                        pkg = pkg,
                        app = app.ifBlank { pkg },
                        startAt = startAt,
                        endAt = lastAt,
                        screen = screen,
                        labels = labels.toList()
                    )
                )
            }
        }

        for (e in sorted) {
            val broken = e.pkg != pkg || e.at - lastAt > SEGMENT_GAP_MS
            if (broken) {
                flush()
                pkg = e.pkg
                app = e.app
                screen = ""
                startAt = e.at
                labels = ArrayList(8)
            }
            if (app.isBlank() && e.app.isNotBlank()) app = e.app
            lastAt = e.at

            when (e.kind) {
                HabitEvent.KIND_OPEN -> {
                    // 进界面本身不是动作，只用来记「这一段是从哪个界面开始的」
                    if (screen.isBlank()) screen = e.screen
                }

                HabitEvent.KIND_TAP -> {
                    val label = e.label.trim()
                    if (label.isEmpty()) continue
                    // 连点同一个控件只算一步（手抖 / 加载慢被点两下）
                    if (labels.lastOrNull() == label) continue
                    labels.add(label)
                    if (labels.size >= ActionMemory.MAX_STEPS) {
                        // 已经够一条记忆的长度了，再点下去也学不进去，先落一段
                        flush()
                        startAt = e.at
                        labels = ArrayList(8)
                    }
                }
            }
        }
        flush()
        return out
    }

    /** 手动路径的指纹哨兵：`pkg#user` */
    fun fingerprintOf(pkg: String): String = pkg + FINGERPRINT_SUFFIX

    /** 一步点击 → 结构化动作（与 `MemoryReplay` / `Executor` 的工具名对齐） */
    fun clickOf(label: String): ActionStep =
        ActionStep("click_text", mapOf("text" to label, "index" to "0"))

    /** episode → 一条待巩固的轨迹 */
    fun toTrace(ep: Episode): RawTrace = RawTrace(
        kind = RawTrace.KIND_USER_PATH,
        // 空意图：手动路径没有「想干什么」，只有「干了什么」
        intent = "",
        intentKey = "",
        fingerprint = fingerprintOf(ep.pkg),
        screenKeys = keysOf(ep),
        pkg = ep.pkg,
        steps = ActionStep.encodeAll(ep.labels.map { clickOf(it) }),
        outcome = RawTrace.OUTCOME_SUCCESS,
        // detail 在这条路径上没有「失败原因」可存，改存应用显示名：
        // 夜里聚类时要用它拼「微信 · 搜索 → 订阅号」这种给人看的名字。
        detail = ep.app.take(RawTrace.DETAIL_MAX),
        source = RawTrace.SOURCE_USER,
        createdAt = ep.startAt
    )

    /**
     * 关键节点明文。
     *
     * 拿不到整棵 UI 树，就把「界面标题 + 点过的按钮」当成关键节点。
     * 它算不出能命中路由的指纹（那需要一个稳定的整屏哈希），
     * 但能在设置页和日志里说清「这条记忆是在哪个界面点了哪几个按钮」。
     */
    fun keysOf(ep: Episode): String = textKeys(ep.screen, ep.labels)

    fun textKeys(screen: String?, labels: List<String>): String {
        val keys = LinkedHashSet<String>(labels.size + 1)
        for (raw in (listOf(screen.orEmpty()) + labels)) {
            val n = ScreenFingerprint.normalize(raw)
            if (n.isNotEmpty()) keys.add("t:$n")
            if (keys.size >= ScreenFingerprint.MAX_KEYS) break
        }
        return ScreenFingerprint.keyString(keys.toList().take(ScreenFingerprint.MAX_KEYS))
    }

    // ==================== 运行时：攒 → 落 ====================

    /**
     * 收一条**已经过隐私闸**的事件（由 `HabitCollector` 在后台线程调用）。
     *
     * 隐私判定不在这里重做一遍：`HabitCollector` 那套闸（免采名单、敏感界面、
     * 密码框、频率上限）是逐条验证过的，这里再抄一份只会有两处口径不一致。
     * 本函数只负责「攒 + 定时落」。
     */
    fun accept(context: Context, event: HabitEvent) {
        val app = context.applicationContext ?: context
        if (!CopilotConfig.isMemoryEnabled(app)) return

        val size: Int
        synchronized(lock) {
            buffer.add(event)
            size = buffer.size
            pendingFlush?.cancel(false)
            pendingFlush = scheduler.schedule(
                { runCatching { flush(app) }.onFailure { Log.w(TAG, "手动路径落库失败：${it.message}") } },
                FLUSH_IDLE_MS,
                TimeUnit.MILLISECONDS
            )
        }
        if (size >= MAX_BUFFER_EVENTS) flush(app)
    }

    /** 把攒下的这一段切好段落库。空闲定时、或缓冲区满时调用 */
    fun flush(context: Context) {
        val app = context.applicationContext ?: context
        val batch: List<HabitEvent>
        synchronized(lock) {
            if (buffer.isEmpty()) return
            batch = ArrayList(buffer)
            buffer.clear()
            pendingFlush?.cancel(false)
            pendingFlush = null
        }
        val episodes = segment(batch)
        if (episodes.isEmpty()) {
            Log.d(TAG, "[采集] 手动路径：${batch.size} 个事件切不出完整动作（不足 $MIN_STEPS 步），丢弃")
            return
        }
        Log.i(
            TAG,
            "[采集] 手动路径：${batch.size} 个事件 → ${episodes.size} 段 " +
                episodes.joinToString("；") { "${it.app} ${it.labels.size} 步（${it.labels.joinToString("→")}）" }
        )
        scope.launch {
            for (ep in episodes) {
                runCatching { MemoryRecorder.writeTrace(app, toTrace(ep), "手动路径") }
                    .onFailure { Log.w(TAG, "[采集] 手动路径写入失败：${it.message}") }
            }
        }
    }

    /** 仅供单测：清掉缓冲与定时器 */
    internal fun resetForTest() {
        synchronized(lock) {
            buffer.clear()
            pendingFlush?.cancel(false)
            pendingFlush = null
        }
    }
}
