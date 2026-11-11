package com.nexus.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作编码单测（0.9.13）。
 *
 * 这一层是记忆的「落库格式」。格式错一位，回放就会拿着半条参数去点屏幕，
 * 而且错得很安静 —— 所以转义、坏行、参数化三件事都要单独钉。
 */
class ActionStepTest {

    @Test
    fun `编码解码往返一致`() {
        val steps = listOf(
            ActionStep("click_text", mapOf("text" to "搜索")),
            ActionStep("click_text", mapOf("text" to "订阅号", "index" to "0")),
            ActionStep("open_app", mapOf("appName" to "微信"))
        )
        val text = ActionStep.encodeAll(steps)
        assertEquals(steps, ActionStep.decodeAll(text))
    }

    @Test
    fun `特殊字符不会破坏格式`() {
        val tricky = listOf(
            ActionStep("click_text", mapOf("text" to "确定\t取消")),
            ActionStep("click_text", mapOf("text" to "第一行\n第二行")),
            ActionStep("click_text", mapOf("text" to "反斜杠\\测试"))
        )
        assertEquals(tricky, ActionStep.decodeAll(ActionStep.encodeAll(tricky)))
    }

    @Test
    fun `坏行与坏参数被跳过而不是整条报废`() {
        val text = "click_text\ttext=搜索\n\n\t\nclick_text\t坏参数没有等号\ttext=订阅号"
        val steps = ActionStep.decodeAll(text)
        assertEquals(2, steps.size)
        assertEquals("搜索", steps[0].args["text"])
        assertEquals("订阅号", steps[1].args["text"])
        // 没有等号的那个参数被丢掉，其余照常保留
        assertEquals(1, steps[1].args.size)
    }

    @Test
    fun `空串解码为空列表`() {
        assertTrue(ActionStep.decodeAll("").isEmpty())
        assertTrue(ActionStep.decodeAll(null).isEmpty())
    }

    @Test
    fun `参数化只归一数字`() {
        val a = ActionStep("click_text", mapOf("text" to "3 条未读"))
        val b = ActionStep("click_text", mapOf("text" to "12 条未读"))
        assertTrue(ActionStep.same(a, b))

        // 文字不同一定不算同一步：点了别人就是点了别人
        val c = ActionStep("click_text", mapOf("text" to "张三"))
        val d = ActionStep("click_text", mapOf("text" to "李四"))
        assertFalse(ActionStep.same(c, d))
    }

    @Test
    fun `工具不同不算同一步`() {
        val a = ActionStep("click_text", mapOf("text" to "确定"))
        val b = ActionStep("click_id", mapOf("text" to "确定"))
        assertFalse(ActionStep.same(a, b))
    }

    @Test
    fun `参数顺序不影响签名`() {
        val a = ActionStep("click_text", linkedMapOf("text" to "搜索", "index" to "0"))
        val b = ActionStep("click_text", linkedMapOf("index" to "0", "text" to "搜索"))
        assertEquals(a.paramKey(), b.paramKey())
    }

    @Test
    fun `从工具参数构造时丢掉空值`() {
        val step = ActionStep.of(
            "click_text",
            mapOf("text" to "搜索", "index" to null, "empty" to "")
        )
        assertEquals(setOf("text"), step.args.keys)
        assertEquals("click_text", step.tool)
    }

    @Test
    fun `超长参数被截断`() {
        val long = "很".repeat(120)
        val step = ActionStep.of("input_text", mapOf("text" to long))
        assertTrue((step.args["text"]?.length ?: 0) <= 40)
    }
}
