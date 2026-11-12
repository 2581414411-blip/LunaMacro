package com.nexus.agent.ui.room

import java.time.LocalTime

/**
 * 时段档位（0.9.20 批次 D）。
 *
 * 五个值对应一天里**她该干什么**，不是「几点」——
 * 07:00 和 08:59 对她来说是同一件事（吃点东西），没必要拆成两个状态。
 */
enum class TimeSlot(val zh: String) {
    /** 06:00 ~ 07:00 醒了但还没起，屋里先亮起来 */
    DAWN("清晨"),

    /** 07:00 ~ 09:00 早饭 */
    MORNING("早上"),

    /** 09:00 ~ 12:00 / 14:00 ~ 18:00 白天该干嘛干嘛 */
    DAY("白天"),

    /** 12:00 ~ 14:00 午饭 + 午休 */
    NOON("中午"),

    /** 18:00 ~ 20:00 傍晚 */
    EVENING("傍晚"),

    /** 20:00 ~ 23:00 晚上，还没到该睡的点 */
    NIGHTFALL("晚上"),

    /** 23:00 ~ 06:00 必须睡觉 */
    NIGHT("深夜")
}

/**
 * 一个时段里「屋子该是什么样」（0.9.20 批次 D）。
 *
 * @property slot      时段
 * @property preferred 这段时间她**倾向于**做的事（做决定的候选池，顺序不重要）
 * @property forced    非空 = 强制状态，行为引擎**不许**覆盖它（只有深夜用）
 * @property lightCap  屋里亮度上限（0~1）。夜里压低，白天拉满
 * @property mealHint  这个时段和「吃」有关时的说法（给提示词用，可为 null）
 */
data class TimeRoutine(
    val slot: TimeSlot,
    val preferred: List<RoomBehavior>,
    val forced: RoomBehavior?,
    val lightCap: Float,
    val mealHint: String?
) {
    /** 深夜才强制睡觉。这一条是**硬约束**，写在数据里而不是散在各处 if 里 */
    val isAsleep: Boolean get() = forced == RoomBehavior.SLEEPING
}

/**
 * 真实时间 → 作息（0.9.20 批次 D）—— 纯函数，不碰 Compose、不碰 Android。
 *
 * ## 为什么用 `LocalTime` 而不是 `System.currentTimeMillis()`
 *
 * 判断的是「她这儿几点了」，不是「过了多少毫秒」。用时间戳就得自己算
 * `(millis / 3600000 + 8) % 24`，还得处理夏令时和用户改时区 —— 手写这套
 * 是本项目里最容易写错的一类代码（FlexiMem 的 `weekStart` 就是当年绕开的）。
 *
 * **`LocalTime` 需要 API 26**，而 minSdk 是 24，所以 `app/build.gradle.kts` 里
 * 打开了 core library desugaring。不开的话在 Android 7 上是
 * `NoClassDefFoundError`，不是「返回 0 点」—— 直接崩。
 *
 * ## 为什么边界写「左闭右开」
 *
 * 07:00 应该已经是早上、09:00 应该已经结束。所以判断一律 `start <= t < end`。
 * 深夜是唯一跨零点的段（23:00 ~ 06:00），靠 `t >= 1380 || t < 360` 表达，
 * 不能写成 `1380 <= t < 360`（那样永远 false，她会整夜不睡）。
 */
object TimeRoutineManager {

    // ------------------------------------------------------------ 边界（分钟数）

    const val MINUTES_PER_DAY = 24 * 60

    const val DAWN_START = 6 * 60
    const val MORNING_START = 7 * 60
    const val MORNING_END = 9 * 60
    const val NOON_START = 12 * 60
    const val NOON_END = 14 * 60
    const val EVENING_START = 18 * 60
    const val EVENING_END = 20 * 60
    const val NIGHT_START = 23 * 60
    const val NIGHT_END = 6 * 60

    /** 各时段的亮度上限。深夜 0.34 —— 再低用户会以为屏幕坏了（0.9.17 踩过） */
    const val LIGHT_NIGHT = 0.34f
    const val LIGHT_DAWN = 0.62f
    const val LIGHT_EVENING = 0.82f
    const val LIGHT_FULL = 1.00f

