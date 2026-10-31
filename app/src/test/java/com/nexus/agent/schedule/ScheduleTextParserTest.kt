package com.nexus.agent.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「说一句话建定时任务」的解析测试。
 *
 * 重点防两类事故：
 * 1. 该识别成任务的话没识别（用户说了「每天八点签到」却当成普通聊天）
 * 2. 不该识别的话被误判（「每天都好累」被建成一个每天的任务）
 */
class ScheduleTextParserTest {

    @Test
    fun `每天早上8点 帮我签到`() {
        val p = ScheduleTextParser.parse("每天早上8点帮我签到")!!
        assertEquals(ScheduledTask.Repeat.DAILY, p.repeat)
        assertEquals(8, p.hour)
        assertEquals(0, p.minute)
        assertEquals("签到", p.prompt)
    }

    @Test
    fun `每天 8点30 提醒我喝水`() {
        val p = ScheduleTextParser.parse("每天8点30提醒我喝水")!!
        assertEquals(ScheduledTask.Repeat.DAILY, p.repeat)
        assertEquals(8, p.hour)
        assertEquals(30, p.minute)
        assertTrue(p.prompt.contains("喝水"))
    }

    @Test
    fun `每天 8点半`() {
        val p = ScheduleTextParser.parse("每天8点半帮我清缓存")!!
        assertEquals(8, p.hour)
        assertEquals(30, p.minute)
    }

    @Test
    fun `每天 8冒号30`() {
        val p = ScheduleTextParser.parse("每天8:30帮我打卡")!!
        assertEquals(8, p.hour)
        assertEquals(30, p.minute)
    }

    @Test
    fun `晚上9点 要换算成21点`() {
        val p = ScheduleTextParser.parse("每天晚上9点清理后台")!!
        assertEquals(21, p.hour)
        assertEquals("清理后台", p.prompt)
    }

    @Test
    fun `下午3点 要换算成15点`() {
        val p = ScheduleTextParser.parse("每天下午3点提醒我开会")!!
        assertEquals(15, p.hour)
    }

    @Test
    fun `中午12点 保持12点`() {
        val p = ScheduleTextParser.parse("每天中午12点提醒我吃饭")!!
        assertEquals(12, p.hour)
    }

    @Test
    fun `凌晨1点 保持1点`() {
        val p = ScheduleTextParser.parse("每天凌晨1点自动备份")!!
        assertEquals(1, p.hour)
    }

    @Test
    fun `中文数字 七点半`() {
        val p = ScheduleTextParser.parse("每天早上七点半叫我起床")!!
        assertEquals(7, p.hour)
        assertEquals(30, p.minute)
    }

    @Test
    fun `中文数字 十点`() {
        val p = ScheduleTextParser.parse("每天晚上十点提醒我睡觉")!!
        assertEquals(22, p.hour)
    }

    @Test
    fun `每周一和周四 解析出两天`() {
        val p = ScheduleTextParser.parse("每周一、周四晚上9点清理后台")!!
        assertEquals(ScheduledTask.Repeat.WEEKLY, p.repeat)
        assertEquals(listOf(1, 4), p.days)
        assertEquals(21, p.hour)
        assertEquals("清理后台", p.prompt)
    }

    @Test
    fun `每周日 解析成7`() {
        val p = ScheduleTextParser.parse("每周日晚上8点提醒我看剧")!!
        assertEquals(listOf(7), p.days)
        assertEquals(20, p.hour)
    }

    @Test
    fun `只说了每周 没说星期几 不认`() {
        assertNull(ScheduleTextParser.parse("每周帮我签到"))
    }

    @Test
    fun `明天早上7点 建成一次性任务`() {
        val p = ScheduleTextParser.parse("明天早上7点叫我起床")!!
        assertEquals(ScheduledTask.Repeat.ONCE, p.repeat)
        assertTrue("一次性任务必须有绝对时间", p.onceAt > System.currentTimeMillis())
        assertEquals(7, p.hour)
    }

    @Test
    fun `普通聊天 不误判`() {
        assertNull(ScheduleTextParser.parse("每天都好累"))
        assertNull(ScheduleTextParser.parse("帮我打开微信"))
        assertNull(ScheduleTextParser.parse("今天天气怎么样"))
    }

    @Test
    fun `频率词说在句尾 也能认`() {
        val p = ScheduleTextParser.parse("晚上十点帮我清后台 每天")!!
        assertEquals(ScheduledTask.Repeat.DAILY, p.repeat)
        assertEquals(22, p.hour)
        assertTrue("句尾的频率词应被剥掉", !p.prompt.contains("每天"))
    }

    @Test
    fun `任务内容不能只剩一个动词之外的噪音`() {
        // 只有时间、没有要干的事 → 不建任务（否则会建出空任务）
        assertNull(ScheduleTextParser.parse("每天8点"))
    }
}
