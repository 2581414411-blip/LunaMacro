package com.nexus.agent.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具风险分级单测（0.9.31 · P0 修闸）。
 *
 * 背景：0.9.30 之前 `checkToolCall` 第一行就是 `if (toolName != "open_app") return false`，
 * 18 个工具里只拦 1 个。这个文件把「每个工具的第几档」钉死，
 * 以后再加工具必须在这里补一行，否则 [SafetyPolicy.riskTable] 的条数断言会红。
 */
class SafetyPolicyRiskTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    private fun risk(tool: String, args: Map<String, Any?> = emptyMap(), fg: String? = wechat) =
        SafetyPolicy.riskOf(tool, args, fg)

    // ---------------------------------------------------------------- 覆盖率

    @Test
    fun `风险表覆盖全部 18 个工具`() {
        assertEquals(18, SafetyPolicy.riskTable().size)
    }

    @Test
    fun `风险表里每个工具都有非空基线`() {
        SafetyPolicy.riskTable().forEach { (name, r) ->
            assertTrue("$name 基线不能为空", r == SafetyPolicy.Risk.SAFE || r == SafetyPolicy.Risk.CAUTION)
        }
    }

    @Test
    fun `未知工具按 CAUTION 处理`() {
        assertEquals(SafetyPolicy.Risk.CAUTION, SafetyPolicy.baseRisk("some_new_tool"))
        assertEquals(SafetyPolicy.Risk.CAUTION, risk("some_new_tool"))
    }

    // ---------------------------------------------------------------- 基线

    @Test
    fun `只读与导航工具是 SAFE`() {
        listOf("wait", "list_macros", "web_search", "press_back", "press_home", "stop_game_auto")
            .forEach { assertEquals("$it 应为 SAFE", SafetyPolicy.Risk.SAFE, SafetyPolicy.baseRisk(it)) }
    }

    @Test
    fun `会点会输的工具是 CAUTION`() {
        listOf(
            "click_text", "click_id", "double_tap", "long_press", "scroll", "drag", "multi_touch",
            "input_text", "play_macro", "start_game_auto", "schedule_task"
        ).forEach { assertEquals("$it 应为 CAUTION", SafetyPolicy.Risk.CAUTION, SafetyPolicy.baseRisk(it)) }
    }

    @Test
    fun `open_app 平时是 SAFE`() {
        assertEquals(SafetyPolicy.Risk.SAFE, risk("open_app", mapOf("appName" to "微信")))
    }

    @Test
    fun `工具名大小写与空格不影响判定`() {
        assertEquals(SafetyPolicy.Risk.CAUTION, risk(" Click_Text "))
    }

    // ---------------------------------------------------------------- 敏感界面

    @Test
    fun `支付宝里点任何东西都是 FORBIDDEN`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("click_text", mapOf("text" to "发送"), alipay)
        )
    }

    @Test
    fun `支付宝里输入文字是 FORBIDDEN`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("input_text", mapOf("text" to "你好"), alipay)
        )
    }

    @Test
    fun `支付宝里播放宏是 FORBIDDEN`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("play_macro", mapOf("macroName" to "签到"), alipay)
        )
    }

    @Test
    fun `敏感界面里返回、回桌面、等待仍然允许`() {
        listOf("press_back", "press_home", "wait", "list_macros").forEach {
            assertEquals("$it 应放行", SafetyPolicy.Risk.SAFE, risk(it, emptyMap(), alipay))
        }
    }

    @Test
    fun `敏感界面拦截的文案是那句固定的`() {
        val v = SafetyPolicy.assess("click_text", mapOf("text" to "发送"), alipay)
        assertEquals(SafetyPolicy.SENSITIVE_UI_MSG, v.reason)
        assertEquals(alipay, v.targetApp)
    }

    @Test
    fun `拿不到前台时不误判成敏感界面`() {
        assertEquals(SafetyPolicy.Risk.CAUTION, risk("click_text", mapOf("text" to "发送"), null))
    }

    @Test
    fun `包名关键词能认出钱袋子类应用`() {
        listOf(
            "com.eg.android.AlipayGphone",
            "cmb.pb",
            "com.icbc",
            "com.unionpay"
        ).forEach { assertTrue("$it 应判敏感", SafetyPolicy.isSensitivePackage(it)) }
        assertFalse(SafetyPolicy.isSensitivePackage("com.tencent.mm"))
        assertFalse(SafetyPolicy.isSensitivePackage(null))
    }

    // ---------------------------------------------------------------- 否决词

    @Test
    fun `点击确认支付被拦下`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("click_text", mapOf("text" to "确认支付"))
        )
    }

    @Test
    fun `协议列出的否决词逐个命中`() {
        listOf(
            "支付", "付款", "转账", "红包", "充值", "提现", "购买", "下单", "结算", "还款",
            "卸载", "注销", "删除账号", "退出登录", "改密码", "密码", "验证码", "免密"
        ).forEach { w ->
            assertEquals(
                "「$w」应判 FORBIDDEN",
                SafetyPolicy.Risk.FORBIDDEN,
                risk("click_text", mapOf("text" to w))
            )
        }
    }

    @Test
    fun `输入验证码被拦下`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("input_text", mapOf("text" to "123456", "target" to "请输入验证码"))
        )
    }

    @Test
    fun `普通发送不会被误拦`() {
        assertEquals(SafetyPolicy.Risk.CAUTION, risk("click_text", mapOf("text" to "发送")))
    }

    @Test
    fun `只读工具不扫否决词`() {
        // 用户问"怎么改密码"是在聊天/搜索，不是在替他改
        assertEquals(SafetyPolicy.Risk.SAFE, risk("web_search", mapOf("query" to "怎么改密码")))
    }

    // ---------------------------------------------------------------- 危险应用

    @Test
    fun `打开银行类应用被拦下`() {
        assertEquals(
            SafetyPolicy.Risk.FORBIDDEN,
            risk("open_app", mapOf("appName" to "支付宝"))
        )
    }

    @Test
    fun `打开普通应用放行`() {
        assertEquals(SafetyPolicy.Risk.SAFE, risk("open_app", mapOf("appName" to "设置")))
    }

    @Test
    fun `打开应用命中的目标会带回 targetApp`() {
        val v = SafetyPolicy.assess("open_app", mapOf("appName" to "招商银行"), wechat)
        assertEquals(SafetyPolicy.Risk.FORBIDDEN, v.risk)
        assertEquals("招商银行", v.targetApp)
    }

    // ---------------------------------------------------------------- 拦截入口

    @Test
    fun `checkToolCall 只拦 FORBIDDEN 不拦 CAUTION`() {
        assertFalse(SafetyPolicy.checkToolCall("click_text", mapOf("text" to "发送")).blocked)
        assertTrue(SafetyPolicy.checkToolCall("click_text", mapOf("text" to "确认支付")).blocked)
    }

    @Test
    fun `checkToolCall 带前台的版本能拦住敏感界面`() {
        val b = SafetyPolicy.checkToolCall(
            "click_text", mapOf("text" to "发送"), alipay,
            SafetyPolicy.isSensitivePackage(alipay)
        )
        assertTrue(b.blocked)
        assertEquals(SafetyPolicy.SENSITIVE_UI_MSG, b.reason)
    }

    @Test
    fun `checkToolCall 老签名仍然拦危险应用`() {
        assertTrue(SafetyPolicy.checkToolCall("open_app", mapOf("appName" to "支付宝")).blocked)
    }
}
