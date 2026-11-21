package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴记忆衰减漏斗单测（0.9.29）。
 *
 * 这个类是纯函数、零 IO，所以每个边界都直接钉死：改 Config 里任何一个数，
 * 这里必须跟着红一次 —— 不然「压了多少、丢了什么」只能靠线上猜。
 */
class MemoryFunnelTest {

    private val now = 1_700_000_000_000L
    private fun min(m: Long) = m * 60_000L

    /** ageMinutes 越大 = 发生得越早（离现在越远） */
    private fun item(ageMinutes: Long, u: String = "他说话", a: String = "她回答") =
        MemoryFunnel.Item(u, a, now - min(ageMinutes))

    /** 同一时刻的一批，用来单独验证配额（不受时间分档干扰） */
    private fun batch(n: Int, ageMinutes: Long = 0) =
        (1..n).map { MemoryFunnel.Item("第$it 条", "答$it", now - min(ageMinutes)) }

    // ---------------------------------------------------------------- 分档

    @Test
    fun `四个时间档的边界`() {
        val cfg = MemoryFunnel.Config()
        assertEquals(MemoryFunnel.Tier.FULL, MemoryFunnel.tierOf(min(10), cfg))
        assertEquals(MemoryFunnel.Tier.COMPRESSED, MemoryFunnel.tierOf(min(10) + 1, cfg))
        assertEquals(MemoryFunnel.Tier.COMPRESSED, MemoryFunnel.tierOf(min(30), cfg))
        assertEquals(MemoryFunnel.Tier.DIGEST, MemoryFunnel.tierOf(min(30) + 1, cfg))
        assertEquals(MemoryFunnel.Tier.DIGEST, MemoryFunnel.tierOf(min(60), cfg))
        assertEquals(MemoryFunnel.Tier.INDEX, MemoryFunnel.tierOf(min(60) + 1, cfg))
        assertEquals(MemoryFunnel.Tier.INDEX, MemoryFunnel.tierOf(23 * 60 * 60_000L, cfg))
        assertEquals(MemoryFunnel.Tier.ARCHIVED, MemoryFunnel.tierOf(23 * 60 * 60_000L + 1, cfg))
    }

    @Test
    fun `十分钟内原样保留`() {
        // 5 分钟前的那条更早，排在前面
        val a = MemoryFunnel.assemble(
            listOf(item(5, "较早已说", "晴天呀"), item(1, "刚说的", "也晴")), now
        )
        assertEquals(2, a.slices.size)
        assertEquals("较早已说", a.slices[0].user)
        assertEquals(MemoryFunnel.Tier.FULL, a.slices[0].tier)
        assertEquals(0, a.compressed)
    }

    // ---------------------------------------------------------------- 21% 抽样

    @Test
    fun `十分钟到三十分钟按二一比例抽样`() {
        val items = (1..10).map { item(15, "第${it}条", "答$it") }
        val a = MemoryFunnel.assemble(items, now)
        // ceil(10 * 0.21) = 3 条进 prompt，其余 7 条只留索引
        assertEquals(3, a.slices.size)
        assertEquals(7, a.index.size)
        assertTrue(a.index.all { kotlin.math.abs(it.weight - 0.818) < 1e-6 })
    }

    @Test
    fun `抽样保留较新的那几条`() {
        val items = (1..10).map { item(15, "第${it}条", "答$it") }
        val a = MemoryFunnel.assemble(items, now)
        assertEquals("第8条", a.slices[0].user)
        assertEquals("第10条", a.slices.last().user)
    }

    @Test
    fun `单独一条也至少留一条`() {
        val a = MemoryFunnel.assemble(listOf(item(20, "只有一条", "嗯")), now)
        assertEquals(1, a.slices.size)
        assertEquals(0, a.index.size)
    }

    // ---------------------------------------------------------------- 索引 / 归档

