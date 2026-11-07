package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.PersonaEngine
import kotlin.math.abs

/**
 * 情绪驱动引擎（0.9.2 批次一）。
 *
 * ## 为什么需要这个类
 *
 * `PersonaProfile.affinity` / `mood` 这两个字段 0.8.0 就在数据库里了，
 * `PersonaEngine.bumpAffinity()` / `updateMood()` 也写好了 —— 但**生产代码从来没调过它们**，
 * 只有单测调。结果就是：设置页那个「好感度 50」永远停在 50，心情永远「平静」。
 * 用户在 0.9.1 的反馈原话是「她还是没有活起来」，根因就在这里：
 * 一个不会因为你说好话而高兴、也不会因为你骂她而难过的角色，当然不像活的。
 *
 * 这个类只干一件小事：**每轮对话结束后，从用户那句话里读情绪，改好感度、改心情、落库。**
 *
 * ## 为什么不直接 +1 / -1
 *
 * 用户要求「普通聊天 +0.2」，但 `affinity` 是 Int 字段（改类型要写数据库迁移，
 * 而这一版不值得为一个小数点动表结构）。所以这里用**小数进位**：
 * 内存/偏好里留一个 `carry` 余数，累够 1 才真正写一次库。
 * 好处是五次普通聊天稳稳 +1，不会因为取整被吞掉。
 *
 * ## 为什么关键词表这么短
 *
 * 不用模型判情绪（每轮多一次请求，还慢）。关键词表只认「明确到你不会误判」的词：
 * 「谢谢」「好棒」是夸，「滚」「烦死了」是骂，其余全算普通。
 * 宁可漏判（普通聊天也是 +0.2，不亏），不要误判 —— 把「你好烦啊我在加班」当成真心讨厌，
 * 好感度掉得莫名其妙，比不涨更伤。
 */
object EmotionEngine {

    private const val TAG = "EmotionEngine"

    /** 余数存在偏好里，进程重启不清零（否则每次冷启动都丢 0~0.9 点好感） */
    private const val PREFS = "luna_emotion"
    private const val KEY_CARRY = "affinity_carry"

    /** 夸奖 / 感谢 —— 明确的好感信号 */
    private val PRAISE_WORDS = listOf(
        "谢谢", "多谢", "感谢", "辛苦了", "辛苦啦", "好棒", "真棒", "太棒",
        "厉害", "牛啊", "牛逼", "给力", "太强", "靠谱", "优秀", "聪明",
        "喜欢你", "爱你", "可爱", "真好", "么么", "抱抱", "乖", "有你在真好",
        "你真好", "离不开你", "有你真好"
    )

    /** 抱怨 / 冷淡 —— 明确的负向信号 */
    private val COLD_WORDS = listOf(
        "滚", "烦死", "闭嘴", "废话", "垃圾", "没用", "讨厌", "笨蛋", "蠢",
        "傻", "别烦我", "不想聊", "离我远点", "没意思", "无聊", "少管",
        "关你屁事", "闭嘴吧", "你懂什么", "废物"
    )

    /**
     * 问句里带「滚」的例外：「滚去睡觉」这种其实是关心。
     * 命中这里就当普通聊天，不扣分。
     */
    private val SOFT_EXCEPTIONS = listOf("滚去睡", "滚去休息", "滚去吃饭")

    /** 用户那句话的三种情绪档 */
    enum class Signal(val label: String) {
        PRAISE("被夸"),
        COLD("被凶"),
        NEUTRAL("普通")
    }

    /** 好感度决定的三档语气 */
    enum class Tier {
        /** < 30：冷淡、简短、懒得管 */
        COLD,

        /** 30 ~ 79：正常礼貌 */
        NORMAL,

        /** >= 80：亲昵、会关心人、会主动分享 */
        CLOSE
    }

    // ------------------------------------------------------------ 判定（纯函数，单测覆盖）

    /** 情绪判定：先查冷淡（否定更强烈），再查夸奖，都不中就是普通 */
    fun signalOf(text: String): Signal {
        val t = text.trim()
        if (t.isEmpty()) return Signal.NEUTRAL
        // 「滚去睡觉」这类是关心，先放行
        if (SOFT_EXCEPTIONS.any { t.contains(it) }) return Signal.NEUTRAL
        if (COLD_WORDS.any { t.contains(it) }) return Signal.COLD
        if (PRAISE_WORDS.any { t.contains(it) }) return Signal.PRAISE
        return Signal.NEUTRAL
    }

