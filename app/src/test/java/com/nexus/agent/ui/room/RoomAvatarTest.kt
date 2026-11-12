package com.nexus.agent.ui.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.9.18 立绘接入的单测。
 *
 * 覆盖三类东西：**姿态往哪个朝向翻**（这是设计决策，不是渲染细节）、
 * **躺平的几何**（转多少度、缩多少、补多少位移，全是手算的常数，
 * 算错人就会飘在半空），以及**颜色矩阵**（角色吃光的那一份）。
 */
class RoomAvatarTest {

    private val eps = 1e-4f

    // ------------------------------------------------------------ 朝向

    @Test
    fun `四个姿态各有明确的朝向`() {
        assertEquals(RoomView.FRONT, RoomAvatar.viewOf(RoomPose.IDLE))
        assertEquals(RoomView.FRONT, RoomAvatar.viewOf(RoomPose.EXCITED))
        // 只有侧面能躺，正面躺下像标本
        assertEquals(RoomView.SIDE, RoomAvatar.viewOf(RoomPose.SLEEPY))
        // 消沉＝转过身去，不说话比说话更响
        assertEquals(RoomView.BACK, RoomAvatar.viewOf(RoomPose.DOWN))
    }

    @Test
    fun `雀跃和消沉不许用同一个朝向`() {
        // 这两个是情绪的南北极，撞了就等于「开心和难过长得一样」
        assertNotEquals(
            RoomAvatar.viewOf(RoomPose.EXCITED),
            RoomAvatar.viewOf(RoomPose.DOWN)
        )
    }

    @Test
    fun `每个姿态都能拿到一个朝向，不存在未覆盖分支`() {
        RoomPose.entries.forEach { pose ->
            val v = RoomAvatar.viewOf(pose)
            assertTrue("${pose.name} 没有朝向", v.zh.isNotBlank())
        }
    }

    // ------------------------------------------------------------ 躺平几何

    @Test
    fun `躺平角度从零转到负八十八度`() {
        assertEquals(0f, RoomAvatar.tiltOf(0f), eps)
        assertEquals(RoomAvatar.LIE_TILT_DEG, RoomAvatar.tiltOf(1f), eps)
        assertEquals(RoomAvatar.LIE_TILT_DEG, RoomAvatar.tiltOf(2f), eps)
        assertEquals(0f, RoomAvatar.tiltOf(-1f), eps)
        // 中间值是线性插值：不能出现「前 80% 不动、最后一下拍倒」
        assertEquals(RoomAvatar.LIE_TILT_DEG * 0.5f, RoomAvatar.tiltOf(0.5f), eps)
    }

    @Test
    fun `躺平要缩小，否则横过来会顶出屏幕两侧`() {
        assertEquals(1f, RoomAvatar.lieScaleOf(0f), eps)
        assertEquals(RoomAvatar.LIE_SHRINK, RoomAvatar.lieScaleOf(1f), eps)
        assertTrue("躺平后的收缩幅度不痛不痒", RoomAvatar.LIE_SHRINK < 0.95f)
        assertEquals(RoomAvatar.LIE_SHRINK, RoomAvatar.lieScaleOf(9f), eps)
    }

    @Test
    fun `躺平的补位要够把身子推回中央`() {
        // 绕脚底转 88° 后身体长度 ≈ 画框高 × 0.86，居中需右移一半
        val bodyLength = RoomAvatar.LIE_SHRINK
        val needed = bodyLength / 2f
        assertEquals(needed, RoomAvatar.LIE_SLIDE, 0.02f)
        assertTrue(RoomAvatar.LIE_SLIDE in 0.3f..0.55f)
    }

    @Test
    fun `躺平要抬半个体厚，不抬会沉进地板`() {
        // 侧视图 Fit 进画框后的宽度 = 画框宽 × (侧视比例 ÷ 画框比例)
        val bodyThickness = RoomAvatar.SIDE_ASPECT / RoomAvatar.FRAME_ASPECT
        assertEquals(bodyThickness / 2f, RoomAvatar.LIE_LIFT, eps)
        assertTrue("抬升量 ${RoomAvatar.LIE_LIFT} 不像半个身厚", RoomAvatar.LIE_LIFT in 0.2f..0.6f)
    }

    @Test
    fun `侧视图比画框窄，否则 Fit 逻辑的假设不成立`() {
        // LIE_LIFT 的推导前提是侧视图「宽度受限、两侧留白」
        assertTrue(RoomAvatar.SIDE_ASPECT < RoomAvatar.FRAME_ASPECT)
    }

    // ------------------------------------------------------------ 站位

    @Test
    fun `脚底落在地板带里，不悬空也不沉底`() {
        val foot = RoomGeometry.heroFootFraction()
        assertTrue("脚踩到墙上了：$foot", foot > RoomGeometry.HORIZON)
        assertTrue("脚掉到屏幕外了：$foot", foot < 0.99f)
        // 站在中景和前排之间 —— 太靠后会跟家具挤，太靠前会顶到抬头显示
        val bandPos = (foot - RoomGeometry.HORIZON) / (1f - RoomGeometry.HORIZON)
        assertTrue("站位太靠后：$bandPos", bandPos > 0.5f)
        assertTrue("站位太靠前：$bandPos", bandPos < 0.92f)
    }

