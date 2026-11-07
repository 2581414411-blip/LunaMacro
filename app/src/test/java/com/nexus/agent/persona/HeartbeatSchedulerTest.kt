package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 心跳判定单测（0.9.2 批次二建，0.9.3 改成 15 分钟节奏后重写）。
 *
 * 这一段逻辑全是时间运算，最容易出的错是「半夜给用户发消息」「刚聊完就追问」
 * 这类用户能立刻感知到的蠢事，所以把 [HeartbeatScheduler.decide] 做成纯函数、逐条钉死。
 *
 * 0.9.3 的语义：**机会每 15 分钟一次，冷却也是 15 分钟** —— 因此这一组的核心断言是
 * 「两条消息之间至少隔 15 分钟，也就是一小时最多 4 条」。
 */
class HeartbeatSchedulerTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour

    /** 一个固定的「现在」：白天 */
    private val now = 1_800_000_000_000L

    @Test
    fun `刚聊完不打扰`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 5 * minute,
            lastHeartbeatAt = 0L,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.NOT_IDLE, v)
    }

    @Test
    fun `十四分钟还差一点`() {
        val v = HeartbeatScheduler.decide(now, 14, now - 14 * minute, 0L, true)
        assertEquals(HeartbeatScheduler.Verdict.NOT_IDLE, v)
    }

    @Test
    fun `恰好十五分钟没说话就开口`() {
        // 边界必须是「过 15 分钟就发」：写成 < 会让 15 分钟整卡在 NOT_IDLE，
        // 而 15 分钟周期意味着下一个机会又要再等一轮，体感是「她怎么两轮才理我」
        val v = HeartbeatScheduler.decide(now, 14, now - 15 * minute, 0L, true)
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `一小时最多四次`() {
        // 从静默状态起，每 15 分钟判定一次，一小时正好 4 次 SEND
        val lastChat = now - day
        var lastBeat = 0L
        var sent = 0
        for (i in 1..4) {
            val t = now + i * 15 * minute
            val v = HeartbeatScheduler.decide(t, 14, lastChat, lastBeat, true)
            assertEquals("第 $i 次应该发", HeartbeatScheduler.Verdict.SEND, v)
            lastBeat = t
            sent++
        }
        assertEquals(4, sent)
        // 跨到下一小时后仍然可以发（每日上限另算，见下面的 0.9.4 用例）
        assertEquals(
            HeartbeatScheduler.Verdict.SEND,
            HeartbeatScheduler.decide(now + 5 * 15 * minute, 15, lastChat, lastBeat, true)
        )
    }

    // ---------------------------------------------------------- 0.9.4 防破产

    @Test
    fun `一天发满十条就闭嘴`() {
        // 10 条是硬上限：再多就是信息轰炸，不是陪伴
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 2 * hour,
            enabled = true,
            sentToday = 10
        )
        assertEquals(HeartbeatScheduler.Verdict.DAILY_LIMIT, v)
    }

    @Test
    fun `第九条还发得出去`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 2 * hour,
            enabled = true,
            sentToday = 9
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `满额时手动触发仍然能发`() {
        // 用户自己点的「现在试」不能被上限挡住，否则点了没反应像是坏了
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 3,
            lastChatAt = now - minute,
            lastHeartbeatAt = now - minute,
            enabled = true,
            force = true,
            sentToday = 99
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `连续两次被晾着就降频到两小时`() {
        // 15 分钟冷却已过，但无视连击 = 2 → 还得再等，不能 15 分钟一刷
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 30 * minute,
            enabled = true,
            ignoredStreak = 2
        )
        assertEquals(HeartbeatScheduler.Verdict.SLOWED, v)
    }

    @Test
    fun `降频满两小时就恢复开口`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 120 * minute,
            enabled = true,
            ignoredStreak = 2
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `只被无视一次不降频`() {
        // 一次没回可能是没看手机，不该立刻当成被嫌弃
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 30 * minute,
            enabled = true,
            ignoredStreak = 1
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `降频门槛就是两次`() {
        assertEquals(2, com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_IGNORED_STREAK_TO_SLOW)
        assertEquals(4L, com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_IGNORED_WINDOW_HOURS)
        assertEquals(120L, com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_SLOW_GAP_MINUTES)
        assertEquals(10, com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_DAILY_LIMIT)
    }

    @Test
    fun `刚发过五分钟还在冷却`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 5 * minute,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.COOLDOWN, v)
    }

    @Test
    fun `冷却十五分钟整可以再发`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = now - 15 * minute,
            enabled = true
        )
        assertEquals(HeartbeatScheduler.Verdict.SEND, v)
    }

    @Test
    fun `冷却过了可以再发`() {
        val v = HeartbeatScheduler.decide(now, 14, now - 3 * day, now - 2 * hour, true)
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
    fun `生成失败进入退避`() {
        // 15 分钟的检查频率配上失效的 Key，会在两小时内打出 8 次请求，
        // 退避就是拦住这个：失败后半小时内直接不发
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = 0L,
            enabled = true,
            backoffUntil = now + 20 * minute
        )
        assertEquals(HeartbeatScheduler.Verdict.BACKOFF, v)
    }

    @Test
    fun `退避过期后恢复`() {
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 14,
            lastChatAt = now - 3 * day,
            lastHeartbeatAt = 0L,
            enabled = true,
            backoffUntil = now - 1
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
    fun `手动强制跳过时间冷却与退避`() {
        // 半夜 + 刚聊完 + 刚问候过 + 正在退避，四道闸全踩，force 仍然要发（不然用户点了没反应）
        val v = HeartbeatScheduler.decide(
            nowMs = now,
            hourOfDay = 3,
            lastChatAt = now - 60_000L,
            lastHeartbeatAt = now - 60_000L,
            enabled = true,
            force = true,
            backoffUntil = now + day
        )
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

    @Test
    fun `周期就是 WorkManager 的下限十五分钟`() {
        assertEquals(15L, HeartbeatScheduler.CHECK_INTERVAL_MINUTES)
        // 周期与冷却同频，才能兑现「每小时 4 次机会」
        assertEquals(
            HeartbeatScheduler.CHECK_INTERVAL_MINUTES,
            HeartbeatScheduler.MIN_GAP_MINUTES
        )
    }
}
