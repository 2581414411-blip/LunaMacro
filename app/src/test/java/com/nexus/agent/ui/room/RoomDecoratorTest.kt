package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装修校验器的单测（0.9.19 批次 C）。
 *
 * 校验器是**唯一**能决定「写什么进房间」的地方，所以它必须是纯函数：
 * 输入一张 JSON 形状的 map，输出一个可执行的 [RoomDecorator.Decision]。
 * 写库在 [RoomDecorator.applyDecision] 里，不在这测。
 *
 * 这些用例的重点不是「正常情况能跑」，而是**模型跑偏时房间不会坏**：
 * 编种类、编槽位、一次吐十几件、给不存在的颜色、把墙刷成 `"rainbow"`。
 */
class RoomDecoratorTest {

    private fun validate(
        json: String?,
        current: List<RoomItem> = emptyList(),
        text: String = ""
    ) = RoomDecorator.validate(
        json?.let { com.nexus.agent.util.MiniJson.parseObject(it) },
        current,
        text
    )

    private fun item(id: Long, kind: String, slot: String) =
        RoomItem(id = id, kind = kind, slot = slot)

    // ------------------------------------------------------------ 拒绝入口

    @Test
    fun `空输入直接拒绝`() {
        assertTrue(validate(null) is RoomDecorator.Decision.Reject)
        assertTrue(validate("") is RoomDecorator.Decision.Reject)
        assertTrue(validate("我不太想改") is RoomDecorator.Decision.Reject)
        assertTrue(validate("{}") is RoomDecorator.Decision.Reject)
    }

    @Test
    fun `乱编的种类一件都不放`() {
        val d = validate("""{"action":"add","items":[{"kind":"helicopter","slot":"left_wall"}]}""")
        assertTrue("认不出的种类必须整条拒绝，不能画方盒子糊过去", d is RoomDecorator.Decision.Reject)
    }

    @Test
    fun `一件合法一件非法时丢掉非法的那件`() {
        val d = validate(
            """{"action":"add","items":[
                {"kind":"sofa","slot":"left_wall","color":"red"},
                {"kind":"helicopter","slot":"left_wall"}
            ]}"""
        )
        assertTrue(d is RoomDecorator.Decision.Add)
        d as RoomDecorator.Decision.Add
        assertEquals(1, d.drafts.size)
        assertEquals(RoomItem.KIND_SOFA, d.drafts[0].kind)
        assertEquals("非法的那件要计数，好让她能回一句「有件没认出来」", 1, d.dropped)
    }

    // ------------------------------------------------------------ 槽位与颜色

    @Test
    fun `乱编的槽位落到默认位置而不是丢掉`() {
        val d = validate("""{"action":"add","items":[{"kind":"sofa","slot":"under_the_floor"}]}""")
        assertTrue("位置说错了也该摆进屋里", d is RoomDecorator.Decision.Add)
        d as RoomDecorator.Decision.Add
        assertEquals(
            RoomLayout.defaultSlotGeometricOf(RoomItem.KIND_SOFA),
            d.drafts[0].slot
        )
    }

    @Test
    fun `中文槽位也能落位`() {
        val d = validate("""{"action":"add","items":[{"kind":"plant","slot":"窗边"}]}""")
        d as RoomDecorator.Decision.Add
        assertEquals(RoomItem.SLOT_WINDOW_SIDE, d.drafts[0].slot)
    }

    @Test
    fun `乱编的颜色退回该种类的默认色`() {
        val d = validate("""{"action":"add","items":[{"kind":"bed","slot":"right_wall","color":"rainbow"}]}""")
        d as RoomDecorator.Decision.Add
        assertEquals(RoomPalette.defaultTintOf(RoomItem.KIND_BED), d.drafts[0].tint)
    }

    @Test
    fun `认得颜色就用它`() {
        val d = validate("""{"action":"add","items":[{"kind":"sofa","slot":"left_wall","color":"red"}]}""")
        d as RoomDecorator.Decision.Add
        assertEquals(RoomPalette.argbOf("red"), d.drafts[0].tint)
    }

    // ------------------------------------------------------------ 数量

    @Test
    fun `屋里满了就不再加`() {
        val full = (1..RoomDecorator.MAX_ITEMS).map { item(it.toLong(), RoomItem.KIND_PLANT, RoomItem.SLOT_BACK_LEFT) }
        val d = validate(
            """{"action":"add","items":[{"kind":"sofa","slot":"left_wall"}]}""",
            current = full
        )
        assertTrue("12 件之后再加必须拒绝", d is RoomDecorator.Decision.Reject)
    }

