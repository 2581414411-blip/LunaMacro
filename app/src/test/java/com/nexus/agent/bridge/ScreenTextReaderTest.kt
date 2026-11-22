package com.nexus.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 读屏文本组装的单测（0.9.31 · 协议 §5.1）。
 *
 * `read()` 那一层要 `AccessibilityNodeInfo`，只能在真机上验；
 * 但"去重 / 限长 / 换行"这些真正会出错的规则全在 [ScreenTextReader.assemble] 里，
 * 抽出来就是为了在这里钉死。
 */
class ScreenTextReaderTest {

    @Test
    fun `空列表返回空结果`() {
        val r = ScreenTextReader.assemble(emptyList())
        assertEquals("", r.text)
        assertEquals(0, r.chars)
        assertFalse(r.truncated)
        assertFalse(r.redacted)
    }

    @Test
    fun `重复的文字只留一次`() {
        val r = ScreenTextReader.assemble(listOf("发送", "发送", "取消", "发送"))
        assertEquals("发送\n取消", r.text)
    }

    @Test
    fun `空白与纯换行被丢掉`() {
        val r = ScreenTextReader.assemble(listOf("  ", "\n", "\t", "确定"))
        assertEquals("确定", r.text)
    }

    @Test
    fun `节点内换行压成空格`() {
        val r = ScreenTextReader.assemble(listOf("第一行\n第二行"))
        assertEquals("第一行 第二行", r.text)
    }

    @Test
    fun `超长按 maxChars 截断并标记`() {
        val r = ScreenTextReader.assemble(listOf("一二三四五", "六七八九十"), maxChars = 8)
        assertEquals("一二三四五", r.text)
        assertTrue(r.truncated)
    }

    @Test
    fun `刚好放得下不算截断`() {
        val r = ScreenTextReader.assemble(listOf("一二三四五"), maxChars = 5)
        assertFalse(r.truncated)
        assertEquals(5, r.chars)
    }

    @Test
    fun `单个节点过长会被砍到 120 字`() {
        val r = ScreenTextReader.assemble(listOf("啊".repeat(500)), maxChars = 2000)
        assertEquals(120, r.text.length)
    }

    @Test
    fun `chars 与 text 长度一致`() {
        val r = ScreenTextReader.assemble(listOf("微信", "发送", "取消"))
        assertEquals(r.text.length, r.chars)
    }

    @Test
    fun `敏感界面脱敏是固定文案且不报错`() {
        val r = ScreenTextReader.Result.redacted()
        assertTrue(r.redacted)
        assertEquals(0, r.chars)
        assertTrue(r.text.contains("敏感界面"))
    }

    @Test
    fun `maxChars 小于等于零时回退默认值`() {
        val long = (1..200).map { "第${it}条" }
        val r = ScreenTextReader.assemble(long, maxChars = 0)
        assertTrue("应该按默认 2000 字处理", r.text.length > 0)
    }
}
