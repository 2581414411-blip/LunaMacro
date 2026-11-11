package com.nexus.agent.persona

/**
 * 刺激 → PAD 增量（0.9.15 阶段 0）。
 *
 * ## 为什么不用模型判情绪
 *
 * 每轮多一次请求 = 每轮多 1~3 秒延迟、多一份 token 账单，而判「这句话是夸我还是骂我」
 * 这件事，关键词表在这些年已经够用（0.9.2 定下的词表是踩过坑调出来的，别推倒重写）。
 *
 * 这一版真正升级的不是「判得准不准」，而是**判完之后落到哪里**：
 * 以前落到三档标签，现在落到一个三维向量。于是「被凶」不只是「难过」，
 * 而是「愉悦度掉、唤醒度升、支配感掉」—— 这三样对应的是三种不同的说话方式。
 *
 * ## 词表为什么是 copy 而不是引用
 *
 * `EmotionEngine` 里的词表是 private，且阶段 0 明确不接线（不动 `EmotionEngine`）。
 * 等阶段 1 接线时，`EmotionEngine` 会改成委托本类，届时那边那份会被删掉 ——
 * **只有一份真相源**。在那之前这里多一份是刻意的，注释里标了 TODO。
 */
object PadAppraisal {

    private const val TAG = "PadDebug"

    /** 夸奖 / 感谢 —— 明确的好感信号（迁自 `EmotionEngine.PRAISE_WORDS`） */
    private val PRAISE_WORDS = listOf(
        "谢谢", "多谢", "感谢", "辛苦了", "辛苦啦", "好棒", "真棒", "太棒",
        "厉害", "牛啊", "牛逼", "给力", "太强", "靠谱", "优秀", "聪明",
        "喜欢你", "爱你", "可爱", "真好", "么么", "抱抱", "乖", "有你在真好",
        "你真好", "离不开你", "有你真好"
    )

    /** 抱怨 / 冷淡 —— 明确的负向信号（迁自 `EmotionEngine.COLD_WORDS`） */
    private val COLD_WORDS = listOf(
        "滚", "烦死", "闭嘴", "废话", "垃圾", "没用", "讨厌", "笨蛋", "蠢",
        "傻", "别烦我", "不想聊", "离我远点", "没意思", "无聊", "少管",
        "关你屁事", "闭嘴吧", "你懂什么", "废物"
    )

    /** 问句里带「滚」的例外：「滚去睡觉」其实是关心（迁自 `EmotionEngine.SOFT_EXCEPTIONS`） */
    private val SOFT_EXCEPTIONS = listOf("滚去睡", "滚去休息", "滚去吃饭")

    /** 被依赖：他让她办事，是「被需要」的正向信号 */
    private val RELIED_WORDS = listOf(
        "帮我", "你帮我", "帮我弄", "帮我看看", "靠你了", "麻烦你", "替我看", "你去弄", "交给你"
    )

    /** 深夜的关怀：深夜 + 这几个词，净效果是「困但被暖到」 */
    private val CARE_WORDS = listOf("睡", "休息", "晚安", "别熬", "早点", "歇会儿")

    /** 多久没说话算「被晾着」 */
    const val IDLE_GAP_MS = 6L * 60 * 60 * 1000

    /** 「被晾着」最多累计几次，再多就成怨妇了 */
    const val MAX_IGNORED = 2

    // ---- 各信号对应的 PAD 增量 ----
    // 数值不是拍脑袋：夸和凶的比值接近 5:1（骂一句要五句好话才缓得回来），
    // 和 0.9.2 定下的好感度惩罚比一致，只是方向从「关系」换到了「心情」。

    val PRAISE = PAD(0.35f, 0.10f, 0.10f)
    val COLD = PAD(-0.45f, 0.20f, -0.25f)
    val RELIED = PAD(0.20f, 0.10f, 0.15f)

