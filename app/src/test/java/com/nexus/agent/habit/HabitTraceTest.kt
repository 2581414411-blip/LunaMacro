package com.nexus.agent.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹切分与技能挖掘的单测（0.9.12）。
 *
 * 这一层的每条规则都会直接变成「她会不会点错按钮」，所以不测「大概能跑」，
 * 而是把每道闸单独钉死：少于 3 次不学、只在一天里不学、危险词不学、
 * 可选步骤不进度骨架。
 */
class HabitTraceTest {

    private val day = 86_400_000L
    /** 2026-11-01 10:00 附近，随便一个固定时刻 */
    private val base = 1_793_000_000_000L

    private fun open(at: Long, pkg: String, app: String, screen: String = "") =
        HabitEvent(at = at, pkg = pkg, app = app, kind = HabitEvent.KIND_OPEN, screen = screen)

    private fun tap(at: Long, pkg: String, app: String, label: String) =
        HabitEvent(at = at, pkg = pkg, app = app, kind = HabitEvent.KIND_TAP, label = label)

    /** 造一次完整操作：open + 一串点击，每步间隔 2 秒 */
    private fun episodeAt(start: Long, pkg: String = "com.tencent.mm", app: String = "微信", labels: List<String>) =
        buildList {
            add(open(start, pkg, app, "微信"))
            labels.forEachIndexed { i, l -> add(tap(start + 2000L * (i + 1), pkg, app, l)) }
        }

    // ---- 切分 ----

    @Test
    fun `同一应用连续点击算一次操作`() {
        val eps = HabitTrace.segment(episodeAt(base, labels = listOf("搜索", "订阅号", "文章")))
        assertEquals(1, eps.size)
        assertEquals(3, eps[0].steps.size)
        assertEquals(listOf("搜索", "订阅号", "文章"), eps[0].steps.map { it.label })
    }

    @Test
    fun `换应用就断开`() {
        val events = episodeAt(base, labels = listOf("搜索", "订阅号")) +
            episodeAt(base + 20_000L, pkg = "com.taobao", app = "淘宝", labels = listOf("购物车", "我的"))
        val eps = HabitTrace.segment(events)
        assertEquals(2, eps.size)
        assertEquals("com.tencent.mm", eps[0].pkg)
        assertEquals("com.taobao", eps[1].pkg)
    }

    @Test
    fun `超过十五秒断档就断开`() {
        val events = listOf(
            open(base, "com.tencent.mm", "微信"),
            tap(base + 1000, "com.tencent.mm", "微信", "搜索"),
            tap(base + 1000 + HabitTrace.SEGMENT_GAP_MS + 1, "com.tencent.mm", "微信", "订阅号")
        )
        val eps = HabitTrace.segment(events)
        // 断开后两边各只剩 1 步，不足 2 步 ⇒ 都不算一次操作
        assertEquals(0, eps.size)
    }

    @Test
    fun `连点同一个控件只算一步`() {
        val events = listOf(
            open(base, "com.tencent.mm", "微信"),
            tap(base + 1000, "com.tencent.mm", "微信", "搜索"),
            tap(base + 1500, "com.tencent.mm", "微信", "搜索"),
            tap(base + 2000, "com.tencent.mm", "微信", "搜索"),
            tap(base + 2500, "com.tencent.mm", "微信", "订阅号")
        )
        val eps = HabitTrace.segment(events)
        assertEquals(1, eps.size)
        assertEquals(listOf("搜索", "订阅号"), eps[0].steps.map { it.label })
    }

    @Test
    fun `超过八步只留前八步`() {
        val labels = (1..12).map { "按钮$it" }
        val eps = HabitTrace.segment(episodeAt(base, labels = labels))
        assertEquals(1, eps.size)
        assertEquals(HabitTrace.MAX_STEPS, eps[0].steps.size)
    }

    // ---- 归一化 ----

    @Test
    fun `数字归一化让不同计数算同一步`() {
        assertEquals("消息 #", HabitTrace.normalizeLabel("消息 12 条"))
        assertEquals(
            HabitTrace.normalizeLabel("3 条未读"),
            HabitTrace.normalizeLabel("12 条未读")
        )
    }

    @Test
    fun `列表序号不被归一`() {
        // 「按钮1/按钮2」是不同目标，混成一步会真的点错
        assertEquals("按钮1", HabitTrace.normalizeLabel("按钮1"))
        assertEquals("第2集", HabitTrace.normalizeLabel("第2集"))
    }