    @Test
    fun `只剩一件空位时只放一件`() {
        val almost = (1 until RoomDecorator.MAX_ITEMS).map {
            item(it.toLong(), RoomItem.KIND_PLANT, RoomItem.SLOT_BACK_LEFT)
        }
        val d = validate(
            """{"action":"add","items":[
                {"kind":"sofa","slot":"left_wall"},
                {"kind":"bed","slot":"right_wall"}
            ]}""",
            current = almost
        )
        d as RoomDecorator.Decision.Add
        assertEquals(1, d.drafts.size)
        assertEquals(1, d.dropped)
    }

    @Test
    fun `一轮超过四件只认四件`() {
        val items = (1..8).joinToString(",") { """{"kind":"plant","slot":"corner"}""" }
        val d = validate("""{"action":"add","items":[$items]}""")
        d as RoomDecorator.Decision.Add
        assertEquals(RoomDecorator.MAX_PER_TURN, d.drafts.size)
    }

    @Test
    fun `新家具不会和已有的抢同一个槽位`() {
        val current = listOf(item(1, RoomItem.KIND_SHELF, RoomItem.SLOT_BACK_LEFT))
        val d = validate(
            """{"action":"add","items":[{"kind":"sofa","slot":"left_wall"}]}""",
            current = current
        )
        d as RoomDecorator.Decision.Add
        // 用户明确说了 left_wall —— 那就照办（说了算），只是不会悄悄换成别的
        assertEquals(RoomItem.SLOT_BACK_LEFT, d.drafts[0].slot)
    }

    // ------------------------------------------------------------ 移动

    @Test
    fun `说搬床就改那件而不是再买一张`() {
        val current = listOf(item(7, RoomItem.KIND_BED, RoomItem.SLOT_BACK_RIGHT))
        val d = validate(
            """{"action":"add","items":[{"kind":"bed","slot":"by_window"}]}""",
            current = current,
            text = "把床搬到窗边"
        )
        d as RoomDecorator.Decision.Add
        assertTrue("有移动动词 + 屋里已有同类 = 挪动", d.relocate)
        assertEquals(RoomItem.SLOT_WINDOW_SIDE, d.drafts[0].slot)
    }

    @Test
    fun `说加床就是再加一张`() {
        val current = listOf(item(7, RoomItem.KIND_BED, RoomItem.SLOT_BACK_RIGHT))
        val d = validate(
            """{"action":"add","items":[{"kind":"bed","slot":"by_window"}]}""",
            current = current,
            text = "再加一张床放窗边"
        )
        d as RoomDecorator.Decision.Add
        assertFalse(d.relocate)
    }

    @Test
    fun `屋里没有那种家具时移动退化成新增`() {
        val d = validate(
            """{"action":"add","items":[{"kind":"sofa","slot":"left_wall"}]}""",
            current = emptyList(),
            text = "把沙发挪到左边"
        )
        d as RoomDecorator.Decision.Add
        assertFalse("屋里没有的东西没法挪，只能新增", d.relocate)
    }

    // ------------------------------------------------------------ 主题

    @Test
    fun `换墙色换地板色`() {
        val d = validate("""{"action":"theme","wall":"dark","floor":"gray"}""")
        assertTrue(d is RoomDecorator.Decision.Theme)
        d as RoomDecorator.Decision.Theme
        assertEquals(RoomPalette.argbOf("dark"), d.wall)
        assertEquals(RoomPalette.argbOf("gray"), d.floor)
    }

    @Test
    fun `只给墙色也可以`() {
        val d = validate("""{"action":"theme","wall":"pink"}""")
        d as RoomDecorator.Decision.Theme
        assertNotNull(d.wall)
        assertEquals("没提到的地板要保持原样", null, d.floor)
    }

    @Test
    fun `一个颜色都不认识就拒绝`() {
        val d = validate("""{"action":"theme","wall":"rainbow","floor":"unicorn"}""")
        assertTrue(d is RoomDecorator.Decision.Reject)
    }

    @Test
    fun `编出来的预设名被丢掉`() {
        val d = validate("""{"action":"theme","preset":"rainy_afternoon"}""")
        assertTrue("只有预设名、且认不出 → 拒绝", d is RoomDecorator.Decision.Reject)
    }

    // ------------------------------------------------------------ 撤销

    @Test
    fun `撤走屋里有的家具`() {
        val current = listOf(
            item(3, RoomItem.KIND_LAMP, RoomItem.SLOT_BACK_MID),
            item(4, RoomItem.KIND_BED, RoomItem.SLOT_BACK_RIGHT)
        )
        val d = validate("""{"action":"remove","kind":"lamp"}""", current)
        d as RoomDecorator.Decision.Remove
        assertEquals(listOf(3L), d.ids)
    }

