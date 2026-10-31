package com.nexus.agent.schedule

import java.util.Calendar

/**
 * 从一句中文里解析出「定时任务」。
 *
 * 支持的说法（实测覆盖日常口语）：
 * - 每天早上 8 点帮我签到
 * - 每天 8:30 提醒我喝水
 * - 每周一、周四晚上 9 点清理后台
 * - 明天早上七点半叫我起床
 *
 * 解析不出来的（返回 null）就老实交给大模型，绝不硬猜 ——
 * 定时任务一旦猜错时间，用户第二天才发现「怎么没跑」。
 */
object ScheduleTextParser {

    /** 解析结果（还没带 id / 展示名，由调用方补） */
    data class Parsed(
        val prompt: String,
        val repeat: ScheduledTask.Repeat,
        val hour: Int,
        val minute: Int,
        val days: List<Int>,
        /** 仅 ONCE 有值 */
        val onceAt: Long
    )

    /** 时段词 → 12 小时制的换算规则 */
    private val PERIODS = listOf(
        "凌晨", "早上", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里"
    )

    /**
     * 时刻：`8点` / `8点半` / `8点30` / `8:30` / `晚上9点`
     */
    private val TIME_RE = Regex(
        "(${PERIODS.joinToString("|")})?\\s*(\\d{1,2})\\s*(?:点\\s*(半|\\d{1,2})?|[时:：]\\s*(\\d{1,2})?)"
    )

    /** 「每周一、周四」里的星期清单（允许夹着「周」字，如「每周一、周四」） */
    private val WEEK_RE = Regex("每(?:周|星期|礼拜)\\s*([一二三四五六日天和及与、,，\\s周]{1,16})")

