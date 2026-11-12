package com.nexus.agent.ui.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕适配（0.9.20 批次 D）。
 *
 * 0.9.19 之前所有横向坐标都按**整屏宽度**归一，放到折叠屏上整间屋子横向散架。
 * 这组测试钉的是「舞台」：普通手机上舞台就是屏宽（零回归），宽屏上舞台变窄、
 * 两边留给墙。
 */
class RoomStageTest {

    @Test
    fun `普通手机舞台就是整个屏幕宽度`() {
        // 1080×2400（9:20）：0.45 比基准 0.62 窄，舞台取屏宽
        val stage = RoomGeometry.stageOf(1080f, 2400f)
        assertEquals(1080f, stage.width, 0.01f)
        assertEquals(0f, stage.left, 0.01f)
        assertEquals(2400f, stage.height, 0.01f)
    }

    @Test
    fun `折叠屏展开时舞台居中 两边是墙`() {
        // 2200×2400：比基准宽，舞台宽度被压在 2400×0.62 = 1488
        val stage = RoomGeometry.stageOf(2200f, 2400f)
        assertEquals(2400f * RoomGeometry.STAGE_ASPECT, stage.width, 0.01f)
        assertEquals((2200f - stage.width) / 2f, stage.left, 0.01f)
    }

    @Test
    fun `舞台永远不会比屏幕宽`() {
        val screens = listOf(
            1080f to 2400f, 1440f to 3200f, 2200f to 2400f,
            2560f to 1600f, 720f to 1600f, 3840f to 2160f
        )
        for ((w, h) in screens) {
            val stage = RoomGeometry.stageOf(w, h)
            assertTrue("${w}x$h 舞台溢出", stage.width <= w + 0.01f)
            assertTrue("$w x $h 舞台跑到屏幕外", stage.left >= -0.01f)
            assertTrue(stage.left + stage.width <= w + 0.01f)
        }
    }

    @Test
    fun `舞台宽度定了 横向映射就定了`() {
        val stage = RoomGeometry.stageOf(2200f, 2400f)
        assertEquals(stage.left, stage.px(0f), 0.01f)
        assertEquals(stage.left + stage.width, stage.px(1f), 0.01f)
        // 正中
        assertEquals(stage.left + stage.width / 2f, stage.px(0.5f), 0.01f)
    }

    @Test
    fun `单位跟着舞台宽走 不跟屏幕`() {
        val phone = RoomGeometry.stageOf(1080f, 2400f)
        val fold = RoomGeometry.stageOf(2200f, 2400f)
        // 折叠屏更宽，但舞台只到 1488，家具不该按 2200 铺
        assertTrue(fold.unit < 2200f * 0.075f)
        assertEquals(phone.width * 0.075f, phone.unit, 0.01f)
        assertTrue(fold.unit > phone.unit)
    }

    @Test
    fun `尺寸为0时不炸`() {
        val stage = RoomGeometry.stageOf(0f, 0f)
        assertTrue(stage.width.isFinite() && stage.width > 0f)
        assertTrue(stage.unit.isFinite() && stage.unit > 0f)
    }

    @Test
    fun `NaN 尺寸不炸`() {
        val stage = RoomGeometry.stageOf(Float.NaN, Float.NaN)
        assertTrue(stage.width.isFinite() && stage.width > 0f)
        assertTrue(stage.height.isFinite() && stage.height > 0f)
    }

    // ------------------------------------------------------------ 躺平的边缘收缩

    @Test
    fun `站在中间不用缩`() {
        assertEquals(1f, RoomAvatar.edgeFitOf(0.5f, 0.2f), 0.001f)
    }

    @Test
    fun `躺在床上要收一点 但不能小到看不见`() {
        // 0.80 处、半身长 0.44 舞台宽：可用空间 0.20，只能收到 0.45 左右
        val fit = RoomAvatar.edgeFitOf(0.80f, 0.44f)
        assertTrue("收得不够，会被裁掉", fit < 0.6f)
        assertTrue("收太狠了", fit >= RoomAvatar.EDGE_FIT_MIN)
    }

    @Test
    fun `没有横向占用就不用缩`() {
        assertEquals(1f, RoomAvatar.edgeFitOf(0.05f, 0f), 0.001f)
        assertEquals(1f, RoomAvatar.edgeFitOf(0.95f, Float.NaN), 0.001f)
    }

    @Test
    fun `收缩之后一定在画面里`() {
        for (x in listOf(0.14f, 0.3f, 0.5f, 0.8f, 0.86f)) {
            val half = 0.44f
            val fit = RoomAvatar.edgeFitOf(x, half)
            val halfAfter = half * fit
            assertTrue("x=$x 左边越界", x - halfAfter >= -0.02f)
            assertTrue("x=$x 右边越界", x + halfAfter <= 1.02f)
        }
    }
}