    @Test
    fun `画框顶端要给抬头显示让出位置`() {
        val top = RoomGeometry.heroFootFraction() - RoomAvatar.FRAME_HEIGHT_FRACTION
        assertTrue("画框顶到了 $top，会盖住名字那一行", top > 0.28f)
        assertTrue("画框太矮了，人看不清", RoomAvatar.FRAME_HEIGHT_FRACTION > 0.35f)
    }

    @Test
    fun `画框高度加脚底位置不能超过屏幕`() {
        assertTrue(
            RoomGeometry.heroFootFraction() <= 1f &&
                RoomAvatar.FRAME_HEIGHT_FRACTION < RoomGeometry.heroFootFraction()
        )
    }

    /**
     * 屏幕底部要给装修输入条留地方（0.9.19）。
     *
     * 站得太靠前，输入条会压住她的脚背 —— 看起来像她陷进地板里。
     * 0.92 这个界对应「底部留 8% 屏高给小工具条」，在 640dp 的矮屏上也够。
     */
    @Test
    fun `脚底上方要给底部工具条留出位置`() {
        val foot = RoomGeometry.heroFootFraction()
        assertTrue("脚底 $foot 太靠下，会被底部输入条压住", foot <= 0.92f)
    }

    // ------------------------------------------------------------ 吃光

    @Test
    fun `角色只吃一部分室内暗度`() {
        assertEquals(1f, RoomAvatar.figureBrightnessOf(0f), eps)
        // 房间暗到四成（遮罩 0.6）时她还有 0.67，不是一起黑掉
        assertEquals(1f - 0.6f * RoomAvatar.FIGURE_LIGHT_SHARE, RoomAvatar.figureBrightnessOf(0.6f), eps)
        assertTrue("吃满了等于看不见", RoomAvatar.figureBrightnessOf(0.6f) > 0.6f)
        assertTrue("一点不吃就是贴纸", RoomAvatar.figureBrightnessOf(0.6f) < 1f)
        // 脏数据钳制
        assertEquals(1f, RoomAvatar.figureBrightnessOf(-1f), eps)
        assertTrue(RoomAvatar.figureBrightnessOf(2f) >= 0f)
    }

    @Test
    fun `只有消沉会掉色，犯困不掉色`() {
        assertEquals(0f, RoomAvatar.desaturateOf(RoomPose.IDLE), eps)
        assertEquals(0f, RoomAvatar.desaturateOf(RoomPose.SLEEPY), eps)
        assertEquals(0f, RoomAvatar.desaturateOf(RoomPose.EXCITED), eps)
        assertEquals(RoomAvatar.DOWN_DESATURATION, RoomAvatar.desaturateOf(RoomPose.DOWN), eps)
        // 「困」是光的事，「没颜色」是她自己的事，不能混
        assertTrue(RoomAvatar.desaturateOf(RoomPose.DOWN) > 0.3f)
    }

    // ------------------------------------------------------------ 颜色矩阵

