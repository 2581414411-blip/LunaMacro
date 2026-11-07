package com.nexus.agent.copilot

import com.nexus.agent.agent.ApiDiagnostics
import com.nexus.agent.agent.LlmClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 云端失败提示的测试（0.9.1）。
 *
 * 这组文案是用户唯一的自救线索：说错一句，他就会跑去改根本没坏的东西
 * （把「余额不足」认成「Key 无效」，于是反复重填 Key 而永远修不好）。
 */
class ApiErrorStateTest {

    @After
    fun tearDown() {
        ApiErrorState.clear()
    }

    @Test
    fun `401 要说 Key 无效而不是余额不足`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("deepseek", LlmClient.ApiException(401, """{"error":"invalid key"}"""))
        val f = ApiErrorState.takeRecent()!!
        assertEquals(ApiDiagnostics.Kind.AUTH, f.kind)
        assertEquals(401, f.code)
        val notice = ApiErrorState.notice(f)
        assertTrue("要提 Key", notice.contains("Key 无效"))
        assertTrue("要指路", notice.contains("设置"))
    }

    @Test
    fun `402 走额度耗尽的文案`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("deepseek", LlmClient.ApiException(402, "Insufficient Balance"))
        val notice = ApiErrorState.notice(ApiErrorState.takeRecent()!!)
        assertTrue("用户要求过这句话", notice.contains("额度耗尽"))
    }

    @Test
    fun `429 带 quota 的响应体被认成欠费`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("qwen", LlmClient.ApiException(429, """{"error":"insufficient_quota"}"""))
        assertEquals(ApiDiagnostics.Kind.QUOTA, ApiErrorState.takeRecent()!!.kind)
    }

    @Test
    fun `超时与 DNS 失败各自有话说`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("deepseek", SocketTimeoutException("20000ms"))
        assertTrue(ApiErrorState.notice(ApiErrorState.takeRecent()!!).contains("超时"))

        ApiErrorState.beginTurn()
        ApiErrorState.record("deepseek", UnknownHostException("api.deepseek.com"))
        assertTrue(ApiErrorState.notice(ApiErrorState.takeRecent()!!).contains("连不上云端"))
    }

    @Test
    fun `第一条失败不被后来的覆盖`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("p", LlmClient.ApiException(401, "a"))
        ApiErrorState.record("p", SocketTimeoutException("b"))
        assertEquals(401, ApiErrorState.takeRecent()!!.code)
    }

    @Test
    fun `新一轮开始会清掉上一轮的旧账`() {
        ApiErrorState.record("p", LlmClient.ApiException(401, "a"))
        ApiErrorState.beginTurn()
        assertNull(ApiErrorState.takeRecent())
        assertEquals("兜底话", ApiErrorState.lastNoticeOr("兜底话"))
    }

    @Test
    fun `过期记录不再生效`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("p", LlmClient.ApiException(402, "x"))
        // maxAgeMs = 0 → 任何记录都算过期，避免聊天界面重试时拿到很久以前的旧账
        assertNull(ApiErrorState.takeRecent(0))
    }

    @Test
    fun `已拿到结论时 lastNoticeOr 就用结论`() {
        ApiErrorState.beginTurn()
        ApiErrorState.record("p", LlmClient.ApiException(402, "x"))
        val text = ApiErrorState.lastNoticeOr("我这边模型没接上")
        assertTrue("不能还是那句含糊话", !text.contains("模型没接上"))
        assertTrue("要带系统提示前缀", text.startsWith("（系统提示："))
    }
}
