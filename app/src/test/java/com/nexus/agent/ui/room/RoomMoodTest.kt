package com.nexus.agent.ui.room

import com.nexus.agent.persona.PAD
import com.nexus.agent.persona.PadDynamics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赛博房间的情绪映射单测（0.9.17 批次 B）。
 *
 * ## 为什么这一层最值得测
 *
 * 房间是**纯视觉**的：光影、姿态、距离一旦算错，代码不会崩、日志不会报，
 * 只会出现「她困得要死但屋里灯火通明」这种没人发现的问题。
 * 把 [RoomMoodMapper] 抽成纯函数，就是为了让这些能断言：
 *
 *  - 犯困 / 消沉 → 亮度必须正好 40%，色温必须偏冷，且犯困才有 Zzz；
 *  - 雀跃 → 亮度拉满、色温偏暖、悬浮；
 *  - 好感度只改距离，不改姿态（混在一起就变成「好感刷满她永远在笑」）；
 *  - 任何输入（包括 NaN）都不许把亮度带出 [0.40, 1.00]。
 */
class RoomMoodTest {

    // ------------------------------------------------------------ 四档姿态

    @Test
    fun `完全中性时站着不动`() {
        val mood = RoomMoodMapper.of(PAD.ZERO, affinity = 50)
        assertEquals(RoomPose.IDLE, mood.pose)
        assertFalse(mood.showZzz)
        assertFalse(mood.hovering)
        assertEquals("平静", mood.label)
    }

    @Test
    fun `犯困时躺平并冒 Zzz`() {
        // A 极低、P 不算太差：这是「凌晨四点的困」，不是「意志消沉」
        val mood = RoomMoodMapper.of(PAD(0f, -0.6f, -0.2f), affinity = 50)
        assertEquals(RoomPose.SLEEPY, mood.pose)
        assertTrue(mood.showZzz)
        assertEquals(RoomMoodMapper.DIM_BRIGHTNESS, mood.brightness, 0.001f)
        assertEquals(0.60f, mood.scrimAlpha, 0.001f)
        assertTrue("犯困必须是冷色温", mood.warmth < 0f)
    }

    @Test
    fun `雀跃时亮度拉满并悬浮`() {
        val mood = RoomMoodMapper.of(PAD(0.6f, 0.6f, 0.4f), affinity = 50)
        assertEquals(RoomPose.EXCITED, mood.pose)
        assertTrue(mood.hovering)
        assertEquals(RoomMoodMapper.FULL_BRIGHTNESS, mood.brightness, 0.001f)
        assertEquals(0f, mood.scrimAlpha, 0.001f)
        assertTrue("雀跃必须是暖色温", mood.warmth > 0.5f)
    }

    @Test
    fun `消沉时变冷但不会冒出 Zzz`() {
        val mood = RoomMoodMapper.of(PAD(-0.6f, -0.3f, -0.2f), affinity = 50)
        assertEquals(RoomPose.DOWN, mood.pose)
        // Zzz 是「睡着了」，消沉是「不想动」，两者不能混
        assertFalse(mood.showZzz)
        assertEquals(RoomMoodMapper.DIM_BRIGHTNESS, mood.brightness, 0.001f)
        assertTrue(mood.warmth < 0f)
    }

    @Test
    fun `真难过的时候不拿困糊过去`() {
        // A 很低但 P 也很低 → 不是困，是难过。与 PadState.describeOf 的判据必须一致
        val mood = RoomMoodMapper.of(PAD(-0.5f, -0.45f, -0.3f), affinity = 50)
        assertEquals(RoomPose.DOWN, mood.pose)
        assertEquals(PadDynamics.labelOf(PAD(-0.5f, -0.45f, -0.3f)), mood.label)
    }

    @Test
    fun `困与消沉的边界按 P 轴分`() {
        // P 刚好在门槛上（−0.20）→ 仍是困
        assertEquals(RoomPose.SLEEPY, RoomMoodMapper.poseOf(PAD(-0.20f, -0.40f, 0f)))
        // 再往下 0.01 → 变成消沉
        assertEquals(RoomPose.DOWN, RoomMoodMapper.poseOf(PAD(-0.21f, -0.40f, 0f)))
    }

    @Test
    fun `雀跃要两个轴都够`() {
        // 只有愉悦度没有唤醒度：高兴但没兴奋，不该飘起来
        assertEquals(RoomPose.IDLE, RoomMoodMapper.poseOf(PAD(0.8f, 0.1f, 0f)))
        // 只有唤醒度没有愉悦度：那是焦躁，不是雀跃
        assertEquals(RoomPose.IDLE, RoomMoodMapper.poseOf(PAD(0.1f, 0.9f, 0f)))
        assertEquals(RoomPose.EXCITED, RoomMoodMapper.poseOf(PAD(0.31f, 0.31f, 0f)))
    }

