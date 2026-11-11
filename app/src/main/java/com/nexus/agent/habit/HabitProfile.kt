package com.nexus.agent.habit

import java.util.Calendar

/** 某个应用的使用概况 */
data class AppStat(
    val name: String,
    /** 事件条数（进界面次数 + 点击次数），反映「碰它的频繁程度」 */
    val count: Int,
    /** 估算停留分钟数 */
    val minutes: Int
)

/** 「A 之后马上打开了 B」 */
data class PathStat(val from: String, val to: String, val count: Int)

/** 「在某个应用里反复点某个按钮」 */
data class TapStat(val app: String, val label: String, val count: Int)

/**
 * 学到的习惯画像（0.9.11）。
 *
 * 注意这份东西的定位：它是**她自己的印象**，不是给用户看的报表。
 * 所以 [brief] 一定要写成「相处久了自然知道」的口吻，
 * 而不是「统计显示您本周使用微信 47 次」—— 后者是监控，前者才是陪伴。
 */
data class HabitProfile(
    val generatedAt: Long = System.currentTimeMillis(),
    /** 覆盖了几天数据 */
    val days: Int = 0,
    /** 一共多少条原始记录 */
    val totalEvents: Int = 0,
    val topApps: List<AppStat> = emptyList(),
    /** 最活跃的几个整点，降序 */
    val peakHours: List<Int> = emptyList(),
    val paths: List<PathStat> = emptyList(),
    val taps: List<TapStat> = emptyList(),
    /** 一段自然语言概述，直接进提示词 */
    val brief: String = ""
) {
    val isEmpty: Boolean get() = totalEvents <= 0 || brief.isBlank()

    /**
     * 注入提示词的那一段。
     *
     * 三条约束写在段尾，缺一条都会让她的表现从「熟」变成「怪」：
     * 别说「根据我的记录」、别逐条念、别在她本来就不知道的时候硬提。
     */
    fun promptBlock(): String {
        if (isEmpty) return ""
        return buildString {
            append("【你平时留意到的他的习惯（你自己看在眼里的，不是他告诉你的）】\n")
            append(brief.trim()).append("\n")
            append("- 这些是你跟他待久了自然知道的，可以顺口带出来（「你又刷上了」），")
            append("但别复述、别说「根据我的记录」「统计显示」，也别一次全倒出来。\n")
            append("- 如果他正在做的和习惯不一致（比如这个点他本该在忙），可以好奇一句。\n")
        }
    }
}

/**
 * 把原始的「他点了什么」聚合成「他是个什么样的人」（0.9.11）。
 *
 * 全程纯函数、零网络、零 Token：先把统计做扎实，她才不会说出
 * 「你最近好像很爱用微信」这种等于没说的话。
 */
object HabitLearner {

    /** 相邻两条记录超过这个间隔，就不算同一次「使用」了 */
    private const val SAME_SESSION_GAP_MS = 5 * 60 * 1000L

    /** 一条记录最多折算多少停留时间（防止挂机把分钟数撑爆） */
    private const val MAX_SLICE_MIN = 5

    /** A→B 路径要求两次打开间隔不超过 10 分钟，否则只是「今天先开了 A 后开了 B」 */
    private const val PATH_GAP_MS = 10 * 60 * 1000L

    /** 时段划分：凌晨 / 早上 / 上午 / 下午 / 晚上 */
    private fun bandOf(hour: Int): String = when (hour) {
        in 0..5 -> "凌晨"
        in 6..8 -> "早上"
        in 9..11 -> "上午"
        in 12..17 -> "下午"
        in 18..23 -> "晚上"
        else -> "深夜"
    }

    private fun hourOf(at: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = at
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    /**
     * 主入口。
     *
     * @param events 已脱敏的原始记录（[HabitPrivacy.screen] 的产物）
     */
    fun learn(events: List<HabitEvent>): HabitProfile {
        val list = events.filter { it.at > 0 && it.app.isNotBlank() }.sortedBy { it.at }
        if (list.isEmpty()) return HabitProfile()

        // ---- 1. 应用频次 + 停留时长 ----
        val counts = LinkedHashMap<String, Int>()
        val minutes = LinkedHashMap<String, Int>()
        for (e in list) {
            counts[e.app] = (counts[e.app] ?: 0) + 1
        }
        for (i in list.indices) {
            val cur = list[i]
            val next = list.getOrNull(i + 1)
            val gap = if (next == null) 0L else (next.at - cur.at).coerceAtLeast(0L)
            val slice = if (gap in 1..SAME_SESSION_GAP_MS) {
                (gap / 60_000L).toInt().coerceIn(0, MAX_SLICE_MIN)
            } else {
                // 最后一条 / 断档：按 1 分钟兜底，避免「用过但显示 0 分钟」的怪结论
                1
            }
            minutes[cur.app] = (minutes[cur.app] ?: 0) + slice
        }
        val topApps = counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(6)
            .map { AppStat(it.key, it.value, minutes[it.key] ?: 0) }

        // ---- 2. 时段分布（只看「进界面」，点击太密会把曲线抹平）----
        val hist = IntArray(24)
        for (e in list) {
            if (e.kind == HabitEvent.KIND_OPEN) hist[hourOf(e.at)]++
        }
        val peakHours = hist.withIndex()
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<IndexedValue<Int>> { it.value }.thenBy { it.index })
            .take(3)
            .map { it.index }

        // ---- 3. 常用路径：A 之后马上开 B ----
        val pathCount = LinkedHashMap<String, Int>()
        for (i in list.indices) {
            val a = list[i]
            val b = list.getOrNull(i + 1) ?: continue
            if (a.kind != HabitEvent.KIND_OPEN || b.kind != HabitEvent.KIND_OPEN) continue
            if (a.app == b.app) continue
            if (b.at - a.at > PATH_GAP_MS) continue
            val key = a.app + "\u0000" + b.app
            pathCount[key] = (pathCount[key] ?: 0) + 1
        }
        val paths = pathCount.entries
            .filter { it.value >= 2 }
            .sortedByDescending { it.value }
            .take(3)
            .map {
                val parts = it.key.split("\u0000")
                PathStat(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, it.value)
            }

        // ---- 4. 常用动作：在哪个应用里老点哪个按钮 ----
        val tapCount = LinkedHashMap<String, Int>()
        for (e in list) {
            if (e.kind != HabitEvent.KIND_TAP) continue
            val label = e.label.trim()
            if (label.isEmpty()) continue
            val key = e.app + "\u0000" + label
            tapCount[key] = (tapCount[key] ?: 0) + 1
        }
        val taps = tapCount.entries
            .filter { it.value >= 3 }
            .sortedByDescending { it.value }
            .take(4)
            .map {
                val parts = it.key.split("\u0000")
                TapStat(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, it.value)
            }

        val days = distinctDays(list)
        val profile = HabitProfile(
            days = days,
            totalEvents = list.size,
            topApps = topApps,
            peakHours = peakHours,
            paths = paths,
            taps = taps
        )
        return profile.copy(brief = composeBrief(profile, list))
    }

