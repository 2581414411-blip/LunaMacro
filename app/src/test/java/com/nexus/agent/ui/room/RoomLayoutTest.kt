package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 槽位映射的单测（0.9.19 批次 C）。
 *
 * 这一层管的是「用户/模型说的位置」到「画布上的坐标」。值得测的只有两类：
 *
 *  1. **别把家具摆到屏幕外**。一个 `place()` 返回 x=1.3 就会让沙发只剩半个，
 *     而这类 bug 在真机上要滑到特定宽高比才出现；
 *  2. **别把话说丢了**。用户说「窗边」，落到墙上或者落到中间，都是「她没听懂」。
 *
 * 所有用例都是纯函数：不碰 Room，不碰 Compose。
 */
class RoomLayoutTest {

    private val allKinds = RoomItem.DECOR_KINDS
    private val allSlots = RoomLayout.SLOTS

    @Test
    fun `任何种类任何槽位都落在安全区内`() {
        for (kind in allKinds) {
            for (slot in allSlots) {
                val p = RoomLayout.place(kind, slot)
                assertTrue(
                    "$kind/$slot 跑出屏幕：x=${p.x} y=${p.y}",
                    RoomLayout.isWithinSafeArea(p)
                )
                assertTrue("$kind/$slot 缩放异常：${p.scale}", p.scale in 0.2f..2f)
            }
        }
    }

    @Test
    fun `认不出的槽位和种类也不会炸`() {
        for (bad in listOf("", "  ", "some_slot_the_model_made_up", "9", "left")) {
            val p = RoomLayout.place("sofa", bad)
            assertTrue("槽位「$bad」把沙发放飞了", RoomLayout.isWithinSafeArea(p))
        }
        val unknownKind = RoomLayout.place("sofa_big", "left_wall")
        assertTrue(RoomLayout.isWithinSafeArea(unknownKind))
        assertEquals(RoomLayout.Mount.FLOOR, unknownKind.mount)
    }

    @Test
    fun `语义槽位翻成几何槽位`() {
        assertEquals(RoomItem.SLOT_BACK_LEFT, RoomLayout.geometricOf(RoomLayout.SLOT_LEFT_WALL))
        assertEquals(RoomItem.SLOT_BACK_RIGHT, RoomLayout.geometricOf(RoomLayout.SLOT_RIGHT_WALL))
        assertEquals(RoomItem.SLOT_MID_CENTER, RoomLayout.geometricOf(RoomLayout.SLOT_CENTER_FLOOR))
        assertEquals(RoomItem.SLOT_WINDOW_SIDE, RoomLayout.geometricOf(RoomLayout.SLOT_BY_WINDOW))
        assertEquals(RoomItem.SLOT_FRONT_LEFT, RoomLayout.geometricOf(RoomLayout.SLOT_CORNER))
        assertEquals(RoomItem.SLOT_ON_DESK, RoomLayout.geometricOf(RoomLayout.SLOT_ON_DESK))
    }

    @Test
    fun `老数据里的几何槽位原样放行`() {
        // 0.9.17/0.9.18 写进库的全是这些名字，装修不能把它们当成非法值
        for (slot in RoomItem.SLOTS) {
            assertEquals("老槽位 $slot 被当成非法值", slot, RoomLayout.geometricOf(slot))
        }
    }

    @Test
    fun `中文方位词也认`() {
        assertEquals(RoomItem.SLOT_BACK_LEFT, RoomLayout.geometricOf("左边"))
        assertEquals(RoomItem.SLOT_WINDOW_SIDE, RoomLayout.geometricOf("靠窗"))
        assertEquals(RoomItem.SLOT_MID_CENTER, RoomLayout.geometricOf("屋子中间"))
        assertEquals(RoomItem.SLOT_ON_DESK, RoomLayout.geometricOf("桌上"))
        assertEquals(RoomItem.SLOT_FRONT_LEFT, RoomLayout.geometricOf("墙角"))
        // 长词优先：「窗边的角落」是窗边，不是角落
        assertEquals(RoomItem.SLOT_WINDOW_SIDE, RoomLayout.geometricOf("窗边的角落"))
    }

    @Test
    fun `乱编的槽位返回 null`() {
        assertNull(RoomLayout.geometricOf("under_the_floor"))
        assertNull(RoomLayout.geometricOf(""))
        assertNull(RoomLayout.geometricOf("  "))
    }

