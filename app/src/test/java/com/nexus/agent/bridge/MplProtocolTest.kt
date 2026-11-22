package com.nexus.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 协议纯逻辑单测（0.9.31）。
 *
 * 这里钉的是**协议正文 §4 / §5 / §6 的语义**，不是实现细节：
 * 超时怎么夹、版本不符回什么码、错误响应长什么样。
 * 对面（MPL）按同一份正文写，两边跑同一批断言，才算"协议实现完成"。
 */
class MplProtocolTest {

    private fun body(
        id: String = "a7f3c1c8-3b21-4f0e-9d6a-2c7e5b1f0a44",
        action: String = "read_screen",
        args: String = "{}",
        extra: String = ""
    ) = """{"v":1,"id":"$id","action":"$action","args":$args,"risk":"safe"$extra}"""

    // ---------------------------------------------------------------- 请求解析

    @Test
    fun `正常请求能解析出全部字段`() {
        val p = MplProtocol.parse(
            body(action = "click_text", args = """{"text":"发送"}""", extra = ""","confirm":true,"timeoutMs":5000""")
        )
        assertTrue(p is MplProtocol.Parsed.Ok)
        val req = (p as MplProtocol.Parsed.Ok).req
        assertEquals("click_text", req.action)
        assertEquals("发送", req.args["text"])
        assertTrue(req.confirm)
        assertEquals(5000L, req.timeoutMs)
    }

    @Test
    fun `版本不符回 UNSUPPORTED_VERSION 且能带回 id`() {
        val p = MplProtocol.parse("""{"v":2,"id":"abc","action":"read_screen","args":{}}""")
        assertTrue(p is MplProtocol.Parsed.Biz)
        val biz = p as MplProtocol.Parsed.Biz
        assertEquals(MplProtocol.Code.UNSUPPORTED_VERSION, biz.code)
        assertEquals("abc", biz.id)
    }

    @Test
    fun `缺 id 是传输层错误`() {
        val p = MplProtocol.parse("""{"v":1,"action":"read_screen"}""")
        assertTrue(p is MplProtocol.Parsed.Bad)
        assertEquals(400, (p as MplProtocol.Parsed.Bad).httpStatus)
    }

    @Test
    fun `不是 JSON 是传输层错误`() {
        assertTrue(MplProtocol.parse("not json") is MplProtocol.Parsed.Bad)
        assertTrue(MplProtocol.parse("") is MplProtocol.Parsed.Bad)
    }

    @Test
    fun `缺 action 回 UNKNOWN_ACTION`() {
        val p = MplProtocol.parse("""{"v":1,"id":"x"}""")
        assertTrue(p is MplProtocol.Parsed.Biz)
        assertEquals(MplProtocol.Code.UNKNOWN_ACTION, (p as MplProtocol.Parsed.Biz).code)
    }

    @Test
    fun `action 大小写统一成小写`() {
        val p = MplProtocol.parse(body(action = "Read_Screen"))
        assertEquals("read_screen", (p as MplProtocol.Parsed.Ok).req.action)
    }

    @Test
    fun `confirm 缺省为 true`() {
        val p = MplProtocol.parse(body())
        assertTrue((p as MplProtocol.Parsed.Ok).req.confirm)
    }

    // ---------------------------------------------------------------- 超时

    @Test
    fun `缺省超时按动作表给`() {
        assertEquals(3_000L, MplProtocol.clampTimeout("read_screen", null))
        assertEquals(20_000L, MplProtocol.clampTimeout("open_app", null))
        assertEquals(60_000L, MplProtocol.clampTimeout("run_macro", null))
    }

    @Test
    fun `客户端要太多按上限夹`() {
        assertEquals(5_000L, MplProtocol.clampTimeout("read_screen", 999_999))
        assertEquals(8_000L, MplProtocol.clampTimeout("click_text", 60_000))
        assertEquals(120_000L, MplProtocol.clampTimeout("run_macro", 10 * 60_000))
    }

    @Test
    fun `小于等于零按缺省`() {
        assertEquals(3_000L, MplProtocol.clampTimeout("read_screen", 0))
        assertEquals(3_000L, MplProtocol.clampTimeout("read_screen", -5))
    }

    @Test
    fun `未知动作的超时也有兜底`() {
        assertEquals(MplProtocol.DEFAULT_TIMEOUT_MS, MplProtocol.clampTimeout("nope", null))
        assertEquals(MplProtocol.MAX_TIMEOUT_MS, MplProtocol.clampTimeout("nope", 999_999))
    }

    // ---------------------------------------------------------------- 动作清单

