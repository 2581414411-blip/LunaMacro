package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳判定单测（0.9.2 批次二）。
 *
 * 这一段逻辑全是时间运算，最容易出的错是「半夜给用户发消息」「刚聊完就追问」
 * 这类用户能立刻感知到的蠢事，所以把 [HeartbeatScheduler.decide] 做成纯函数、逐条钉死。
 */
class HeartbeatSchedulerTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    /** 一个固定的「现在」：2026-11-07 14:00 附近，白天 */
    private val now = 1_800_000_000_000L

    @Test
    fun `刚聊完不打扰`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 2 * hour,
            lastHeartbeatAt = 0L,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.NOT_IDLE, v)
    }

    @Test
    fun `二十三点九小时还差一点`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 23 * hour,
            lastHeartbeatAt = 0L,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.NOT_IDLE, v)
    }

    @Test
    fun `超过一天没聊且是白天就发`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 25 * hour,
            lastHeartbeatAt = 0L,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `夜里不发`() {
        listOf(0, 8, 22, 23).forEach { h ->
            val v = HeartbeatScheduler.decide(
                nowMs = now,
                hourOfDay = h,
                lastChatAt = now - 3 * day,
                lastHeartbeatAt = 0L,
                enabled = true
            )
            assertEquals("$h 点不该发", HeartbeatScheduler.Verdict.NIGHT, v)
        }
    }

    @Test
    fun `白天窗口边界`() {
        assertEquals(
            HeartbeatScheduler.Verdict.SEND,
            HeartbeatScheduler.decide(now, 9, now - 3 * day, 0L, true)
        )
        assertEquals(
            HeartbeatScheduler.Verdict.SEND,
            HeartbeatScheduler.decide(now, 21, now - 3 * day, 0L, true)
        )
    }

    @Test
    fun `从没聊过不发`() {
        val v = HeartbeatScheduler.decide(now, 14, 0L, 0L, true)
        assertEquals(HeartbeatScheduler.Verdict.NEVER_CHATTED, v)
    }

    @Test
    fun `刚问候过进入冷却`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 2 * hour,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.COOLDOWN, v)
    }

    @Test
    fun `冷却过了可以再发`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 21 * hour,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `开关关掉一律不发`() {
        val v = HeartbeatScheduler.decide(now, 14, now - 3 * day, 0L, enabled = false)
        assertEquals(HeartbeatScheduler.Verdict.DISABLED, v)
        // force 也不能绕过总开关
        assertEquals(
            HeartbeatScheduler.Verdict.DISABLED,
            HeartbeatScheduler.decide(now, 14, now - 3 * day, 0L, false, force = true)
        )
    }

    @Test
    fun `手动强制跳过时间与冷却`() {
        // 半夜 + 刚聊完 + 刚问候过，三条闸全踩，force 仍然要发（不然用户点了没反应）
        val v = HeartbeatScheduler.decide(now, 3, now - 60_000L, now - 60_000L, true, force = true)
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `白天注册三分钟后就检查一次`() {
        assertEquals(3L, HeartbeatScheduler.initialDelayMinutes(14, 0))
        assertEquals(3L, HeartbeatScheduler.initialDelayMinutes(9, 1))
    }

    @Test
    fun `夜里注册要等到早上`() {
        // 23:00 → 距次日 9:00 是 600 分钟，再加 5 分钟余量
        assertEquals(605L, HeartbeatScheduler.initialDelayMinutes(23, 0))
        // 02:30 → 距 9:00 是 390 分钟
        assertEquals(395L, HeartbeatScheduler.initialDelayMinutes(2, 30))
        // 22:00 算夜里
        assertEquals(665L, HeartbeatScheduler.initialDelayMinutes(22, 0))
    }

    @Test
    fun `延迟永远为正数`() {
        (0..23).forEach { h ->
            assertTrue("$h 点算出来的延迟不能是 0", HeartbeatScheduler.initialDelayMinutes(h, 59) > 0)
        }
    }
}