    private fun distinctDays(events: List<HabitEvent>): Int {
        val set = HashSet<Long>(8)
        val cal = Calendar.getInstance()
        for (e in events) {
            cal.timeInMillis = e.at
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            set.add(cal.timeInMillis)
        }
        return set.size
    }

    /**
     * 把统计拼成人话。
     *
     * 句子刻意写成她自己的口吻（「你几乎每天……」「一有空就……」），
     * 只陈述**足够稳定的模式**：单次行为不写，否则整段就变成流水账。
     */
    private fun composeBrief(p: HabitProfile, events: List<HabitEvent>): String {
        if (p.topApps.isEmpty()) return ""
        val sb = StringBuilder()

        val names = p.topApps.take(3).joinToString("、") { it.name }
        sb.append("这些天你摸得最多的是").append(names)
        val head = p.topApps.first()
        if (p.days >= 2 && head.count >= p.days) {
            sb.append("，其中「").append(head.name).append("」几乎天天都开")
        }
        sb.append("。\n")

        // 时段：找出峰值所在的那个「带」，再说这个带里最常开的应用
        if (p.peakHours.isNotEmpty()) {
            val peak = p.peakHours.first()
            val band = bandOf(peak)
            val hourLabel = String.format("%d 点前后", peak)
            val appInBand = mostUsedInBand(events, band)
            sb.append("你").append(band).append("最活跃（").append(hourLabel).append("）")
            if (appInBand != null) sb.append("，那个点通常是在「").append(appInBand).append("」")
            sb.append("。\n")
        }

        // 周末 vs 工作日：只对比「最常用应用」是否换了一个，别做复杂判断
        val weekendTop = topAppOn(events) { at ->
            val c = Calendar.getInstance()
            c.timeInMillis = at
            val d = c.get(Calendar.DAY_OF_WEEK)
            d == Calendar.SATURDAY || d == Calendar.SUNDAY
        }
        val weekdayTop = topAppOn(events) { at ->
            val c = Calendar.getInstance()
            c.timeInMillis = at
            val d = c.get(Calendar.DAY_OF_WEEK)
            d != Calendar.SATURDAY && d != Calendar.SUNDAY
        }
        if (weekendTop != null && weekdayTop != null && weekendTop != weekdayTop) {
            sb.append("工作日你多在「").append(weekdayTop)
                .append("」，周末换成「").append(weekendTop).append("」。\n")
        }

        if (p.paths.isNotEmpty()) {
            val top = p.paths.take(2).joinToString("，") { "打开${it.from}后接着看${it.to}" }
            sb.append("你常这么连着走：").append(top).append("。\n")
        }

        if (p.taps.isNotEmpty()) {
            val top = p.taps.take(2).joinToString("，") { "在${it.app}里点「${it.label}」" }
            sb.append("手最熟的动作是").append(top).append("。\n")
        }

        if (sb.length > BRIEF_MAX) {
            return sb.substring(0, BRIEF_MAX).trimEnd() + "…"
        }
        return sb.toString().trimEnd()
    }

    /** 某个时段带里用得最多的应用 */
    private fun mostUsedInBand(events: List<HabitEvent>, band: String): String? {
        val counts = LinkedHashMap<String, Int>()
        for (e in events) {
            if (bandOf(hourOf(e.at)) != band) continue
            counts[e.app] = (counts[e.app] ?: 0) + 1
        }
        return counts.entries.maxByOrNull { it.value }?.key
    }

    /** 满足条件那批记录里用得最多的应用 */
    private fun topAppOn(events: List<HabitEvent>, pick: (Long) -> Boolean): String? {
        val counts = LinkedHashMap<String, Int>()
        for (e in events) {
            if (!pick(e.at)) continue
            counts[e.app] = (counts[e.app] ?: 0) + 1
        }
        val top = counts.entries.maxByOrNull { it.value } ?: return null
        // 那条分支样本太少就不下结论（比如周末只记了 2 条）
        return if (top.value >= 3) top.key else null
    }

    /** 画像正文长度上限 —— 提示词里给习惯留这么多就够了，再多会盖过人格 */
    const val BRIEF_MAX = 420
}
