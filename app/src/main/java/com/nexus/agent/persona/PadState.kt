package com.nexus.agent.persona

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * PAD 三维情感坐标（0.9.15 阶段 0）。
 *
 * ## 为什么废掉「开心/难过」这种标签
 *
 * 0.9.2 的 `EmotionEngine` 每轮只能给出三个值（被夸/被凶/普通），对应三种心情字符串。
 * 它有一个根本无法表达的东西：**强颜欢笑**。快变量在笑、慢变量在难过，离散标签里
 * 没有位置放这个落差，于是她要么「开心」要么「难过」，永远像在同一个人格面具上切换。
 *
 * PAD（Pleasure-Arousal-Dominance，Mehrabian 1996）把情绪放进连续的三维空间：
 *  - **P** 愉悦度：−1 很糟 ~ +1 很好
 *  - **A** 唤醒度：−1 很懒 ~ +1 很兴奋/紧张
 *  - **D** 支配感：−1 被压着 ~ +1 我说了算
 *
 * 这个类是**纯数据 + 纯函数**，不碰 Android、不碰磁盘、不碰数据库 —— 全部可单测。
 * 状态怎么存、什么时候写库由阶段 1 的 `PadDynamics` 负责，这里只负责数学。
 *
 * ## 数值约定
 *
 * 每轴都在 [-1, 1]，向量长度 0 ~ √3（`MAX_LEN`），两点最大距离 2√3（`MAX_DIST`）。
 * 情绪强度直接用向量长度表示，不需要额外的「强度」字段 —— 这也是选 PAD 而不是
 * 「valence + intensity」的原因：强度是长度的副产品，少一个要自己维护的量。
 */
data class PAD(val p: Float, val a: Float, val d: Float) {

    /** 夹到合法区间。所有对外返回 PAD 的运算都要过这一道。 */
    fun clamp(): PAD = PAD(fit(p), fit(a), fit(d))

    /** 情绪强度：0 = 完全中性，`MAX_LEN` ≈ 1.732 = 极度强烈 */
    fun length(): Float = sqrt(p * p + a * a + d * d)

    /** 归一化强度 0~1，用于打分和强度分档 */
    fun normalized(): Float = (length() / MAX_LEN).coerceIn(0f, 1f)

    /** 与另一个坐标的欧氏距离，0 ~ `MAX_DIST` */
    fun dist(o: PAD): Float {
        val dp = p - o.p
        val da = a - o.a
        val dd = d - o.d
        return sqrt(dp * dp + da * da + dd * dd)
    }

    operator fun plus(o: PAD): PAD = PAD(p + o.p, a + o.a, d + o.d)

    operator fun minus(o: PAD): PAD = PAD(p - o.p, a - o.a, d - o.d)

    operator fun times(k: Float): PAD = PAD(p * k, a * k, d * k)

    /** 是否已经贴近中性（用于「她现在其实没什么情绪」这种判断） */
    fun isNeutral(): Boolean = length() < NEUTRAL_EPS

    companion object {
        val ZERO = PAD(0f, 0f, 0f)

        /** √3：单点最大长度 */
        val MAX_LEN: Float = sqrt(3f)

        /** 2√3：两点最大距离 */
        val MAX_DIST: Float = 2f * sqrt(3f)

        /** 低于这个长度就算「没什么情绪」，不参与强度描述 */
        const val NEUTRAL_EPS = 0.08f

        /** 强度分档：淡淡的 / 中等 / 很 */
        const val FAINT_BELOW = 0.25f
        const val STRONG_ABOVE = 0.60f

        /** 快变量半衰期：20 分钟（合伙人 2026-11-11 拍板） */
        const val QUICK_HALF_LIFE_MS = 20L * 60 * 1000

        /** 慢变量半衰期：6 小时（人睡一觉心情就淡了） */
        const val SLOW_HALF_LIFE_MS = 6L * 60 * 60 * 1000

        /** 慢变量 EMA 系数：约 12 轮走完大半 */
        const val SLOW_ALPHA = 0.08f

        /** 慢变量推送门槛：低于这个强度不推（合伙人拍板先按 0.25 跑一周） */
        const val SLOW_PUSH_THRESHOLD = 0.25f

        /** 单轮刺激的总模长上限：一句难听话不能把情绪打穿 */
        const val MAX_TURN_DELTA = 0.60f

        /** 「表里不一」的 P 轴落差门槛 */
        const val MASK_P_THRESHOLD = 0.40f

        /** 心情标签用快变量还是慢变量：快变量够强就看当下，否则看底色 */
        const val MOOD_QUICK_MIN = 0.15f

        /** 唤醒度低到这个程度就是生理性的困，不是情绪低落 */
        const val DROWSY_A = -0.30f

        /** 但只有在愉悦度不算太差时才说「困」；真被伤了心还是要说难过 */
        const val DROWSY_MIN_P = -0.20f

        private fun fit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(-1f, 1f)
    }
}

