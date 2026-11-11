package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 羁绊卡片展示逻辑单测（0.9.2 批次四）。
 *
 * 这些是用户直接盯着看的字，等级跳档错一格、心情配错表情都很明显，所以逐档钉死。
 */
class BondStateTest {

    @Test
    fun `五个等级的边界`() {
        assertEquals(1, BondState.levelOf(0).level)
        assertEquals(1, BondState.levelOf(29).level)
        assertEquals(2, BondState.levelOf(30).level)
        assertEquals(2, BondState.levelOf(49).level)
        assertEquals(3, BondState.levelOf(50).level)
        assertEquals(3, BondState.levelOf(69).level)
        assertEquals(4, BondState.levelOf(70).level)
        assertEquals(4, BondState.levelOf(89).level)
        assertEquals(5, BondState.levelOf(90).level)
        assertEquals(5, BondState.levelOf(100).level)
    }

    @Test
    fun `越界值也能落在合理档位`() {
        assertEquals("低于 0 当 0", 1, BondState.levelOf(-20).level)
        assertEquals("高于 100 当 100", 5, BondState.levelOf(999).level)
    }

    @Test
    fun `等级文案`() {
        assertEquals("Lv.1 陌生", BondState.labelOf(0))
        assertEquals("Lv.3 朋友", BondState.labelOf(60))
        assertEquals("Lv.5 羁绊", BondState.labelOf(100))
    }

    @Test
    fun `差值提示`() {
        assertEquals(18, BondState.levelOf(12).toNext(12))
        assertEquals(1, BondState.levelOf(49).toNext(49))
        assertNull("满级没有下一级", BondState.levelOf(100).toNext(100))
    }

    @Test
    fun `进度条比例`() {
        assertEquals(0f, BondState.progressOf(0), 0.001f)
        assertEquals(0.5f, BondState.progressOf(50), 0.001f)
        assertEquals(1f, BondState.progressOf(100), 0.001f)
        assertEquals("越界要夹住", 1f, BondState.progressOf(180), 0.001f)
        assertEquals("负数不能画到左边", 0f, BondState.progressOf(-30), 0.001f)
    }

    @Test
    fun `心情对应表情`() {
        assertEquals("😊", BondState.emojiOf("开心"))
        assertEquals("😔", BondState.emojiOf("难过"))
        assertEquals("😤", BondState.emojiOf("有点生气"))
        assertEquals("😴", BondState.emojiOf("有点困"))
        assertEquals("😌", BondState.emojiOf("平静"))
        assertEquals("😳", BondState.emojiOf("害羞"))
    }

    @Test
    fun `自由文本的心情按包含关系匹配`() {
        // 用户可以在人格设置里把心情手输成任何话
        assertEquals("😴", BondState.emojiOf("熬夜有点累了"))
        assertEquals("😊", BondState.emojiOf("被夸了挺高兴的"))
        assertEquals("😤", BondState.emojiOf("被你气到了"))
    }

    @Test
    fun `认不出的心情给中性表情而不是乱猜`() {
        assertEquals("🙂", BondState.emojiOf("不知道什么状态"))
        assertEquals("😌", BondState.emojiOf(""))
    }

    /**
     * 0.9.15 接上 PAD 之后，心情会开始出现「雀跃」「依恋」「轻蔑」这些八分区的词。
     * 这一条是护栏：漏掉任何一个，羁绊面板的表情就会退化成 🙂 —— 表情卡全一样，
     * 比不显示还像是坏的。
     */
    @Test
    fun `八分区中文都能认出表情`() {
        assertEquals("😄", BondState.emojiOf("雀跃"))
        assertEquals("🥰", BondState.emojiOf("依恋"))
        assertEquals("😌", BondState.emojiOf("松弛"))
        assertEquals("😊", BondState.emojiOf("温顺"))
        assertEquals("😏", BondState.emojiOf("轻蔑"))
        assertEquals("😰", BondState.emojiOf("焦躁"))
        assertEquals("😤", BondState.emojiOf("敌意"))
        assertEquals("😔", BondState.emojiOf("消沉"))
    }

    @Test
    fun `带强度前缀的八分区也认得`() {
        // 提示词里是「淡淡的焦躁」「很雀跃」，不是光秃秃的两个字
        assertEquals("😰", BondState.emojiOf("淡淡的焦躁"))
        assertEquals("😄", BondState.emojiOf("很雀跃"))
        assertEquals("😌", BondState.emojiOf("淡淡的松弛"))
    }

    @Test
    fun `八分区直出表情不会退化成中性`() {
        // 以后 UI 拿到 Octant 就直接用它，别再拼中文串喂 emojiOf
        Octant.values().forEach { o ->
            assertTrue("${o.zh} 没有表情", BondState.emojiFor(o) != "🙂")
        }
        assertEquals("😰", BondState.emojiFor(Octant.ANXIOUS))
    }

    @Test
    fun `每档都有一句人话`() {
        listOf(10, 40, 60, 80, 95).forEach { a ->
            val hint = BondState.hintOf(a)
            assertTrue("$a 分档的文案不能为空", hint.isNotBlank())
            assertTrue("$a 分档的文案不该出现数字", !hint.contains("/100"))
        }
    }

    @Test
    fun `好感度越高文案越亲近`() {
        assertEquals("还不太熟，说话客气得像刚加上的同事", BondState.hintOf(5))
        assertEquals("你在她心里的位置很稳，她会主动找你", BondState.hintOf(95))
    }
}
