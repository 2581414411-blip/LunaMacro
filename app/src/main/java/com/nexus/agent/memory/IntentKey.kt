package com.nexus.agent.memory

/**
 * 任务意图归一化（0.9.13 外挂记忆）。
 *
 * 记忆要能命中，得先能回答「这句话和当初那句是不是同一个意思」。
 * 真做语义得靠向量模型，那就又回到「每次都要调模型」——本轮改造的初衷正好相反。
 * 所以这里用两条**零成本**的规则凑出一个够用的判断：
 *
 * 1. **归一化**：去标点、去语气词、去客套前缀，剩下的当桶键；
 * 2. **字符二元组 Dice 相似度**：中文不分词也能算，
 *    「帮我打开微信」和「打开微信」这种只差客套话的说法能对上。
 *
 * 措辞差得远的两句话（「打开微信搜订阅号」vs「在微信里搜订阅号」）会判成不同任务，
 * 这是**故意的**：多调一次模型只是慢两秒，把两件不同的事认成一件才会点错地方。
 *
 * ## 为什么这个「不准」是可以接受的
 *
 * 因为意图**不是唯一约束**。路由还要过界面指纹（Jaccard ≥ 0.75）这道强闸：
 * 同一句话站在不同界面上，一律不算命中。
 * 反过来，只要界面一模一样、意图又像，回放的路线大概率还是那条路。
 * 换句话说：界面管「在哪儿」，意图管「要干什么」，两道都过才动手。
 *
 * 阈值定在 0.72 的依据：手动试过一批真实说法的两两相似度，
 * 0.6 会把「打开淘宝」和「打开淘宝看看购物车」判成同一件事（差一个子任务就点错），
 * 0.8 则连「帮我打开微信」和「打开微信」都过不了。
 */
object IntentKey {

    /** 归一化后最长保留多少字（再长就不是一句话，是任务书） */
    const val MAX_CHARS = 40

    /** 意图相似度阈值 */
    const val MATCH_THRESHOLD = 0.72f

    /**
     * 客套前缀。只剥**开头**的，不剥中间 ——
     * 「等一下」剥成「等」会改变语义，而这种改变没有任何提醒。
     */
    private val LEADING_FILLERS = listOf(
        "麻烦你", "麻烦帮我", "麻烦", "请你", "请帮我", "帮我", "帮忙", "给我",
        "能不能", "可不可以", "可以帮我", "你帮我", "我要你", "我想要", "我要",
        "现在", "帮我"
    )

    /** 句尾语气词 */
    private val TRAILING_FILLERS = listOf(
        "一下", "吧", "呢", "呗", "啊", "呀", "嘛", "谢谢", "好不好", "行不行", "。", "！"
    )

    /**
     * 归一化：小写、去标点空白、剥客套、剥语气词、截断。
     *
     * 结果既当「记忆桶键」，也当相似度计算的输入。
     */
    fun normalize(raw: String): String {
        var s = raw.lowercase().trim()
        if (s.isEmpty()) return ""
        // 标点与空白全部抹掉（中英文都算），保留字母数字与汉字
        // 用 Unicode 大类 P 而不是 ASCII 的 Punct：中文全角标点属于 P，不属于 Punct
        s = s.replace(Regex("[\\p{P}\\s]+"), "")
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in LEADING_FILLERS) {
                if (s.length > f.length && s.startsWith(f)) {
                    s = s.substring(f.length)
                    changed = true
                }
            }
        }
        changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (f in TRAILING_FILLERS) {
                if (s.length > f.length && s.endsWith(f)) {
                    s = s.substring(0, s.length - f.length)
                    changed = true
                }
            }
        }
        if (s.length > MAX_CHARS) s = s.take(MAX_CHARS)
        return s
    }

    /** 字符二元组集合；单字时退化成自身 */
    fun bigrams(s: String): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length == 1) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /** Dice 系数（0~1） */
    fun similarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        val sa = bigrams(a)
        val sb = bigrams(b)
        if (sa.isEmpty() || sb.isEmpty()) return 0f
        var inter = 0
        val (small, large) = if (sa.size <= sb.size) sa to sb else sb to sa
        for (x in small) if (x in large) inter++
        return 2f * inter / (sa.size + sb.size)
    }

    /** 归一化后直接比 */
    fun same(rawA: String, rawB: String): Boolean =
        normalize(rawA) == normalize(rawB)

    /** 归一化 + 相似度比；任一侧为空一律 false */
    fun similar(rawA: String, rawB: String): Boolean {
        val a = normalize(rawA)
        val b = normalize(rawB)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || similarity(a, b) >= MATCH_THRESHOLD
    }
}
