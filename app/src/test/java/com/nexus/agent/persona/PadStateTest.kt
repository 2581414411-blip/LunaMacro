package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PAD 动力学单测（0.9.15 阶段 0）。
 *
 * 这组测试钉的是**数学**，不是文案：衰减曲线、EMA、八分区的符号定义、
 * 「表里不一」的触发边界。文案以后还会改，这几条不能动。
 */
class PadStateTest {

    private val day = 24L * 60 * 60 * 1000

    // ------------------------------------------------------------ 衰减

    @Test
    fun `经过一个半衰期刚好走一半`() {
        val from = PAD(1f, 0f, 0f)
        val base = PAD(0f, 0f, 0f)
        val after = PadDynamics.decayToward(from, base, PAD.QUICK_HALF_LIFE_MS, PAD.QUICK_HALF_LIFE_MS)
        assertEquals(0.5f, after.p, 0.01f)
    }

    @Test
    fun `经过两个半衰期走四分之三`() {
        val after = PadDynamics.decayToward(
            PAD(1f, 0f, 0f), PAD.ZERO, PAD.QUICK_HALF_LIFE_MS * 2, PAD.QUICK_HALF_LIFE_MS
        )
        assertEquals(0.25f, after.p, 0.01f)
    }

    @Test
    fun `时间没走过就一点都不衰减`() {
        val from = PAD(0.7f, -0.3f, 0.2f)
        val after = PadDynamics.decayToward(from, PAD.ZERO, 0L, PAD.QUICK_HALF_LIFE_MS)
        assertEquals(from, after)
    }

    @Test
    fun `衰减方向朝向基线而不是零点`() {
        val base = PAD(0.4f, 0.4f, 0.4f)
        val after = PadDynamics.decayToward(PAD(-0.8f, -0.8f, -0.8f), base, 10 * day, PAD.QUICK_HALF_LIFE_MS)
        // 十天足够衰减到基线附近，而不该停在 0
        assertEquals(0.4f, after.p, 0.01f)
    }

    @Test
    fun `衰减结果永远落在合法区间`() {
        val after = PadDynamics.decayToward(PAD(5f, -5f, 9f), PAD.ZERO, 1L, 1L)
        assertTrue(after.p in -1f..1f)
        assertTrue(after.a in -1f..1f)
        assertTrue(after.d in -1f..1f)
    }

    // ------------------------------------------------------------ EMA

    @Test
    fun `慢变量每推一次就靠近快变量一点`() {
        val slow0 = PAD.ZERO
        val quick = PAD(0.8f, 0f, 0f)
        val slow1 = PadDynamics.emaToward(slow0, quick, PAD.SLOW_ALPHA)
        assertEquals(0.064f, slow1.p, 0.005f)
        val slow2 = PadDynamics.emaToward(slow1, quick, PAD.SLOW_ALPHA)
        assertTrue(slow2.p > slow1.p)
        assertTrue(slow2.p < quick.p)
    }

    @Test
    fun `慢变量不会越过快变量`() {
        val slow = PadDynamics.emaToward(PAD(0.9f, 0f, 0f), PAD(-0.9f, 0f, 0f), 1f)
        assertEquals(-0.9f, slow.p, 0.001f)
    }

    // ------------------------------------------------------------ 强度与基线

    @Test
    fun `感性越高同样刺激的倍率越大`() {
        assertTrue(PadDynamics.sensitivityScale(100) > PadDynamics.sensitivityScale(50))
        assertTrue(PadDynamics.sensitivityScale(50) > PadDynamics.sensitivityScale(0))
        assertEquals(0.70f, PadDynamics.sensitivityScale(0), 0.001f)
        assertEquals(1.30f, PadDynamics.sensitivityScale(100), 0.001f)
    }

    @Test
    fun `单轮增量封顶后方向不变`() {
        val capped = PadDynamics.capTo(PAD(3f, 3f, 3f), PAD.MAX_TURN_DELTA)
        assertEquals(PAD.MAX_TURN_DELTA, capped.length(), 0.01f)
        // 三个分量同比例缩小，方向不变
        assertEquals(capped.p / capped.a, 1f, 0.01f)
    }

