package com.nexus.agent.agent

import com.nexus.agent.data.db.MistakeLog
import com.nexus.agent.data.db.MistakeNote
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错题本单元测试。
 *
 * 只测纯函数部分（指纹归一化 / 教训清洗 / 提示词拼装）——
 * 落库与反思要 Room + 网络，属于 instrumented 范畴，这里不碰。
 */
class MistakeBookTest {

    @After
    fun tearDown() {
        MistakeBook.resetForTest()
    }

    @Test
    fun `同类错误不同目标必须落到同一个指纹`() {
        val a = MistakeBook.normalizeSignature(
            MistakeLog.KIND_TOOL, "click_text", "没办成：未找到「确定」"
        )
        val b = MistakeBook.normalizeSignature(
            MistakeLog.KIND_TOOL, "click_text", "没办成：未找到「取消」"
        )
        assertEquals(a, b)
        assertEquals("tool|click_text|未找到", a)
    }

    @Test
    fun `不同工具的同类错误不能混成一桶`() {
        val click = MistakeBook.normalizeSignature(
            MistakeLog.KIND_TOOL, "click_text", "未找到控件"
        )
        val open = MistakeBook.normalizeSignature(
            MistakeLog.KIND_TOOL, "open_app", "未找到应用"
        )
        assertFalse(click == open)
    }

    @Test
    fun `不同错误类别分开计数`() {
        val notFound = MistakeBook.normalizeSignature(MistakeLog.KIND_TOOL, "click_text", "未找到控件")
        val timeout = MistakeBook.normalizeSignature(MistakeLog.KIND_TOOL, "click_text", "请求超时")
        assertEquals("tool|click_text|超时", timeout)
        assertFalse(notFound == timeout)
    }

    @Test
    fun `没有关键词时退化为原文摘要且不会崩`() {
        val sig = MistakeBook.normalizeSignature(MistakeLog.KIND_TASK, "", "  奇怪的东西  发生了 ")
        assertTrue(sig.startsWith("task|"))
        assertFalse(sig.contains("  "))
    }

    @Test
    fun `教训清洗去掉编号引号与解释`() {
        assertEquals(
            "click_text 找不到时先 scroll 再试",
            MistakeBook.sanitizeLesson("1. 教训：\"click_text 找不到时先 scroll 再试\"")
        )
        assertEquals(
            "先 scroll 再点",
            MistakeBook.sanitizeLesson("- 先 scroll 再点\n\n解释：因为列表要滚动才会加载。")
        )
    }

    @Test
    fun `教训超长要截断`() {
        val long = "啊".repeat(200)
        assertEquals(MistakeNote.LESSON_MAX_CHARS, MistakeBook.sanitizeLesson(long).length)
    }

    @Test
    fun `空输出清洗得到空串`() {
        assertEquals("", MistakeBook.sanitizeLesson(null))
        assertEquals("", MistakeBook.sanitizeLesson("\n\n   \n"))
    }

    @Test
    fun `没有教训时提示词块为空串`() {
        MistakeBook.useLessonsForTest(emptyList())
        assertEquals("", MistakeBook.promptBlock())
    }

    @Test
    fun `有教训时提示词块带标题与条目`() {
        MistakeBook.useLessonsForTest(listOf("点击前先确认弹窗已关闭", "open_app 用全名"))
        val block = MistakeBook.promptBlock()
        assertTrue(block.contains("【错题本"))
        assertTrue(block.contains("- 点击前先确认弹窗已关闭"))
        assertTrue(block.contains("- open_app 用全名"))
        // 必须提醒模型「别在回复里提」，否则它会把教训当答案念给用户
        assertTrue(block.contains("不要在回复里提"))
    }

    @Test
    fun `提示词块按上限截断`() {
        MistakeBook.useLessonsForTest(List(10) { "教训$it" })
        val block = MistakeBook.promptBlock(limit = 3)
        assertTrue(block.contains("教训0"))
        assertTrue(block.contains("教训2"))
        assertFalse(block.contains("教训3"))
    }
}