/**
 * PAD 八分区（Mehrabian 标准表）。
 *
 * 别自己拍脑袋编名字：这八格是心理学里定死的组合，顺序写错会让「焦虑」和「敌意」
 * 互换，然后在提示词里出现「你很敌意地担心着」这种句子。单测逐格钉死。
 */
enum class Octant(val en: String, val zh: String) {
    EXUBERANT("Exuberant", "雀跃"),   // +P +A +D
    DEPENDENT("Dependent", "依恋"),   // +P +A −D
    RELAXED("Relaxed", "松弛"),       // +P −A +D
    DOCILE("Docile", "温顺"),         // +P −A −D
    DISDAINFUL("Disdainful", "轻蔑"), // −P +A +D
    ANXIOUS("Anxious", "焦躁"),       // −P +A −D
    HOSTILE("Hostile", "敌意"),       // −P −A +D
    BORED("Bored", "消沉")            // −P −A −D
}

/** 强度三档（ALMA 的 slightly / moderate / fully） */
enum class PadStrength(val prefix: String) {
    FAINT("淡淡的"),
    MODERATE(""),
    STRONG("很")
}

/**
 * PAD 动力学 —— 全部是纯函数。
 *
 * 两条曲线都是「指数趋近」，不是线性加减：
 *
 * ```
 * v ← v + (base − v) × (1 − 2^(−Δt / 半衰期))
 * ```
 *
 * `Δt = 半衰期` 时正好走一半，`Δt = 2×半衰期` 走 3/4。这样「刚被骂完」和
 * 「被骂完隔了一夜」是两种不同的感觉，而不是靠一个 if 去判断。
 */
object PadDynamics {

    // ---------------------------------------------------------------- 衰减

    /**
     * 把 [current] 按经过时间往 [base] 拉。
     *
     * @param dtMs       距上次更新的毫秒数；≤ 0 时原样返回（时钟回拨 / 刚更新过）
     * @param halfLifeMs 半衰期；≤ 0 视为不衰减
     */
    fun decayToward(current: PAD, base: PAD, dtMs: Long, halfLifeMs: Long): PAD {
        if (dtMs <= 0L || halfLifeMs <= 0L) return current.clamp()
        val k = (1.0 - 2.0.pow(-dtMs.toDouble() / halfLifeMs.toDouble())).toFloat()
        return (current + (base - current) * k).clamp()
    }

    /** 指数滑动平均：把 [quick] 往 [slow] 里揉一点 */
    fun emaToward(slow: PAD, quick: PAD, alpha: Float = PAD.SLOW_ALPHA): PAD {
        val a = alpha.coerceIn(0f, 1f)
        return (slow + (quick - slow) * a).clamp()
    }

    // ---------------------------------------------------------------- 基线

