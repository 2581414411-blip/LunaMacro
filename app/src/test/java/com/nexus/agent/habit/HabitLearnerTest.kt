package com.nexus.agent.habit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 习惯聚合的靶场（0.9.11）。
 *
 * 这里钉的不是「算法漂亮」，而是三条会直接影响她说话像不像人的结论：
 * 最常用的应用排对了没、时段找对了没、A→B 的连带习惯抓到了没。
 * 这些一旦算错，她说出来就是「你最近好像很爱用微信」这种等于没说的话。
 */
class HabitLearnerTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(2026, Calendar.NOVEMBER, day, hour, minute, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun open(t: Long, app: String, screen: String = "") = HabitEvent(
        at = t, pkg = "pkg.$app", app = app, kind = HabitEvent.KIND_OPEN, screen = screen
    )

    private fun tap(t: Long, app: String, label: String, screen: String = "") = HabitEvent(
        at = t, pkg = "pkg.$app", app = app, kind = HabitEvent.KIND_TAP, screen = screen, label = label
    )

    @Test
    fun `没有数据时画像为空`() {
        val p = HabitLearner.learn(emptyList())
        assertTrue(p.isEmpty)
        assertEquals("", p.brief)
        assertTrue(p.promptBlock().isEmpty())
    }

    @Test
    fun `最常用的应用排在最前`() {
        val events = listOf(
            open(at(10, 21), "微信"),
            open(at(10, 21, 1), "哔哩哔哩"),
            open(at(10, 22), "微信"),
            open(at(10, 22, 5), "哔哩哔哩"),
            open(at(11, 21), "微信")
        )
        val p = HabitLearner.learn(events)
        assertEquals("微信", p.topApps.first().name)
        assertEquals(3, p.topApps.first().count)
    }

    @Test
    fun `最活跃时段能找出来`() {
        val events = listOf(
            open(at(10, 8), "微信"),
            open(at(10, 21), "哔哩哔哩"),
            open(at(10, 21, 10), "哔哩哔哩"),
            open(at(10, 21, 20), "哔哩哔哩")
        )
        val p = HabitLearner.learn(events)
        assertEquals(21, p.peakHours.first())
    }

    @Test
    fun `反复出现的连带路径会被记下`() {
        val events = listOf(
            open(at(10, 21), "微信"),
            open(at(10, 21, 1), "哔哩哔哩"),
            open(at(10, 22), "微信"),
            open(at(10, 22, 1), "哔哩哔哩")
        )
        val p = HabitLearner.learn(events)
        val path = p.paths.firstOrNull()
        assertTrue("应该抓到 微信→哔哩哔哩", path != null && path.from == "微信" && path.to == "哔哩哔哩")
        assertEquals(2, path!!.count)
    }

    @Test
    fun `间隔太久的两次打开不算路径`() {
        val events = listOf(
            open(at(10, 9), "微信"),
            open(at(10, 21), "哔哩哔哩"),
            open(at(11, 9), "微信"),
            open(at(11, 21), "哔哩哔哩")
        )
        val p = HabitLearner.learn(events)
        assertTrue("隔了 12 小时不算连带习惯", p.paths.isEmpty())
    }

    @Test
    fun `同一个按钮点得够多才进画像`() {
        val events = listOf(
            tap(at(10, 21), "微信", "朋友圈"),
            tap(at(10, 22), "微信", "朋友圈"),
            tap(at(10, 23), "微信", "朋友圈")
        )
        val p = HabitLearner.learn(events)
        assertEquals(1, p.taps.size)
        assertEquals("朋友圈", p.taps.first().label)
        assertEquals(3, p.taps.first().count)
    }

    @Test
    fun `只看一次的行为不进动作画像`() {
        val events = listOf(tap(at(10, 21), "微信", "设置"))
        val p = HabitLearner.learn(events)
        assertTrue("偶尔点一次不算习惯", p.taps.isEmpty())
    }

    @Test
    fun `画像正文有内容且不超长`() {
        val events = buildList {
            for (d in 10..13) {
                add(open(at(d, 21), "微信"))
                add(open(at(d, 21, 1), "哔哩哔哩"))
                add(tap(at(d, 21, 2), "哔哩哔哩", "点赞"))
                add(tap(at(d, 21, 3), "哔哩哔哩", "点赞"))
            }
        }
        val p = HabitLearner.learn(events)
        assertTrue(p.brief.isNotBlank())
        assertTrue("正文不能超过提示词预算", p.brief.length <= HabitLearner.BRIEF_MAX + 1)
        assertTrue(p.brief.contains("微信"))
        assertEquals("覆盖 4 天", 4, p.days)
    }

    @Test
    fun `提示词段带使用约束且不说统计`() {
        val events = listOf(
            open(at(10, 21), "微信"),
            open(at(10, 21, 1), "哔哩哔哩"),
            open(at(11, 21), "微信"),
            open(at(11, 21, 1), "哔哩哔哩")
        )
        val block = HabitLearner.learn(events).promptBlock()
        assertTrue(block.contains("习惯"))
        // 这三条约束是「像人」和「像监控」的分界线，缺一条都不能上线
        assertTrue(block.contains("别说「根据我的记录」"))
        assertTrue(block.contains("别一次全倒出来"))
    }

    @Test
    fun `乱序输入也能算对`() {
        val events = listOf(
            open(at(10, 21), "微信"),
            open(at(9, 20), "微信"),
            open(at(10, 22), "微信")
        ).shuffled()
        val p = HabitLearner.learn(events)
        assertEquals(3, p.topApps.first().count)
        assertFalse(p.brief.isBlank())
    }
}
