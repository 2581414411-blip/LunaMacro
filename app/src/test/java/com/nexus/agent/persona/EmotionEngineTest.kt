package com.nexus.agent.persona

import com.nexus.agent.copilot.PersonaEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 情绪引擎单测（0.9.2 批次一）。
 *
 * 重点不是「关键词命中」，而是**状态真的会动**：
 * 0.9.1 之前 bumpAffinity/updateMood 一次都没被生产代码调过，
 * 这组测试就是那道「以后再有人把这条线剪断就红」的护栏。
 */
class EmotionEngineTest {

    @Before
    fun setUp() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
    }

    // ------------------------------------------------------------ 情绪判定

    @Test
    fun `夸奖和感谢判为正向`() {
        assertEquals(EmotionEngine.Signal.PRAISE, EmotionEngine.signalOf("谢谢你啊"))
        assertEquals(EmotionEngine.Signal.PRAISE, EmotionEngine.signalOf("你真棒"))
        assertEquals(EmotionEngine.Signal.PRAISE, EmotionEngine.signalOf("今天辛苦了"))
        assertEquals(EmotionEngine.Signal.PRAISE, EmotionEngine.signalOf("好喜欢你"))
    }

    @Test
    fun `骂人判为负向`() {
        assertEquals(EmotionEngine.Signal.COLD, EmotionEngine.signalOf("滚"))
        assertEquals(EmotionEngine.Signal.COLD, EmotionEngine.signalOf("烦死了别吵"))
        assertEquals(EmotionEngine.Signal.COLD, EmotionEngine.signalOf("闭嘴"))
        assertEquals(EmotionEngine.Signal.COLD, EmotionEngine.signalOf("你真没用"))
    }

    @Test
    fun `普通聊天判为中性`() {
        assertEquals(EmotionEngine.Signal.NEUTRAL, EmotionEngine.signalOf("今天天气怎么样"))
        assertEquals(EmotionEngine.Signal.NEUTRAL, EmotionEngine.signalOf("帮我把闹钟关了"))
        assertEquals(EmotionEngine.Signal.NEUTRAL, EmotionEngine.signalOf(""))
    }

    @Test
    fun `滚去睡觉是关心不是骂人`() {
        // 「滚」在负向词表里，但「滚去睡」是催人睡觉，扣分就太蠢了
        assertEquals(EmotionEngine.Signal.NEUTRAL, EmotionEngine.signalOf("滚去睡觉"))
        assertEquals(EmotionEngine.Signal.NEUTRAL, EmotionEngine.signalOf("滚去吃饭"))
    }

    @Test
    fun `加分与心情映射`() {
        assertEquals(1.0, EmotionEngine.deltaOf(EmotionEngine.Signal.PRAISE), 1e-9)
        assertEquals(-1.0, EmotionEngine.deltaOf(EmotionEngine.Signal.COLD), 1e-9)
        assertEquals(0.2, EmotionEngine.deltaOf(EmotionEngine.Signal.NEUTRAL), 1e-9)

        assertEquals("开心", EmotionEngine.moodOf(EmotionEngine.Signal.PRAISE))
        assertEquals("难过", EmotionEngine.moodOf(EmotionEngine.Signal.COLD))
        assertEquals("平静", EmotionEngine.moodOf(EmotionEngine.Signal.NEUTRAL))
    }

    // ------------------------------------------------------------ 分档

    @Test
    fun `好感度三档边界`() {
        assertEquals(EmotionEngine.Tier.COLD, EmotionEngine.tierOf(0))
        assertEquals(EmotionEngine.Tier.COLD, EmotionEngine.tierOf(29))
        assertEquals(EmotionEngine.Tier.NORMAL, EmotionEngine.tierOf(30))
        assertEquals(EmotionEngine.Tier.NORMAL, EmotionEngine.tierOf(79))
        assertEquals(EmotionEngine.Tier.CLOSE, EmotionEngine.tierOf(80))
        assertEquals(EmotionEngine.Tier.CLOSE, EmotionEngine.tierOf(100))
    }

    @Test
    fun `冷淡档要求极短回复`() {
        val block = EmotionEngine.toneBlock(10, "难过")
        assertTrue("要带心情", block.contains("【当前心情】难过"))
        assertTrue("要明确冷淡", block.contains("有点冷"))
        assertTrue("要限制字数", block.contains("八个字"))
        assertTrue("禁止关心", block.contains("不要关心"))
    }

    @Test
    fun `正常档不冷淡也不撒娇`() {
        val block = EmotionEngine.toneBlock(50, "平静")
        assertTrue(block.contains("还算正常"))
        assertTrue(block.contains("别腻"))
        assertTrue(!block.contains("有点冷"))
    }

    @Test
    fun `亲昵档会主动关心`() {
        val block = EmotionEngine.toneBlock(90, "开心")
        assertTrue(block.contains("很亲近"))
        assertTrue(block.contains("关心"))
        assertTrue(block.contains("主动分享"))
    }

    @Test
    fun `提示词里绝不暴露好感度数字`() {
        val block = EmotionEngine.toneBlock(88, "开心")
        assertTrue("要写禁止说出数字", block.contains("绝对不要说出这些数字"))
    }

    // ------------------------------------------------------------ 状态真的会动

    @Test
    fun `被夸一次立刻涨一点并变开心`() {
        val before = PersonaEngine.profileSnapshot().affinity
        EmotionEngine.onTurn(null, "谢谢你，真棒")
        assertEquals(before + 1, PersonaEngine.profileSnapshot().affinity)
        assertEquals("开心", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `被凶一次立刻掉一点并变难过`() {
        val before = PersonaEngine.profileSnapshot().affinity
        EmotionEngine.onTurn(null, "滚，烦死了")
        assertEquals(before - 1, PersonaEngine.profileSnapshot().affinity)
        assertEquals("难过", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `普通聊天累积五次才涨一点`() {
        val before = PersonaEngine.profileSnapshot().affinity
        repeat(4) { EmotionEngine.onTurn(null, "嗯，今天上班挺累的") }
        assertEquals("四次还不够一点", before, PersonaEngine.profileSnapshot().affinity)
        EmotionEngine.onTurn(null, "晚上吃什么好呢")
        assertEquals("第五次补上", before + 1, PersonaEngine.profileSnapshot().affinity)
    }

    @Test
    fun `普通聊天气氛回落平静`() {
        EmotionEngine.onTurn(null, "好棒")
        assertEquals("开心", PersonaEngine.profileSnapshot().mood)
        EmotionEngine.onTurn(null, "我在写代码")
        assertEquals("普通一句就回落平静（严格按规则，不做额外惯性）", "平静", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `好感度夹在 0 到 100 之间`() {
        PersonaEngine.bumpAffinity(100)
        assertEquals(100, PersonaEngine.profileSnapshot().affinity)
        EmotionEngine.onTurn(null, "谢谢你")
        assertEquals("已经在顶了不该越界", 100, PersonaEngine.profileSnapshot().affinity)

        PersonaEngine.bumpAffinity(-100)
        EmotionEngine.onTurn(null, "滚")
        assertEquals("已经在底了不该越界", 0, PersonaEngine.profileSnapshot().affinity)
    }

    @Test
    fun `余数会保留下来不丢`() {
        EmotionEngine.onTurn(null, "普通一句")
        assertEquals(0.2, EmotionEngine.carry(null), 0.01)
        EmotionEngine.onTurn(null, "再来一句")
        assertEquals(0.4, EmotionEngine.carry(null), 0.01)
    }
}
