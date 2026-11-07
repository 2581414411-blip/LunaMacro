package com.nexus.agent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * API 诊断的纯逻辑测试（0.9.1）。
 *
 * 这些函数决定「弹窗上写哪句话」，写错一句用户就会去改错地方：
 * 把「余额不足」说成「Key 无效」，他会反复重填 Key 而永远修不好。
 */
class ApiDiagnosticsTest {

    @Test
    fun `余额接口挂在域名根上而不是 v1 下面`() {
        assertEquals("https://api.deepseek.com", ApiDiagnostics.originOf("https://api.deepseek.com/v1"))
        assertEquals("https://api.deepseek.com", ApiDiagnostics.originOf("https://api.deepseek.com/v1/"))
        assertEquals(
            "https://api.deepseek.com/user/balance",
            ApiDiagnostics.balanceUrl("https://api.deepseek.com/v1")
        )
        // 带端口的自建服务要保住端口
        assertEquals("http://192.168.31.46:11434", ApiDiagnostics.originOf("http://192.168.31.46:11434/v1"))
    }

    @Test
    fun `models 地址只去掉末尾斜杠`() {
        assertEquals("https://api.openai.com/v1/models", ApiDiagnostics.modelsUrl("https://api.openai.com/v1"))
        assertEquals("https://api.openai.com/v1/models", ApiDiagnostics.modelsUrl("https://api.openai.com/v1/"))
    }

    @Test
    fun `状态码分类覆盖三类真实故障`() {
        assertEquals(ApiDiagnostics.Kind.OK, ApiDiagnostics.classify(200))
        assertEquals(ApiDiagnostics.Kind.AUTH, ApiDiagnostics.classify(401))
        assertEquals(ApiDiagnostics.Kind.AUTH, ApiDiagnostics.classify(403))
        assertEquals(ApiDiagnostics.Kind.QUOTA, ApiDiagnostics.classify(402))
        assertEquals(ApiDiagnostics.Kind.BAD_URL, ApiDiagnostics.classify(404))
        assertEquals(ApiDiagnostics.Kind.SERVER, ApiDiagnostics.classify(503))
        assertEquals(ApiDiagnostics.Kind.NETWORK, ApiDiagnostics.classify(0))
    }

    @Test
    fun `429 要看响应体区分限流和欠费`() {
        // 中转商常把「余额不足」也写成 429，光看状态码会误导用户去等
        assertEquals(ApiDiagnostics.Kind.RATE_LIMIT, ApiDiagnostics.classify(429, "Too Many Requests"))
        assertEquals(
            ApiDiagnostics.Kind.QUOTA,
            ApiDiagnostics.classify(429, """{"error":{"message":"insufficient quota"}}""")
        )
        assertEquals(
            ApiDiagnostics.Kind.QUOTA,
            ApiDiagnostics.classify(429, """{"error":{"message":"当前账户余额不足"}}""")
        )
    }

    @Test
    fun `400 里带 insufficient balance 也归为欠费`() {
        assertEquals(
            ApiDiagnostics.Kind.QUOTA,
            ApiDiagnostics.classify(400, """{"error":{"message":"Insufficient Balance"}}""")
        )
        assertEquals(ApiDiagnostics.Kind.UNKNOWN, ApiDiagnostics.classify(400, "bad param"))
    }

    @Test
    fun `每条结论都要能指导下一步操作`() {
        val auth = ApiDiagnostics.titleOf(ApiDiagnostics.Kind.AUTH, 401)
        assertTrue("要提到 Key", auth.contains("Key"))
        assertTrue("要带状态码便于排查", auth.contains("401"))

        val quota = ApiDiagnostics.titleOf(ApiDiagnostics.Kind.QUOTA, 402)
        assertTrue("要提到充值或换 Key", quota.contains("充值") || quota.contains("Key"))

        val badUrl = ApiDiagnostics.titleOf(ApiDiagnostics.Kind.BAD_URL, 404)
        assertTrue("要提醒 /v1", badUrl.contains("/v1"))
    }

    @Test
    fun `DeepSeek 余额按币种格式化成人民币`() {
        val json = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"12.50","granted_balance":"2.50","topped_up_balance":"10.00"}
            ]}
        """.trimIndent()
        val (balance, detail) = ApiDiagnostics.formatBalance(json)
        assertEquals("余额：¥12.50", balance)
        assertEquals("", detail)
    }

    @Test
    fun `多币种余额全部列出`() {
        val json = """{"is_available":true,"balance_infos":[
            {"currency":"CNY","total_balance":"8.00"},
            {"currency":"USD","total_balance":"1.25"}]}"""
        val (balance, _) = ApiDiagnostics.formatBalance(json)
        assertEquals("余额：¥8.00 / $1.25", balance)
    }

    @Test
    fun `账户不可用要额外说明`() {
        val json = """{"is_available":false,"balance_infos":[{"currency":"CNY","total_balance":"0.00"}]}"""
        val (balance, detail) = ApiDiagnostics.formatBalance(json)
        assertEquals("余额：¥0.00", balance)
        assertTrue(detail.contains("不可用"))
    }

    @Test
    fun `余额响应解析不出来时不编造数字`() {
        val (balance, detail) = ApiDiagnostics.formatBalance("""{"foo":1}""")
        assertNull("宁可显示查不到，也不能瞎写余额", balance)
        assertTrue(detail.isNotBlank())
        // 完全不是 JSON 也不许崩
        val (b2, _) = ApiDiagnostics.formatBalance("<html>502</html>")
        assertNull(b2)
    }

    @Test
    fun `货币符号默认人民币`() {
        assertEquals("¥", ApiDiagnostics.symbolOf("CNY"))
        assertEquals("¥", ApiDiagnostics.symbolOf(""))
        assertEquals("$", ApiDiagnostics.symbolOf("USD"))
        assertEquals("HK$", ApiDiagnostics.symbolOf("hkd"))
    }

    @Test
    fun `响应原文摘要不会把整页 HTML 糊到弹窗上`() {
        val long = "<html>" + "x".repeat(900) + "</html>"
        val s = ApiDiagnostics.snippet(long)
        assertTrue("要截断", s.length <= 401)
        assertEquals("空响应不留空白", "", ApiDiagnostics.snippet("   "))
    }

    @Test
    fun `models 计数只在能解析时返回数字`() {
        assertEquals(2, ApiDiagnostics.countModels("""{"data":[{"id":"a"},{"id":"b"}]}"""))
        assertEquals(0, ApiDiagnostics.countModels("not json"))
    }
}
