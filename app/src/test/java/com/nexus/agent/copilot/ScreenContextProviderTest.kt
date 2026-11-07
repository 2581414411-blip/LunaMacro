package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 感知层判定规则的测试（0.9.1）。
 *
 * 这一层决定「一句话回得快还是慢」：判定错了，要么每句闲聊都去唤醒 1.4GB 视觉模型，
 * 要么明明微信界面写满字却说「我看不到」。所以规则本身必须能在纯 JVM 上被打靶。
 */
class ScreenContextProviderTest {

    @Test
    fun `文字够多时判定为 Text 且不触发视觉`() {
        val texts = listOf("老张", "今晚吃饭", "明天见", "收到")
        val ctx = ScreenContextProvider.decide("微信", texts)
        assertTrue("4 个文字节点应该走 Text，实际=$ctx", ctx is ScreenContext.Text)
        ctx as ScreenContext.Text
        assertEquals(4, ctx.nodeCount)
        assertEquals("[当前应用：微信][屏幕文字：老张，今晚吃饭，明天见，收到]", ctx.toPromptNote())
    }

    @Test
    fun `刚好三个文字节点仍然要看图`() {
        // 规则是「> 3」才走文字；3 个往往只有标题/时间/按钮，信息量不足以判断用户在干嘛
        val ctx = ScreenContextProvider.decide("某游戏", listOf("开始", "设置", "排行"))
        assertTrue("3 个节点应该走 NeedVision，实际=$ctx", ctx is ScreenContext.NeedVision)
        assertEquals(3, (ctx as ScreenContext.NeedVision).nodeCount)
    }

    @Test
    fun `完全没有文字判定为 NeedVision 并说明原因`() {
        val ctx = ScreenContextProvider.decide("哔哩哔哩", emptyList())
        assertTrue(ctx is ScreenContext.NeedVision)
        val v = ctx as ScreenContext.NeedVision
        assertEquals("哔哩哔哩", v.appName)
        assertTrue("原因里要能看出是文字太少", v.reason.contains("0 处文字"))
    }

    @Test
    fun `屏幕文字总长被截到预算内，优先保留前面的`() {
        val long = (1..60).map { "第${it}条会话记录" }
        val joined = ScreenContextProvider.joinWithinBudget(long)
        assertTrue("总长不能超过预算，实际=${joined.length}", joined.length <= ScreenContextProvider.MAX_TEXT_CHARS)
        assertTrue("前面的内容要保留", joined.startsWith("第1条会话记录"))
        assertTrue("末尾的应该被丢掉", !joined.contains("第60条会话记录"))
    }

    @Test
    fun `纯时间电量符号这类噪音节点被过滤`() {
        assertNull(ScreenContextProvider.acceptLabel("12:30"))
        assertNull(ScreenContextProvider.acceptLabel("86%"))
        assertNull(ScreenContextProvider.acceptLabel("1/8"))
        assertNull(ScreenContextProvider.acceptLabel("•••"))
        assertNull(ScreenContextProvider.acceptLabel(" "))
        assertNull(ScreenContextProvider.acceptLabel("。"))
    }

    @Test
    fun `正常文字节点保留并压掉多余空白`() {
        assertEquals("今晚吃饭", ScreenContextProvider.acceptLabel("  今晚吃饭 "))
        assertEquals("老张 发了消息", ScreenContextProvider.acceptLabel("老张\n发了消息"))
        // 中文单字信息量太低（"好" "嗯"），节点数统计会失真，直接丢
        assertNull(ScreenContextProvider.acceptLabel("好"))
    }

    @Test
    fun `超长节点被截断以免撑爆提示词`() {
        val long = "很长的正文".repeat(50)
        val label = ScreenContextProvider.acceptLabel(long)
        assertTrue("要截断", label != null && label.length <= 61)
        assertTrue("截断要有省略号", label!!.endsWith("…"))
    }

    @Test
    fun `模式名固定为 Text Vision None 三个词`() {
        assertEquals("Text", ScreenContextProvider.modeOf(ScreenContext.Text("微信", "abc", 5)))
        assertEquals("Vision", ScreenContextProvider.modeOf(ScreenContext.NeedVision("游戏", "没字", 0)))
        assertEquals("None", ScreenContextProvider.modeOf(ScreenContext.Unavailable(null, "没开无障碍")))
    }

    @Test
    fun `用户没问屏幕时不该插入没看到屏幕的说明`() {
        assertTrue(PersonaEngine.asksAboutScreen("你现在能看到我屏幕里是什么吗"))
        assertTrue(PersonaEngine.asksAboutScreen("看下我这个页面"))
        assertTrue(PersonaEngine.asksAboutScreen("我在干嘛"))
        assertEquals(false, PersonaEngine.asksAboutScreen("在吗"))
        assertEquals(false, PersonaEngine.asksAboutScreen("帮我记一下明天买牛奶"))
    }
}
