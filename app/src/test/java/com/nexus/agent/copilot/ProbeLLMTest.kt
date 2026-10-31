package com.nexus.agent.copilot

import com.nexus.agent.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 探针模型 / 迷你 JSON / 回复清洗的单元测试。
 *
 * 这三块都是「模型输出不可控」的第一道闸门：一旦解析错了，
 * 轻则答非所问，重则替用户乱点屏幕，所以全部按「容忍脏输出」的要求写断言。
 */
class ProbeLLMTest {

    private val tools = setOf("open_app", "click_text", "press_back", "wait")

    // ---------------------------------------------------------------- MiniJson

    @Test
    fun `miniJson 解析嵌套对象与数组`() {
        val obj = MiniJson.parseObject(
            """{"type":"action","tool":"click_text","params":{"text":"确定","index":2},"list":[1,"a",true,null]}"""
        )
        assertEquals("action", obj?.get("type"))
        val params = obj?.get("params") as Map<*, *>
        assertEquals("确定", params["text"])
        assertEquals(2L, params["index"])
        val list = obj?.get("list") as List<*>
        assertEquals(4, list.size)
        assertEquals(true, list[2])
        assertNull(list[3])
    }

    @Test
    fun `miniJson 容忍围栏与前后废话`() {
        val obj = MiniJson.parseObject("好的，结果如下：\n```json\n{\"type\":\"chat\"}\n```\n以上。")
        assertEquals("chat", obj?.get("type"))
    }

    @Test
    fun `miniJson 遇到坏 JSON 返回 null 而不是抛异常`() {
        assertNull(MiniJson.parseObject("{\"type\": }"))
        assertNull(MiniJson.parseObject("完全没有大括号"))
        assertNull(MiniJson.parseObject(""))
    }

    @Test
    fun `miniJson 字符串里的花括号不影响配对`() {
        val obj = MiniJson.parseObject("""{"reply":"他说 {不要慌} 然后跑了","type":"chat"}""")
        assertEquals("他说 {不要慌} 然后跑了", obj?.get("reply"))
    }

    // ---------------------------------------------------------------- 探针解析

    @Test
    fun `探针 chat 判定`() {
        assertEquals(ProbeLLM.Verdict.Chat, ProbeLLM.parse("""{"type":"chat"}""", tools))
    }

    @Test
    fun `探针 action 判定带参数`() {
        val v = ProbeLLM.parse(
            """{"type":"action","tool":"open_app","params":{"appName":"微信"}}""",
            tools
        )
        assertTrue(v is ProbeLLM.Verdict.Action)
        v as ProbeLLM.Verdict.Action
        assertEquals("open_app", v.tool)
        assertEquals("微信", v.params["appName"])
    }

    @Test
    fun `探针 task 判定`() {
        val v = ProbeLLM.parse("""{"type":"task","goal":"去设置里把开发者选项打开"}""", tools)
        assertEquals("去设置里把开发者选项打开", (v as ProbeLLM.Verdict.Task).goal)
    }

    @Test
    fun `探针给了不存在的工具名必须判失败`() {
        // 执行瞎编的工具没有意义，必须返回 null 让调用方降级走老路径
        assertNull(ProbeLLM.parse("""{"type":"action","tool":"fly_to_moon"}""", tools))
    }

    @Test
    fun `探针缺 type 或缺 goal 判失败`() {
        assertNull(ProbeLLM.parse("""{"tool":"open_app"}""", tools))
        assertNull(ProbeLLM.parse("""{"type":"task"}""", tools))
        assertNull(ProbeLLM.parse("抱歉我听不懂", tools))
    }

    @Test
    fun `探针不认识的 type 当作闲聊`() {
        // 宁可当聊天回一句，也不要因为模型自创类型就整轮失败
        assertEquals(ProbeLLM.Verdict.Chat, ProbeLLM.parse("""{"type":"query"}""", tools))
    }

    @Test
    fun `探针工具名大小写不敏感`() {
        val v = ProbeLLM.parse("""{"type":"action","tool":"OPEN_APP","params":{}}""", tools)
        assertEquals("open_app", (v as ProbeLLM.Verdict.Action).tool)
    }

    @Test
    fun `探针提示词必须明确禁止人格扮演`() {
        val p = ProbeLLM.buildPrompt("微信", "open_app：打开应用\nclick_text：按文字点击")
        assertTrue(p.contains("意图分类器"))
        assertTrue(p.contains("禁止任何人格扮演"))
        assertTrue(p.contains("open_app"))
        assertTrue(p.contains("微信"))
        assertTrue(p.contains("拿不准时一律输出 chat"))
    }

    // ---------------------------------------------------------------- 回复清洗

    @Test
    fun `清洗回复时剥掉代码围栏和自报家门`() {
        assertEquals("好，已经打开了", PersonaEngine.sanitizeReply("```\n宏月：好，已经打开了\n```"))
        assertEquals("在的", PersonaEngine.sanitizeReply("\"在的\""))
    }

    @Test
    fun `清洗回复时从被带偏的 JSON 里抠 reply`() {
        assertEquals(
            "办好了",
            PersonaEngine.sanitizeReply("""{"type":"chat","reply":"办好了"}""")
        )
    }

    @Test
    fun `清洗回复时去掉括号动作描写`() {
        assertEquals("行吧，你自己看着办。", PersonaEngine.sanitizeReply("（叹气）行吧，你自己看着办。"))
    }

    @Test
    fun `清洗空回复得到空串`() {
        assertEquals("", PersonaEngine.sanitizeReply(null))
        assertEquals("", PersonaEngine.sanitizeReply("   "))
    }

    @Test
    fun `角色提示词在有工具结果时要求人格化交付`() {
        val p = PersonaEngine.buildReplyPrompt("微信", "成功点击\"确定\"", hasImage = false)
        assertTrue(p.contains("你刚替用户做完的事"))
        assertTrue(p.contains("不许说「工具返回」"))
        assertTrue(p.contains("这一轮没有截图就绝对不要描述画面"))
    }

    @Test
    fun `角色提示词在纯聊天时不含工具段落`() {
        val p = PersonaEngine.buildReplyPrompt(null, null, hasImage = true)
        assertFalse(p.contains("你刚替用户做完的事"))
        assertTrue(p.contains("这一轮有屏幕截图"))
    }

    @Test
    fun `本地视觉描述会被写进角色提示词并允许接话`() {
        val p = PersonaEngine.buildReplyPrompt(
            foregroundApp = null,
            toolResult = null,
            hasImage = false,
            screenNote = "微信聊天界面，用户正和朋友聊周末去哪玩"
        )
        assertTrue(p.contains("【你这一轮看到的屏幕】"))
        assertTrue(p.contains("微信聊天界面，用户正和朋友聊周末去哪玩"))
        // 有描述时不能再说「有截图」——否则模型会以为自己也拿到了图
        assertFalse(p.contains("这一轮有屏幕截图"))
    }

    @Test
    fun `没看到画面时不许出现画面段落`() {
        val p = PersonaEngine.buildReplyPrompt(null, null, hasImage = false)
        assertFalse(p.contains("【你这一轮看到的屏幕】"))
        assertTrue(p.contains("这一轮没有截图就绝对不要描述画面"))
    }
}
