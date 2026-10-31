package com.nexus.agent.copilot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 人格记忆的纯逻辑测试（不依赖 Android）。
 *
 * 重点验证两件事：
 *  1. 环形缓冲真的只留 [PersonaEngine.MAX_TURNS] 轮，多了丢最旧的
 *  2. 空回复不会被记成一轮（否则历史里会出现「你：」后面啥都没有）
 */
class PersonaEngineTest {

    @Before
    fun setUp() = PersonaEngine.clearMemory()

    @After
    fun tearDown() = PersonaEngine.clearMemory()

    @Test
    fun `初始状态没有记忆`() {
        assertEquals(0, PersonaEngine.turnCount())
        assertTrue(PersonaEngine.historyMessages().isEmpty())
        assertEquals("", PersonaEngine.historyText())
    }

    @Test
    fun `记一轮后能取回`() {
        PersonaEngine.recordTurn("在吗", "在")
        assertEquals(1, PersonaEngine.turnCount())
        val msgs = PersonaEngine.historyMessages()
        assertEquals(2, msgs.size)
        assertEquals("user", msgs[0].role)
        assertEquals("在吗", msgs[0].content)
        assertEquals("assistant", msgs[1].role)
        assertEquals("在", msgs[1].content)
    }

    @Test
    fun `超过六轮丢最旧的`() {
        for (i in 1..10) {
            PersonaEngine.recordTurn("问题$i", "回答$i")
        }
        assertEquals(PersonaEngine.MAX_TURNS, PersonaEngine.turnCount())

        val msgs = PersonaEngine.historyMessages()
        // 应该只剩 5~10 轮，最早的是「问题5」
        assertEquals(PersonaEngine.MAX_TURNS * 2, msgs.size)
        assertEquals("问题5", msgs.first().content)
        assertEquals("回答10", msgs.last().content)
    }

    @Test
    fun `空回复不计入记忆`() {
        PersonaEngine.recordTurn("在吗", "")
        PersonaEngine.recordTurn("在吗", "   ")
        assertEquals(0, PersonaEngine.turnCount())
    }

    @Test
    fun `空提问不计入记忆`() {
        PersonaEngine.recordTurn("", "在")
        PersonaEngine.recordTurn("   ", "在")
        assertEquals(0, PersonaEngine.turnCount())
    }

    @Test
    fun `最近一条回复能取到`() {
        PersonaEngine.recordTurn("一", "第一次")
        PersonaEngine.recordTurn("二", "第二次")
        assertEquals("第二次", PersonaEngine.lastAssistantReply())
    }

    @Test
    fun `历史文本带角色前缀且长内容被截断`() {
        val long = "啊".repeat(200)
        PersonaEngine.recordTurn(long, long)
        val text = PersonaEngine.historyText()
        assertTrue(text.startsWith("用户："))
        assertTrue(text.contains("\n你："))
        // 每条截断到 60 字：整段应该远小于 400 字
        assertTrue("历史文本过长：${text.length}", text.length < 200)
    }

    @Test
    fun `清空记忆后回到初始状态`() {
        PersonaEngine.recordTurn("在吗", "在")
        PersonaEngine.clearMemory()
        assertEquals(0, PersonaEngine.turnCount())
        assertEquals("", PersonaEngine.historyText())
    }
}
