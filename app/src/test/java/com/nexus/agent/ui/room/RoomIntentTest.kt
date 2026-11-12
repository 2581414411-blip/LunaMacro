package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这句话是不是在装修」的单测（0.9.19 批次 C）。
 *
 * 拦截器的两难：**漏拦**的代价是「说了没反应」，**误拦**的代价是
 * 「她根本没在听你说话」。所以两类用例都必须有：
 *
 *  - 该拦的：加家具、换墙色、挪位置、撤家具；
 *  - 不该拦的：单纯提到家具、说的是手机屏幕、问句、长段落。
 */
class RoomIntentTest {

    // ------------------------------------------------------------ 该拦的

    @Test
    fun `加家具能听懂`() {
        val m = RoomIntent.parse("加个红色的沙发")
        assertNotNull(m)
        assertEquals("add", m!!["action"])
        @Suppress("UNCHECKED_CAST")
        val items = m["items"] as List<Map<String, Any?>>
        assertEquals(RoomItem.KIND_SOFA, items[0]["kind"])
        assertEquals("red", items[0]["color"])
    }

    @Test
    fun `带位置的加法`() {
        val m = RoomIntent.parse("窗边放盆花")!!
        @Suppress("UNCHECKED_CAST")
        val items = m["items"] as List<Map<String, Any?>>
        assertEquals(RoomItem.KIND_PLANT, items[0]["kind"])
        assertEquals(RoomLayout.SLOT_BY_WINDOW, items[0]["slot"])
    }

    @Test
    fun `换墙色能听懂`() {
        val m = RoomIntent.parse("把墙换成深蓝色")!!
        assertEquals("theme", m["action"])
        assertEquals("navy", m["wall"])
        assertNull("没提地板就别动地板", m["floor"])
    }

    @Test
    fun `墙和地板一起换`() {
        val m = RoomIntent.parse("墙换成绿色，地板换成灰色")!!
        assertEquals("green", m["wall"])
        assertEquals("gray", m["floor"])
    }

    @Test
    fun `色名在名词前面也认`() {
        val m = RoomIntent.parse("把蓝色的墙换个色")!!
        assertEquals("blue", m["wall"])
    }

    @Test
    fun `撤家具能听懂`() {
        val m = RoomIntent.parse("把台灯删掉")!!
        assertEquals("remove", m["action"])
        assertEquals(RoomItem.KIND_LAMP, m["kind"])
    }

    @Test
    fun `搬家具被识别成移动`() {
        val m = RoomIntent.parse("把床挪到窗边")!!
        assertEquals("add", m["action"])
        assertTrue(RoomIntent.isMove("把床挪到窗边"))
    }

    @Test
    fun `一句话说两件也认`() {
        val m = RoomIntent.parse("加个沙发和一张地毯")!!
        @Suppress("UNCHECKED_CAST")
        val items = m["items"] as List<Map<String, Any?>>
        assertEquals(2, items.size)
        assertEquals(RoomItem.KIND_SOFA, items[0]["kind"])
        assertEquals(RoomItem.KIND_RUG, items[1]["kind"])
    }

    @Test
    fun `长词优先 落地灯不会被认成灯`() {
        // 两种都是 lamp，这里验的是「命中一次」而不是「命中两次」
        assertEquals(listOf(RoomItem.KIND_LAMP), RoomIntent.findKinds("买个落地灯"))
        assertEquals(listOf(RoomItem.KIND_DESK), RoomIntent.findKinds("加张书桌"))
    }

    @Test
    fun `海报和画都算挂画`() {
        assertEquals(listOf(RoomItem.KIND_POSTER), RoomIntent.findKinds("墙上挂幅画"))
        assertEquals(listOf(RoomItem.KIND_POSTER), RoomIntent.findKinds("来张海报"))
    }

    // ------------------------------------------------------------ 不该拦的

    @Test
    fun `只提名词不算指令`() {
        assertNull(RoomIntent.parse("沙发"))
        assertNull(RoomIntent.parse("那张床好旧"))
        assertFalse(RoomIntent.shouldIntercept("沙发"))
    }

    @Test
    fun `说到手机屏幕就不截`() {
        assertFalse(
            "「把屏幕上的沙发图标删掉」是操作手机，不是装修",
            RoomIntent.shouldIntercept("把屏幕上的沙发图标删掉")
        )
        assertFalse(RoomIntent.shouldIntercept("微信里那个沙发表情在哪"))
    }

    @Test
    fun `纯闲聊不截`() {
        assertFalse(RoomIntent.shouldIntercept("今天好累啊"))
        assertFalse(RoomIntent.shouldIntercept("你在干嘛"))
    }

    @Test
    fun `太长的不截`() {
        val long = "我想跟你聊聊".repeat(30)
        assertFalse(RoomIntent.shouldIntercept(long))
    }

    @Test
    fun `没有具体动作的泛泛而谈交给聊天`() {
        // 认不出动作 → parse 为 null；也没有「房间+家具」的组合 → 不拦
        assertFalse(RoomIntent.shouldIntercept("换个颜色吧"))
    }

    @Test
    fun `房间加家具的说法留下来给模型`() {
        // 关键词量不出槽位/颜色，但「房间 + 家具」都在 → 值得问一次模型
        assertTrue(RoomIntent.shouldIntercept("房间里应该有个沙发"))
    }

    // ------------------------------------------------------------ 解析结果直接喂校验器

    @Test
    fun `本地解析的结果能直接通过校验器`() {
        val current = RoomDefaults.furniture(0L)
        for (text in listOf(
            "加个红色的沙发",
            "窗边放盆花",
            "把墙换成深蓝色",
            "把床挪到窗边",
            "把台灯删掉",
            "屋子中间铺个地毯"
        )) {
            val map = RoomIntent.parse(text)
            assertNotNull("解析失败：$text", map)
            val d = RoomDecorator.validate(map, current, text)
            assertTrue(
                "「$text」解析出来了，却没通过校验：$d",
                d !is RoomDecorator.Decision.Reject
            )
        }
    }

    @Test
    fun `解析出来的东西长得和模型 JSON 一样`() {
        // 同一个校验器能吃两种来源，靠的就是这个形状
        val local = RoomIntent.parse("加个红色沙发")!!
        val fromModel = MiniJson.parseObject(
            """{"action":"add","items":[{"kind":"sofa","color":"red"}]}"""
        )!!
        assertEquals(fromModel.keys, local.keys)
    }
}
