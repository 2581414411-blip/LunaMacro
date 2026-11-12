package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 房间几何与默认布置的单测（0.9.17 批次 B）。
 *
 * 几何层没有什么「聪明」逻辑，值得测的都是**会让画面穿帮**的那些：
 *
 *  - 槽位坐标跑出 0~1（家具飞出屏幕）；
 *  - 绘制顺序不稳定（同一批数据两次渲染，家具前后关系互换，画面像在抖）；
 *  - 远近缩放反了（远处的床比近处的沙发还大）；
 *  - 默认布置里两件家具抢同一个槽位（互相盖住）。
 */
class RoomGeometryTest {

    @Test
    fun `每个槽位都有合法锚点`() {
        for (slot in RoomItem.SLOTS) {
            val a = RoomGeometry.anchorOf(slot)
            assertTrue("$slot 的 x 越界：${a.x}", a.x in 0f..1f)
            assertTrue("$slot 的 y 越界：${a.y}", a.y in 0f..1f)
            assertTrue("$slot 的深度不合法：${a.depth}", a.depth in 0..2)
        }
    }

    @Test
    fun `未知槽位不会变成 NaN 或飞出屏幕`() {
        val a = RoomGeometry.anchorOf("some_slot_the_model_made_up")
        assertTrue(a.x in 0f..1f)
        assertTrue(a.y in 0f..1f)
    }

    @Test
    fun `绘制顺序永远先远后近且稳定`() {
        val items = listOf(
            item(RoomItem.KIND_SOFA, RoomItem.SLOT_FRONT_LEFT),
            item(RoomItem.KIND_SHELF, RoomItem.SLOT_BACK_LEFT),
            item(RoomItem.KIND_DESK, RoomItem.SLOT_MID_LEFT),
            item(RoomItem.KIND_RUG, RoomItem.SLOT_FRONT_MID)
        )
        val ordered = RoomGeometry.ordered(items)
        val depths = ordered.map { RoomGeometry.anchorOf(it.slot).depth }
        assertEquals(depths.sorted(), depths)
        // 同一批数据排两次必须一模一样，否则每帧都可能互换图层
        assertEquals(ordered, RoomGeometry.ordered(items.reversed()))
    }

    @Test
    fun `越近的东西越大`() {
        val back = RoomGeometry.depthScaleOf(RoomItem.SLOT_BACK_MID)
        val mid = RoomGeometry.depthScaleOf(RoomItem.SLOT_MID_LEFT)
        val front = RoomGeometry.depthScaleOf(RoomItem.SLOT_FRONT_MID)
        assertTrue("后景应该比中景小", back < mid)
        assertTrue("中景应该比前景小", mid < front)
    }

    @Test
    fun `家具自身缩放被钳制住`() {
        assertEquals(1f, RoomGeometry.clampScale(Float.NaN), 0f)
        assertEquals(RoomItem.SCALE_MIN, RoomGeometry.clampScale(0.01f), 0f)
        assertEquals(RoomItem.SCALE_MAX, RoomGeometry.clampScale(99f), 0f)
        assertEquals(1.2f, RoomGeometry.clampScale(1.2f), 0f)
    }

    private fun item(kind: String, slot: String) = RoomItem(kind = kind, slot = slot)
}

/**
 * 默认布置（首进屋那套）的约束。
 *
 * 这套数据是「她的屋子」的初始状态，用户第一眼看到的就是它。
 * 三条硬要求：七种家具都出现一次、槽位不重复、每件都在合法枚举里。
 */
class RoomDefaultsTest {

    @Test
    fun `默认布置覆盖全部七种家具`() {
        val kinds = RoomDefaults.furniture(0L).map { it.kind }.toSet()
        assertEquals(RoomItem.KINDS.toSet(), kinds)
    }

    @Test
    fun `默认布置没有两件家具抢同一个槽位`() {
        val slots = RoomDefaults.furniture(0L).map { it.slot }
        assertEquals("槽位重复会把两件家具画在同一个位置", slots.size, slots.distinct().size)
    }

    @Test
    fun `默认布置里的每个字段都在合法枚举内`() {
        for (it in RoomDefaults.furniture(0L)) {
            assertTrue("未知家具种类 ${it.kind}", it.kind in RoomItem.KINDS)
            assertTrue("未知槽位 ${it.slot}", it.slot in RoomItem.SLOTS)
            assertTrue("缩放越界 ${it.scale}", it.scale in RoomItem.SCALE_MIN..RoomItem.SCALE_MAX)
        }
    }

    @Test
    fun `默认主题是不透明的暖木屋`() {
        val t = RoomDefaults.theme(0L)
        assertEquals(RoomTheme.PRESET_DEFAULT, t.preset)
        assertTrue(t.window)
        assertEquals(0xFF, (t.wall ushr 24) and 0xFF)
        assertEquals(0xFF, (t.floor ushr 24) and 0xFF)
        assertEquals(RoomTheme.SINGLETON_ID, t.id)
    }

    @Test
    fun `认不出的预设名落到默认主题`() {
        assertSame(RoomDefaults.presetOf("rainy_afternoon"), RoomDefaults.presetOf(RoomTheme.PRESET_DEFAULT))
        val night = RoomDefaults.presetOf(RoomTheme.PRESET_NIGHT)
        assertEquals(RoomTheme.PRESET_NIGHT, night.name)
        assertNotSame(RoomDefaults.presetOf(RoomTheme.PRESET_DEFAULT), night)
    }

    @Test
    fun `空主题用默认值而不是零`() {
        val t = RoomTheme()
        assertNotNull(t.wall)
        assertEquals(RoomTheme.DEFAULT_WALL, t.wall)
        assertEquals(RoomTheme.DEFAULT_FLOOR, t.floor)
    }
}
