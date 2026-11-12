package com.nexus.agent.ui.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 作息表（0.9.20 批次 D）。
 *
 * 这一组测试守的是**边界**：07:00 算早上还是算天亮，看起来是件小事，
 * 但它决定她这会儿在不在吃早饭。边界一律左闭右开，写错一格就会出现
 * 「08:59 和 09:00 是两种人生」。
 */
class TimeRoutineManagerTest {

    private fun slotAt(hour: Int, minute: Int = 0): TimeSlot =
        TimeRoutineManager.routineOf(LocalTime.of(hour, minute)).slot

    // ------------------------------------------------------------ 时段边界

    @Test
    fun `06点整是清晨`() {
        assertEquals(TimeSlot.DAWN, slotAt(6))
    }

    @Test
    fun `07点整是早上`() {
        assertEquals(TimeSlot.MORNING, slotAt(7))
    }

    @Test
    fun `08点59还是早上`() {
        assertEquals(TimeSlot.MORNING, slotAt(8, 59))
    }

    @Test
    fun `09点整转白天`() {
        assertEquals(TimeSlot.DAY, slotAt(9))
    }

    @Test
    fun `12点整转中午`() {
        assertEquals(TimeSlot.NOON, slotAt(12))
    }

    @Test
    fun `14点整结束午休`() {
        assertEquals(TimeSlot.DAY, slotAt(14))
    }

    @Test
    fun `18点整是傍晚`() {
        assertEquals(TimeSlot.EVENING, slotAt(18))
    }

    @Test
    fun `20点整是晚上`() {
        assertEquals(TimeSlot.NIGHTFALL, slotAt(20))
    }

    @Test
    fun `23点整必须睡`() {
        assertEquals(TimeSlot.NIGHT, slotAt(23))
    }

    @Test
    fun `午夜仍是深夜`() {
        assertEquals(TimeSlot.NIGHT, slotAt(0))
    }

    @Test
    fun `凌晨5点59还是深夜`() {
        assertEquals(TimeSlot.NIGHT, slotAt(5, 59))
    }

    @Test
    fun `负数和越界分钟不炸`() {
        // 有时代码会传「今天第几分钟」，跨天算错就是负数
        assertEquals(TimeSlot.NIGHT, TimeRoutineManager.slotOf(-60))
        assertEquals(TimeSlot.NIGHT, TimeRoutineManager.slotOf(24 * 60 + 30))
    }

    // ------------------------------------------------------------ 强制状态

    @Test
    fun `只有深夜是强制的`() {
        for (slot in TimeSlot.values()) {
            val routine = TimeRoutineManager.routineOfMinute(
                // 每个时段取一个代表分钟，避免只测到 NIGHT
                when (slot) {
                    TimeSlot.DAWN -> 6 * 60 + 10
                    TimeSlot.MORNING -> 7 * 60 + 30
                    TimeSlot.DAY -> 10 * 60
                    TimeSlot.NOON -> 12 * 60 + 30
                    TimeSlot.EVENING -> 18 * 60 + 30
                    TimeSlot.NIGHTFALL -> 21 * 60
                    TimeSlot.NIGHT -> 2 * 60
                }
            )
            assertEquals(slot, routine.slot)
            if (slot == TimeSlot.NIGHT) {
                assertEquals(RoomBehavior.SLEEPING, routine.forced)
                assertTrue(routine.isAsleep)
            } else {
                assertNull("除了深夜，不该有任何强制状态", routine.forced)
            }
        }
    }

    @Test
    fun `每个时段的候选行为都不含睡觉和聊天`() {
        for (minute in 0 until 24 * 60 step 7) {
            val routine = TimeRoutineManager.routineOfMinute(minute)
            // 深夜的候选池是空的（只有 forced），其余时段必须有得选
            if (routine.forced == null) {
                assertTrue("$minute 分的候选池空了", routine.preferred.isNotEmpty())
            }
            assertTrue(
                "候选池里不该有 SLEEPING：睡觉只能由 forced 表达",
                routine.preferred.none { it == RoomBehavior.SLEEPING }
            )
            assertTrue(routine.preferred.none { it == RoomBehavior.CHATTING })
        }
    }