    /**
     * 深夜的强制状态。
     *
     * 用 `forced` 而不是「preferred 里塞一个 SLEEPING」：两者语义不同 ——
     * 早上「可能想吃饭」可以被行为引擎换掉，深夜「必须睡」不能被换掉。
     * 混在一起写，某个随机分支就会让她凌晨三点在屋里散步。
     */
    private val ROUTINES: Map<TimeSlot, TimeRoutine> = mapOf(
        TimeSlot.DAWN to TimeRoutine(
            slot = TimeSlot.DAWN,
            preferred = listOf(RoomBehavior.IDLE, RoomBehavior.SITTING),
            forced = null,
            lightCap = LIGHT_DAWN,
            mealHint = null
        ),
        TimeSlot.MORNING to TimeRoutine(
            slot = TimeSlot.MORNING,
            preferred = listOf(RoomBehavior.EATING, RoomBehavior.IDLE),
            forced = null,
            lightCap = LIGHT_FULL,
            mealHint = "早饭"
        ),
        TimeSlot.DAY to TimeRoutine(
            slot = TimeSlot.DAY,
            preferred = listOf(
                RoomBehavior.IDLE, RoomBehavior.WALKING, RoomBehavior.SITTING,
                RoomBehavior.BROWSING
            ),
            forced = null,
            lightCap = LIGHT_FULL,
            mealHint = null
        ),
        TimeSlot.NOON to TimeRoutine(
            slot = TimeSlot.NOON,
            preferred = listOf(RoomBehavior.EATING, RoomBehavior.SITTING),
            forced = null,
            lightCap = LIGHT_FULL,
            mealHint = "午饭"
        ),
        TimeSlot.EVENING to TimeRoutine(
            slot = TimeSlot.EVENING,
            preferred = listOf(RoomBehavior.IDLE, RoomBehavior.WALKING, RoomBehavior.BROWSING),
            forced = null,
            lightCap = LIGHT_EVENING,
            mealHint = "晚饭"
        ),
        TimeSlot.NIGHTFALL to TimeRoutine(
            slot = TimeSlot.NIGHTFALL,
            preferred = listOf(
                RoomBehavior.IDLE, RoomBehavior.SITTING, RoomBehavior.WALKING,
                RoomBehavior.BROWSING
            ),
            forced = null,
            lightCap = LIGHT_EVENING,
            mealHint = null
        ),
        TimeSlot.NIGHT to TimeRoutine(
            slot = TimeSlot.NIGHT,
            // 候选池是空的：深夜她不「选」做什么，只有 forced 这一条路。
            // 把 SLEEPING 也塞进候选池会让「强制」和「倾向」两件事混成一件，
            // 于是某个随机分支真的会把凌晨三点的她叫起来散步。
            preferred = emptyList(),
            forced = RoomBehavior.SLEEPING,
            lightCap = LIGHT_NIGHT,
            mealHint = null
        )
    )

    /** 分钟数 → 时段。所有边界判断的**唯一**出口，别在别处再写一遍 if */
    fun slotOf(minuteOfDay: Int): TimeSlot {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return when {
            m >= NIGHT_START || m < NIGHT_END -> TimeSlot.NIGHT
            m < MORNING_START -> TimeSlot.DAWN
            m < MORNING_END -> TimeSlot.MORNING
            m < NOON_START -> TimeSlot.DAY
            m < NOON_END -> TimeSlot.NOON
            m < EVENING_START -> TimeSlot.DAY
            m < EVENING_END -> TimeSlot.EVENING
            else -> TimeSlot.NIGHTFALL
        }
    }

    fun routineOf(time: LocalTime): TimeRoutine {
        val slot = slotOf(time.hour * 60 + time.minute)
        return ROUTINES.getValue(slot)
    }

    fun routineOfMinute(minuteOfDay: Int): TimeRoutine = ROUTINES.getValue(slotOf(minuteOfDay))

    // ------------------------------------------------------------ 给提示词/气泡用

    /**
     * 一句话说清「现在几点」。
     *
     * 只到分钟，不带「上午/下午」这种系统腔 —— 她要能直接照着说
     * 「都两点了还不睡」，而不是「当前时间为 02:13」。
     */
    fun clockOf(time: LocalTime): String {
        val h = time.hour
        val m = time.minute
        return when {
            h < 6 -> "半夜${h}点" + if (m > 0) "$m 分" else ""
            h < 9 -> "早上${h}点" + if (m > 0) "$m 分" else ""
            h == 12 -> "中午十二点" + if (m > 0) "$m 分" else ""
            h < 18 -> "下午${h - 12}点" + if (m > 0) "$m 分" else ""
            h < 23 -> "晚上${h - 12}点" + if (m > 0) "$m 分" else ""
            else -> "夜里${h - 12}点" + if (m > 0) "$m 分" else ""
        }
    }

