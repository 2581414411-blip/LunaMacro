package com.nexus.agent.memory

import com.nexus.agent.data.db.ActionMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 经验加工引擎单测（0.9.13）。
 *
 * 三件事直接决定「她学到的是一条能用的路，还是一串随机点击」：
 *  - 骨架合并（偶尔才做的那一步必须被剔掉）；
 *  - 状态机（没成功够数绝不能变可回放）；
 *  - 达尔文评分（不能一条早高峰操作把别的全压下去）。
 */
class MemoryConsolidatorTest {

    private fun step(text: String) = ActionStep("click_text", mapOf("text" to text))

    private fun mem(state: String, consecutiveFail: Int = 0) = ActionMemory(
        memoryKey = "k",
        intent = "打开微信搜订阅号",
        intentKey = "打开微信搜订阅号",
        fingerprint = "com.tencent.mm#abcd",
        steps = "click_text\ttext=搜索",
        state = state,
        consecutiveFail = consecutiveFail
    )

    // ---- 骨架合并 ----

    @Test
    fun `偶尔才做的一步不进骨架`() {
        val old = listOf(step("搜索"), step("订阅号"))
        val new = listOf(step("搜索"), step("订阅号"), step("某一篇文章"))
        val merged = MemoryConsolidator.mergeSteps(old, new)
        assertEquals(listOf("搜索", "订阅号"), merged.map { it.args["text"] })
    }

    @Test
    fun `计数变化不影响骨架`() {
        val old = listOf(step("3 条未读"), step("订阅号"))
        val new = listOf(step("12 条未读"), step("订阅号"))
        assertEquals(2, MemoryConsolidator.mergeSteps(old, new).size)
    }

    @Test
    fun `差太多就不硬求交集`() {
        val old = listOf(step("搜索"), step("订阅号"))
        val new = listOf(step("搜索"), step("购物车"))
        // LCS 只有 1 步，小于 2 时保留新的那条 —— 硬求交集会得到一条几乎空的记忆
        assertEquals(listOf("搜索", "购物车"), MemoryConsolidator.mergeSteps(old, new).map { it.args["text"] })
    }

    @Test
    fun `空轨迹不会把已有骨架清掉`() {
        val old = listOf(step("搜索"))
        assertEquals(old, MemoryConsolidator.mergeSteps(old, emptyList()))
        assertEquals(listOf(step("搜索")), MemoryConsolidator.mergeSteps(emptyList(), listOf(step("搜索"))))
    }

    // ---- 状态机 ----

    @Test
    fun `成功次数不够不许变可回放`() {
        val m = mem(ActionMemory.STATE_CANDIDATE)
        assertEquals(ActionMemory.STATE_CANDIDATE, MemoryConsolidator.nextStateOnSuccess(m, 1))
        assertEquals(ActionMemory.STATE_CANDIDATE, MemoryConsolidator.nextStateOnSuccess(m, 2))
    }

    @Test
    fun `成功累计三次才转正`() {
        val m = mem(ActionMemory.STATE_CANDIDATE)
        assertEquals(ActionMemory.STATE_ACTIVE, MemoryConsolidator.nextStateOnSuccess(m, 3))
        assertEquals(ActionMemory.STATE_ACTIVE, MemoryConsolidator.nextStateOnSuccess(m, 9))
    }

    @Test
    fun `作废过的记忆要重新攒够次数才复活`() {
        val revoked = mem(ActionMemory.STATE_REVOKED)
        assertEquals(ActionMemory.STATE_CANDIDATE, MemoryConsolidator.nextStateOnSuccess(revoked, 1))
        assertEquals(ActionMemory.STATE_ACTIVE, MemoryConsolidator.nextStateOnSuccess(revoked, 3))
    }

    @Test
    fun `已经转正的不会因为一次成功掉级`() {
        val active = mem(ActionMemory.STATE_ACTIVE)
        assertEquals(ActionMemory.STATE_ACTIVE, MemoryConsolidator.nextStateOnSuccess(active, 5))
    }

    // ---- 经验清洗 ----

    @Test
    fun `经验只留第一句并剥掉序号前缀`() {
        assertEquals(
            "在搜索页先点搜索框等键盘弹出",
            MemoryConsolidator.sanitizeTip("1. 经验：在搜索页先点搜索框等键盘弹出\n后面还有解释")
        )
        assertEquals("先滚动再点", MemoryConsolidator.sanitizeTip("- 先滚动再点"))
        assertEquals("", MemoryConsolidator.sanitizeTip(null))
        assertEquals("", MemoryConsolidator.sanitizeTip("  \n "))
    }

    @Test
    fun `记忆桶键由指纹和意图组成`() {
        val k = MemoryConsolidator.memoryKeyOf("com.a#1", "打开微信")
        assertTrue(k.startsWith("com.a#1|"))
        assertTrue(k.contains("打开微信"))
    }

    // ---- 达尔文评分 ----

    @Test
    fun `评分落在 0 到 1 之间`() {
        val now = 1_800_000_000_000L
        listOf(
            MemoryWeight.scoreOf(0, 0, 0, 0, now),
            MemoryWeight.scoreOf(10, 10, 0, now, now),
            MemoryWeight.scoreOf(1, 0, 10, now - 90L * 86_400_000, now)
        ).forEach {
            assertTrue("score=$it", it in 0f..1f)
        }
    }

    @Test
    fun `同样次数下成功率高的评分更高`() {
        val now = 1_800_000_000_000L
        val good = MemoryWeight.scoreOf(5, 9, 1, now, now)
        val bad = MemoryWeight.scoreOf(5, 1, 9, now, now)
        assertTrue("good=$good bad=$bad", good > bad)
    }

    @Test
    fun `同样成功率下越久没用评分越低`() {
        val now = 1_800_000_000_000L
        val fresh = MemoryWeight.scoreOf(5, 5, 0, now - 86_400_000, now)
        val stale = MemoryWeight.scoreOf(5, 5, 0, now - 60L * 86_400_000, now)
        assertTrue("fresh=$fresh stale=$stale", fresh > stale)
    }

    @Test
    fun `频率封顶不会压垮别的维度`() {
        val now = 1_800_000_000_000L
        val a = MemoryWeight.scoreOf(10, 10, 0, now, now)
        val b = MemoryWeight.scoreOf(1000, 1000, 0, now, now)
        assertEquals(a, b, 0.0001f)
    }
}
