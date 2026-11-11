package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刺激判定单测（0.9.15 阶段 0）。
 *
 * 重点不是词表全不全，而是四条规则：
 *  - 冷比热优先（阴阳怪气算凶）；
 *  - 软例外放行（「滚去睡觉」是关心）；
 *  - 重复词不叠加（一句三个「谢谢」不该是三倍）；
 *  - 单轮总模长封顶（一句难听话不能把情绪打穿）。
 */
class PadAppraisalTest {

    private fun appraise(
        text: String,
        idleMs: Long = 0L,
        isLateNight: Boolean = false
    ) = PadAppraisal.appraise(PadAppraisal.Event(text, idleMs, isLateNight))

    // ------------------------------------------------------------ 基本信号

    @Test
    fun `夸奖抬高愉悦度`() {
        val r = appraise("谢谢你啊")
        assertTrue(r.signals.contains(PadSignal.PRAISE))
        assertTrue(r.delta.p > 0f)
        assertTrue(r.delta.d > 0f)
    }

    @Test
    fun `被凶压低愉悦度和支配感并抬高唤醒度`() {
        val r = appraise("滚")
        assertTrue(r.signals.contains(PadSignal.COLD))
        assertTrue(r.delta.p < 0f)
        assertTrue(r.delta.a > 0f)
        assertTrue(r.delta.d < 0f)
    }

    @Test
    fun `被凶的力度大于被夸`() {
        val cold = appraise("烦死了").delta.length()
        val praise = appraise("你真棒").delta.length()
        assertTrue(cold > praise)
    }

    @Test
    fun `软例外是关心不是骂`() {
        val r = appraise("滚去睡觉")
        assertTrue(r.isEmpty)
        assertEquals(PAD.ZERO, r.delta)
    }

    @Test
    fun `阴阳怪气优先判凶`() {
        // 含冷词也含夸词 —— 冷词否决
        val r = appraise("你懂什么，谢谢啊")
        assertTrue(r.signals.contains(PadSignal.COLD))
        assertFalse(r.signals.contains(PadSignal.PRAISE))
    }

    @Test
    fun `重复的夸奖词只算一次`() {
        val once = appraise("谢谢")
        val many = appraise("谢谢谢谢谢谢")
        assertEquals(once.delta.length(), many.delta.length(), 0.001f)
    }

    @Test
    fun `空文本什么都不发生`() {
        assertTrue(appraise("   ").isEmpty)
    }

    // ------------------------------------------------------------ 被依赖

    @Test
    fun `被依赖是正向且能和被夸叠加`() {
        val rely = appraise("帮我看看这个")
        assertTrue(rely.signals.contains(PadSignal.RELIED))
        assertTrue(rely.delta.p > 0f)

        val both = appraise("谢谢你帮我弄")
        assertTrue(both.signals.contains(PadSignal.PRAISE))
        assertTrue(both.signals.contains(PadSignal.RELIED))
        assertTrue(both.delta.p > appraise("谢谢你啊").delta.p)
    }

    // ------------------------------------------------------------ 被晾着

    @Test
    fun `几小时没说话不算被晾着`() {
        assertTrue(appraise("在吗", idleMs = 3 * 60 * 60 * 1000L).signals
            .none { it == PadSignal.IGNORED })
    }

    @Test
    fun `六小时没说话算一次`() {
        val r = appraise("在吗", idleMs = 6 * 60 * 60 * 1000L)
        assertEquals(1, r.signals.count { it == PadSignal.IGNORED })
    }

    @Test
    fun `被晾着累计不超过两次`() {
        val r = appraise("在吗", idleMs = 30 * 60 * 60 * 1000L)
        assertEquals(PadAppraisal.MAX_IGNORED, r.signals.count { it == PadSignal.IGNORED })
    }

    @Test
    fun `被晾着压低唤醒度`() {
        // 空文本直接短路：一句话都没说，构不成「被晾着」
        assertTrue(appraise("", idleMs = 12 * 60 * 60 * 1000L).isEmpty)
        val r = appraise("嗯", idleMs = 12 * 60 * 60 * 1000L)
        assertTrue(r.delta.a < 0f)
    }

    // ------------------------------------------------------------ 深夜

    @Test
    fun `深夜会让人犯困`() {
        val r = appraise("还没睡啊", isLateNight = true)
        assertTrue(r.signals.contains(PadSignal.LATE_NIGHT))
        assertTrue(r.delta.a < 0f)
    }

    @Test
    fun `深夜被关心是困但暖`() {
        // 注意 plain 不能含 CARE_WORDS（睡/休息/晚安…），否则两条都触发关怀，比不出差异
        val plain = appraise("在忙什么呢", isLateNight = true)
        val cared = appraise("早点睡吧", isLateNight = true)
        assertTrue(cared.signals.contains(PadSignal.CARE_AT_NIGHT))
        assertTrue(cared.delta.p > plain.delta.p)
    }

    @Test
    fun `白天说早点睡不算深夜关怀`() {
        val r = appraise("早点睡吧", isLateNight = false)
        assertFalse(r.signals.contains(PadSignal.CARE_AT_NIGHT))
    }

    // ------------------------------------------------------------ 封顶与倍率

    @Test
    fun `单轮总增量不会超过上限`() {
        // 被夸 + 被依赖 + 深夜 + 关怀，四项叠加
        val r = appraise("谢谢你帮我弄，早点睡吧", isLateNight = true)
        assertTrue(r.delta.length() <= PAD.MAX_TURN_DELTA + 0.001f)
    }

    @Test
    fun `感性的人反应更大但同样封顶`() {
        val raw = PAD(0.5f, 0.2f, 0.1f)
        val calm = PadAppraisal.scaled(raw, sensibility = 0)
        val sensitive = PadAppraisal.scaled(raw, sensibility = 100)
        assertTrue(sensitive.length() > calm.length())
        assertTrue(sensitive.length() <= PAD.MAX_TURN_DELTA + 0.001f)
    }

    @Test
    fun `零增量乘倍率还是零`() {
        assertEquals(0f, PadAppraisal.scaled(PAD.ZERO, 100).length(), 0.0001f)
    }
}
