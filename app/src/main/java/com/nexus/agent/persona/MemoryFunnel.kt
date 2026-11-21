package com.nexus.agent.persona

import kotlin.math.ceil

/**
 * 时间轴记忆衰减漏斗（CognitiveScheduler 阶段 1）。
 *
 * ## 它替代的是什么
 *
 * 以前发 prompt 前的历史处理只有一句 `history.takeLast(REPLY_HISTORY_TURNS)` ——
 * **按条数硬裁**：不管这段对话是 10 分钟前还是 8 小时前，只要在最近 12 轮里就全量塞进去。
 * 结果是两头的错：
 *  - 隔夜之后接着说，模型会把昨天的事当成刚发生（"你刚才说的那个"其实是一天前）；
 *  - 真正新鲜的上下文被同一条数上限挤掉。
 *
 * 这里改成**按时间差（Time Delta）决定保真度**，离现在越远，信息越薄：
 *
 * | Δt | 档位 | 处理 | prompt 里的形态 |
 * |---|---|---|---|
 * | ≤ 10 分钟 | [Tier.FULL] | 原样 | 原文 |
 * | 10–30 分钟 | [Tier.COMPRESSED] | 按 [Config.compressRatio]（0.21）抽样，抽中的取核心句 | 压缩句 |
 * | 30–60 分钟 | [Tier.DIGEST] | 极简摘要（每条一句） | 极简句 |
 * | 1–23 小时 | [Tier.INDEX] | **不进 prompt**，只留 [Config.indexWeight]（0.818）权重的索引 | 索引（待召回） |
 * | > 23 小时 | [Tier.ARCHIVED] | 交给 FlexiMem L3/L4（周记 + 滚动摘要） | 无 |
 *
 * ## 为什么是纯规则（0 Token）
 *
 * 「21% 比例压缩提取核心事实」最直觉的实现是叫模型压 —— 但那是**每轮多一次请求**，
 * 比原来更慢更贵，和这个调度器存在的理由正好相反。所以压缩全部用规则：
 * 去括号旁白 → 取首句 → 截断。它保住的是「谁在什么时候说了什么」，丢的是修辞。
 *
 * ## 上下文减负（硬配额）
 *
 * 除了按时间衰减，还有一条**配额**：每 10 条历史必须有 1 条被压缩或降级
 * （[Config.minCompressPerTen]）。即使整段对话都在 10 分钟内，也会从**最老**的一条开始压，
 * 最近 [Config.protectRecent] 条永不压缩（正在说的这轮不能被摘要掉）。
 *
 * ## 线程
 *
 * 全静态纯函数，无 IO、无 Android 依赖，可直接在纯 JVM 单测里打靶。
 */
object MemoryFunnel {

    /** 漏斗的输入单元。刻意不直接用 `LocalDigitalBeing.Turn`：那一层带 Android 依赖，纯单测会被拖下水。 */
    data class Item(val user: String, val assistant: String, val at: Long)

    enum class Tier {
        /** 原样保留 */
        FULL,
        /** 抽样 + 核心句 */
        COMPRESSED,
        /** 极简摘要 */
        DIGEST,
        /** 只留索引，等召回 */
        INDEX,
        /** 超出 23 小时，交给 FlexiMem */
        ARCHIVED
    }

    /** 进入 prompt 的一条（user/assistant 成对，与消息格式一一对应） */
    data class Slice(
        val user: String,
        val assistant: String,
        val at: Long,
        val tier: Tier,
        val weight: Double
    )

    /** 不进 prompt、只留「有这么件事」的索引，需要时由 FlexiMem.recall 唤醒 */
    data class IndexRef(val at: Long, val excerpt: String, val weight: Double)

