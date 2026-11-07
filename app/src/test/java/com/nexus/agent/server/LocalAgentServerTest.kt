package com.nexus.agent.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地 HTTP 接口的纯逻辑测试。
 *
 * 网络栈本身要靠真机验证（见交付报告），但两件最容易让客户端接不上的事必须单测钉住：
 *  1. **请求体字段名**：手表端写 `text`、PC 端写 `message`，都得能懂，
 *     否则对方会收到一个莫名其妙的 400；
 *  2. **JSON 转义**：回复里带引号或换行时不能把响应体拼错。
 */
class LocalAgentServerTest {

    @Test
    fun `标准 message 字段`() {
        assertEquals("在吗", LocalAgentServer.extractMessage("""{"message":"在吗"}"""))
    }

    @Test
    fun `兼容 text 与 content 字段`() {
        assertEquals("在吗", LocalAgentServer.extractMessage("""{"text":"在吗"}"""))
        assertEquals("在吗", LocalAgentServer.extractMessage("""{"content":"在吗"}"""))
    }

    @Test
    fun `裸文本也收`() {
        assertEquals("在吗", LocalAgentServer.extractMessage("在吗"))
    }

    @Test
    fun `空体与坏 JSON 都返回空串`() {
        assertEquals("", LocalAgentServer.extractMessage(""))
        assertEquals("", LocalAgentServer.extractMessage("   "))
        assertEquals("", LocalAgentServer.extractMessage("{不是 JSON"))
        assertEquals("", LocalAgentServer.extractMessage("""{"foo":"bar"}"""))
    }

    @Test
    fun `超长消息会被截断`() {
        val long = "啊".repeat(5000)
        val got = LocalAgentServer.extractMessage("""{"message":"$long"}""")
        assertTrue("不该原样收下 5000 字：${got.length}", got.length <= 2000)
    }

    @Test
    fun `前后空白会被清掉`() {
        assertEquals("在吗", LocalAgentServer.extractMessage("""{"message":"  在吗  "}"""))
    }

    @Test
    fun `JSON 转义不会把响应拼坏`() {
        val q = LocalAgentServer.quote("他说：\"好\"\n第二行\\反斜杠")
        assertTrue(q.startsWith("\"") && q.endsWith("\""))
        assertTrue("引号必须转义", q.contains("\\\""))
        assertTrue("换行必须转义", q.contains("\\n"))
        assertTrue("反斜杠必须转义", q.contains("\\\\"))
        // 不能有裸换行，否则响应体会被截断
        assertTrue("不能出现裸换行", !q.contains("\n"))
    }

    @Test
    fun `路径常量稳定`() {
        // 跨端 SDK 直接抄这三个字符串，改了就是破坏兼容
        assertEquals("/api/ping", LocalAgentServer.API_PING)
        assertEquals("/api/status", LocalAgentServer.API_STATUS)
        assertEquals("/api/chat", LocalAgentServer.API_CHAT)
        assertEquals(8080, LocalAgentServer.DEFAULT_PORT)
    }
}