    @Test
    fun `基线由性格推出来且不越界`() {
        val warm = PadDynamics.baselineOf(
            extroversion = 90, sensibility = 70, rebelliousness = 80, affinity = 95
        )
        val cold = PadDynamics.baselineOf(
            extroversion = 10, sensibility = 30, rebelliousness = 10, affinity = 5
        )
        assertTrue(warm.p > cold.p)
        assertTrue(warm.d > cold.d)
        for (v in listOf(warm, cold)) {
            assertTrue(v.p in -1f..1f)
            assertTrue(v.a in -1f..1f)
            assertTrue(v.d in -1f..1f)
        }
    }

    // ------------------------------------------------------------ 八分区

    @Test
    fun `八分区的符号组合逐个对上`() {
        assertEquals(Octant.EXUBERANT, PadDynamics.octantOf(PAD(0.5f, 0.5f, 0.5f)))
        assertEquals(Octant.DEPENDENT, PadDynamics.octantOf(PAD(0.5f, 0.5f, -0.5f)))
        assertEquals(Octant.RELAXED, PadDynamics.octantOf(PAD(0.5f, -0.5f, 0.5f)))
        assertEquals(Octant.DOCILE, PadDynamics.octantOf(PAD(0.5f, -0.5f, -0.5f)))
        assertEquals(Octant.DISDAINFUL, PadDynamics.octantOf(PAD(-0.5f, 0.5f, 0.5f)))
        assertEquals(Octant.ANXIOUS, PadDynamics.octantOf(PAD(-0.5f, 0.5f, -0.5f)))
        assertEquals(Octant.HOSTILE, PadDynamics.octantOf(PAD(-0.5f, -0.5f, 0.5f)))
        assertEquals(Octant.BORED, PadDynamics.octantOf(PAD(-0.5f, -0.5f, -0.5f)))
    }

    @Test
    fun `强度三档按向量长度切`() {
        assertEquals(PadStrength.FAINT, PadDynamics.strengthOf(PAD(0.10f, 0f, 0f)))
        assertEquals(PadStrength.MODERATE, PadDynamics.strengthOf(PAD(0.35f, 0f, 0f)))
        assertEquals(PadStrength.STRONG, PadDynamics.strengthOf(PAD(0.70f, 0f, 0f)))
    }

    @Test
    fun `中性坐标描述成平静而不是雀跃`() {
        // (0,0,0) 按符号会被判成 +P+A+D（雀跃），必须在描述层拦掉
        assertEquals(Octant.EXUBERANT, PadDynamics.octantOf(PAD.ZERO))
        assertEquals("平静", PadDynamics.describeOf(PAD.ZERO))
    }

    @Test
    fun `描述带强度前缀`() {
        // 注意符号：a 为负、d 为正才是「松弛」。只给 P 正值会落进「雀跃」，
        // 这是八分区最容易写错的地方，所以这里用完整的三轴向量。
        assertEquals("淡淡的松弛", PadDynamics.describeOf(PAD(0.18f, -0.02f, 0.02f)))
        assertEquals("松弛", PadDynamics.describeOf(PAD(0.40f, -0.05f, 0.05f)))
        assertEquals("很松弛", PadDynamics.describeOf(PAD(0.75f, -0.10f, 0.10f)))
    }

    /**
     * 0.9.15 跑轨迹跑出来的真问题：凌晨四点被关心一下，坐标是 (−0.01, −0.45, −0.12)，
     * 八分区给「消沉」—— 但她不是意志消沉，是困得说不出话。
     * 低唤醒是生理状态，低愉悦才是情绪低落，混了模型就会把「困」演成「抑郁」。
     */
    @Test
    fun `低唤醒不说消沉说犯困`() {
        assertEquals("犯困", PadDynamics.describeOf(PAD(-0.01f, -0.45f, -0.12f)))
        // 心情标签走同一条路，羁绊面板才知道该挂 😴
        assertEquals("犯困", PadDynamics.moodLabelOf(PAD(-0.01f, -0.45f, -0.12f), PAD.ZERO))
    }