    @Test
    fun `没有滤镜时是单位矩阵`() {
        val m = RoomAvatar.colorMatrixOf(brightness = 1f, warmth = 0f, desaturate = 0f)
        assertEquals(20, m.size)
        assertEquals(1f, m[0], eps)
        assertEquals(1f, m[6], eps)
        assertEquals(1f, m[12], eps)
        assertEquals(1f, m[18], eps)
        // 其余非零项必须为 0
        for (i in listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 13, 14, 15, 16, 17, 19)) {
            assertEquals("下标 $i 应为 0", 0f, m[i], eps)
        }
    }

    @Test
    fun `亮度是三个通道同比缩放`() {
        val m = RoomAvatar.colorMatrixOf(0.5f, 0f, 0f)
        assertEquals(0.5f, m[0], eps)
        assertEquals(0.5f, m[6], eps)
        assertEquals(0.5f, m[12], eps)
        // 通道间不许串色
        assertEquals(0f, m[1], eps)
        assertEquals(0f, m[5], eps)
    }

    @Test
    fun `暖色温抬红压蓝，冷色温反过来`() {
        val warm = RoomAvatar.colorMatrixOf(1f, 1f, 0f)
        val cool = RoomAvatar.colorMatrixOf(1f, -1f, 0f)
        assertTrue("暖光要抬红", warm[0] > 1f)
        assertTrue("暖光要压蓝", warm[12] < 1f)
        assertTrue("冷光要压红", cool[0] < 1f)
        assertTrue("冷光要抬蓝", cool[12] > 1f)
        // 绿通道不动：动绿会发黄
        assertEquals(1f, warm[6], eps)
        assertEquals(1f, cool[6], eps)
        // 幅度必须克制 —— 立绘是画好的，把蓝头发染成橘色就认不出来了
        assertTrue(warm[0] < 1.10f)
    }

    @Test
    fun `去饱和后三行完全一致，等于灰度`() {
        val m = RoomAvatar.colorMatrixOf(1f, 0f, 1f)
        for (c in 0..2) {
            assertEquals("第 ${c} 列 R/G 行不一致", m[c], m[5 + c], eps)
            assertEquals("第 ${c} 列 R/B 行不一致", m[c], m[10 + c], eps)
        }
        // 标准 luminance 权重
        assertEquals(0.299f, m[0], eps)
        assertEquals(0.587f, m[1], eps)
        assertEquals(0.114f, m[2], eps)
    }

    @Test
    fun `所有系数永远在合法范围内`() {
        var b = 0f
        while (b <= 1f) {
            var w = -1f
            while (w <= 1f) {
                var d = 0f
                while (d <= 1f) {
                    val m = RoomAvatar.colorMatrixOf(b, w, d)
                    m.forEachIndexed { i, v ->
                        assertTrue(
                            "m[$i]=$v 越界 (b=$b w=$w d=$d)",
                            v >= -0.001f && v <= 1.2f
                        )
                    }
                    assertTrue("alpha 行必须保持原样", m[18] == 1f && m[15] == 0f)
                    d += 0.25f
                }
                w += 0.25f
            }
            b += 0.25f
        }
    }

    // ------------------------------------------------------------ 影子

    @Test
    fun `悬浮时影子缩小变淡，躺平时拉长`() {
        val stand = RoomAvatar.shadowOf(hover = 0f, lie = 0f)
        val flying = RoomAvatar.shadowOf(hover = 1f, lie = 0f)
        val lying = RoomAvatar.shadowOf(hover = 0f, lie = 1f)

        assertTrue("飞起来影子该变小", flying.widthScale < stand.widthScale)
        assertTrue("飞起来影子该变淡", flying.alpha < stand.alpha)
        assertTrue("躺下影子该摊开", lying.widthScale > stand.widthScale)
        assertTrue("正常站姿要看得见影子", stand.alpha in 0.15f..0.40f)
    }

    @Test
    fun `影子参数被钳制，脏数据不会画出巨大黑块`() {
        listOf(
            RoomAvatar.shadowOf(-5f, -5f),
            RoomAvatar.shadowOf(9f, 9f),
            RoomAvatar.shadowOf(Float.NaN, Float.NaN)
        ).forEach {
            assertTrue("宽度越界 ${it.widthScale}", it.widthScale in 0.1f..1.0f)
            assertTrue("alpha 越界 ${it.alpha}", it.alpha in 0f..0.5f)
        }
    }

    // ------------------------------------------------------------ Zzz

    @Test
    fun `Zzz 站姿在右上，躺姿落到左边`() {
        val stand = RoomAvatar.zzzAnchorOf(0f)
        val lying = RoomAvatar.zzzAnchorOf(1f)
        assertTrue("站姿的 Zzz 该在右边：${stand.xRatio}", stand.xRatio > 0.6f)
        assertTrue("躺姿的 Zzz 该在左边：${lying.xRatio}", lying.xRatio < 0.4f)
        assertTrue("躺下后 Zzz 要跟着往下走", lying.yRatio > stand.yRatio)
    }

    @Test
    fun `Zzz 的两个位置都不许飞出画框`() {
        listOf(RoomAvatar.zzzAnchorOf(0f), RoomAvatar.zzzAnchorOf(1f), RoomAvatar.zzzAnchorOf(0.5f))
            .forEach {
                assertTrue("x 越界 ${it.xRatio}", it.xRatio in 0f..1f)
                assertTrue("y 越界 ${it.yRatio}", it.yRatio in -0.2f..1f)
            }
    }

    @Test
    fun `Zzz 锚点对 NaN 也安全`() {
        val nan = RoomAvatar.zzzAnchorOf(Float.NaN)
        assertTrue(nan.xRatio.isFinite())
        assertTrue(nan.yRatio.isFinite())
    }

    // ------------------------------------------------------------ 与情绪层联动

    @Test
    fun `情绪映射出来的姿态能被立绘层接住`() {
        // 端到端：PAD 输入 → RoomMood 姿态 → 立绘朝向，中间不许断
        val cases = listOf(
            Triple(0f, 0f, RoomView.FRONT),
            Triple(0.8f, 0.8f, RoomView.FRONT),
            Triple(0f, -0.9f, RoomView.SIDE),
            Triple(-0.8f, -0.5f, RoomView.BACK)
        )
        cases.forEach { (p, a, expected) ->
            val mood = RoomMoodMapper.of(
                com.nexus.agent.persona.PAD(p, a, 0f),
                affinity = 50
            )
            assertEquals(
                "P=$p A=$a 姿态=${mood.pose}",
                expected,
                RoomAvatar.viewOf(mood.pose)
            )
        }
    }
}
