package com.nexus.agent.copilot

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 认知调度器单测（0.9.29）。
 *
 * 只打纯逻辑（本地规则 / 记忆匹配 / 超时闸），不碰 Context 和数据库 ——
 * 需要 DB 的那层在 `decide()` 里，属于集成范畴，不放进纯 JVM 用例。
 */
class CognitiveSchedulerTest {

    private val now = 1_700_000_000_000L

    // ---------------------------------------------------------------- 本地直达

    @Test
    fun `静默指令本地直达`() {
        val r = CognitiveScheduler.localReplyOf("别说了", now)
        assertEquals("好。", r?.first)
        assertEquals("hush", r?.second)
    }

    @Test
    fun `时间查询本地直达`() {
        val r = CognitiveScheduler.localReplyOf("现在几点了", now)
        assertNotNull(r)
        assertEquals("time", r?.second)
        assertTrue("应该是一句能直接说出口的话：${r?.first}", r!!.first.contains("现在"))
    }

    @Test
    fun `寒暄一律不上本地`() {
        // 本地模板答「在的」和云端那句「怎么这么晚还没睡」是两个人，必须上云
        assertNull(CognitiveScheduler.localReplyOf("在吗", now))
        assertNull(CognitiveScheduler.localReplyOf("晚安", now))
        assertNull(CognitiveScheduler.localReplyOf("今天好累", now))
    }

    @Test
    fun `超长句子不抢答`() {
        val long = "现在几点了" + "我".repeat(50)
        assertNull(CognitiveScheduler.localReplyOf(long, now))
    }

    @Test
    fun `记住的名字直接复述`() {
        val r = CognitiveScheduler.localReplyOf(
            "你还记得我叫什么吗", now, listOf("用户叫老王", "他喜欢安静")
        )
        assertEquals("用户叫老王", r?.first)
    }

    @Test
    fun `没有对应事实时不硬答`() {
        assertNull(CognitiveScheduler.localReplyOf("我叫什么", now, listOf("他喜欢安静")))
    }

    // ---------------------------------------------------------------- 记忆匹配

    @Test
    fun `带具体词的回忆能命中`() {
        val fact = "他上周说要去体检"
        assertNotNull(CognitiveScheduler.matchFact("我上周说的体检你还记得吗", listOf(fact)))
    }

    @Test
    fun `不相关的记忆不硬答`() {
        assertNull(CognitiveScheduler.matchFact("我上周说的体检", listOf("他喜欢猫")))
    }

    @Test
    fun `过长的记忆不直接念出来`() {
        val long = "他上周说他要去体检" + "顺便".repeat(30)
        assertNull(CognitiveScheduler.matchFact("我上周说的体检", listOf(long)))
    }

    @Test
    fun `二字组切分忽略标点`() {
        val g = CognitiveScheduler.grams("体检，报告")
        assertTrue(g.contains("体检"))
        assertTrue(g.contains("报告"))
        assertTrue(g.none { it.contains("，") })
    }

    // ---------------------------------------------------------------- 超时闸

    @Test
    fun `超时抛 DeadlineExceeded 而不是挂住`() = runBlocking {
        var thrown: CognitiveScheduler.DeadlineExceeded? = null
        try {
            CognitiveScheduler.runLimited(50L, "test") { delay(5_000L) }
        } catch (e: CognitiveScheduler.DeadlineExceeded) {
            thrown = e
        }
        assertNotNull("50ms 的闸必须掐断 5s 的活", thrown)
        assertEquals("test", thrown?.stage)
    }

    @Test
    fun `没超时就正常返回`() = runBlocking {
        val v = CognitiveScheduler.runLimited(5_000L, "test") { 42 }
        assertEquals(42, v)
    }

    @Test
    fun `只有后台来源吃六十分钟`() {
        assertEquals(
            CognitiveScheduler.BACKGROUND_LIMIT_MS,
            CognitiveScheduler.limitOf(CognitiveScheduler.Source.SCHEDULE)
        )
        assertEquals(
            CognitiveScheduler.BACKGROUND_LIMIT_MS,
            CognitiveScheduler.limitOf(CognitiveScheduler.Source.QQ)
        )
        assertEquals(
            CognitiveScheduler.CHAT_LIMIT_MS,
            CognitiveScheduler.limitOf(CognitiveScheduler.Source.CHAT)
        )
        assertEquals(
            CognitiveScheduler.CHAT_LIMIT_MS,
            CognitiveScheduler.limitOf(CognitiveScheduler.Source.ROOM)
        )
    }

    @Test
    fun `超时提示必须是一句人话`() {
        val line = CognitiveScheduler.timeoutLine(CognitiveScheduler.BACKGROUND_LIMIT_MS)
        assertTrue(line.contains("60"))
        assertTrue("不能把异常文本直接甩给用户：$line", !line.contains("Exception"))
    }

    // ---------------------------------------------------------------- 漏斗接线

    @Test
    fun `滑窗转成漏斗输入`() {
        val turns = listOf(
            com.nexus.agent.persona.LocalDigitalBeing.Turn("刚说的", "嗯", now - 60_000),
            com.nexus.agent.persona.LocalDigitalBeing.Turn("两小时前", "哦", now - 2 * 60 * 60_000)
        )
        val a = CognitiveScheduler.contextFor(turns, now)
        assertEquals(2, a.total)
        assertEquals(1, a.slices.size)
        assertEquals(1, a.index.size)
    }
}