    /** 中文数字（用户口头说「八点」时常见） */
    private val CN_NUM = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    fun parse(text: String): Parsed? {
        val raw = text.trim()
        if (raw.isBlank()) return null

        // 1) 先认「重复方式」——没有每天都/每周几这类词就不算定时任务
        val repeat: ScheduledTask.Repeat
        var days: List<Int> = emptyList()
        val isWeekly = raw.contains("每周") || raw.contains("每星期") || raw.contains("每礼拜")
        when {
            isWeekly -> {
                repeat = ScheduledTask.Repeat.WEEKLY
                days = parseWeekDays(raw)
                if (days.isEmpty()) return null
            }

            raw.contains("每天") || raw.contains("每日") || raw.contains("天天") ->
                repeat = ScheduledTask.Repeat.DAILY

            raw.contains("明天") || raw.contains("今晚") || raw.contains("今天") ||
                raw.contains("后天") -> repeat = ScheduledTask.Repeat.ONCE

            else -> return null
        }

        // 2) 时刻
        val timeMatch = TIME_RE.find(normalizeNumbers(raw)) ?: return null
        val hour = adjustHour(
            period = timeMatch.groupValues[1],
            rawHour = timeMatch.groupValues[2].toIntOrNull() ?: return null
        )
        val minute = when {
            timeMatch.groupValues[3] == "半" -> 30
            timeMatch.groupValues[3].isNotBlank() -> timeMatch.groupValues[3].toIntOrNull() ?: 0
            timeMatch.groupValues[4].isNotBlank() -> timeMatch.groupValues[4].toIntOrNull() ?: 0
            else -> 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null

        // 3) 一次性任务要算出具体日期
        val onceAt = if (repeat == ScheduledTask.Repeat.ONCE) {
            onceTimestamp(raw, hour, minute) ?: return null
        } else 0L

        // 4) 任务内容：优先取时刻之后那句；时刻在句尾（「帮我签到 每天早上8点」）时
        //    退回去把频率词/时刻词整段剔掉再取。剔除后为空说明用户只说了时间，
        //    那就不是任务，直接不认（否则会建出一条空任务）。
        val normalized = normalizeNumbers(raw)
        val after = normalized.substring(timeMatch.range.last + 1)
        var prompt = cleanPrompt(after)
        if (prompt.isBlank()) {
            prompt = cleanPrompt(
                normalized
                    .replace(TIME_RE, "")
                    .replace(WEEK_RE, "")
                    .replace(Regex("每天|每日|天天|明天|今天|后天|今晚|明早"), "")
            )
        }
        if (prompt.length < 2) return null

        return Parsed(prompt, repeat, hour, minute, days, onceAt)
    }

    // ------------------------------------------------------------------ 内部

    /** 把「八点」「十二点」这类中文数字换成阿拉伯数字，方便统一用正则抓 */
    private fun normalizeNumbers(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // 「十」单独出现（十点）或「十X」（十二点）
            if (c == '十') {
                val next = text.getOrNull(i + 1)
                val nextVal = next?.let { CN_NUM[it] }
                if (nextVal != null && nextVal in 1..9) {
                    sb.append(10 + nextVal)
                    i += 2
                } else {
                    sb.append(10)
                    i += 1
                }
                continue
            }
            val v = CN_NUM[c]
            if (v != null && v in 1..9) {
                // 只替换紧跟在「点/时/:/：」前面的那个字，避免把「一起」换成「1起」
                val next = text.getOrNull(i + 1)
                if (next == '点' || next == '时' || next == ':' || next == '：') {
                    sb.append(v)
                } else {
                    sb.append(c)
                }
                i += 1
                continue
            }
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }

    private fun adjustHour(period: String, rawHour: Int): Int = when (period) {
        "凌晨" -> if (rawHour == 12) 0 else rawHour
        "中午" -> if (rawHour in 1..10) rawHour + 12 else rawHour
        "下午", "傍晚", "晚上", "夜里" -> if (rawHour in 1..11) rawHour + 12 else rawHour
        else -> rawHour
    }

    private fun parseWeekDays(text: String): List<Int> {
        val m = WEEK_RE.find(text) ?: return emptyList()
        val seg = m.groupValues[1]
        val out = mutableListOf<Int>()
        seg.forEach { c ->
            when (c) {
                '一' -> if (1 !in out) out.add(1)
                '二' -> if (2 !in out) out.add(2)
                '三' -> if (3 !in out) out.add(3)
                '四' -> if (4 !in out) out.add(4)
                '五' -> if (5 !in out) out.add(5)
                '六' -> if (6 !in out) out.add(6)
                '日', '天' -> if (7 !in out) out.add(7)
                else -> Unit
            }
        }
        return out.sorted()
    }

    /** 「明天 / 今天 / 后天 + 时刻」→ 绝对时间戳 */
    private fun onceTimestamp(text: String, hour: Int, minute: Int): Long? {
        val offsetDays = when {
            text.contains("后天") -> 2
            text.contains("明天") || text.contains("今晚") || text.contains("明早") -> 1
            else -> 0
        }
        val c = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 「今天 7 点」而现在已经 9 点 → 顺延到明天，别给个过去的时间
        if (c.timeInMillis <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    private val LEAD_NOISE = listOf(
        "帮我", "给我", "替我", "请你", "请", "你", "麻烦", "定时", "自动", "记得", "要", "就在", "然后", "，", "。",
        "、", " ", "：", ":"
    )

    private val TAIL_NOISE = listOf(
        "吧", "啊", "呢", "哦", "。", "！", "!", "，", "~",
        // 用户常把频率词甩在句尾：「帮我清后台 每天」
        "每天", "每日", "每周"
    )

    private fun cleanPrompt(text: String): String {
        var s = text.trim()
        // 反复剥前缀（「帮我定时」要剥两次）
        var changed = true
        while (changed) {
            changed = false
            for (p in LEAD_NOISE) {
                if (s.startsWith(p) && s.length > p.length) {
                    s = s.substring(p.length).trim()
                    changed = true
                }
            }
        }
        changed = true
        while (changed) {
            changed = false
            for (p in TAIL_NOISE) {
                if (s.endsWith(p) && s.length > p.length) {
                    s = s.dropLast(p.length).trim()
                    changed = true
                }
            }
        }
        return s
    }
}
