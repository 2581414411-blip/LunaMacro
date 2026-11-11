package com.nexus.agent.persona

import java.util.Locale
import kotlin.math.exp

/**
 * 记忆召回打分 —— 情绪调制的检索（0.9.15 阶段 0）。
 *
 * ## 它替换的是什么
 *
 * 现状是 `MemoryDao.top(10)`：按权重排前 10 条，平铺进提示词。她开心时和难过时
 * 看到的是同一份东西，所以「情感失忆」不是记不住，是**记住了但从来不被情绪唤醒**。
 *
 * ## 为什么不是改 `MemoryRouter`
 *
 * `MemoryRouter` 是动作回放路由器（界面指纹 + 意图 → 直接点屏幕）。让情绪参与它，
 * 会让两条本该命中的肌肉记忆因为「此刻心情不对」而拒绝回放 —— 那是拿任务成功率
 * 换一个它根本不需要的能力。动作回放必须确定性，这条边界不动。
 *
 * ## 公式
 *
 * ```
 * semantic   = 关键词重叠率
 * congruence = 1 − dist(当前 PAD, 记忆 PAD) / (2√3)
 * w_emo      = 0.15 + 0.35 × 当前情绪强度        ← 自适应，
 * effectiveAge = age / (1 + 0.8 × 记忆强度)      ← 情绪越强越难忘
 * recency    = exp(−effectiveAge / 30 天)
 *
 * score = (1 − w_emo) × semantic + w_emo × congruence + 0.25 × recency
 * ```
 *
 * 三个设计意图：
 *
 * 1. **自适应权重**（来自 AFT）：她平静时靠语义找记忆，情绪激烈时靠情绪找记忆。
 *    固定权重做不到「同一套代码、不同人格时刻」。
 * 2. **强度调制遗忘**：不是给强度单独加权，而是把它揉进时间衰减的分母 ——
 *    语义上就是「这段记忆老得比别人慢」，而不是「这条记忆额外加 0.1 分」。
 * 3. **语义硬门槛**：没有它，用户难过时她会翻出一件毫不相干的伤心事。
 *    情绪一致性只许**重排**，不许**凭空引入**。
 *
 * ## 迁移兼容
 *
 * 老记忆 `pad = (0,0,0)`、`intensity = 0`：`congruence` 对它们是一个**相同的常数**，
 * 排序上不产生任何偏好，只整体压缩分差。这一点有单测钉着（`中性记忆不偏置`）。
 *
 * 全部纯函数，可 JVM 单测。
 */
object MemoryRanker {

    /** 关键词分隔符：不能用逗号（按钮文字里就有中文逗号，会把一个词劈两半） */
    const val KEY_SEP = '\u0001'

    /** 语义分低于这个值的记忆直接出局 */
    const val SEMANTIC_FLOOR = 0.20f

    /** 记忆的「半衰期」：30 天 */
    const val TAU_MS = 30L * 24 * 60 * 60 * 1000

    /** 时间衰减在总分里的权重 */
    const val W_RECENCY = 0.25f

    /** 情绪一致性权重下限（平静时） */
    const val W_EMO_MIN = 0.15f

    /** 情绪一致性权重上限（情绪极强时） */
    const val W_EMO_MAX = 0.50f

    /** 记忆强度的「延寿」系数：强度 1.0 的记忆，年龄按 1/1.8 折算 */
    const val INTENSITY_SLOWDOWN = 0.8f

    // ---------------------------------------------------------------- 数据结构

    /** 一条候选记忆（从 `MemoryEntity` 转过来，但不依赖 Room） */
    data class Candidate(
        val id: Long,
        val fact: String,
        /** `KEY_SEP` 分隔的关键词；空 = 老数据，打分时现切 */
        val keywords: String = "",
        /** 编码时刻的情绪坐标；`PAD.ZERO` = 老数据（中性） */
        val pad: PAD = PAD.ZERO,
        /** 编码时的情绪强度 0~1；0 = 老数据 */
        val intensity: Float = 0f,
        val createdAt: Long = 0L
    )

    /** 打分结果，每个分量都留着，排障时能回答「为什么是它」 */
    data class Ranked(
        val candidate: Candidate,
        val semantic: Float,
        val congruence: Float,
        val recency: Float,
        val score: Float
    ) {
        val eligible: Boolean get() = semantic >= SEMANTIC_FLOOR
    }

    // ---------------------------------------------------------------- 关键词

    /**
     * 把一句话切成关键词。
     *
     * 中文按连续片段取 2/3-gram，英文/数字取整词（`ComfyUI`、`deepseek` 这类几乎
     * 不会误召回）。和 `FlexiMem.extractKeywords` 是同一口径，但**故意各自实现**：
     * 那个 object 持有一个线程池，纯 JVM 单测里不想因为切个词就拉起一个 Executor。
     *
     * 两处口径若发生漂移，以本文件为准（它是检索打分的直接依据）。
     */
    fun tokenize(text: String, max: Int = 6): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()

        val candidates = LinkedHashMap<String, Int>()
        fun add(token: String, weight: Int) {
            val t = token.trim()
            if (t.length < 2) return
            // 全是虚字（「我们」「这个」）的直接丢
            if (t.none { it !in STOP_CHARS }) return
            candidates[t] = (candidates[t] ?: 0) + weight
        }