    /**
     * 这一档情绪对应加多少好感。
     *
     * 夸奖 +1.0、被凶 -1.0、普通 +0.2 —— 负向惩罚是正向奖励的 5 倍：
     * 骂一句要五句好话才缓得回来，符合「关系是双向的」这个直觉。
     */
    fun deltaOf(signal: Signal): Double = when (signal) {
        Signal.PRAISE -> 1.0
        Signal.COLD -> -1.0
        Signal.NEUTRAL -> 0.2
    }

    /** 这一档情绪对应什么心情（会直接写进人格档案，进提示词） */
    fun moodOf(signal: Signal): String = when (signal) {
        Signal.PRAISE -> "开心"
        Signal.COLD -> "难过"
        Signal.NEUTRAL -> "平静"
    }

    /** 好感度分档 */
    fun tierOf(affinity: Int): Tier = when {
        affinity < 30 -> Tier.COLD
        affinity >= 80 -> Tier.CLOSE
        else -> Tier.NORMAL
    }

    // ------------------------------------------------------------ 语气（进 System Prompt）

    /**
     * 按好感度阈值给一段**强制**语气约束。
     *
     * 这里刻意写得像命令（「必须」「禁止」）而不是建议：
     * 角色 LLM 对「温柔一点」这类软话基本无视，对「回复不超过八个字」才真听话。
     */
    fun toneBlock(affinity: Int, mood: String): String = buildString {
        append("【当前心情】$mood（好感度 $affinity/100，绝对不要说出这些数字）\n")
        when (tierOf(affinity)) {
            Tier.COLD -> {
                append("【你现在对他有点冷】\n")
                append("- 回复必须极短：一到八个字，「哦」「知道了」「嗯」「随你」这种就够。\n")
                append("- 不要关心他、不要追问、不要主动开话题、不要用语气词和表情。\n")
                append("- 不要解释你为什么冷淡，也不要提「好感度」这种词，就是懒得搭理。\n")
            }
            Tier.NORMAL -> {
                append("【你们现在的关系还算正常】\n")
                append("- 正常说话：可以接梗、可以吐槽一两句，但别腻、别撒娇。\n")
                append("- 一到三句，说清就行。\n")
            }
            Tier.CLOSE -> {
                append("【你们现在很亲近】\n")
                append("- 语气亲昵、放松，可以用「你」之外的称呼（比如外号、喂）。\n")
                append("- 会关心他吃没吃、累不累、在忙什么；会主动分享你「今天」的心情和小事。\n")
                append("- 允许一句带情绪的抱怨或撒娇，但别黏人到假。\n")
            }
        }
    }

    // ------------------------------------------------------------ 落地

    /**
     * 一轮对话结束后调用（[PersonaEngine.recordTurn] 里）。
     *
     * 必须容忍 `context == null`（纯 JVM 单测、未 init 的场景），
     * 这时余数退回内存变量，不碰 SharedPreferences。
     */
    fun onTurn(context: Context?, userText: String) {
        val signal = signalOf(userText)
        val delta = deltaOf(signal)
        val mood = moodOf(signal)

        val total = readCarry(context) + delta
        val whole = total.toInt() // 向零截断：-0.8 → 0，正负混合也不会一次爆掉
        val rest = total - whole
        writeCarry(context, rest)

        if (whole != 0) {
            PersonaEngine.bumpAffinity(whole)
        }
        if (mood != PersonaEngine.profileSnapshot().mood) {
            PersonaEngine.updateMood(mood)
        }
        Log.d(
            TAG,
            "情绪：${signal.label} delta=$delta 余数=${String.format(java.util.Locale.US, "%.1f", rest)} " +
                "好感=${PersonaEngine.profileSnapshot().affinity} 心情=$mood"
        )
    }

    /** 当前余数（单测与排障用） */
    fun carry(context: Context?): Double = readCarry(context)

    /** 仅供单测：清掉余数 */
    internal fun resetForTest() {
        memCarry = 0.0
    }

    // ------------------------------------------------------------ 余数存取

    /** `context == null` 时的兜底（单测 / 未初始化） */
    private var memCarry = 0.0

    private fun readCarry(context: Context?): Double {
        if (context == null) return memCarry
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_CARRY, 0f)
                .toDouble()
        }.getOrDefault(memCarry)
    }

    private fun writeCarry(context: Context?, value: Double) {
        memCarry = value
        if (context == null) return
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_CARRY, value.toFloat())
                .apply()
        }
    }

    /** 余数不需要对外，但排障时想知道「还差几次才 +1」 */
    @Suppress("unused")
    private fun remainingToNextPoint(carry: Double): Double = 1.0 - abs(carry)
}