    @Test
    fun `空白被压缩`() {
        assertEquals("搜索 商品", HabitTrace.normalizeLabel("  搜索 \n 商品 "))
    }

    // ---- LCS / 相似度 ----

    private fun steps(vararg labels: String) =
        labels.map { HabitTrace.TraceStep("", it) }

    @Test
    fun `LCS 取公共子序列`() {
        val a = steps("搜索", "订阅号", "文章")
        val b = steps("搜索", "文章")
        assertEquals(listOf("搜索", "文章"), HabitTrace.lcs(a, b).map { it.label })
    }

    @Test
    fun `相似度用 Dice 系数`() {
        val a = steps("A", "B", "C", "D")
        val b = steps("A", "B", "C")
        // 2*3 / (4+3)
        assertEquals(6.0 / 7.0, HabitTrace.similarity(a, b), 0.0001)
    }

    // ---- 挖掘 ----

    @Test
    fun `三次跨两天才学成候选`() {
        val labels = listOf("搜索", "订阅号")
        val events = episodeAt(base, labels = labels) +
            episodeAt(base + day, labels = labels) +
            episodeAt(base + day + 3600_000L, labels = labels)
        val cands = HabitTrace.mine(events)
        assertEquals(1, cands.size)
        assertEquals(3, cands[0].occurrences)
        assertEquals(2, cands[0].days)
        assertEquals(listOf("搜索", "订阅号"), cands[0].steps.map { it.label })
    }

    @Test
    fun `只在同一天里重复不算习惯`() {
        val labels = listOf("搜索", "订阅号")
        val events = episodeAt(base, labels = labels) +
            episodeAt(base + 3600_000L, labels = labels) +
            episodeAt(base + 7200_000L, labels = labels)
        assertTrue(HabitTrace.mine(events).isEmpty())
    }

    @Test
    fun `只重复两次不算`() {
        val labels = listOf("搜索", "订阅号")
        val events = episodeAt(base, labels = labels) + episodeAt(base + day, labels = labels)
        assertTrue(HabitTrace.mine(events).isEmpty())
    }

    @Test
    fun `可选步骤不进入骨架`() {
        val core = listOf("搜索", "订阅号", "文章")
        val events = episodeAt(base, labels = core) +
            episodeAt(base + day, labels = core) +
            episodeAt(base + day + 3600_000L, labels = core + listOf("分享"))
        val cands = HabitTrace.mine(events)
        assertEquals(1, cands.size)
        // 「分享」只有一次出现，不是每次都会做 ⇒ 不进骨架
        assertEquals(core, cands[0].steps.map { it.label })
    }

    @Test
    fun `命中支付类词的动作永远不产候选`() {
        val labels = listOf("商品详情", "立即购买")
        val events = episodeAt(base, pkg = "com.taobao", app = "淘宝", labels = labels) +
            episodeAt(base + day, pkg = "com.taobao", app = "淘宝", labels = labels) +
            episodeAt(base + day + 3600_000L, pkg = "com.taobao", app = "淘宝", labels = labels)
        assertTrue(HabitTrace.mine(events).isEmpty())
    }

    @Test
    fun `发送类动作标成 caution 但仍产出`() {
        val labels = listOf("输入框", "发送")
        val events = episodeAt(base, labels = labels) +
            episodeAt(base + day, labels = labels) +
            episodeAt(base + day + 3600_000L, labels = labels)
        val cands = HabitTrace.mine(events)
        assertEquals(1, cands.size)
        assertEquals(HabitTrace.RISK_CAUTION, cands[0].risk)
    }

    @Test
    fun `已存在的签名不再重复产出`() {
        val labels = listOf("搜索", "订阅号")
        val events = episodeAt(base, labels = labels) +
            episodeAt(base + day, labels = labels) +
            episodeAt(base + day + 3600_000L, labels = labels)
        val first = HabitTrace.mine(events)
        assertEquals(1, first.size)
        val again = HabitTrace.mine(events, resumeFrom = setOf(first[0].signature))
        assertTrue(again.isEmpty())
    }

    @Test
    fun `风险分级取最严的一档`() {
        assertEquals(HabitTrace.RISK_SAFE, HabitTrace.riskOf(steps("搜索", "订阅号")))
        assertEquals(HabitTrace.RISK_CAUTION, HabitTrace.riskOf(steps("搜索", "发送")))
        assertEquals(HabitTrace.RISK_BLOCKED, HabitTrace.riskOf(steps("搜索", "确认支付")))
    }
}
