package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 副驾驶防复读（0.9.4 批次四）。
 *
 * 这些断言全是「用户能直接感知到的行为」：该闭嘴时闭嘴、换词复读要拦住、
 * 真正的新吐槽要放出去。阈值改错会立刻让三条里的一条红，不用等真机。
 */
class DanmakuGuardTest {

    // ------------------------------------------------------------ 沉默指令

    @Test
    fun `标准沉默指令被识别`() {
        assertTrue(DanmakuGuard.isSilent("<SILENT>"))
        assertTrue(DanmakuGuard.isSilent("  <silent>  "))
        assertTrue(DanmakuGuard.isSilent("[SILENT]"))
        assertTrue(DanmakuGuard.isSilent("（SILENT）"))
    }

    @Test
    fun `带解释的沉默指令也算沉默`() {
        // 模型常写「<SILENT>，因为画面没有变化」
        assertTrue(DanmakuGuard.isSilent("<SILENT>，因为画面没有变化"))
        assertTrue(DanmakuGuard.isSilent("SILENT"))
    }

    @Test
    fun `正常弹幕不会被当成沉默`() {
        assertFalse(DanmakuGuard.isSilent("哈哈，被苦力怕炸飞了吧"))
        assertFalse(DanmakuGuard.isSilent("666，这波走位无敌"))
        // 空内容按「没话说」处理
        assertTrue(DanmakuGuard.isSilent(""))
        assertTrue(DanmakuGuard.isSilent(null))
    }

    // ------------------------------------------------------------ 套话

    @Test
    fun `客观描述套话全部命中`() {
        assertTrue(DanmakuGuard.isBoilerplate("我看到这是一个游戏界面"))
        assertTrue(DanmakuGuard.isBoilerplate("当前应用是微信"))
        assertTrue(DanmakuGuard.isBoilerplate("屏幕上有一个按钮"))
        assertTrue(DanmakuGuard.isBoilerplate("画面中是一个桌面"))
        assertTrue(DanmakuGuard.isBoilerplate("这张截图显示的是聊天列表"))
        assertTrue(DanmakuGuard.isBoilerplate("可以看到右下角有个加号"))
    }

    @Test
    fun `正常吐槽不误杀`() {
        // 「这波」「这」之类常用字不能进关键词，否则真实弹幕会被误杀
        assertFalse(DanmakuGuard.isBoilerplate("这波走位太秀了"))
        assertFalse(DanmakuGuard.isBoilerplate("又白给了"))
        assertFalse(DanmakuGuard.isBoilerplate("快跑啊"))
    }

    // ------------------------------------------------------------ 相似度

    @Test
    fun `完全相同是1`() {
        assertEquals(1.0, DanmakuGuard.similarity("666", "666"), 1e-9)
    }

    @Test
    fun `只差标点算同一句`() {
        val a = "哈哈，被苦力怕炸飞了吧"
        val b = "哈哈 被苦力怕炸飞了吧！"
        assertEquals(1.0, DanmakuGuard.similarity(a, b), 1e-9)
    }

    @Test
    fun `换一两个字判为重复`() {
        val a = "哈哈被苦力怕炸飞了吧"
        val b = "哈哈被苦力怕炸飞了"
        assertTrue(DanmakuGuard.similarity(a, b) >= DanmakuGuard.SIMILARITY_LIMIT)
    }

    @Test
    fun `子串判为重复`() {
        val a = "被苦力怕炸飞了"
        val b = "哈哈，被苦力怕炸飞了吧"
        assertEquals(1.0, DanmakuGuard.similarity(a, b), 1e-9)
    }

    @Test
    fun `两句话意思不同不会判重`() {
        val a = "666，这波走位无敌"
        val b = "血不多了赶紧跑"
        assertTrue(DanmakuGuard.similarity(a, b) < DanmakuGuard.SIMILARITY_LIMIT)
    }

    @Test
    fun `空串不会算出1以外的怪值`() {
        assertEquals(0.0, DanmakuGuard.similarity("", "666"), 1e-9)
        assertEquals(1.0, DanmakuGuard.similarity("", ""), 1e-9)
    }

    // ------------------------------------------------------------ 队列

    @Test
    fun `最近说过的会被拦住`() {
        val g = DanmakuGuard()
        assertNull(g.rejectReason("666，这波走位无敌"))
        g.record("666，这波走位无敌")
        assertNotNull(g.rejectReason("666 这波走位无敌！"))
        assertEquals("和最近说过的「666，这波走位无敌」太像", g.rejectReason("666，这波走位无敌"))
    }

    @Test
    fun `新的一句话照常放行`() {
        val g = DanmakuGuard()
        g.record("666，这波走位无敌")
        assertNull(g.rejectReason("别站原地，背后有人"))
    }

    @Test
    fun `只记最近五条`() {
        val g = DanmakuGuard()
        // 必须是真正不同的话：像「第0句…」「第2句…」这种只差一个字符的，
        // 相似度天然 0.9，会被判复读 —— 那测的就不是队列长度而是去重了
        val lines = listOf(
            "血不多了赶紧跑",
            "这波操作很秀啊",
            "被偷袭了吧兄弟",
            "前面有个宝箱别漏",
            "别贪那个人头",
            "回城补个血先",
            "打野来了小心点"
        )
        lines.forEach { g.record(it) }
        assertEquals(5, g.size())
        // 最早两句已经被挤出队列，可以说
        assertNull(g.rejectReason("血不多了赶紧跑"))
        assertNull(g.rejectReason("这波操作很秀啊"))
        // 还在队列里的照样拦
        assertNotNull(g.rejectReason("打野来了小心点"))
    }

    @Test
    fun `沉默与套话在入队前就被拦掉`() {
        val g = DanmakuGuard()
        assertEquals("模型选择沉默", g.rejectReason("<SILENT>"))
        assertEquals("套话（客观描述屏幕）", g.rejectReason("这是一个手机桌面"))
        assertEquals("空内容", g.rejectReason("   "))
        assertTrue("被拦下的不该进历史", g.recent().isEmpty())
    }

    @Test
    fun `换应用后历史清空`() {
        val g = DanmakuGuard()
        g.record("666，这波走位无敌")
        g.reset()
        assertEquals(0, g.size())
        assertNull("换个游戏说同一句话不算复读", g.rejectReason("666，这波走位无敌"))
    }
}