        var i = 0
        while (i < raw.length) {
            if (isCjk(raw[i])) {
                var j = i
                while (j < raw.length && isCjk(raw[j])) j++
                val run = raw.substring(i, j)
                for (n in intArrayOf(2, 3)) {
                    if (run.length < n) continue
                    for (k in 0..run.length - n) add(run.substring(k, k + n), if (n == 3) 3 else 2)
                }
                i = j
            } else {
                i++
            }
        }
        Regex("[A-Za-z0-9_]{2,20}").findAll(raw)
            .forEach { add(it.value.lowercase(Locale.ROOT), 8) }

        return candidates.entries
            .filter { it.value >= 2 || it.key.length >= 3 }
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { rankOf(it.key) }
                    .thenByDescending { it.key.length }
                    .thenByDescending { it.value }
            )
            .map { it.key }
            // 3-gram 已经覆盖了它自己的 2-gram，别把「马拉松」和「拉松」都留着
            .fold(ArrayList<String>()) { acc, k ->
                if (acc.none { it.contains(k) || k.contains(it) }) acc.add(k)
                acc
            }
            .take(max)
    }

    /** 英文整词优先，其次比实字数量 */
    private fun rankOf(token: String): Int =
        if (token.all { it.code < 128 }) 100 + token.length
        else token.count { it !in STOP_CHARS }

    fun encodeKeywords(tokens: List<String>): String = tokens.joinToString(KEY_SEP.toString())

    fun decodeKeywords(raw: String): List<String> =
        if (raw.isBlank()) emptyList()
        else raw.split(KEY_SEP).map { it.trim() }.filter { it.isNotEmpty() }

    // ---------------------------------------------------------------- 三个分量

    /**
     * 语义相似度 = 命中数 / min(两侧词数)。
     *
     * 用 min 而不是 query 的词数：一条只有两个关键词的记忆，两个都命中就该算完全相关，
     * 不该因为「用户这句话里有六个词」而被压到 0.33。
     */
    fun semanticOf(queryTokens: List<String>, memory: Candidate): Float {
        if (queryTokens.isEmpty()) return 0f
        val memTokens = decodeKeywords(memory.keywords).ifEmpty { tokenize(memory.fact) }
        if (memTokens.isEmpty()) return 0f
        var hit = 0
        for (q in queryTokens) {
            if (memTokens.any { it.contains(q) || q.contains(it) }) hit++
        }
        if (hit == 0) return 0f
        val denom = minOf(queryTokens.size, memTokens.size).coerceAtLeast(1)
        return (hit.toFloat() / denom).coerceIn(0f, 1f)
    }

    /** 情绪一致性：PAD 空间里离得越近越像「同一种心情」 */
    fun congruenceOf(current: PAD, memoryPad: PAD): Float =
        (1f - current.dist(memoryPad) / PAD.MAX_DIST).coerceIn(0f, 1f)

    /** 时间衰减，强度高的记忆老得慢 */
    fun recencyOf(createdAt: Long, intensity: Float, now: Long): Float {
        val age = (now - createdAt).coerceAtLeast(0L).toDouble()
        val slow = 1.0 + INTENSITY_SLOWDOWN * intensity.coerceIn(0f, 1f)
        return exp(-age / (TAU_MS * slow)).toFloat()
    }

    /** 当前情绪越强，越依赖情绪一致性 */
    fun emoWeightOf(current: PAD): Float =
        (W_EMO_MIN + (W_EMO_MAX - W_EMO_MIN) * current.normalized()).coerceIn(W_EMO_MIN, W_EMO_MAX)

    // ---------------------------------------------------------------- 打分

    fun scoreOne(
        memory: Candidate,
        queryTokens: List<String>,
        current: PAD,
        now: Long = System.currentTimeMillis()
    ): Ranked {
        val semantic = semanticOf(queryTokens, memory)
        val congruence = congruenceOf(current, memory.pad)
        val recency = recencyOf(memory.createdAt, memory.intensity, now)
        val w = emoWeightOf(current)
        val score = (1f - w) * semantic + w * congruence + W_RECENCY * recency
        return Ranked(memory, semantic, congruence, recency, score)
    }

    /**
     * 排序取前 [limit] 条。
     *
     * 没过语义门槛的直接出局 —— 返回的空列表是调用方回退老排序的信号，
     * 不是「没有记忆」。
     */
    fun rank(
        memories: List<Candidate>,
        query: String,
        current: PAD,
        now: Long = System.currentTimeMillis(),
        limit: Int = 10
    ): List<Ranked> {
        if (memories.isEmpty() || limit <= 0) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        return memories
            .map { scoreOne(it, tokens, current, now) }
            .filter { it.eligible }
            .sortedWith(
                compareByDescending<Ranked> { it.score }
                    .thenByDescending { it.candidate.createdAt }
            )
            .take(limit)
    }

    // ---------------------------------------------------------------- 内部

    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    /**
     * 虚字表。
     *
     * 只留「单字虚词」而不是整词表：`t.none { it !in STOP_CHARS }` 这个判据
     * 已经天然把「我们」「这个」这类整词拦掉了（它们每个字都是虚字）。
     */
    private val STOP_CHARS = setOf(
        '我', '你', '他', '她', '它', '们', '这', '那', '哪', '个', '的', '了', '是',
        '在', '有', '和', '就', '不', '都', '很', '还', '也', '才', '把', '被', '让',
        '给', '对', '啊', '呢', '吗', '吧', '哦', '嗯', '呀', '什', '么', '为', '怎',
        '因', '所', '以', '但', '而', '或', '者', '如', '果', '已', '经', '将', '要',
        '会', '能', '可', '说', '做', '到', '去', '来', '看', '一', '下', '太', '更',
        '最', '没', '别', '再', '又', '之', '与', '及', '并', '且', '却', '则', '于',
        '其', '此', '着', '过', '地', '得'
    )
}