    @Test
    fun `真难过的低唤醒还是消沉`() {
        // P 也很低 = 被伤了心，这时候用「困」糊过去是撒谎。
        // 这个向量长度 0.64 > STRONG_ABOVE，所以会带「很」前缀 —— 本来就该是很丧
        assertEquals("很消沉", PadDynamics.describeOf(PAD(-0.45f, -0.35f, -0.30f)))
    }

    // ------------------------------------------------------------ 表里不一

    @Test
    fun `嘴上开心心里难过判为表里不一`() {
        val quick = PAD(0.6f, 0.2f, 0.1f)
        val slow = PAD(-0.5f, 0f, 0f)
        assertTrue(PadDynamics.isMasked(quick, slow))
    }

    @Test
    fun `同向只是程度不同不算表里不一`() {
        // 都是开心，只是程度差得远 —— 这不是强颜欢笑
        assertFalse(PadDynamics.isMasked(PAD(0.9f, 0f, 0f), PAD(0.1f, 0f, 0f)))
    }

    @Test
    fun `异号但落差太小不算表里不一`() {
        // 差别太小，说出来就是矫情
        assertFalse(PadDynamics.isMasked(PAD(0.10f, 0f, 0f), PAD(-0.10f, 0f, 0f)))
    }

    @Test
    fun `提示词段落里有底色和眼下两行`() {
        val block = PadDynamics.promptBlock(PAD(0.1f, 0f, 0f), PAD(-0.3f, 0f, 0f))
        assertTrue(block.contains("底色"))
        assertTrue(block.contains("眼下"))
        // 必须明确禁止报数值，否则她会说「我现在愉悦度是 0.1」
        assertTrue(block.contains("禁止提任何数值"))
    }

    @Test
    fun `表里不一时提示词里会说出心里那点情绪`() {
        val block = PadDynamics.promptBlock(PAD(0.6f, 0f, 0f), PAD(-0.6f, 0f, 0f))
        assertTrue(block.contains("嘴上说得出话"))
    }

    // ------------------------------------------------------------ 推进

    @Test
    fun `快变量先衰减再加刺激`() {
        val base = PAD.ZERO
        // 上一轮是 1.0，隔了一个半衰期后衰减到 0.5，再加 0.2
        val next = PadDynamics.evolveQuick(
            current = PAD(1f, 0f, 0f),
            base = base,
            delta = PAD(0.2f, 0f, 0f),
            dtMs = PAD.QUICK_HALF_LIFE_MS,
            sensibility = 50
        )
        // 敏感性 50 → 倍率 1.0，0.5 + 0.2 = 0.7
        assertEquals(0.7f, next.p, 0.02f)
    }

    @Test
    fun `慢变量低于门槛就不推`() {
        val slow = PAD(0.1f, 0f, 0f)
        // 快变量强度 0.1 < 0.25，走门控分支：只衰减，不 EMA
        val next = PadDynamics.evolveSlow(slow, PAD(0.1f, 0f, 0f), PAD.ZERO, dtMs = 0L)
        assertEquals(slow.p, next.p, 0.001f)
    }

    @Test
    fun `慢变量超过门槛才被推`() {
        val slow = PAD.ZERO
        val next = PadDynamics.evolveSlow(slow, PAD(0.8f, 0f, 0f), PAD.ZERO, dtMs = 0L)
        assertTrue(next.p > 0f)
        assertTrue(next.p < 0.1f) // 一轮只挪一小步
    }

    @Test
    fun `心情标签优先看当下`() {
        assertEquals("松弛", PadDynamics.moodLabelOf(PAD(0.5f, -0.1f, 0.1f), PAD(-0.5f, 0f, 0f)))
    }

    @Test
    fun `当下很淡时心情标签退回底色`() {
        assertEquals("焦躁", PadDynamics.moodLabelOf(PAD(0.02f, 0f, 0f), PAD(-0.5f, 0.5f, -0.5f)))
    }

    @Test
    fun `全都贴在中性时标签是平静`() {
        assertEquals("平静", PadDynamics.moodLabelOf(PAD.ZERO, PAD.ZERO))
    }
}
