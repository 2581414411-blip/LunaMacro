package com.nexus.agent.agent.tools

import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.schedule.ScheduleScheduler
import com.nexus.agent.schedule.ScheduleStore
import com.nexus.agent.schedule.ScheduleTextParser
import com.nexus.agent.schedule.ScheduledTask
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * schedule_task —— 建一个定时任务（到点自动执行）。
 *
 * 两条入口共用这一个工具：
 * 1. 大模型按 schema 调用（参数齐全）
 * 2. [com.nexus.agent.copilot.CopilotIntentParser] 从用户原话里解析出参数后调用
 *
 * 参数均可缺省：只给 `prompt` 时，工具会自己再跑一遍自然语言解析
 * （例如 prompt = 「每天早上8点帮我签到」也能建对），降低模型漏字段的概率。
 */
object ScheduleTool : Tool {

    override val name = "schedule_task"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("prompt", JSONObject().apply {
                put("type", "string")
                put("description", "到点要执行的任务，如「打开微信签到领每日奖励」")
            })
            put("repeat", JSONObject().apply {
                put("type", "string")
                put("description", "重复方式：daily（每天）/ weekly（每周）/ once（只一次），默认 daily")
            })
            put("time", JSONObject().apply {
                put("type", "string")
                put("description", "24 小时制时刻，如 08:00、21:30")
            })
            put("days", JSONObject().apply {
                put("type", "array")
                put("description", "repeat=weekly 时的星期，1=周一 … 7=周日，如 [1,4]")
                put("items", JSONObject().put("type", "integer"))
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "展示名，可省略（默认取任务内容前 12 字）")
            })
        })
        put("required", JSONArray().put("prompt"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val ctx = ShizukuManager.appContext() ?: return "创建失败：应用上下文未初始化"

        val promptRaw = args.str("prompt").ifBlank { args.str("text") }
        if (promptRaw.isBlank()) return "创建失败：缺少任务内容（prompt）"

        // 先按参数建；参数不全就用自然语言解析兜底
        val parsed = ScheduleTextParser.parse(promptRaw)
        val repeat = parseRepeat(args.str("repeat")) ?: parsed?.repeat ?: ScheduledTask.Repeat.DAILY
        val (hour, minute) = parseTime(args.str("time"))
            ?: parsed?.let { it.hour to it.minute }
            ?: return "创建失败：没看懂时间，请说「每天8点」或给 time=HH:mm"
        val days = parseDays(args["days"]) ?: parsed?.days ?: emptyList()
        val onceAt = if (repeat == ScheduledTask.Repeat.ONCE) {
            // 优先用调用方算好的绝对时间（「明天早上7点」必须落在明天）
            args.long("once_at")
                ?: parsed?.onceAt?.takeIf { it > 0L }
                ?: nextOccurrenceOf(hour, minute)
        } else 0L

        if (repeat == ScheduledTask.Repeat.WEEKLY && days.isEmpty()) {
            return "创建失败：每周任务要指定星期（days=[1,4]）"
        }
        if (hour !in 0..23 || minute !in 0..59) return "创建失败：时间不合法（$hour:$minute）"

        val task = ScheduledTask(
            id = UUID.randomUUID().toString(),
            title = args.str("title").ifBlank { promptRaw.take(12) },
            prompt = promptRaw,
            repeat = repeat,
            hour = hour,
            minute = minute,
            daysOfWeek = days.distinct().sorted(),
            onceAt = onceAt,
            enabled = true
        )
        if (!ScheduleStore.upsert(ctx, task)) {
            return "创建失败：定时任务已达 ${ScheduleStore.MAX_TASKS} 条上限，先删掉几条"
        }
        ScheduleScheduler.schedule(ctx, task)

        val whenText = task.repeatText() + if (repeat == ScheduledTask.Repeat.ONCE) {
            "（${formatOnce(onceAt)}）"
        } else ""
        val exact = if (ScheduleScheduler.canUseExactAlarms(ctx)) "" else "，注意：未授予精确闹钟，可能晚几分钟"
        return "已创建定时任务「${task.title}」：$whenText 执行「${task.prompt}」$exact"
    }

    // ------------------------------------------------------------------ 参数解析

    private fun Map<String, Any?>.str(key: String): String =
        (this[key] as? String)?.trim().orEmpty()

    private fun Map<String, Any?>.long(key: String): Long? = when (val v = this[key]) {
        is Number -> v.toLong().takeIf { it > 0L }
        is String -> v.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    private fun parseRepeat(raw: String): ScheduledTask.Repeat? = when (raw.lowercase()) {
        "daily", "day", "每天", "每日" -> ScheduledTask.Repeat.DAILY
        "weekly", "week", "每周" -> ScheduledTask.Repeat.WEEKLY
        "once", "one", "一次", "只一次" -> ScheduledTask.Repeat.ONCE
        else -> null
    }

    /** 支持 "08:00" / "8:00" / "8点30" */
    private fun parseTime(raw: String): Pair<Int, Int>? {
        if (raw.isBlank()) return null
        val m = Regex("(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2})?").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h !in 0..23 || min !in 0..59) return null
        return h to min
    }

    private fun parseDays(raw: Any?): List<Int>? {
        val list = mutableListOf<Int>()
        when (raw) {
            is List<*> -> raw.forEach { v ->
                val d = when (v) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
                if (d != null && d in 1..7) list.add(d)
            }

            is JSONArray -> for (i in 0 until raw.length()) {
                val d = raw.optInt(i)
                if (d in 1..7) list.add(d)
            }

            is String -> raw.split(",", "，", "、").forEach { s ->
                s.trim().toIntOrNull()?.takeIf { it in 1..7 }?.let { list.add(it) }
            }
        }
        return list.distinct().sorted().ifEmpty { null }
    }

    /** 「只一次」但没给日期时，取今天这个时间，过了就明天 */
    private fun nextOccurrenceOf(hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun formatOnce(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return "${c.get(Calendar.MONTH) + 1}月${c.get(Calendar.DAY_OF_MONTH)}日 " +
            "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
