package com.nexus.agent.persona

import com.nexus.agent.data.db.InnerThought
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心智引擎的判定与提示词（0.9.6）。
 *
 * 这一层最容易出问题的地方是「该想的时候不想、不该想的时候瞎想」：
 * 半夜生成一段独白没人看还烧钱；刚聊完就脑补等于自说自话；
 * 一分钟内连想八次会把接口刷爆。所以判定用纯函数写，逐条钉死。
 */
class InnerMonologueTest {

    private val minute = 60_000L
    private val hour = 3_600_000L
    private val now = 1_700_000_000_000L

    // ---------------------------------------------------------------- 判定

    @Test
    fun `刚聊完不想事情`() {
        assertEquals(
            InnerMonologueEngine.Verdict.NOT_IDLE,
            InnerMonologueEngine.decide(
                nowMs = now,
                hourOfDay = 14,
                lastChatAt = now - 5 * minute,
                lastThoughtAt = 0
            )
        )
    }

    @Test
    fun `安静够久就可以想`() {
        assertEquals(
            InnerMonologueEngine.Verdict.SEND,
            InnerMonologueEngine.decide(
                nowMs = now,
                hourOfDay = 14,
                lastChatAt = now - 40 * minute,
                lastThoughtAt = 0
            )
        )
    }

    @Test
    fun `夜里不想`() {
        listOf(23, 0, 3, 6).forEach { h ->
            assertEquals(
                "$h 点不该想事情",
                InnerMonologueEngine.Verdict.NIGHT,
                InnerMonologueEngine.decide(now, h, now - 10 * hour, 0)
            )
        }
        // 边界：7 点可以，22 点可以
        listOf(7, 22).forEach { h ->
            assertEquals(
                "$h 点应该可以想",
                InnerMonologueEngine.Verdict.SEND,
                InnerMonologueEngine.decide(now, h, now - 10 * hour, 0)
            )
        }
    }

    @Test
    fun `从没聊过就没有素材`() {
        assertEquals(
            InnerMonologueEngine.Verdict.NEVER_CHATTED,
            InnerMonologueEngine.decide(now, 14, lastChatAt = 0, lastThoughtAt = 0)
        )
    }

    @Test
    fun `两次想事情不能挨太近`() {
        assertEquals(
            InnerMonologueEngine.Verdict.TOO_SOON,
            InnerMonologueEngine.decide(now, 14, now - 10 * hour, now - 30 * minute)
        )
        assertEquals(
            InnerMonologueEngine.Verdict.SEND,
            InnerMonologueEngine.decide(now, 14, now - 10 * hour, now - 2 * hour)
        )
    }

    @Test
    fun `关掉了就什么都不做`() {
        assertEquals(
            InnerMonologueEngine.Verdict.DISABLED,
            InnerMonologueEngine.decide(now, 14, now - 10 * hour, 0, enabled = false)
        )
    }

    @Test
    fun `周期比心跳慢得多`() {
        // 心跳 15 分钟一次，想事情 2 小时一次 —— 想事情本来就该慢
        assertEquals(2L, InnerMonologueEngine.CHECK_INTERVAL_HOURS)
        assertTrue(
            "冷却必须小于周期，否则周期漂移会把这一次直接吞掉",
            InnerMonologueEngine.MIN_GAP_MINUTES < InnerMonologueEngine.CHECK_INTERVAL_HOURS * 60
        )
    }

    // ---------------------------------------------------------------- 提示词

    private fun prompt(
        facts: List<String> = emptyList(),
        topic: String? = null,
        summary: String? = null,
        mood: String = "平静",
        affinity: Int = 60
    ): String = InnerMonologueEngine.buildPrompt(
        name = "宏月",
        mood = mood,
        affinity = affinity,
        facts = facts,
        topic = topic,
        recentSummary = summary,
        hourOfDay = 15
    )

    @Test
    fun `提示词带上记挂着的事`() {
        val p = prompt(facts = listOf("用户叫老张"), topic = "明天要体检")
        assertTrue(p.contains("宏月"))
        assertTrue(p.contains("用户叫老张"))
        assertTrue("没有话题，她就只能泛泛地想", p.contains("明天要体检"))
        assertTrue(p.contains("30 个字"))
        assertTrue("要明确禁止元描述，否则会写成日记", p.contains("元描述"))
    }

    @Test
    fun `好感度决定独白的底色`() {
        assertTrue(prompt(affinity = 90).contains("很想黏着他"))
        assertTrue(prompt(affinity = 10).contains("生他的气"))
        assertTrue(prompt(affinity = 50).contains("平常心"))
    }

    @Test
    fun `没有素材时不留空段`() {
        val p = prompt()
        assertFalse(p.contains("你记得关于他的事"))
        assertFalse(p.contains("你一直记挂着的"))
        assertFalse(p.contains("你们最近聊过"))
        // 但任务说明必须在，否则模型不知道自己该干嘛
        assertTrue(p.contains("没有人在跟你说话"))
    }

    // ---------------------------------------------------------------- 清洗

    @Test
    fun `去掉引号与前缀`() {
        assertEquals("也不知道他体检怎么样了", InnerMonologueEngine.clean("「也不知道他体检怎么样了」"))
        assertEquals("也不知道他体检怎么样了", InnerMonologueEngine.clean("“也不知道他体检怎么样了”"))
    }

    @Test
    fun `多行只取第一句有效的`() {
        val raw = "好的，我来写一句。\n也不知道他体检结果怎么样了，有点担心\n（完）"
        assertEquals("也不知道他体检结果怎么样了，有点担心", InnerMonologueEngine.clean(raw))
    }

    @Test
    fun `太短的和空的都算没想出来`() {
        assertNull(InnerMonologueEngine.clean(null))
        assertNull(InnerMonologueEngine.clean(""))
        assertNull(InnerMonologueEngine.clean("嗯"))
        assertNull(InnerMonologueEngine.clean("好的，我来写"))
    }

    @Test
    fun `超长截断到上限`() {
        val long = "想".repeat(120)
        val out = InnerMonologueEngine.clean(long)!!
        assertEquals(InnerThought.CONTENT_MAX, out.length)
    }
}