    data class Assembled(
        val slices: List<Slice>,
        val index: List<IndexRef>,
        val total: Int,
        val compressed: Int,
        val dropped: Int
    ) {
        /** 进入 prompt 的字符数（估算 token 用，中文约 1 字 1 token） */
        val chars: Int get() = slices.sumOf { it.user.length + it.assistant.length }

        /** 被压缩或降级的比例。配额要求它 ≥ 0.1（每 10 条 1 条） */
        val compressedRatio: Double get() = if (total <= 0) 0.0 else compressed.toDouble() / total

        fun isEmpty(): Boolean = slices.isEmpty() && index.isEmpty()
    }

    data class Config(
        /** ≤ 10 分钟：全量 */
        val fullMs: Long = 10 * 60_000L,
        /** ≤ 30 分钟：21% 抽样压缩 */
        val compressMs: Long = 30 * 60_000L,
        /** ≤ 60 分钟：极简摘要 */
        val digestMs: Long = 60 * 60_000L,
        /** ≤ 23 小时：只留索引 */
        val indexMs: Long = 23 * 60 * 60_000L,
        /** 10–30 分钟档保留多少比例 */
        val compressRatio: Double = 0.21,
        /** 索引档的权重 */
        val indexWeight: Double = 0.818,
        /** 每 10 条历史至少压几条 */
        val minCompressPerTen: Int = 1,
        /** 最近几条永不压缩 */
        val protectRecent: Int = 3,
        /** 核心句上限 */
        val coreMax: Int = 42,
        /** 极简摘要上限 */
        val digestMax: Int = 18,
        /** 索引摘录上限 */
        val indexMax: Int = 12
    )

