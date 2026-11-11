package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆召回打分单测（0.9.15 阶段 0）。
 *
 * 最重要的一条是 **`中性记忆不偏置`**：它是这次数据库迁移的安全底线。
 * 老数据 pad 全是 0，如果新公式会让它们集体沉底或集体冒头，
 * 用户升级后会发现「她的记忆突然变了」，而日志里什么都看不出来。
 */
class MemoryRankerTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun mem(
        id: Long,
        fact: String,
        keywords: String = "",
        pad: PAD = PAD.ZERO,
        intensity: Float = 0f,
        createdAt: Long = now - day
    ) = MemoryRanker.Candidate(id, fact, keywords, pad, intensity, createdAt)

    private fun kw(vararg words: String) = MemoryRanker.encodeKeywords(words.toList())

    // ------------------------------------------------------------ 关键词

    @Test
    fun `切词能切出有意义的片段`() {
        val tokens = MemoryRanker.tokenize("他讨厌早起")
        assertTrue(tokens.any { it.contains("早起") })
        assertTrue(tokens.size <= 6)
    }

    @Test
    fun `全是虚字的片段会被丢掉`() {
        // 「我们」「这个」整词都是虚字，不该进关键词
        val tokens = MemoryRanker.tokenize("我们")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun `英文整词保留`() {
        val tokens = MemoryRanker.tokenize("他在弄 ComfyUI")
        assertTrue(tokens.contains("comfyui"))
    }

    @Test
    fun `关键词编解码往返一致且中文逗号不劈词`() {
        val tricky = listOf("今天，明天", "早起")
        val decoded = MemoryRanker.decodeKeywords(MemoryRanker.encodeKeywords(tricky))
        assertEquals(tricky, decoded)
    }

    @Test
    fun `空关键词解码为空列表`() {
        assertTrue(MemoryRanker.decodeKeywords("").isEmpty())
        assertTrue(MemoryRanker.decodeKeywords("   ").isEmpty())
    }

    // ------------------------------------------------------------ 语义分

    @Test
    fun `同一件事的语义分是满的`() {
        val m = mem(1, "他讨厌早起", kw("讨厌", "早起"))
        assertEquals(1f, MemoryRanker.semanticOf(listOf("早起"), m), 0.001f)
    }

    @Test
    fun `毫无关系的记忆语义分为零`() {
        val m = mem(1, "报销还没弄", kw("报销", "没弄"))
        assertEquals(0f, MemoryRanker.semanticOf(listOf("早起"), m), 0.001f)
    }

    @Test
    fun `老数据没有关键词时现切也能算分`() {
        // keywords 为空 = 迁移前的老记忆，必须照样能参与打分
        val m = mem(1, "他讨厌早起", keywords = "")
        assertTrue(MemoryRanker.semanticOf(listOf("早起"), m) > 0f)
    }

    @Test
    fun `记忆词少时不会因为查询词多而被压低`() {
        // 只有两个关键词，命中一个就算 1/2，而不是 1/6
        val m = mem(1, "他讨厌早起", kw("讨厌", "早起"))
        assertEquals(0.5f, MemoryRanker.semanticOf(listOf("早起", "别的", "无关", "词", "啊", "嗯"), m), 0.001f)
    }

    // ------------------------------------------------------------ 情绪一致性

    @Test
    fun `情绪一致时距离为零`() {
        val cur = PAD(-0.6f, 0.2f, -0.1f)
        assertEquals(1f, MemoryRanker.congruenceOf(cur, cur), 0.001f)
    }

    @Test
    fun `完全相反时一致性为零`() {
        assertEquals(
            0f,
            MemoryRanker.congruenceOf(PAD(1f, 1f, 1f), PAD(-1f, -1f, -1f)),
            0.001f
        )
    }

    @Test
    fun `难过时更容易想起难过的记忆`() {
        val neg = mem(1, "他讨厌早起", kw("讨厌", "早起"), PAD(-0.8f, 0f, 0f), 0.8f)
        val pos = mem(2, "他讨厌早起", kw("讨厌", "早起"), PAD(0.8f, 0f, 0f), 0.8f)
        val sad = MemoryRanker.rank(listOf(neg, pos), "早起", PAD(-0.8f, 0f, 0f), now, 10)
        assertEquals(listOf(1L, 2L), sad.map { it.candidate.id })

        val happy = MemoryRanker.rank(listOf(neg, pos), "早起", PAD(0.8f, 0f, 0f), now, 10)
        assertEquals(listOf(2L, 1L), happy.map { it.candidate.id })
    }

    // ------------------------------------------------------------ 迁移安全底线

    @Test
    fun `中性记忆不偏置`() {
        val newer = mem(1, "他讨厌早起", kw("讨厌", "早起"), createdAt = now - day)
        val older = mem(2, "他讨厌早起", kw("讨厌", "早起"), createdAt = now - 2 * day)

        // 无论她此刻是什么心情，老数据（pad 全 0）的相对顺序都不能变
        for (cur in listOf(PAD.ZERO, PAD(0.9f, 0.8f, 0.7f), PAD(-0.9f, -0.8f, -0.7f))) {
            val ranked = MemoryRanker.rank(listOf(newer, older), "早起", cur, now, 10)
            assertEquals(listOf(1L, 2L), ranked.map { it.candidate.id })
        }
    }

    @Test
    fun `中性记忆的一致性分彼此相同`() {
        val a = mem(1, "他讨厌早起", kw("讨厌", "早起"))
        val b = mem(2, "他讨厌早起", kw("讨厌", "早起"))
        val cur = PAD(0.6f, -0.4f, 0.2f)
        val ca = MemoryRanker.congruenceOf(cur, a.pad)
        val cb = MemoryRanker.congruenceOf(cur, b.pad)
        assertEquals(ca, cb, 0.0001f)
    }

    // ------------------------------------------------------------ 硬门槛

    @Test
    fun `语义不相关的记忆即便情绪完全一致也进不来`() {
        // 这是保命闸：没有它，难过时会翻出一件毫不相干的伤心事
        val unrelated = mem(1, "报销还没弄", kw("报销", "没弄"), PAD(-0.8f, 0f, 0f), 1f)
        val ranked = MemoryRanker.rank(listOf(unrelated), "早起", PAD(-0.8f, 0f, 0f), now, 10)
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `没过门槛的记忆被标记为不合格`() {
        val unrelated = mem(1, "报销还没弄", kw("报销"))
        val scored = MemoryRanker.scoreOne(unrelated, listOf("早起"), PAD.ZERO, now)
        assertTrue(!scored.eligible)
    }

    // ------------------------------------------------------------ 时间与强度

    @Test
    fun `越新的记忆保留越完整`() {
        assertEquals(1f, MemoryRanker.recencyOf(now, 0f, now), 0.001f)
        val fresh = MemoryRanker.recencyOf(now - day, 0f, now)
        val old = MemoryRanker.recencyOf(now - 10 * day, 0f, now)
        assertTrue(fresh > old)
    }

    @Test
    fun `情绪强烈的记忆老得更慢`() {
        val flat = MemoryRanker.recencyOf(now - 30 * day, 0f, now)
        val vivid = MemoryRanker.recencyOf(now - 30 * day, 1f, now)
        assertTrue(vivid > flat)
    }

    @Test
    fun `时钟回拨不会算出负年龄`() {
        assertEquals(1f, MemoryRanker.recencyOf(now + day, 0f, now), 0.001f)
    }

    @Test
    fun `同龄同语义时高强度的记忆排前`() {
        val flat = mem(1, "他讨厌早起", kw("讨厌", "早起"), intensity = 0f, createdAt = now - 30 * day)
        val vivid = mem(2, "他讨厌早起", kw("讨厌", "早起"), intensity = 1f, createdAt = now - 30 * day)
        val ranked = MemoryRanker.rank(listOf(flat, vivid), "早起", PAD.ZERO, now, 10)
        assertEquals(2L, ranked.first().candidate.id)
    }

    // ------------------------------------------------------------ 自适应权重

    @Test
    fun `平静时情绪权重最低`() {
        assertEquals(MemoryRanker.W_EMO_MIN, MemoryRanker.emoWeightOf(PAD.ZERO), 0.001f)
    }

    @Test
    fun `情绪极强时情绪权重最高`() {
        assertEquals(MemoryRanker.W_EMO_MAX, MemoryRanker.emoWeightOf(PAD(1f, 1f, 1f)), 0.001f)
    }

    @Test
    fun `情绪权重随强度单调上升`() {
        val weak = MemoryRanker.emoWeightOf(PAD(0.2f, 0f, 0f))
        val mid = MemoryRanker.emoWeightOf(PAD(0.6f, 0f, 0f))
        val strong = MemoryRanker.emoWeightOf(PAD(1f, 0f, 0f))
        assertTrue(weak < mid)
        assertTrue(mid < strong)
    }

    // ------------------------------------------------------------ 排序外壳

    @Test
    fun `最多返回 limit 条`() {
        val many = (1..20).map {
            mem(it.toLong(), "他讨厌早起", kw("讨厌", "早起"), createdAt = now - it * day)
        }
        assertEquals(5, MemoryRanker.rank(many, "早起", PAD.ZERO, now, 5).size)
    }

    @Test
    fun `空查询返回空列表`() {
        val m = mem(1, "他讨厌早起", kw("讨厌", "早起"))
        assertTrue(MemoryRanker.rank(listOf(m), "   ", PAD.ZERO, now, 10).isEmpty())
    }

    @Test
    fun `空候选返回空列表`() {
        assertTrue(MemoryRanker.rank(emptyList(), "早起", PAD.ZERO, now, 10).isEmpty())
    }

    @Test
    fun `limit 为零时不返回任何东西`() {
        val m = mem(1, "他讨厌早起", kw("讨厌", "早起"))
        assertTrue(MemoryRanker.rank(listOf(m), "早起", PAD.ZERO, now, 0).isEmpty())
    }
}
