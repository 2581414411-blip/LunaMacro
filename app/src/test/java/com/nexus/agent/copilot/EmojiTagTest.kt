package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情标签替换（0.9.6）。
 *
 * 这里真正的风险不是「不换」，而是**乱换**：模型写出 `[图片]`、`[系统提示]`、
 * `[你的名字]` 时，如果无脑替换成长文本就会把句子弄坏。所以白名单之外一律原样保留。
 */
class EmojiTagTest {

    @Test
    fun `白名单标签换成 emoji`() {
        assertEquals("哈哈，被炸飞了吧😂", EmojiTag.expand("哈哈，被炸飞了吧[笑死]"))
        assertEquals("想你了🥰", EmojiTag.expand("想你了[贴贴]"))
    }

    @Test
    fun `一段里多个标签都能换`() {
        assertEquals("😭😑", EmojiTag.expand("[大哭][无语]"))
    }

    @Test
    fun `没在白名单里的原样保留`() {
        assertEquals("[图片]", EmojiTag.expand("[图片]"))
        assertEquals("看这个[1]", EmojiTag.expand("看这个[1]"))
        // 超过 4 个字的方括号本来就不该被当成标签
        assertEquals("[这是一段很长的说明]", EmojiTag.expand("[这是一段很长的说明]"))
    }

    @Test
    fun `没有方括号时原样返回`() {
        val s = "今天真冷，早点睡。"
        assertEquals(s, EmojiTag.expand(s))
    }

    @Test
    fun `提示词里列出的标签必须都能换`() {
        // 提示词只列常用的几个，但列出来的每一个都必须在白名单里 ——
        // 否则模型照着提示词写 [xxx]，用户看到的就是一串方括号
        EmojiTag.PROMPT_TAGS.forEach { tag ->
            assertTrue("提示词里的 [$tag] 换不出 emoji", EmojiTag.expand("[$tag]") != "[$tag]")
        }
        assertTrue(EmojiTag.promptHint().contains("[大哭]"))
    }

    @Test
    fun `提示词不能把所有标签都堆进去`() {
        // 全列会吃掉上下文预算：常用子集要明显小于全量
        assertTrue(
            "提示词标签数 ${EmojiTag.PROMPT_TAGS.size} 太多了",
            EmojiTag.PROMPT_TAGS.size <= 12
        )
        assertTrue(EmojiTag.PROMPT_TAGS.all { EmojiTag.MAP.containsKey(it) })
    }
}