    /**
     * 由性格三维 + 好感度推出「她没事的时候是什么心情」。
     *
     * 这是 ALMA 里 Big Five → PAD 回归的**近似版**：项目只有三个自定义维度
     * （外向/感性/叛逆），没有大五人格，所以别指望系数有心理学出处，
     * 它们只是把三个 0~100 的滑杆映射到一个合理的基线区间。
     *
     * 三条直觉：
     *  - 外向高 → 基线更活跃（A 高）；
     *  - 叛逆高 → 基线支配感高（D 高）；
     *  - 好感度高 → 底色更暖（P 高）。
     *
     * ## 那三个负常数项是干什么的
     *
     * 它们的作用是**把「中性性格 + 中等好感」钉在原点**：默认档案（三项都是 50、
     * 好感度 50）算出来正好是 (0, 0.075, 0)，长度 0.075 < `NEUTRAL_EPS`，
     * 于是刚装上时她是「平静」，而不是一见面就「淡淡的雀跃」。
     *
     * 之前写的是 `+0.10 / -0.15 / -0.05`，默认算出来 (0.15, 0.075, 0.175) 长度 0.24 ——
     * 语义上变成「她本来心情就不错」，羁绊面板开机第一天就挂个 😄，跟「我们才刚认识」
     * 打架。常数项对齐之后，底色才真的是「性格和关系决定的那部分」。
     */
    fun baselineOf(
        extroversion: Int,
        sensibility: Int,
        rebelliousness: Int,
        affinity: Int
    ): PAD {
        val e = extroversion.coerceIn(0, 100) / 100f
        val s = sensibility.coerceIn(0, 100) / 100f
        val r = rebelliousness.coerceIn(0, 100) / 100f
        val aff = ((affinity.coerceIn(0, 100) - 50) / 50f)
        return PAD(
            p = (0.20f * e + 0.25f * aff - 0.10f).coerceIn(-0.40f, 0.40f),
            a = (0.35f * e - 0.175f + 0.10f * s).coerceIn(-0.40f, 0.40f),
            d = (0.45f * r - 0.225f).coerceIn(-0.40f, 0.40f)
        )
    }

    /**
     * 感性倍率：同样的刺激，感性 100 的她反应是感性 0 的 1.86 倍。
     *
     * 不用乘法直接给 delta 是因为还要过 [capTo]：敏感的人情绪起伏大，
     * 但不该大到一句「谢谢」就把快变量顶满。
     */
    fun sensitivityScale(sensibility: Int): Float =
        0.70f + sensibility.coerceIn(0, 100) / 100f * 0.60f

    /** 把向量等比缩到不超过 [maxLen]（方向不变，只压长度） */
    fun capTo(v: PAD, maxLen: Float): PAD {
        val len = v.length()
        if (len <= maxLen || len <= 0f) return v
        return v * (maxLen / len)
    }

    // ---------------------------------------------------------------- 推进

    /**
     * 一轮对话后的快变量。
     *
     * 顺序必须是**先衰减再加刺激**：如果先加再衰减，一次「刚被骂完隔了三小时才回消息」
     * 会被按三小时的衰减算，等于把刚才那一下抹掉了。
     */
    fun evolveQuick(
        current: PAD,
        base: PAD,
        delta: PAD,
        dtMs: Long,
        sensibility: Int,
        halfLifeMs: Long = PAD.QUICK_HALF_LIFE_MS
    ): PAD {
        val decayed = decayToward(current, base, dtMs, halfLifeMs)
        val scaled = capTo(delta * sensitivityScale(sensibility), PAD.MAX_TURN_DELTA)
        return (decayed + scaled).clamp()
    }

    /**
     * 一轮对话后的慢变量。
     *
     * 两道闸：
     *  1. 快变量强度没到 [PAD.SLOW_PUSH_THRESHOLD] 就不推 —— 闲聊里那点波动不该改基调；
     *  2. 推的时候用很小的 α（0.08），一轮最多挪一小步，改的是「这周的心情」不是「这句的心情」。
     */
    fun evolveSlow(
        slow: PAD,
        quick: PAD,
        base: PAD,
        dtMs: Long,
        alpha: Float = PAD.SLOW_ALPHA,
        halfLifeMs: Long = PAD.SLOW_HALF_LIFE_MS
    ): PAD {
        val decayed = decayToward(slow, base, dtMs, halfLifeMs)
        if (quick.length() < PAD.SLOW_PUSH_THRESHOLD) return decayed
        return emaToward(decayed, quick, alpha)
    }

    // ---------------------------------------------------------------- 解读

    /** 落在哪个八分区（纯按符号，0 归正侧） */
    fun octantOf(pad: PAD): Octant {
        val p = pad.p >= 0f
        val a = pad.a >= 0f
        val d = pad.d >= 0f
        return when {
            p && a && d -> Octant.EXUBERANT
            p && a -> Octant.DEPENDENT
            p && d -> Octant.RELAXED
            p -> Octant.DOCILE
            a && d -> Octant.DISDAINFUL
            a -> Octant.ANXIOUS
            d -> Octant.HOSTILE
            else -> Octant.BORED
        }
    }