    /**
     * 从 [time] 到下一个时段边界的分钟数（1 ~ 1440）。
     *
     * 用来安排「下一次重新判作息」的时机：睡觉不该等到下一次每分钟轮询，
     * 23:00 一到就该躺下。
     */
    fun minutesToNextBoundary(time: LocalTime): Int {
        val m = time.hour * 60 + time.minute
        val boundaries = listOf(
            DAWN_START, MORNING_START, MORNING_END, NOON_START,
            NOON_END, EVENING_START, EVENING_END, NIGHT_START, NIGHT_END
        )
        val next = boundaries.map { b ->
            val d = b - m
            if (d > 0) d else d + MINUTES_PER_DAY
        }.min()
        return next.coerceIn(1, MINUTES_PER_DAY)
    }

    /**
     * 她此刻「在干嘛」的中文，写在抬头显示上。
     *
     * 和 [RoomBehavior.zh] 的区别：这里带上时段色彩（「在窗边啃早饭」），
     * 抬头显示读起来才像她在过日子，而不是状态机在报状态。
     */
    fun sceneZh(routine: TimeRoutine, behavior: RoomBehavior): String = when (behavior) {
        RoomBehavior.SLEEPING -> "睡着了"
        RoomBehavior.EATING -> "在桌边吃${routine.mealHint ?: "东西"}"
        RoomBehavior.SITTING -> if (routine.slot == TimeSlot.NOON) "在午休" else "坐着发呆"
        RoomBehavior.WALKING -> "在屋里转悠"
        RoomBehavior.BROWSING -> "在电视前刷视频"
        RoomBehavior.CHATTING -> "跟你说话"
        RoomBehavior.IDLE -> when (routine.slot) {
            TimeSlot.NIGHT -> "困得不行"
            TimeSlot.DAWN, TimeSlot.MORNING -> "刚醒"
            TimeSlot.EVENING -> "在看窗外"
            else -> "发着呆"
        }
    }

    /**
     * 点她时喂给角色模型的「触发句」。
     *
     * 这不是用户说的话，是**场景**：模型需要知道「他点了你一下」「现在几点」
     * 「你本来在干嘛」，否则她会回一句无头无尾的「你好呀」——
     * 那一瞬间房间就从「活的」变回「聊天框」了。
     */
    fun triggerOf(time: LocalTime, routine: TimeRoutine, behavior: RoomBehavior): String {
        val doing = sceneZh(routine, behavior)
        return "（他把指头戳到你身上了。现在是${clockOf(time)}，你${doing}。）"
    }

    /**
     * 模型不可用时的兜底台词。
     *
     * **必须有**：房间在离线状态下也要有反应。一句本地台词虽然不如模型自然，
     * 但「点了没反应」会让人以为界面死了 —— 那比台词笨得多。
     *
     * 选取规则：[seed] 取模，同一个场景多次点击不会永远是同一句，
     * 又不需要随机数（单测能钉住）。
     */
    fun fallbackLine(routine: TimeRoutine, behavior: RoomBehavior, seed: Int): String {
        val lines = when {
            routine.isAsleep -> listOf(
                "唔……你怎么进来了……",
                "几点了……我还没睡够呢。",
                "别开灯……"
            )
            behavior == RoomBehavior.EATING -> listOf(
                "我正吃着呢，你要不要也来一口？",
                "你也来吃点吧，别站着看。",
                "唔（嘴里塞满了）……等我咽下去。"
            )
            behavior == RoomBehavior.SITTING -> listOf(
                "站着干嘛，坐啊。",
                "我就歇会儿，没偷懒。",
                "嗯？你也累了？"
            )
            behavior == RoomBehavior.BROWSING -> listOf(
                "哎你快看这个，笑死我了。",
                "哈哈这个猫也太蠢了吧。",
                "我刚刷到一个特别离谱的，等会儿发你。",
                "别吵别吵，这段快到了。"
            )
            behavior == RoomBehavior.WALKING -> listOf(
                "哎，你什么时候进来的？",
                "我正走着呢，差点撞到你。",
                "屋子有点闷，走两步。"
            )
            routine.slot == TimeSlot.MORNING -> listOf(
                "早啊，你起这么早？",
                "刚醒……你说话小点声。",
                "早饭吃了吗？"
            )
            routine.slot == TimeSlot.EVENING -> listOf(
                "你来了，看窗外，天都黑了。",
                "今天过得挺快的吧。",
                "要不要一起待会儿。"
            )
            else -> listOf(
                "你怎么突然进来了？",
                "嗯？我在发呆，被你打断了。",
                "站这么近干嘛。",
                "你靠这么近，有事就说。"
            )
        }
        val i = ((seed % lines.size) + lines.size) % lines.size
        return lines[i]
    }
}
