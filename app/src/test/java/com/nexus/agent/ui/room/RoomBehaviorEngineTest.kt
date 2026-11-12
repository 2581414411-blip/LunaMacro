package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 行为状态机（0.9.20 批次 D）。
 *
 * 守三件事：
 * 1. **夜晚必须睡** —— 不管 `bored` 传什么；
 * 2. **该去哪儿就去哪儿** —— 睡觉去床、吃饭去桌、坐着去沙发，找不到家具才退回中间；
 * 3. **位置不会跑到屏幕外** —— 她会走动，坐标是算出来的，不能靠肉眼验。
 */
class RoomBehaviorEngineTest {

    private fun item(kind: String, slot: String) =
        RoomItem(kind = kind, slot = slot)

    private val defaultRoom = listOf(
        item(RoomItem.KIND_BED, RoomItem.SLOT_BACK_RIGHT),
        item(RoomItem.KIND_DESK, RoomItem.SLOT_MID_LEFT),
        item(RoomItem.KIND_SOFA, RoomItem.SLOT_FRONT_LEFT),
        item(RoomItem.KIND_POSTER, RoomItem.SLOT_ON_WALL)
    )

    private fun nightRoutine() = TimeRoutineManager.routineOf(LocalTime.of(2, 0))
    private fun dayRoutine() = TimeRoutineManager.routineOf(LocalTime.of(10, 0))
    private fun morningRoutine() = TimeRoutineManager.routineOf(LocalTime.of(7, 30))
    private fun noonRoutine() = TimeRoutineManager.routineOf(LocalTime.of(12, 30))

    // ------------------------------------------------------------ 目标点