    private val PAREN = Regex("[（(][^）)]{0,40}[）)]")
    private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '\n')

    /**
     * 组装一轮要发给模型的上下文。
     *
     * @param items 历史轮次（顺序无所谓，内部按 [Item.at] 升序）
     * @param now   基准时间（单测注入，生产传 `System.currentTimeMillis()`）
     */
    fun assemble(items: List<Item>, now: Long, cfg: Config = Config()): Assembled {
        if (items.isEmpty()) return Assembled(emptyList(), emptyList(), 0, 0, 0)

        val sorted = items.filter { it.user.isNotBlank() || it.assistant.isNotBlank() }
            .sortedBy { it.at }
        if (sorted.isEmpty()) return Assembled(emptyList(), emptyList(), 0, 0, 0)

        val tiers = sorted.map { tierOf((now - it.at).coerceAtLeast(0L), cfg) }
        val out = ArrayList<Slice>(sorted.size)
        val index = ArrayList<IndexRef>()

        // ---- 第一遍：按档位落到 prompt 或索引 ----
        val compressIdx = tiers.indices.filter { tiers[it] == Tier.COMPRESSED }
        val keepCount =
            if (compressIdx.isEmpty()) 0
            else maxOf(1, ceil(compressIdx.size * cfg.compressRatio).toInt())
        // 同档内保留**较新**的那几条：老的那批更该降级
        val keepSet = compressIdx.takeLast(keepCount).toHashSet()

        sorted.forEachIndexed { i, item ->
            when (tiers[i]) {
                Tier.FULL -> out.add(Slice(item.user, item.assistant, item.at, Tier.FULL, 1.0))
                Tier.COMPRESSED ->
                    if (i in keepSet) {
                        out.add(
                            Slice(
                                core(item.user, cfg), core(item.assistant, cfg),
                                item.at, Tier.COMPRESSED, 1.0
                            )
                        )
                    } else {
                        index.add(IndexRef(item.at, excerpt(item.user.ifBlank { item.assistant }, cfg), cfg.indexWeight))
                    }
                Tier.DIGEST -> out.add(
                    Slice(
                        digest(item.user, cfg), digest(item.assistant, cfg),
                        item.at, Tier.DIGEST, 1.0
                    )
                )
                Tier.INDEX -> index.add(
                    IndexRef(item.at, excerpt(item.user.ifBlank { item.assistant }, cfg), cfg.indexWeight)
                )
                Tier.ARCHIVED -> Unit
            }
        }

        var compressed = out.count { it.tier != Tier.FULL } + index.size +
            tiers.count { it == Tier.ARCHIVED }

        // ---- 第二遍：硬配额「每 10 条压 1 条」 ----
        // 整段对话都在 10 分钟内时上面一条都没压，这里从最老的开始补，
        // 但绝不碰最近 protectRecent 条（正在聊的不能被摘要掉）。
        val required = ceil(sorted.size / 10.0).toInt() * cfg.minCompressPerTen
        val floor = (out.size - cfg.protectRecent).coerceAtLeast(0)
        var cursor = 0
        while (compressed < required && cursor < out.size) {
            val s = out[cursor]
            if (s.tier == Tier.FULL && cursor < floor) {
                out[cursor] = s.copy(
                    user = core(s.user, cfg),
                    assistant = core(s.assistant, cfg),
                    tier = Tier.COMPRESSED
                )
                compressed++
            }
            cursor++
        }

        return Assembled(
            slices = out,
            index = index,
            total = sorted.size,
            compressed = compressed,
            dropped = tiers.count { it == Tier.ARCHIVED }
        )
    }

    fun tierOf(ageMs: Long, cfg: Config = Config()): Tier = when {
        ageMs <= cfg.fullMs -> Tier.FULL
        ageMs <= cfg.compressMs -> Tier.COMPRESSED
        ageMs <= cfg.digestMs -> Tier.DIGEST
        ageMs <= cfg.indexMs -> Tier.INDEX
        else -> Tier.ARCHIVED
    }

    /** 核心句：去括号旁白 → 取首句 → 截断。保住「谁说了什么」，丢修辞。 */
    fun core(text: String, cfg: Config = Config()): String =
        clip(firstSentence(stripParen(text)), cfg.coreMax)

    /** 极简摘要：同一条规则，截得更短 */
    fun digest(text: String, cfg: Config = Config()): String =
        clip(firstSentence(stripParen(text)), cfg.digestMax)

    /** 索引摘录：只留开头几个字，用于日志和日后召回 */
    fun excerpt(text: String, cfg: Config = Config()): String =
        clip(stripParen(text), cfg.indexMax)

    internal fun stripParen(text: String): String = text.replace(PAREN, "").trim()

    internal fun firstSentence(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val end = t.indexOfFirst { it in SENTENCE_END }
        return if (end < 0) t else t.substring(0, end + 1).trim()
    }

    internal fun clip(text: String, max: Int): String {
        val t = text.trim()
        if (max <= 0 || t.length <= max) return t
        return t.take(max).trimEnd() + "…"
    }

    /** 相对时间标签，给 prompt 用（让模型知道这是「刚才」还是「今天早上」） */
    fun relativeLabel(at: Long, now: Long): String {
        val minutes = ((now - at) / 60_000L).coerceAtLeast(0L)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            minutes < 24 * 60 -> "${minutes / 60}小时前"
            else -> "${minutes / (24 * 60)}天前"
        }
    }

    /**
     * 拼成 prompt 里的一段（给需要「整块上下文」的调用方用，例如语音链路）。
     *
     * 人格主链路不用这个 —— 它走 messages 数组，user/assistant 成对更准。
     */
    fun toPromptBlock(a: Assembled, now: Long): String {
        if (a.slices.isEmpty()) return ""
        return buildString {
            a.slices.forEach { s ->
                val tag = if (s.tier == Tier.FULL) "" else "·摘要"
                append("【").append(relativeLabel(s.at, now)).append(tag).append("】\n")
                if (s.user.isNotBlank()) append("他：").append(s.user).append('\n')
                if (s.assistant.isNotBlank()) append("你：").append(s.assistant).append('\n')
            }
        }.trim()
    }

    /** 一行日志，用来验证漏斗到底干了什么（改参数时看这个） */
    fun debugLine(a: Assembled): String =
        "总数=${a.total} 进prompt=${a.slices.size} 压缩=${a.compressed} 索引=${a.index.size} " +
            "丢弃=${a.dropped} 体积=${a.chars}字 压缩比=${"%.2f".format(a.compressedRatio)}"
}
