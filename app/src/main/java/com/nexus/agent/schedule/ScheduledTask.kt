package com.nexus.agent.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 一条定时任务。
 *
 * 设计取舍：
 * - 只存「什么时候跑 + 跑什么」，不存执行计划。每次触发后按 [repeat] 重新算下一次，
 *   这样即使手机中途关机、时区变了，重启后重排也不会错乱。
 * - 重复规则的表达刻意做窄（每天 / 每周几 / 只一次），比 cron 简单但覆盖真实需求：
 *   签到、清后台、看行情、定时播报。
 */
data class ScheduledTask(
    /** 稳定 id（UUID），同时用作 AlarmManager 的 PendingIntent requestCode */
    val id: String,
    /** 展示名（用户不填时取任务内容前 12 字） */
    val title: String,
    /** 下发给 Agent 的自然语言任务 */
    val prompt: String,
    val repeat: Repeat,
    /** 0~23 */
    val hour: Int,
    /** 0~59 */
    val minute: Int,
    /** 每周几（ISO：1=周一 … 7=周日），仅 WEEKLY 有意义 */
    val daysOfWeek: List<Int>,
    /** 绝对触发时间（毫秒），仅 ONCE 有意义 */
    val onceAt: Long,
    val enabled: Boolean,
    /** 上次执行时间（0 = 从未执行） */
    val lastRunAt: Long = 0L,
    /** 上次结果：""（未跑过）/ ok / skip / fail */
    val lastStatus: String = "",
    /** 上次结果摘要（失败原因或回复节选） */
    val lastSummary: String = ""
) {

    enum class Repeat {
        /** 每天固定时刻 */
        DAILY,

        /** 每周若干天的固定时刻 */
        WEEKLY,

        /** 只跑一次 */
        ONCE
    }

    // ------------------------------------------------------------------ 时间推算

    /**
     * 下一次触发时间戳（毫秒）；返回 0 表示「不会再触发」。
     *
     * @param from 从这个时刻之后开始找（默认现在）。测试时传固定值即可。
     */
    fun nextTriggerAt(from: Long = System.currentTimeMillis()): Long {
        if (!enabled) return 0L
        return when (repeat) {
            Repeat.ONCE -> if (onceAt > from) onceAt else 0L
            Repeat.DAILY -> nextDailyAt(from)
            Repeat.WEEKLY -> nextWeeklyAt(from)
        }
    }

    private fun nextDailyAt(from: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = from }
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        // 今天这个点已经过了 → 顺延到明天同一时刻
        if (c.timeInMillis <= from) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun nextWeeklyAt(from: Long): Long {
        if (daysOfWeek.isEmpty()) return 0L
        // 从今天开始往后找 8 天：最多覆盖到「今天这个点已过、但今天在名单里」的情况
        for (offset in 0..7) {
            val c = Calendar.getInstance().apply { timeInMillis = from }
            c.add(Calendar.DAY_OF_YEAR, offset)
            c.set(Calendar.HOUR_OF_DAY, hour)
            c.set(Calendar.MINUTE, minute)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (!daysOfWeek.contains(isoDayOfWeek(c))) continue
            if (c.timeInMillis > from) return c.timeInMillis
        }
        return 0L
    }

    /** 规则的人类可读描述，如「每天 08:00」「每周一/三 20:00」 */
    fun repeatText(): String = when (repeat) {
        Repeat.DAILY -> "每天 $hourText"
        Repeat.WEEKLY -> "每周${daysOfWeek.sorted().joinToString("/") { WEEK_CN[it - 1] }} $hourText"
        Repeat.ONCE -> "只一次"
    }

    val hourText: String
        get() = "%02d:%02d".format(hour, minute)

    // ------------------------------------------------------------------ 序列化

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("prompt", prompt)
        put("repeat", repeat.name)
        put("hour", hour)
        put("minute", minute)
        put("days", JSONArray(daysOfWeek))
        put("onceAt", onceAt)
        put("enabled", enabled)
        put("lastRunAt", lastRunAt)
        put("lastStatus", lastStatus)
        put("lastSummary", lastSummary)
    }

    companion object {
        /** ISO 星期中文简称，索引 = ISO 星期 - 1 */
        val WEEK_CN = arrayOf("一", "二", "三", "四", "五", "六", "日")

        /**
         * 从 JSON 还原；字段缺失一律用安全默认值，坏数据不能把整张表带崩。
         */
        fun fromJson(o: JSONObject): ScheduledTask {
            val repeat = runCatching { Repeat.valueOf(o.optString("repeat", "DAILY")) }
                .getOrDefault(Repeat.DAILY)
            val days = mutableListOf<Int>()
            o.optJSONArray("days")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optInt(i)
                    if (d in 1..7) days.add(d)
                }
            }
            return ScheduledTask(
                id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                title = o.optString("title"),
                prompt = o.optString("prompt"),
                repeat = repeat,
                hour = o.optInt("hour", 8).coerceIn(0, 23),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                daysOfWeek = days.distinct().sorted(),
                onceAt = o.optLong("onceAt", 0L),
                enabled = o.optBoolean("enabled", true),
                lastRunAt = o.optLong("lastRunAt", 0L),
                lastStatus = o.optString("lastStatus"),
                lastSummary = o.optString("lastSummary")
            )
        }

        /** Calendar.DAY_OF_WEEK（周日=1）→ ISO 星期（周一=1 … 周日=7） */
        fun isoDayOfWeek(c: Calendar): Int = when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.SUNDAY -> 7
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            else -> 6
        }
    }
}