    @Test
    fun `NaN 输入当作平静而不是炸掉`() {
        val mood = RoomMoodMapper.of(PAD(Float.NaN, Float.NaN, Float.NaN), affinity = 50)
        assertEquals(RoomPose.IDLE, mood.pose)
        assertTrue(mood.brightness.isFinite())
        assertTrue(mood.distance.isFinite())
    }

    // ------------------------------------------------------------ 亮度

    @Test
    fun `亮度永远钳在四成到满之间`() {
        // 全区间网格扫描：P/A 各 21 档，含所有边界（±1.0、0、±0.3）
        for (i in -10..10) {
            for (j in -10..10) {
                val p = i / 10f
                val a = j / 10f
                val mood = RoomMoodMapper.of(PAD(p, a, 0f), affinity = 50)
                assertTrue(
                    "亮度越界 p=$p a=$a → ${mood.brightness}",
                    mood.brightness >= RoomMoodMapper.DIM_BRIGHTNESS - 1e-4f &&
                        mood.brightness <= RoomMoodMapper.FULL_BRIGHTNESS + 1e-4f
                )
                assertTrue(
                    "遮罩 alpha 越界 p=$p a=$a → ${mood.scrimAlpha}",
                    mood.scrimAlpha in 0f..RoomMood.MAX_SCRIM
                )
            }
        }
    }

    @Test
    fun `平静时心情越好屋里越亮`() {
        val dim = RoomMoodMapper.of(PAD(-0.10f, -0.10f, 0f), affinity = 50)
        val bright = RoomMoodMapper.of(PAD(0.20f, 0.20f, 0f), affinity = 50)
        assertEquals(RoomPose.IDLE, dim.pose)
        assertEquals(RoomPose.IDLE, bright.pose)
        assertTrue(
            "平静档的亮度必须随 P/A 连续调制，否则两种心情下房间一模一样",
            bright.brightness > dim.brightness
        )
        assertTrue(dim.brightness >= RoomMoodMapper.IDLE_BRIGHTNESS_FLOOR)
    }

    // ------------------------------------------------------------ 色温

    @Test
    fun `冷色温偏蓝暖色温偏红`() {
        val cold = RoomMoodMapper.ambientArgbOf(-0.85f)
        val warm = RoomMoodMapper.ambientArgbOf(0.90f)
        val mid = RoomMoodMapper.ambientArgbOf(0f)

        assertTrue("冷色温应该是蓝多于红", cold.blueOf() > cold.redOf())
        assertTrue("暖色温应该是红多于蓝", warm.redOf() > warm.blueOf())
        // 中间值必须落在两端之间，不能跟端点相等（否则就是硬切，动画再顺也没用）
        assertTrue(mid.redOf() > cold.redOf() && mid.redOf() < warm.redOf())
        assertEquals(0xFF, (cold shr 24) and 0xFF)
    }

    @Test
    fun `平静时几乎没有色滤镜`() {
        val mood = RoomMoodMapper.of(PAD.ZERO, affinity = 50)
        assertEquals(RoomMoodMapper.TINT_IDLE, mood.tintAlpha, 1e-6f)
        assertTrue("平静不该带明显色温偏移", kotlin.math.abs(mood.warmth) < 0.05f)
    }

    // ------------------------------------------------------------ 距离

    @Test
    fun `好感度越高她离屏幕越近`() {
        val far = RoomMoodMapper.distanceOf(0)
        val mid = RoomMoodMapper.distanceOf(50)
        val near = RoomMoodMapper.distanceOf(100)
        assertEquals(RoomMoodMapper.FAR_DISTANCE, far, 1e-6f)
        assertEquals(RoomMoodMapper.NEAR_DISTANCE, near, 1e-6f)
        assertTrue(far < mid && mid < near)
    }

    @Test
    fun `好感度越界输入被钳制`() {
        assertEquals(RoomMoodMapper.FAR_DISTANCE, RoomMoodMapper.distanceOf(-20), 1e-6f)
        assertEquals(RoomMoodMapper.NEAR_DISTANCE, RoomMoodMapper.distanceOf(200), 1e-6f)
    }

    @Test
    fun `好感度只改距离不改姿态`() {
        val sad = PAD(-0.6f, -0.3f, 0f)
        val a = RoomMoodMapper.of(sad, affinity = 0)
        val b = RoomMoodMapper.of(sad, affinity = 100)
        assertEquals(a.pose, b.pose)
        assertEquals(a.brightness, b.brightness, 1e-6f)
        assertTrue(b.distance > a.distance)
    }

    // ------------------------------------------------------------ 与提示词同源

    @Test
    fun `房间里的状态词与提示词同一套`() {
        val pad = PAD(0.7f, 0.7f, 0.5f)
        assertEquals(PadDynamics.labelOf(pad), RoomMoodMapper.of(pad, 50).label)
        assertEquals("雀跃", RoomMoodMapper.of(pad, 50).label)
    }
}