    @Test
    fun `睡觉去床边`() {
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.SLEEPING, defaultRoom)
        // 床在 back_right（0.80），落点应该就是那张床的横向位置
        assertEquals(0.80f, spot.x, 0.001f)
    }

    @Test
    fun `吃饭去书桌`() {
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.EATING, defaultRoom)
        assertEquals(0.24f, spot.x, 0.001f)
    }

    @Test
    fun `坐着去沙发`() {
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.SITTING, defaultRoom)
        assertEquals(0.16f, spot.x, 0.001f)
    }

    @Test
    fun `屋里没床就退回中间站着而不是躺在空气里`() {
        val empty = emptyList<RoomItem>()
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.SLEEPING, empty)
        assertEquals(RoomGeometry.HERO_X, spot.x, 0.001f)
        assertEquals(RoomGeometry.HERO_Y, spot.y, 0.001f)
    }

    @Test
    fun `挂画不是站位`() {
        // 墙面件的 y 是屏幕比例不是地板带，站过去会浮在半空
        val onlyPoster = listOf(item(RoomItem.KIND_POSTER, RoomItem.SLOT_ON_WALL))
        assertNull(RoomBehaviorEngine.spotNear(RoomItem.KIND_POSTER, onlyPoster))
    }

    @Test
    fun `任何家具给的站位都在安全范围里`() {
        val kinds = RoomItem.DECOR_KINDS
        val slots = RoomItem.SLOTS
        for (k in kinds) {
            for (s in slots) {
                val spot = RoomBehaviorEngine.spotNear(k, listOf(item(k, s))) ?: continue
                assertTrue("$k/$s 的 x 越界：${spot.x}", spot.x in 0.14f..0.86f)
                assertTrue("$k/$s 的 y 越界：${spot.y}", spot.y in 0f..RoomBehaviorEngine.SAFE_FEET_Y_MAX)
            }
        }
    }

    @Test
    fun `乱数据不炸`() {
        val weird = listOf(item(RoomItem.KIND_BED, "完全不存在的槽位"))
        val spot = RoomBehaviorEngine.spotOf(RoomBehavior.SLEEPING, weird)
        assertTrue(spot.x.isFinite() && spot.y.isFinite())
    }

    // ------------------------------------------------------------ 决策

    @Test
    fun `深夜不管闷不闷都要睡`() {
        val r = nightRoutine()
        for (bored in listOf(true, false)) {
            val next = RoomBehaviorEngine.nextAutoBehavior(r, bored, 3, RoomBehavior.IDLE)
            assertEquals(RoomBehavior.SLEEPING, next)
        }
    }

    @Test
    fun `白天没闷够就继续发呆`() {
        val next = RoomBehaviorEngine.nextAutoBehavior(dayRoutine(), false, 3, RoomBehavior.IDLE)
        assertEquals(RoomBehavior.IDLE, next)
    }

    @Test
    fun `吃饭没闷够时不会被心跳打断`() {
        // 心跳每分钟一次，而吃饭要坐满 150 秒 —— 如果「没闷够」返回 IDLE，
        // 这顿饭只吃一分钟就被拽去发呆。这条测试是钉这个的。
        val next = RoomBehaviorEngine.nextAutoBehavior(
            morningRoutine(), bored = false, seed = 3, current = RoomBehavior.EATING
        )
        assertEquals(RoomBehavior.EATING, next)
    }

    @Test
    fun `午休没闷够时不会被心跳打断`() {
        val next = RoomBehaviorEngine.nextAutoBehavior(
            noonRoutine(), bored = false, seed = 4, current = RoomBehavior.SITTING
        )
        assertEquals(RoomBehavior.SITTING, next)
    }

    @Test
    fun `白天闷够了会去做候选池里的事`() {
        val r = dayRoutine()
        val picks = (0..20).map { RoomBehaviorEngine.nextAutoBehavior(r, true, it, RoomBehavior.IDLE) }
        assertTrue("一次都没换过动作", picks.any { it != RoomBehavior.IDLE })
        assertTrue(
            "换了候选池以外的事",
            picks.all { it in r.preferred }
        )
    }

    @Test
    fun `同一个 seed 结果稳定`() {
        val r = dayRoutine()
        val a = RoomBehaviorEngine.nextAutoBehavior(r, true, 11, RoomBehavior.IDLE)
        val b = RoomBehaviorEngine.nextAutoBehavior(r, true, 11, RoomBehavior.IDLE)
        assertEquals(a, b)
    }

    @Test
    fun `聊天中不会被一分钟心跳打断`() {
        val next = RoomBehaviorEngine.nextAutoBehavior(dayRoutine(), true, 5, RoomBehavior.CHATTING)
        assertEquals(RoomBehavior.CHATTING, next)
    }

    @Test
    fun `早饭时段的候选里有吃饭`() {
        assertTrue(morningRoutine().preferred.contains(RoomBehavior.EATING))
    }

    @Test
    fun `每个时段的候选池都不是空的`() {
        for (minute in 0 until 24 * 60 step 11) {
            val r = TimeRoutineManager.routineOfMinute(minute)
            assertTrue(
                "$minute 分挑不出动作",
                RoomBehaviorEngine.nextAutoBehavior(r, true, 1, RoomBehavior.IDLE) != RoomBehavior.CHATTING
            )
        }
    }

    // ------------------------------------------------------------ 走路

    @Test
    fun `路程太短就不走`() {
        val a = RoomBehaviorEngine.Spot(0.5f, 0.7f)
        val b = RoomBehaviorEngine.Spot(0.52f, 0.7f)
        assertEquals(0, RoomBehaviorEngine.walkMillis(a, b))
    }

    @Test
    fun `越远走得越久 但有上限`() {
        val a = RoomBehaviorEngine.Spot(0.1f, 0.7f)
        val near = RoomBehaviorEngine.walkMillis(a, RoomBehaviorEngine.Spot(0.3f, 0.7f))
        val far = RoomBehaviorEngine.walkMillis(a, RoomBehaviorEngine.Spot(0.9f, 0.7f))
        assertTrue(near > 0)
        assertTrue(far > near)
        assertTrue(
            "走太久像卡住了",
            far <= RoomBehaviorEngine.WALK_BASE_MS + RoomBehaviorEngine.WALK_SPAN_MS
        )
    }

    @Test
    fun `朝向按位移判断`() {
        assertEquals(1, RoomBehaviorEngine.facingOf(0.2f, 0.6f))
        assertEquals(-1, RoomBehaviorEngine.facingOf(0.6f, 0.2f))
        // 原地微动不该翻转，否则她会原地抖着翻面
        assertEquals(0, RoomBehaviorEngine.facingOf(0.5f, 0.505f))
    }

    @Test
    fun `随便走走不会选到脚下`() {
        for (seed in 0..20) {
            val from = RoomBehaviorEngine.Spot(0.30f, 0.62f)
            val to = RoomBehaviorEngine.wanderSpot(from, seed)
            assertTrue(
                "seed=$seed 选到了脚下",
                RoomBehaviorEngine.distanceOf(from, to) >= RoomBehaviorEngine.MIN_WALK_DELTA
            )
        }
    }

    @Test
    fun `随便走走的点都在屋内`() {
        for (p in RoomBehaviorEngine.WALK_SPOTS) {
            assertTrue("x 越界 ${p.x}", p.x in 0f..1f)
            assertTrue("y 越界 ${p.y}", p.y in 0f..0.9f)
        }
    }

    // ------------------------------------------------------------ 停留时长

    @Test
    fun `睡觉不会被当成几分钟就结束的动作`() {
        assertTrue(
            RoomBehaviorEngine.holdMillis(RoomBehavior.SLEEPING) >
                RoomBehaviorEngine.holdMillis(RoomBehavior.EATING)
        )
        assertTrue(
            RoomBehaviorEngine.holdMillis(RoomBehavior.EATING) >
                RoomBehaviorEngine.holdMillis(RoomBehavior.IDLE)
        )
    }

    @Test
    fun `心跳间隔是一分钟`() {
        assertEquals(60_000L, RoomBehaviorEngine.TICK_MS)
    }

    @Test
    fun `默认站位就是批次B 那个位置`() {
        val home = RoomBehaviorEngine.homeSpot()
        assertEquals(RoomGeometry.HERO_X, home.x, 0.0001f)
        assertEquals(RoomGeometry.HERO_Y, home.y, 0.0001f)
    }

    @Test
    fun `每种行为都有中文名`() {
        for (b in RoomBehavior.values()) {
            assertNotNull(b.zh)
            assertTrue(b.zh.isNotBlank())
        }
    }
}
