package com.nexus.agent.persona

import com.nexus.agent.data.db.MemoryEntity
import java.util.Locale

/**
 * 召回桥接层（0.9.15 阶段 2）—— 把 Room 实体接进 [MemoryRanker] 的纯数学。
 *
 * ## 为什么单独一层
 *
 * `MemoryRanker` 刻意不认识 `MemoryEntity`：它是纯函数，单测里不该为了算个分去
 * 构造 Room 实体。但「实体 → 候选」这段映射总得有人做，而且它本身就是策略
 * （老数据的 `pad=0` 怎么解释、没有 `keywords` 时现切、强度怎么算），
 * 值得被单测钉住 —— 于是单独成一层，不塞进 DAO 也不塞进引擎。
 *
 * ## 一条硬规则：没有语义命中就回退
 *
 * [factsOf] 返回空列表的语义是「**这次没排出来，请调用方走老排序**」，
 * 不是「她没有记忆」。空查询、候选为空、全都没过语义门槛，三种情况都归空。
 * 调用方（`RoomPersonaStore`）一律回退 `top(limit)` —— 宁可拿一份平庸的记忆，
 * 也不要让提示词里那一段凭空消失。
 */
object MemoryRecall {

    /** 从 `long_term_memory` 里取多少条当候选池（注入 10 条，池子太小没得挑） */
    const val POOL = 200

    /**
     * 实体 → 打分候选。
     *
     * 强度**不读库里的 `emotionalIntensity`**，而是由 PAD 现算：两者本来就该是
     * 同一个量（落库时写的就是 `pad.normalized()`），现算可以让「以后调了强度口径」
     * 立即对老数据生效，而不用迁移。库里的列留给「外部工具写进来的记忆」用。
     */
    fun candidateOf(e: MemoryEntity): MemoryRanker.Candidate {
        val pad = e.padOf()
        val intensity = if (pad.isNeutral()) e.emotionalIntensity else pad.normalized()
        return MemoryRanker.Candidate(
            id = e.id,
            fact = e.fact,
            keywords = e.keywords,
            pad = pad,
            intensity = intensity.coerceIn(0f, 1f),
            createdAt = e.createdAt
        )
    }

    /** 落库时写的强度 —— 与 [candidateOf] 同一个口径，改这里就够了 */
    fun intensityOf(pad: PAD): Float = pad.normalized().coerceIn(0f, 1f)

    /**
     * 排序。空列表 = 没排出来（调用方回退老排序）。
     */
    fun rank(
        entities: List<MemoryEntity>,
        query: String,
        current: PAD,
        now: Long = System.currentTimeMillis(),
        limit: Int = 10
    ): List<MemoryRanker.Ranked> {
        if (entities.isEmpty()) return emptyList()
        return MemoryRanker.rank(entities.map { candidateOf(it) }, query, current, now, limit)
    }

    /** 只要事实原文（提示词用的就是它） */
    fun factsOf(
        entities: List<MemoryEntity>,
        query: String,
        current: PAD,
        now: Long = System.currentTimeMillis(),
        limit: Int = 10
    ): List<String> = rank(entities, query, current, now, limit).map { it.candidate.fact }

    /**
     * 排障行：`adb logcat -s PadDebug | grep 召回`
     *
     * 一行说清「选了谁、为什么」—— 少了它，用户抱怨「她提了一件莫名其妙的事」时
     * 只能猜是记忆错了还是打分错了。
     */
    fun explain(
        ranked: List<MemoryRanker.Ranked>,
        current: PAD,
        pool: Int
    ): String {
        val head = "【召回】候选=$pool 选中=${ranked.size} " +
            "当前=${PadDynamics.describeOf(current)} " +
            "表情权重=${"%.2f".format(Locale.US, MemoryRanker.emoWeightOf(current))}"
        if (ranked.isEmpty()) return head
        val top = ranked.take(3).joinToString(" | ") {
            "「${it.candidate.fact.take(12)}」" +
                " 语义=${"%.2f".format(Locale.US, it.semantic)}" +
                " 一致=${"%.2f".format(Locale.US, it.congruence)}" +
                " 分=${"%.2f".format(Locale.US, it.score)}"
        }
        return "$head\n          → $top"
    }
}