    /**
     * 被晾着：三轴一起往下走，**支配感掉得比愉悦度还多**。
     *
     * 第一版写的是 `(-0.15, -0.15, -0.05)`，跑轨迹时露了馅：隔一夜没说话再开口，
     * 算出 (−0.19, −0.41, +0.08) —— D 是正的，落进了「敌意」。
     * 可「他一天没理我」不是敌意，是**蔫**：她说不上话、也使不上劲。
     * D 不掉下去，坐标就会把「失落」误读成「有攻击性」。
     */
    val IGNORED = PAD(-0.16f, -0.14f, -0.20f)
    val LATE_NIGHT = PAD(-0.05f, -0.20f, 0f)
    val CARE_AT_NIGHT = PAD(0.20f, -0.10f, 0.05f)

    /** 一次刺激的全部输入 */
    data class Event(
        /** 用户这一轮说的话 */
        val text: String,
        /** 距上一轮过去多久；0 = 不知道 / 首次 */
        val idleMs: Long = 0L,
        /** 现在是不是深夜（由 `PresenceSense.isLateNight` 给） */
        val isLateNight: Boolean = false
    )

    /** 判定结果：总增量 + 命中了哪些信号（信号列表只用于日志与排障） */
    data class Result(val delta: PAD, val signals: List<PadSignal>) {
        val isEmpty: Boolean get() = signals.isEmpty()
    }

    /**
     * 判一次。
     *
     * 顺序有讲究：**先判冷（否定更强），再判夸**。「你懂什么，谢谢啊」这种反讽
     * 应该算被凶 —— 宁可误判成凶，也不要让一句阴阳怪气被当成真心夸奖。
     */
    fun appraise(event: Event): Result {
        val text = event.text.trim()
        val signals = ArrayList<PadSignal>(4)
        if (text.isEmpty()) return Result(PAD.ZERO, signals)

        var delta = PAD.ZERO
        // 「滚去睡觉」这类是关心，先放行，不参与冷/热判定
        val soft = SOFT_EXCEPTIONS.any { text.contains(it) }

        if (!soft) {
            when {
                COLD_WORDS.any { text.contains(it) } -> {
                    delta += COLD
                    signals += PadSignal.COLD
                }
                PRAISE_WORDS.any { text.contains(it) } -> {
                    delta += PRAISE
                    signals += PadSignal.PRAISE
                }
            }
        }

        // 被依赖可以和其他信号叠加：「谢谢你帮我弄这个」既被夸又被依赖
        if (RELIED_WORDS.any { text.contains(it) }) {
            delta += RELIED
            signals += PadSignal.RELIED
        }

        // 被晾着：每 6 小时算一次，累计封顶
        val ignored = (event.idleMs / IDLE_GAP_MS).toInt().coerceIn(0, MAX_IGNORED)
        if (ignored > 0) {
            delta += IGNORED * ignored.toFloat()
            repeat(ignored) { signals += PadSignal.IGNORED }
        }

        if (event.isLateNight) {
            delta += LATE_NIGHT
            signals += PadSignal.LATE_NIGHT
            // 深夜的关心是叠加的：本来犯困（A 掉），被关心一下心情又好回来
            if (CARE_WORDS.any { text.contains(it) }) {
                delta += CARE_AT_NIGHT
                signals += PadSignal.CARE_AT_NIGHT
            }
        }

        return Result(PadDynamics.capTo(delta, PAD.MAX_TURN_DELTA), signals)
    }

    /**
     * 乘上感性倍率并封顶。
     *
     * 单独一步而不是并进 [appraise]：`appraise` 是「这句话有多重」，
     * 这一步是「她有多敏感」，两个问题分开才好单独调参和单测。
     */
    fun scaled(delta: PAD, sensibility: Int): PAD =
        PadDynamics.capTo(delta * PadDynamics.sensitivityScale(sensibility), PAD.MAX_TURN_DELTA)
}

/** 命中的信号种类（中文名只用于日志，`adb logcat -s PadDebug` 一眼能读） */
enum class PadSignal(val zh: String) {
    PRAISE("被夸"),
    COLD("被凶"),
    RELIED("被依赖"),
    IGNORED("被晾着"),
    LATE_NIGHT("深夜"),
    CARE_AT_NIGHT("深夜关怀")
}
