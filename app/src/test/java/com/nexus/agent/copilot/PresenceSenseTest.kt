package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 临场感单测（0.9.2 批次三）。
 *
 * 这里只测纯函数（[PresenceSense.Now] 的手工构造 + 文案拼装），
 * 不碰 Context —— 「读电量」那部分是系统 API，真机验证。
 */
class PresenceSenseTest {

    private fun at(hour: Int, minute: Int = 0, battery: Int? = null, charging: Boolean = false) =
        PresenceSense.Now(hour, minute, battery, charging)

    @Test
    fun `时钟两位格式`() {
        assertEquals("23:30", at(23, 30).clock)
        assertEquals("09:05", at(9, 5).clock)
        assertEquals("00:00", at(0, 0).clock)
    }

    @Test
    fun `时段划分`() {
        assertEquals("凌晨", PresenceSense.periodOf(3))
        assertEquals("清早", PresenceSense.periodOf(6))
        assertEquals("上午", PresenceSense.periodOf(9))
        assertEquals("中午", PresenceSense.periodOf(12))
        assertEquals("下午", PresenceSense.periodOf(15))
        assertEquals("傍晚", PresenceSense.periodOf(18))
        assertEquals("晚上", PresenceSense.periodOf(20))
        assertEquals("深夜", PresenceSense.periodOf(23))
    }

    @Test
    fun `深夜与夜里的边界`() {
        assertTrue(at(23).isLateNight)
        assertTrue(at(2).isLateNight)
        assertFalse("22:30 还不算深夜，只是夜里", at(22).isLateNight)
        assertTrue(at(22).isNight)
        assertFalse("5 点已经不算深夜", at(5).isLateNight)
        assertFalse(at(14).isNight)
    }

    @Test
    fun `低电量判定要考虑充电状态`() {
        assertTrue(at(14, battery = 15).isLowBattery)
        assertFalse("在充电就不算低电", at(14, battery = 15, charging = true).isLowBattery)
        assertFalse(at(14, battery = 80).isLowBattery)
        assertFalse("读不到电量时不吓唬人", at(14, battery = null).isLowBattery)
    }

    @Test
    fun `描述里带时间和电量`() {
        val s = PresenceSense.describe(at(23, 30, 15))
        assertTrue(s.contains("23:30"))
        assertTrue(s.contains("深夜"))
        assertTrue(s.contains("15%"))
    }

    @Test
    fun `充电时描述要说明`() {
        val s = PresenceSense.describe(at(14, 0, 62, charging = true))
        assertTrue(s.contains("在充电"))
    }

    @Test
    fun `深夜要提困意和早睡`() {
        val block = PresenceSense.presenceBlock(at(23, 30, 80))
        assertTrue(block.contains("【现在你所在的世界】"))
        assertTrue(block.contains("困意"))
        assertTrue(block.contains("早点睡"))
    }

    @Test
    fun `白天满电不加多余的关心`() {
        val block = PresenceSense.presenceBlock(at(14, 0, 100))
        assertFalse("白天空调房里塞困意是污染", block.contains("困意"))
        assertFalse(block.contains("充电"))
    }

    @Test
    fun `低电量要提醒充电`() {
        val block = PresenceSense.presenceBlock(at(15, 0, 12))
        assertTrue(block.contains("快没电"))
        assertTrue(block.contains("充电"))
    }

    @Test
    fun `电量个位数要更急`() {
        val block = PresenceSense.presenceBlock(at(15, 0, 4))
        assertTrue(block.contains("个位数"))
        assertTrue(block.contains("赶紧充电"))
    }

    @Test
    fun `正在充电就不要再催`() {
        val block = PresenceSense.presenceBlock(at(15, 0, 12, charging = true))
        assertFalse("已经在充了还催就没眼力见", block.contains("快没电"))
    }

    @Test
    fun `主动问候的时间提示分时段`() {
        assertTrue(PresenceSense.greetingTimeHint(at(2)).contains("很晚"))
        assertTrue(PresenceSense.greetingTimeHint(at(12)).contains("饭点"))
        assertTrue(PresenceSense.greetingTimeHint(at(7)).contains("刚醒"))
        assertTrue(PresenceSense.greetingTimeHint(at(16)).contains("想起他"))
    }
}