    @Test
    fun `挂画一定上墙而且最先画`() {
        for (slot in allSlots) {
            val p = RoomLayout.place(RoomItem.KIND_POSTER, slot)
            assertEquals("挂画不该落地", RoomLayout.Mount.WALL, p.mount)
            assertEquals("挂画必须最先画，否则会被地板家具盖住", -1, p.depth)
            assertTrue("挂画挂在墙的上半部分：${p.y}", p.y in 0.1f..0.6f)
        }
    }

    @Test
    fun `桌面件被缩小`() {
        val onDesk = RoomLayout.place(RoomItem.KIND_LAMP, RoomLayout.SLOT_ON_DESK)
        val onFloor = RoomLayout.place(RoomItem.KIND_LAMP, RoomLayout.SLOT_LEFT_WALL)
        assertTrue(
            "台灯放桌面必须比落地小：桌面 ${onDesk.scale} vs 落地 ${onFloor.scale}",
            onDesk.scale < onFloor.scale * 0.8f
        )
    }

    @Test
    fun `越靠前的槽位画得越靠下`() {
        val wall = RoomLayout.place(RoomItem.KIND_SHELF, RoomLayout.SLOT_LEFT_WALL)
        val corner = RoomLayout.place(RoomItem.KIND_SHELF, RoomLayout.SLOT_CORNER)
        assertTrue("靠墙的应该在画面上方（y 更小）", wall.y < corner.y)
        assertTrue("角落的离镜头更近，应该更大", corner.scale > wall.scale)
    }

    @Test
    fun `没说位置时挑一个还空着的槽位`() {
        val used = mutableListOf<String>()
        val first = RoomLayout.freeSlotGeometricOf(RoomItem.KIND_SOFA, used)
        used.add(first)
        val second = RoomLayout.freeSlotGeometricOf(RoomItem.KIND_SOFA, used)
        assertFalse("两件沙发被摆进同一个槽位：$first", first == second)
    }

    @Test
    fun `槽位全被占满时回默认位置而不是丢掉`() {
        val all = RoomLayout.SLOTS.mapNotNull { RoomLayout.geometricOf(it) } + RoomItem.SLOT_ON_WALL
        val slot = RoomLayout.freeSlotGeometricOf(RoomItem.KIND_PLANT, all)
        assertNotNull(slot)
        assertTrue("必须返回一个真实槽位", slot.isNotBlank())
    }

    @Test
    fun `语义槽位能反查回中文说法`() {
        assertEquals(RoomLayout.SLOT_BY_WINDOW, RoomLayout.semanticOf(RoomItem.SLOT_WINDOW_SIDE))
        // 老槽位没有语义名（它是纯几何的）—— 反查不到是正常的，不要硬编一个
        assertNull(RoomLayout.semanticOf(RoomItem.SLOT_BACK_MID))
    }

    @Test
    fun `每个语义槽位都有独立的落点`() {
        val xs = allSlots.map { RoomLayout.place(RoomItem.KIND_PLANT, it) }
            .map { "${it.x}/${it.y}/${it.depth}" }
        assertEquals("有两个语义槽位落在同一点上", allSlots.size, xs.distinct().size)
    }

    /**
     * 这条守的是一个真出过的问题：改成走 `place()` 之后，家具实例自己的
     * `scale` 字段被漏乘了，默认布置里那件 1.15 倍的地毯悄悄缩回 1.0。
     * 单测看不出来（画面只是小了一点），所以钉在这里。
     */
    @Test
    fun `家具自身的缩放要算进去`() {
        val normal = RoomItem(kind = RoomItem.KIND_RUG, slot = RoomItem.SLOT_FRONT_MID, scale = 1f)
        val big = normal.copy(scale = 1.15f)
        assertTrue(
            "实例上的 scale 被吞了：${RoomLayout.finalScaleOf(normal)} vs ${RoomLayout.finalScaleOf(big)}",
            RoomLayout.finalScaleOf(big) > RoomLayout.finalScaleOf(normal)
        )
        // 越界值仍然被钳制，不会把地毯放大十倍冲出屏幕
        val crazy = normal.copy(scale = 99f)
        assertEquals(
            RoomLayout.finalScaleOf(normal) * RoomItem.SCALE_MAX,
            RoomLayout.finalScaleOf(crazy),
            1e-4f
        )
    }
}
