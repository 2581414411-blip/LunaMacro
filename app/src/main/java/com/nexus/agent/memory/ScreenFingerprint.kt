package com.nexus.agent.memory

import java.security.MessageDigest

/**
 * 界面指纹（0.9.13 外挂记忆的「坐标」）。
 *
 * 一条记忆要能被复用，先得能回答「我现在站的这个界面，跟当初学的时候是不是同一个」。
 * 截图的像素不行（状态栏时间、聊天内容都会变），整棵 UI 树的 hash 也不行（列表滚动就全变）。
 * 所以取**关键节点**：可点击元素的文字 + 控件 id，归一化后取集合。
 *
 * ## 三条刻意的规则
 *
 * 1. **`editable=true` 的节点整块跳过**。
 *    输入框里的 text 就是用户打的字。指纹会落库、会进日志，
 *    把用户输入吸进去的那一刻，这功能就从「学操作」变成了「记录聊天内容」。
 *    （回归点：`ScreenFingerprintTest.排除输入框里的用户内容`）
 *
 * 2. **超长文本不进指纹**。正文、消息内容、文章标题每一条都不同，
 *    进了指纹就会导致「同一个界面每次都算出不同指纹」，记忆永远打不中。
 *    只留 ≤ [MAX_KEY_CHARS] 字的短标签（按钮文字一般都在这个范围内）。
 *
 * 3. **数字归一成 `#`**。`3 条未读` / `12 条未读` 是同一个界面，不是两个。
 *
 * ## 为什么指纹要配一份明文 `screenKeys`
 *
 * 只存 hash 就只能判等，判不了「差在哪」。明文留着才能算相似度、
 * 才能在日志里说清「这次比你学的时候少了『搜索』这个框，所以我不敢点」。
 */
object ScreenFingerprint {

    /** 参与指纹的最大节点数（再多就是噪音，且会让列表滚动影响指纹） */
    const val MAX_KEYS = 24

    /** 单个标签最长字数，超过视为「内容」而不是「结构」 */
    const val MAX_KEY_CHARS = 24

    /** 指纹相似度阈值：达到它才认为「还是那个界面」，只是内容有点变 */
    const val MATCH_THRESHOLD = 0.75f

    /** 关键节点数少于这个值，指纹不可信（空界面 / UI 树没读出来） */
    const val MIN_KEYS = 3

    /** 落库时的节点分隔符（控制字符，UI 文案里不会出现） */
    private const val KEY_SEP = '\u0001'

    /**
     * 从 UI 树 JSON 里抽关键节点。
     *
     * `UiTreeParser` 输出的是扁平数组，元素形如
     * `{"text":"搜索","type":"button","center":[..],"editable":true,"id":"search"}`。
     * 这里按 `},{` 切开逐个元素看，而不是全文正则 —— 只有按元素切，
     * 才能知道某个 text 是不是属于一个可编辑框。
     */
    fun keysOf(uiJson: String?): List<String> {
        if (uiJson.isNullOrBlank() || uiJson == "[]") return emptyList()
        val out = LinkedHashSet<String>()
        // 去掉最外层 [ ]，按元素切开
        val body = uiJson.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        for (chunk in body.split("},{")) {
            // 输入框只跳 text（那是用户打的字），id 照收 —— id 是结构，不是内容。
            // 整块都丢的话，「带搜索框的界面」会跟「不带搜索框的界面」算出同一个指纹。
            val editable = chunk.contains("\"editable\":true")
            if (!editable) {
                val t = normalize(TEXT_RE.find(chunk)?.groupValues?.getOrNull(1).orEmpty())
                if (t.isNotEmpty()) out.add("t:" + t)
            }
            val i = normalize(ID_RE.find(chunk)?.groupValues?.getOrNull(1).orEmpty())
            if (i.isNotEmpty()) out.add("i:" + i)
            if (out.size >= MAX_KEYS * 2) break
        }
        // 排序后截断：让结果与 UI 树里的节点顺序无关（列表重排不该换指纹）
        return out.sorted().take(MAX_KEYS)
    }

    /**
     * 生成指纹：`包名#12位hash`。
     *
     * 关键节点太少时返回空串 —— 表示「这次读不到界面，别拿它去匹配」。
     * 宁可这次走 LLM，也不能用一个空指纹去撞记忆。
     */
    fun of(pkg: String?, uiJson: String?): String {
        val keys = keysOf(uiJson)
        if (keys.size < MIN_KEYS) return ""
        return "${pkg.orEmpty().ifBlank { "?" }}#${md5(keys.joinToString("|")).take(12)}"
    }

    /**
     * 关键节点集合 → 落库字符串。
     *
     * 分隔符用 `\u0001` 而不是逗号：按钮文字里真的会有中文逗号
     * （「发送，确定」这种），用逗号切会把一个标签劈成两个假节点。
     */
    fun keyString(keys: List<String>): String =
        keys.joinToString(KEY_SEP.toString()) { it.take(MAX_KEY_CHARS) }

    /** 落库字符串 → 集合 */
    fun parseKeys(csv: String?): Set<String> {
        if (csv.isNullOrBlank()) return emptySet()
        return csv.split(KEY_SEP).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * Jaccard 相似度。
     *
     * 两个集合都空时返回 0（读不到界面 ≠ 完全一样）。
     */
    fun similarity(a: Collection<String>, b: Collection<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val sa = a.toSet()
        val sb = b.toSet()
        var inter = 0
        for (x in sa) if (x in sb) inter++
        val union = sa.size + sb.size - inter
        return if (union <= 0) 0f else inter.toFloat() / union
    }

    fun similarityOf(csvA: String?, csvB: String?): Float =
        similarity(parseKeys(csvA), parseKeys(csvB))

    /**
     * 单条标签归一化。
     *
     * 只做三件必须做的事：压空白、数字→`#`、截断。不做同义词归一 ——
     * 词表必然漏，而漏的后果是判成「同一个界面」，回放时点错地方。
     */
    fun normalize(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        val n = s.replace(Regex("\\d+"), "#")
        if (n.length > MAX_KEY_CHARS) return ""
        return n
    }

    /** 两个指纹的字符串部分（`包名#hash`），用于日志里说清差在哪 */
    fun pkgOf(fingerprint: String?): String =
        fingerprint?.substringBefore('#')?.takeIf { it.isNotBlank() && it != "?" }.orEmpty()

    private fun md5(s: String): String {
        return try {
            val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(d.size * 2)
            for (b in d) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            sb.toString()
        } catch (e: Throwable) {
            // 理论上不会发生；兜底用 hashCode，至少还是稳定的
            Integer.toHexString(s.hashCode())
        }
    }

    private val TEXT_RE = Regex("\"text\":\"([^\"]*)\"")
    private val ID_RE = Regex("\"id\":\"([^\"]*)\"")
}