    @Test
    fun `一小时到二十三小时只留索引且权重是零点八一八`() {
        val a = MemoryFunnel.assemble(listOf(item(120, "昨晚说的", "嗯")), now)
        assertTrue(a.slices.isEmpty())
        assertEquals(1, a.index.size)
        assertEquals(0.818, a.index[0].weight, 1e-6)
    }

    @Test
    fun `超过二十三小时交给 FlexiMem 丢弃`() {
        val a = MemoryFunnel.assemble(listOf(item(24 * 60, "很久以前", "嗯")), now)
        assertEquals(0, a.slices.size)
        assertEquals(0, a.index.size)
        assertEquals(1, a.dropped)
    }

    // ---------------------------------------------------------------- 硬配额

    @Test
    fun `每十条至少压一条`() {
        val a = MemoryFunnel.assemble(batch(12), now)
        assertTrue("12 条至少要压 2 条，实际 ${a.compressed}", a.compressed >= 2)
        // 压缩不是丢弃：12 条都还在
        assertEquals(12, a.total)
        assertEquals(12, a.slices.size)
    }

    @Test
    fun `最近三条永不压缩`() {
        val a = MemoryFunnel.assemble(batch(12), now)
        a.slices.takeLast(3).forEach {
            assertEquals("最近三条必须是原文：${it.user}", MemoryFunnel.Tier.FULL, it.tier)
        }
    }

    @Test
    fun `不满足配额时从最老的开始压`() {
        val a = MemoryFunnel.assemble(batch(12), now)
        assertEquals(MemoryFunnel.Tier.COMPRESSED, a.slices[0].tier)
        // 没有句末标点，核心句就是原文
        assertEquals("第1 条", a.slices[0].user)
    }

    // ---------------------------------------------------------------- 规则压缩

    @Test
    fun `核心句去掉括号旁白并只取首句`() {
        val raw = "（把头发别到耳后）她说好。后面还有一句不该出现。"
        assertEquals("她说好。", MemoryFunnel.core(raw))
    }

    @Test
    fun `极简摘要比核心句更短`() {
        val raw = "一".repeat(60)
        assertTrue(MemoryFunnel.digest(raw).length <= 19)
        assertTrue(MemoryFunnel.core(raw).length <= 43)
    }

    @Test
    fun `空文本不会炸`() {
        assertEquals("", MemoryFunnel.core(""))
        assertEquals("", MemoryFunnel.digest("   "))
    }

    // ---------------------------------------------------------------- 顺序 / 空集

    @Test
    fun `乱序输入按时间升序输出`() {
        // 输入顺序是「先近后远」，输出必须反过来
        val a = MemoryFunnel.assemble(
            listOf(item(1, "刚说的", "a"), item(5, "更早说的", "b")), now
        )
        assertEquals("更早说的", a.slices[0].user)
        assertEquals("刚说的", a.slices[1].user)
    }

    @Test
    fun `空历史返回空结果`() {
        val a = MemoryFunnel.assemble(emptyList(), now)
        assertEquals(0, a.total)
        assertTrue(a.isEmpty())
        assertEquals(0.0, a.compressedRatio, 1e-9)
    }

    // ---------------------------------------------------------------- 展示

    @Test
    fun `相对时间标签`() {
        assertEquals("刚刚", MemoryFunnel.relativeLabel(now - 1_000, now))
        assertEquals("5分钟前", MemoryFunnel.relativeLabel(now - min(5), now))
        assertEquals("2小时前", MemoryFunnel.relativeLabel(now - min(120), now))
        assertEquals("1天前", MemoryFunnel.relativeLabel(now - min(24 * 60), now))
    }

    @Test
    fun `prompt 块带时间标签且标出摘要`() {
        val a = MemoryFunnel.assemble(
            listOf(item(2, "刚说的", "嗯"), item(15, "半小时前说的", "哦")), now
        )
        val block = MemoryFunnel.toPromptBlock(a, now)
        assertTrue(block.contains("【2分钟前】"))
        assertTrue(block.contains("【15分钟前·摘要】"))
    }
}
