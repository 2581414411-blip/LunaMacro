package com.nexus.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 定时任务「下一次触发时间」的推算测试。
 *
 * 这块逻辑错一次，用户第二天就会发现任务没跑，而且在界面上完全看不出来，
 * 所以用固定时间点把边界全钉一遍：当天已过 / 当天未到 / 跨周 / 只一次 / 已关闭。
 */
class ScheduledTaskTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun base(
        repeat: ScheduledTask.Repeat,
        hour: Int,
        minute: Int,
        days: List<Int> = emptyList(),
        onceAt: Long = 0L,
        enabled: Boolean = true
    ) = ScheduledTask(
        id = "t1", title = "t", prompt = "p", repeat = repeat,
        hour = hour, minute = minute, daysOfWeek = days, onceAt = onceAt, enabled = enabled
    )

    /** 2026-10-31 是周六 */
    private val sat0822 = at(2026, 10, 31, 8, 22)

    @Test
    fun `每天 时间未到 就是今天`() {
        val next = base(ScheduledTask.Repeat.DAILY, 9, 0).nextTriggerAt(sat0822)
        assertEquals(at(2026, 10, 31, 9, 0), next)
    }

    @Test
    fun `每天 时间已过 顺延到明天`() {
        val next = base(ScheduledTask.Repeat.DAILY, 8, 0).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 1, 8, 0), next)
    }

    @Test
    fun `每天 正好同一分钟 视为已过 顺延到明天`() {
        val next = base(ScheduledTask.Repeat.DAILY, 8, 22).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 1, 8, 22), next)
    }

    @Test
    fun `每周一 从周六算起是后天`() {
        val next = base(ScheduledTask.Repeat.WEEKLY, 8, 0, listOf(1)).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 2, 8, 0), next)
    }

    @Test
    fun `每周六 当天已过 跳到下周六`() {
        val next = base(ScheduledTask.Repeat.WEEKLY, 8, 0, listOf(6)).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 7, 8, 0), next)
    }

    @Test
    fun `每周六 当天未到 就是今天`() {
        val next = base(ScheduledTask.Repeat.WEEKLY, 20, 0, listOf(6)).nextTriggerAt(sat0822)
        assertEquals(at(2026, 10, 31, 20, 0), next)
    }

    @Test
    fun `每周六和周日 取最近的周日`() {
        val next = base(ScheduledTask.Repeat.WEEKLY, 7, 0, listOf(6, 7)).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 1, 7, 0), next)
    }

    @Test
    fun `每周日 明天`() {
        val next = base(ScheduledTask.Repeat.WEEKLY, 8, 0, listOf(7)).nextTriggerAt(sat0822)
        assertEquals(at(2026, 11, 1, 8, 0), next)
    }

    @Test
    fun `每周 但没选星期几 不排程`() {
        assertEquals(0L, base(ScheduledTask.Repeat.WEEKLY, 8, 0, emptyList()).nextTriggerAt(sat0822))
    }

    @Test
    fun `只一次 将来时间按原样返回`() {
        val target = at(2026, 11, 3, 15, 30)
        assertEquals(target, base(ScheduledTask.Repeat.ONCE, 15, 30, onceAt = target).nextTriggerAt(sat0822))
    }

    @Test
    fun `只一次 过去时间不再触发`() {
        val target = at(2026, 10, 1, 15, 30)
        assertEquals(0L, base(ScheduledTask.Repeat.ONCE, 15, 30, onceAt = target).nextTriggerAt(sat0822))
    }

    @Test
    fun `关闭的任务不排程`() {
        assertEquals(0L, base(ScheduledTask.Repeat.DAILY, 9, 0, enabled = false).nextTriggerAt(sat0822))
    }

    @Test
    fun `下一次触发一定晚于起点 且不超过一周`() {
        // 遍历所有 weekday × 时刻组合做不变量校验，防止某条边角规则漏判
        for (day in 1..7) {
            for (hour in 0..23) {
                val task = base(ScheduledTask.Repeat.WEEKLY, hour, 30, listOf(day))
                val next = task.nextTriggerAt(sat0822)
                assertTrue("day=$day hour=$hour 应有下次触发", next > sat0822)
                assertTrue("day=$day hour=$hour 应在 7 天内", next - sat0822 <= 7L * 24 * 3600 * 1000)
                val c = Calendar.getInstance().apply { timeInMillis = next }
                assertEquals("day=$day hour=$hour 星期不符", day, ScheduledTask.isoDayOfWeek(c))
            }
        }
    }

    @Test
    fun `重复文案可读`() {
        val daily = base(ScheduledTask.Repeat.DAILY, 8, 5)
        assertEquals("每天 08:05", daily.repeatText())
        val weekly = base(ScheduledTask.Repeat.WEEKLY, 21, 0, listOf(1, 4))
        assertEquals("每周一/四 21:00", weekly.repeatText())
        assertNotNull(weekly.repeatText())
    }
}
