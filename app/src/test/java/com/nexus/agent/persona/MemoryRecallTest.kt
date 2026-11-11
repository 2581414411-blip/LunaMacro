package com.nexus.agent.persona

import com.nexus.agent.data.db.MemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 召回桥接层测试（0.9.15 阶段 2）。
 *
 * 这一层最容易出的错不是算错分，而是**把老数据算偏**：
 * 0.9.14 之前的记忆全是 `pad=(0,0,0)`、`intensity=0`、没有关键词，
 * 如果它们在打分时被系统性地抬高或压低，用户升级当天就会发现「她提的东西变了」。
 */
class MemoryRecallTest {

    private val now = 1_700_000_000_000L

    private fun entity(
        id: Long,
        fact: String,
        pad: PAD = PAD.ZERO,
        keywords: String = "",
        intensity: Float = 0f,
        createdAt: Long = now
    ) = MemoryEntity(
        id = id,
        fact = fact,
        weight = 0.9f,
        createdAt = createdAt,
        lastHitAt = createdAt,
        padP = pad.p,
        padA = pad.a,
        padD = pad.d,
        emotionalIntensity = intensity,
        keywords = keywords
    )

    @Test
    fun `实体映射保留三轴坐标`() {
        val c = MemoryRecall.candidateOf(entity(1, "用户怕黑", pad = PAD(-0.4f, 0.3f, -0.2f)))
        assertEquals(-0.4f, c.pad.p, 0.001f)
        assertEquals(0.3f, c.pad.a, 0.001f)
        assertEquals(-0.2f, c.pad.d, 0.001f)
    }

    @Test
    fun `强度由 PAD 现算`() {
        // PAD 长度 0.6 的坐标：normalized ≈ 0.6/1.732 ≈ 0.346
        val c = MemoryRecall.candidateOf(entity(1, "用户失恋了", pad = PAD(0.6f, 0f, 0f)))
        assertEquals(0.6f / PAD.MAX_LEN, c.intensity, 0.001f)
        assertEquals(MemoryRecall.intensityOf(PAD(0.6f, 0f, 0f)), c.intensity, 0.0001f)
    }

    @Test
    fun `老数据的强度是零`() {
        val c = MemoryRecall.candidateOf(entity(1, "用户是程序员"))
        assertEquals(0f, c.intensity, 0.0001f)
        assertTrue(c.pad.isNeutral())
    }

    @Test
    fun `老数据之间不产生情绪偏好`() {
        // 两条同样「没有情感指纹」的记忆，情绪一致性必须完全相同 —— 否则排序
        // 会被一个跟内容无关的量决定
        val list = listOf(
            entity(1, "用户喜欢喝美式"),
            entity(2, "用户喜欢喝拿铁")
        )
        val ranked = MemoryRecall.rank(list, "喜欢喝什么", PAD(0.8f, 0.6f, 0.4f), now)
        assertEquals(2, ranked.size)
        assertEquals(ranked[0].congruence, ranked[1].congruence, 0.0001f)
    }

    @Test
    fun `没有语义命中时返回空表示该回退`() {
        // 空列表的语义是「请走老排序」，不是「她没有记忆」。
        // 没有它，她难过时随便翻一条不相干的伤心事出来，比记不住更吓人。
        val facts = MemoryRecall.factsOf(
            listOf(entity(1, "用户喜欢喝美式")),
            "明天要体检",
            PAD(-0.6f, 0.4f, -0.3f),
            now
        )
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `有语义命中时按分排序并回到原文`() {
        val list = listOf(
            entity(1, "用户喜欢喝美式咖啡"),
            entity(2, "用户讨厌早起")
        )
        val facts = MemoryRecall.factsOf(list, "美式咖啡", PAD.ZERO, now)
        assertFalse(facts.isEmpty())
        assertEquals("用户喜欢喝美式咖啡", facts.first())
    }

    @Test
    fun `空候选池直接返回空`() {
        assertTrue(MemoryRecall.factsOf(emptyList(), "随便说点什么", PAD.ZERO, now).isEmpty())
    }

    @Test
    fun `排障行说清挑了谁和为什么`() {
        val list = listOf(entity(1, "用户喜欢喝美式咖啡"))
        val ranked = MemoryRecall.rank(list, "美式咖啡", PAD(0.5f, 0.3f, 0.2f), now)
        val line = MemoryRecall.explain(ranked, PAD(0.5f, 0.3f, 0.2f), list.size)
        assertTrue(line.contains("【召回】"))
        assertTrue("要能看出候选池多大", line.contains("候选=1"))
        assertTrue("要能看出当前心情", line.contains("当前="))
        assertTrue("要能看到选中的那条", line.contains("美式咖啡"))
    }
}