    @Test
    fun `0_9_31 只上报两个只读动作`() {
        assertEquals(listOf("read_screen", "open_app"), MplProtocol.actions())
        assertTrue(MplProtocol.isSupported("read_screen"))
        assertTrue(MplProtocol.isSupported("open_app"))
        assertFalse(MplProtocol.isSupported("click_text"))
    }

    @Test
    fun `六个动作的规格与协议表格一致`() {
        val map = MplProtocol.SPECS.associateBy { it.name }
        assertEquals(6, map.size)
        assertEquals(3_000L to 5_000L, map["read_screen"]!!.defaultMs to map["read_screen"]!!.maxMs)
        assertEquals(20_000L to 30_000L, map["open_app"]!!.defaultMs to map["open_app"]!!.maxMs)
        assertEquals(5_000L to 8_000L, map["click_text"]!!.defaultMs to map["click_text"]!!.maxMs)
        assertEquals(8_000L to 12_000L, map["input_text"]!!.defaultMs to map["input_text"]!!.maxMs)
        assertEquals(60_000L to 120_000L, map["run_macro"]!!.defaultMs to map["run_macro"]!!.maxMs)
        assertEquals(3_000L to 5_000L, map["list_macros"]!!.defaultMs to map["list_macros"]!!.maxMs)
    }

    @Test
    fun `只读分类决定忙闸要不要拦`() {
        assertTrue(MplProtocol.isReadOnly("read_screen"))
        assertTrue(MplProtocol.isReadOnly("open_app"))
        assertFalse(MplProtocol.isReadOnly("click_text"))
        assertFalse(MplProtocol.isReadOnly("unknown_action"))
    }

    // ---------------------------------------------------------------- 响应

    @Test
    fun `成功响应字段齐全且 error 为 null`() {
        val o = JSONObject(
            MplProtocol.okJson("id1", 12, JSONObject().put("text", "hi"), listOf("text_truncated"))
        )
        assertEquals(1, o.getInt("v"))
        assertEquals("id1", o.getString("id"))
        assertTrue(o.getBoolean("ok"))
        assertEquals(12L, o.getLong("elapsedMs"))
        assertEquals("hi", o.getJSONObject("data").getString("text"))
        assertEquals("text_truncated", o.getJSONArray("warnings").getString(0))
        assertTrue(o.isNull("error"))
    }

    @Test
    fun `错误响应把用不到的字段写成 null 而不是省略`() {
        val o = JSONObject(
            MplProtocol.errJson("id2", 9, MplProtocol.Code.BUSY, "她正忙", retryAfterMs = 1000)
        )
        assertFalse(o.getBoolean("ok"))
        val e = o.getJSONObject("error")
        assertEquals(MplProtocol.Code.BUSY, e.getString("code"))
        assertEquals(1000L, e.getLong("retryAfterMs"))
        assertTrue("step 应该是 null", e.isNull("step"))
        assertTrue("reason 应该是 null", e.isNull("reason"))
        assertTrue(o.isNull("data"))
    }

    @Test
    fun `ping 只回 v 和 app`() {
        val o = JSONObject(MplProtocol.pingJson())
        assertEquals(1, o.getInt("v"))
        assertEquals(MplProtocol.APP_NAME, o.getString("app"))
        assertEquals(2, o.length())
    }

    @Test
    fun `错误码恰好 12 个且互不相同`() {
        val codes = listOf(
            MplProtocol.Code.UNSUPPORTED_VERSION, MplProtocol.Code.UNKNOWN_ACTION,
            MplProtocol.Code.FORBIDDEN, MplProtocol.Code.NEEDS_CONFIRM,
            MplProtocol.Code.APP_NOT_FOUND, MplProtocol.Code.TARGET_NOT_FOUND,
            MplProtocol.Code.STOPPED_AT_STEP, MplProtocol.Code.TIMEOUT,
            MplProtocol.Code.BUSY, MplProtocol.Code.PERMISSION_DENIED,
            MplProtocol.Code.UNKNOWN_RESULT, MplProtocol.Code.CANCELLED
        )
        assertEquals(12, codes.size)
        assertEquals(12, codes.toSet().size)
    }

    @Test
    fun `请求里塞 token 字段不会被当成参数`() {
        val p = MplProtocol.parse(
            """{"v":1,"id":"x","action":"read_screen","args":{},"risk":"safe","token":"deadbeef"}"""
        )
        val req = (p as MplProtocol.Parsed.Ok).req
        assertNull(req.args["token"])
        assertEquals("x", req.id)
    }
}
