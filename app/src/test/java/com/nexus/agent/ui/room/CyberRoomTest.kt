package com.nexus.agent.ui.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赛博电视的空间约束（0.9.26 批次 F）。
 *
 * 这类测试的价值不在于「验证常量等于自己」，而在于把**肉眼才能发现的问题**
 * 变成断言：电视挂到地板带里、座位顶到输入条、屏幕上沿压住她的头顶 ——
 * 这些在真机上都是「看起来怪怪的」，但不会报错，只能靠数字钉住。
 */
class CyberRoomTest {

    @Test
    fun `电视必须挂在墙面而不是地板上`() {
        // HORIZON = 0.60 以上是墙。电视中心如果低于它，就不是挂墙而是摆地上了
        assertTrue(
            "电视中心 y=${RoomGeometry.TV_CENTER_Y} 掉进了地板带",
            RoomGeometry.TV_CENTER_Y < RoomGeometry.HORIZON
        )
        // 挂在墙上也得留边：贴到 0.1 会被状态栏/HUD 压住
        assertTrue(
            "电视太高，会顶到抬头显示",
            RoomGeometry.TV_CENTER_Y > 0.24f
        )
    }

    @Test
    fun `电视不挡她的站位`() {
        // 她默认站 0.50。电视中心必须在右边足够远，
        // 否则「她站在屋子中间」时正好挡住屏幕
        val gap = kotlin.math.abs(RoomGeometry.TV_CENTER_X - RoomGeometry.HERO_X)
        assertTrue("电视离她的站位太近：$gap", gap > 0.15f)
    }

    @Test
    fun `电视尺寸不超过舞台的三分之一`() {
        assertTrue(
            "电视占舞台 ${RoomGeometry.TV_WIDTH_FRAC}，太大了",
            RoomGeometry.TV_WIDTH_FRAC in 0.20f..0.34f
        )
        // 高度不能顶穿地板线：舞台高 × 0.30 × 0.62 ≈ 0.186，中心 0.44 → 下沿 0.53
        val bottom = RoomGeometry.TV_CENTER_Y + RoomGeometry.TV_WIDTH_FRAC * RoomGeometry.TV_ASPECT / 2f
        assertTrue("电视下沿到了 $bottom，压到地板了", bottom < RoomGeometry.HORIZON)
    }

    @Test
    fun `看电视的座位在安全区内`() {
        val seat = RoomGeometry.cyberSeat()
        assertTrue(
            "座位 x=${seat.x} 越界",
            seat.x in RoomBehaviorEngine.SAFE_FEET_X_MIN..RoomBehaviorEngine.SAFE_FEET_X_MAX
        )
        assertTrue(
            "座位 y=${seat.y} 会顶到底部输入条",
            seat.y <= RoomBehaviorEngine.SAFE_FEET_Y_MAX
        )
    }

    @Test
    fun `BROWSING 会走到电视前而不是原地站着`() {
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.BROWSING, emptyList())
        val seat = RoomGeometry.cyberSeat()
        assertEquals(seat.x, spot.x, 0.0001f)
        assertEquals(seat.y, spot.y, 0.0001f)
        // 和默认站位不同 —— 否则「她去看电视」这件事在画面上不存在
        assertTrue(
            "看电视的站位和呆站一样",
            kotlin.math.abs(spot.x - RoomGeometry.HERO_X) > 0.1f
        )
    }

    @Test
    fun `看电视比发呆待得久`() {
        assertTrue(
            "看电视的保持时长还没发呆长，她会在电视刚起画面时就走了",
            RoomBehaviorEngine.holdMillis(RoomBehavior.BROWSING) >
                RoomBehaviorEngine.holdMillis(RoomBehavior.IDLE)
        )
    }

    @Test
    fun `白天和晚上都可能去看电视`() {
        // 候选池里没有 BROWSING 的话，她永远走不到电视前 —— 这是最容易漏的一处
        val day = TimeRoutineManager.routineOf(java.time.LocalTime.of(15, 0))
        val night = TimeRoutineManager.routineOf(java.time.LocalTime.of(21, 0))
        assertTrue("白天候选池里没有看电视", day.preferred.contains(RoomBehavior.BROWSING))
        assertTrue("晚上候选池里没有看电视", night.preferred.contains(RoomBehavior.BROWSING))
    }

    @Test
    fun `深夜不会爬起来看电视`() {
        val night = TimeRoutineManager.routineOf(java.time.LocalTime.of(2, 0))
        assertEquals(RoomBehavior.SLEEPING, night.forced)
        assertEquals(
            RoomBehavior.SLEEPING,
            RoomBehaviorEngine.nextAutoBehavior(night, bored = true, seed = 5, current = RoomBehavior.IDLE)
        )
    }
}