    @Test
    fun `深夜比白天暗 比傍晚也暗`() {
        val night = TimeRoutineManager.routineOf(LocalTime.of(2, 0))
        val evening = TimeRoutineManager.routineOf(LocalTime.of(19, 0))
        val day = TimeRoutineManager.routineOf(LocalTime.of(10, 0))
        assertTrue(night.lightCap < evening.lightCap)
        assertTrue(evening.lightCap <= day.lightCap)
        // 亮度上限不能是 0：那等于关屏
        assertTrue(night.lightCap > 0.1f)
    }

    // ------------------------------------------------------------ 下一个边界

    @Test
    fun `下一个边界永远不为零`() {
        // 返回 0 会让调用方算出「立刻再检查一次」，然后死循环
        for (minute in 0 until 24 * 60) {
            val t = LocalTime.of(minute / 60, minute % 60)
            assertTrue("$minute 分算出了 0", TimeRoutineManager.minutesToNextBoundary(t) > 0)
        }
    }

    @Test
    fun `23点整距离天亮还有七小时`() {
        assertEquals(7 * 60, TimeRoutineManager.minutesToNextBoundary(LocalTime.of(23, 0)))
    }

    @Test
    fun `8点距离9点还有一小时`() {
        assertEquals(60, TimeRoutineManager.minutesToNextBoundary(LocalTime.of(8, 0)))
    }

    // ------------------------------------------------------------ 文案

    @Test
    fun `几点说得出口`() {
        assertEquals("半夜3点", TimeRoutineManager.clockOf(LocalTime.of(3, 0)))
        assertEquals("早上7点", TimeRoutineManager.clockOf(LocalTime.of(7, 0)))
        assertEquals("中午十二点", TimeRoutineManager.clockOf(LocalTime.of(12, 0)))
        assertEquals("下午3点20 分", TimeRoutineManager.clockOf(LocalTime.of(15, 20)))
        assertEquals("晚上9点", TimeRoutineManager.clockOf(LocalTime.of(21, 0)))
        assertEquals("夜里11点", TimeRoutineManager.clockOf(LocalTime.of(23, 0)))
    }

    @Test
    fun `每个时段每个行为都有中文说法`() {
        for (minute in 0 until 24 * 60 step 37) {
            val routine = TimeRoutineManager.routineOfMinute(minute)
            for (b in RoomBehavior.values()) {
                val zh = TimeRoutineManager.sceneZh(routine, b)
                assertTrue("$minute 分的 ${b.name} 没有说法", zh.isNotBlank())
            }
        }
    }

    @Test
    fun `触发句带上了时间和她在干嘛`() {
        val routine = TimeRoutineManager.routineOf(LocalTime.of(2, 13))
        val text = TimeRoutineManager.triggerOf(LocalTime.of(2, 13), routine, RoomBehavior.SLEEPING)
        assertTrue(text.contains("半夜2点"))
        assertTrue(text.contains("睡着了"))
        assertTrue("得让模型知道是用户点的那一下", text.contains("戳"))
    }

    @Test
    fun `兜底台词同一个 seed 永远同一句`() {
        val routine = TimeRoutineManager.routineOf(LocalTime.of(2, 13))
        val a = TimeRoutineManager.fallbackLine(routine, RoomBehavior.SLEEPING, 7)
        val b = TimeRoutineManager.fallbackLine(routine, RoomBehavior.SLEEPING, 7)
        assertEquals(a, b)
        assertNotNull(a)
        assertTrue(a.isNotBlank())
    }

    @Test
    fun `兜底台词换 seed 会换话`() {
        val routine = TimeRoutineManager.routineOf(LocalTime.of(10, 0))
        val lines = (0..8).map { TimeRoutineManager.fallbackLine(routine, RoomBehavior.IDLE, it) }.toSet()
        assertTrue("来回就一句，点两下就露馅了", lines.size > 1)
    }

    @Test
    fun `兜底台词不会越界`() {
        val routine = TimeRoutineManager.routineOf(LocalTime.of(10, 0))
        // 负数 seed 也要能取到（聊天计数理论上不会负，但别让它抛越界）
        for (seed in -20..20) {
            assertTrue(TimeRoutineManager.fallbackLine(routine, RoomBehavior.IDLE, seed).isNotBlank())
        }
    }
}