    @Test
    fun `撤屋里没有的东西要如实说`() {
        val current = listOf(item(3, RoomItem.KIND_LAMP, RoomItem.SLOT_BACK_MID))
        val d = validate("""{"action":"remove","kind":"bed"}""", current)
        assertTrue(d is RoomDecorator.Decision.Reject)
    }

    @Test
    fun `撤的时候种类乱编也不会误删`() {
        val current = listOf(item(3, RoomItem.KIND_LAMP, RoomItem.SLOT_BACK_MID))
        val d = validate("""{"action":"remove","kind":"everything"}""", current)
        assertTrue(d is RoomDecorator.Decision.Reject)
    }

    // ------------------------------------------------------------ 模型的不规范输出

    @Test
    fun `漏了 action 但给了 items 也照做`() {
        val d = validate("""{"items":[{"kind":"rug","slot":"center_floor"}]}""")
        assertTrue("小模型经常漏 action，救回来比整句丢掉好", d is RoomDecorator.Decision.Add)
    }

    @Test
    fun `带代码围栏和废话的 JSON 也能解析`() {
        val d = validate(
            """
            好的，这是修改后的房间：
            ```json
            {"action":"add","items":[{"kind":"lamp","slot":"on_desk","color":"yellow"}]}
            ```
            需要我再调整吗？
            """.trimIndent()
        )
        assertTrue(d is RoomDecorator.Decision.Add)
        d as RoomDecorator.Decision.Add
        assertEquals(RoomItem.SLOT_ON_DESK, d.drafts[0].slot)
    }

    @Test
    fun `单数 item 字段也认`() {
        val d = validate("""{"action":"add","item":{"kind":"desk","slot":"corner"}}""")
        assertTrue(d is RoomDecorator.Decision.Add)
    }

    @Test
    fun `未知 action 拒绝而不是猜`() {
        val d = validate("""{"action":"paint_the_sky","wall":"blue"}""")
        assertTrue(d is RoomDecorator.Decision.Reject)
    }

    // ------------------------------------------------------------ 提示词契约

    @Test
    fun `提示词里公示的每个值都在白名单里`() {
        val prompt = RoomDecorator.SYSTEM_PROMPT
        for (kind in RoomItem.DECOR_KINDS) {
            assertTrue("提示词没公示种类 $kind", prompt.contains(kind))
            assertNotNull("公示了但校验器不认：$kind", RoomDecorator.canonicalKind(kind))
        }
        for (slot in RoomLayout.SLOTS) {
            assertTrue("提示词没公示槽位 $slot", prompt.contains(slot))
            assertNotNull("公示了但映射不出坐标：$slot", RoomLayout.geometricOf(slot))
        }
        for (s in RoomPalette.SWATCHES) {
            assertTrue("提示词没公示颜色 ${s.name}", prompt.contains(s.name))
            assertNotNull("公示了但调色板不认：${s.name}", RoomPalette.argbOf(s.name))
        }
        // 任务书自己给的示例用到了 gray，公示集必须覆盖它，否则模型照抄示例就被拒
        assertTrue("示例里的 gray 必须在公示集里", prompt.contains("gray"))
    }

    @Test
    fun `提示词不许模型给坐标`() {
        assertTrue(RoomDecorator.SYSTEM_PROMPT.contains("不允许输出坐标"))
    }

    /**
     * 这条是补一个真实踩过的坑：`navy` 只写了中文写法，英文名漏了，
     * 于是模型给 `"wall":"navy"` 会被拒、而「深蓝色」能过 —— 同一个颜色两种结局。
     * 词表是数据，数据就要有个自检。
     */
    @Test
    fun `每种颜色都能被自己的英文名和中文名认出来`() {
        for (s in RoomPalette.SWATCHES + RoomPalette.EXTRA) {
            assertEquals("英文名认不出 ${s.name}", s.name, RoomPalette.nameOf(s.name))
            assertEquals("中文名认不出 ${s.zh}", s.name, RoomPalette.nameOf(s.zh))
        }
        // 「深蓝」不带「色」也必须认，否则「把墙换成深蓝」整句白说
        assertEquals("navy", RoomPalette.nameOf("深蓝"))
        assertEquals(RoomPalette.argbOf("navy"), RoomPalette.argbOf("深蓝色"))
    }

    @Test
    fun `深蓝不会被当成浅蓝`() {
        assertEquals("navy", RoomPalette.nameOf("深蓝色"))
        assertEquals("blue", RoomPalette.nameOf("蓝色"))
        assertFalse(RoomPalette.argbOf("深蓝") == RoomPalette.argbOf("蓝"))
    }
}