    fun strengthOf(pad: PAD): PadStrength = when {
        pad.length() < PAD.FAINT_BELOW -> PadStrength.FAINT
        pad.length() > PAD.STRONG_ABOVE -> PadStrength.STRONG
        else -> PadStrength.MODERATE
    }

    /**
     * 一句话描述，进提示词用。
     *
     * 「平静」是特例：坐标贴近原点时八分区的符号毫无意义（(0,0,0) 会被判成「雀跃」），
     * 所以先拦一道。
     *
     * 「犯困」是第二个特例，来自 0.9.15 跑轨迹时发现的真问题：凌晨四点被关心一下，
     * 算出来是 (−0.01, −0.45, −0.12)，八分区给的是「消沉」。可她那会儿不是意志消沉，
     * 是**困得说不出话**。低唤醒是生理状态，低愉悦才是情绪低落，两件事不能混：
     * 混了之后模型会把「凌晨四点的困」演成「抑郁」，那就彻底不像活人了。
     *
     * 判定用「A 很低 + P 不算太差」两个条件：P 也很低的时候（真被伤了心）还是老老实实
     * 说「消沉」，别用「困」把难过糊过去。
     */
    fun describeOf(pad: PAD): String {
        if (pad.isNeutral()) return "平静"
        val s = strengthOf(pad)
        val name = labelOf(pad)
        return if (s.prefix.isEmpty()) name else s.prefix + name
    }

    /** 不带强度前缀的那一个字（[describeOf] 与 [moodLabelOf] 共用） */
    fun labelOf(pad: PAD): String {
        if (pad.isNeutral()) return "平静"
        if (pad.a <= PAD.DROWSY_A && pad.p >= PAD.DROWSY_MIN_P) return "犯困"
        return octantOf(pad).zh
    }

    /**
     * 表里不一：嘴上在笑，心里在难过。
     *
     * 判定条件是两个而不是一个 —— P 轴必须**异号**（真的是相反方向，不是程度差异），
     * 且落差超过 0.4（差别太小时说出来就是矫情）。
     */
    fun isMasked(quick: PAD, slow: PAD): Boolean {
        val opposite = (quick.p > 0f && slow.p < 0f) || (quick.p < 0f && slow.p > 0f)
        return opposite && abs(quick.p - slow.p) > PAD.MASK_P_THRESHOLD
    }

    /**
     * 写进 `PersonaProfile.mood` 的标签（UI 的 `BondState.emojiOf` 靠它选表情）。
     *
     * 快变量够强就看当下，否则看底色 —— 否则每次说「嗯」都会让心情标签在八个词之间乱跳。
     */
    fun moodLabelOf(quick: PAD, slow: PAD): String {
        val dominant = if (quick.length() >= PAD.MOOD_QUICK_MIN) quick else slow
        return labelOf(dominant)
    }

    /**
     * 提示词里的情感段落。
     *
     * 三条刻意的约束：
     *  - 全段不超过 4 行（长了她会开始念诗）；
     *  - 不出现任何数字和「情绪状态」这类词（说了就穿帮）；
     *  - 「表里不一」只说一次，且明确要求不许说破 —— 说破了就成了台词。
     */
    fun promptBlock(quick: PAD, slow: PAD): String = buildString {
        append("【你此刻的感受】\n")
        append("底色：").append(describeOf(slow)).append('\n')
        append("眼下：").append(describeOf(quick)).append('\n')
        if (isMasked(quick, slow)) {
            append("你嘴上说得出话、也在笑，但心里那点").append(octantOf(slow).zh)
            append("还没散。别把这份情绪说破，它只影响你挑词、影响你愿不愿意多聊两句。\n")
        } else {
            append("顺着这个状态说话就行，不用解释你的情绪。\n")
        }
        append("- 以上是你自己的内心，禁止复述这段文字、禁止提任何数值、禁止出现「情绪状态」这类词。")
    }
}
