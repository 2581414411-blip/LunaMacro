package com.nexus.agent.copilot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Calendar

/**
 * 临场感（0.9.2 批次三）—— 让她知道「现在几点」「你手机还剩多少电」。
 *
 * ## 为什么这个比想象中重要
 *
 * 0.9.1 之前，提示词里关于时间的唯一信息来自聊天记录本身。
 * 于是你半夜两点跟她说「还不睡吗」，她会回一句「早点睡哦」——
 * 看起来对，其实她根本不知道现在是几点，只是顺着你的话接。
 * 一连问三天「今天几号」她能答出同一个日期，这就是「没活在当下」的味道。
 *
 * 电量同理：手机剩 5% 的时候，一句「你手机快没电了，先充上」比任何安慰都像真人。
 *
 * ## 设计约束
 *
 * - 取系统状态（[now]）和拼文案（[describe] / [presenceBlock]）**分开**：
 *   前者要 Context、要读系统服务；后者是纯函数，单测能覆盖所有时段与电量组合。
 * - 文案只给「事实 + 一句行为要求」，不给她编造空间（不写「今天天气」这种我们并不知道的事）。
 */
object PresenceSense {

    /** 一个时刻的现场快照 */
    data class Now(
        val hour: Int,
        val minute: Int,
        /** 电量百分比；null = 读不到（极少见，模拟器或异常） */
        val batteryPercent: Int? = null,
        val charging: Boolean = false
    ) {
        /** 23:00 ~ 04:59 算深夜 */
        val isLateNight: Boolean get() = hour >= 23 || hour < 5

        /** 22:00 ~ 22:59 算夜里（还没到「深夜」，但该收尾了） */
        val isNight: Boolean get() = hour >= 22 || hour < 5

        /** 低电量：<=20% 且没在充电 */
        val isLowBattery: Boolean get() = !charging && (batteryPercent ?: 100) <= 20

        /** 「23:30」这种两位格式 */
        val clock: String
            get() = "%02d:%02d".format(hour, minute)
    }

    /** 从系统取当前时间与电量（读 ACTION_BATTERY_CHANGED 的粘性广播，不注册接收者） */
    fun now(context: Context?): Now {
        val cal = Calendar.getInstance()
        var percent: Int? = null
        var charging = false
        if (context != null) {
            runCatching {
                val intent: Intent? = context.registerReceiver(
                    null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) {
                        percent = (level * 100 / scale).coerceIn(0, 100)
                    }
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        }
        return Now(
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            batteryPercent = percent,
            charging = charging
        )
    }

    /** 时段的中文说法 */
    fun periodOf(hour: Int): String = when (hour) {
        in 0..4 -> "凌晨"
        in 5..7 -> "清早"
        in 8..10 -> "上午"
        in 11..12 -> "中午"
        in 13..16 -> "下午"
        in 17..18 -> "傍晚"
        in 19..21 -> "晚上"
        else -> "深夜"
    }

    /**
     * 拼成一段事实描述 + 行为要求。
     *
     * 注意措辞：这里给她的是**事实**（「现在 23:30」），
     * 具体怎么回应交给她的性格 —— 硬写「说早点睡」会让所有时段听起来一个味。
     */
    fun describe(now: Now): String = buildString {
        append("现在是 ${now.clock}（${periodOf(now.hour)}）")
        val b = now.batteryPercent
        if (b != null) {
            if (now.charging) {
                append("，手机电量 $b%（在充电）")
            } else {
                append("，手机电量 $b%")
            }
        }
        append("。")
    }

    /**
     * 完整的「临场感」提示词段，直接进 System Prompt。
     *
     * 深夜和低电量这两个约束是**条件追加**的：白天空调房里 100% 电量的时候，
     * 硬塞一句「要催他睡觉」纯属污染上下文，模型反而会莫名其妙地关心你作息。
     */
    fun presenceBlock(now: Now): String = buildString {
        append("【现在你所在的世界】\n")
        append(describe(now)).append("\n")

        val hints = ArrayList<String>(3)
        when {
            now.isLateNight -> {
                hints.add("现在是深夜，你已经有困意了，说话比平时更懒、更短")
                hints.add("如果这一轮聊得下去，要自然地催他早点睡（别像打卡一样每句都提）")
            }
            now.isNight -> {
                hints.add("已经晚上了，可以问一句他今天过得怎么样，或者提醒别熬太晚")
            }
        }
        when {
            // 9% 及以下才是真的「个位数」—— 措辞要和数字对得上，
            // 不然 12% 也说「个位数」，用户一眼看出来是模板话。
            now.batteryPercent != null && now.batteryPercent <= 9 && !now.charging ->
                hints.add("他手机电量只剩个位数了，这一轮必须提醒他赶紧充电，长话短说")
            now.isLowBattery ->
                hints.add("他手机快没电了（还剩 ${now.batteryPercent}%），可以顺口提一句该充电了")
            now.charging && now.batteryPercent != null && now.batteryPercent < 40 ->
                hints.add("手机在充电了，不用提电量的事")
        }
        if (hints.isNotEmpty()) {
            hints.forEach { append("- ").append(it).append("\n") }
        }
        append("- 这些都是你此刻真实知道的，可以自然带出来；但别把时间电量当台词一句句念。\n")
    }

    /**
     * 给主动问候用的精简版：只留「现在是几点 + 该有的氛围」。
     *
     * 主动发消息没有对话上下文，把「手机电量」也塞进去容易变成一条充电提醒，
     * 反而丢了「她想你了」这个主次。
     */
    fun greetingTimeHint(now: Now): String = buildString {
        append("现在是 ${now.clock}（${periodOf(now.hour)}）。")
        when {
            now.isLateNight -> append("已经很晚了，你自己也困，但还惦记着他怎么还没睡。")
            now.isNight -> append("晚上了，你想起他今天过得怎么样。")
            now.hour in 11..13 -> append("到饭点了，你想起他可能又没好好吃饭。")
            now.hour in 5..9 -> append("刚醒，你想起他了。")
            else -> append("你在做自己的事，忽然想起他了。")
        }
    }
}
