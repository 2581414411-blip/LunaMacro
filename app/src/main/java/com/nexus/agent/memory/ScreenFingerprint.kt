package com.nexus.agent.memory

import java.security.MessageDigest

/**
 * 界面指纹（0.9.13 外挂记忆的「坐标」，0.9.34 换成协议 v1.2 口径）。
 *
 * 一条记忆要能被复用，先得能回答「我现在站的这个界面，跟当初学的时候是不是同一个」。
 * 截图的像素不行（状态栏时间、聊天内容都会变），整棵 UI 树的 hash 也不行（列表滚动就全变）。
 * 所以取**关键节点**：可点击元素的文字 + 控件 id + （id 全空时的）结构坐标，归一化后取集合。
 *
 * ## token 体系（`docs/mpl-protocol-v1.md`）
 *
 * ```
 * i:<viewId>              有这个就用它，最可信
 * c:<短类名>#<dep>.<sib>  没有 id 时的结构回退（dep 从根算，sib 同 dep 同类的次序）
 * t:<文本>                非输入框的短标签；输入框的 text 在 UiTreeParser 已被掩码
 * ```
 *
 * 来源 token 只有 `i:` 和 `c:` 两种，一个节点必出其一（缺 dep/sib 时不出 `c:`）。
 *
 * ## 三个刻意的规则
 *
 * 1. **`editable=true` 的节点不出 `t:`**。
 *    输入框里的 text 就是用户打的字。指纹会落库、会进日志，
 *    把用户输入吸进去的那一刻，这功能就从「学操作」变成了「记录聊天内容」。
 *    （回归点：`ScreenFingerprintTest.排除输入框里的用户内容`）
 *
 * 2. **超长文本不进指纹**。正文、消息内容、文章标题每一条都不同，
 *    进了指纹就会导致「同一个界面每次都算出不同指纹」，记忆永远打不中。
 *
 * 3. **数字归一成 `#`**。`3 条未读` / `12 条未读` 是同一个界面，不是两个。
 *
 * ## 为什么指纹要配一份明文 `screenKeys`
 *
 * 只存 hash 就只能判等，判不了「差在哪」。明文留着才能算相似度、
 * 才能在日志里说清「这次比你学的时候少了『搜索』这个框，所以我不敢点」。
 *
 * ## 0.9.34：两档，阈值不同
 *
 * - `fp-v1:id`（来源 token 里 i: 占比 ≥ [ID_RATIO_THRESHOLD]）：可落库、可跨会话命中，阈值 [THRESHOLD_ID]；
 * - `fp-v1:cls`（id 太少，主要靠结构）：**只用于同一次动作前后的 `changed` 判定，不许当经验库的钥匙**。
 *   Compose / RN 的树会因重组"同位置换节点"，序号整体平移，隔几天再打开早漂了。
 *   落库时 [keyString] 会把 `c:` token 过滤掉，于是这类条目即使进了库也匹配不上——
 *   这是刻意的，不是漏。
 */
object ScreenFingerprint {

    /** 指纹格式版本。算法再改就换前缀，老条目靠权重衰减自然淘汰 */
    const val VERSION = "fp-v1"

    /** 档位标签 */
    const val TIER_ID = "id"
    const val TIER_CLS = "cls"

    /** 参与指纹的最大**节点数**（不是 token 数）。 */
    const val MAX_NODES = 24

    /** 单个标签最长字数，超过视为「内容」而不是「结构」 */
    const val MAX_KEY_CHARS = 24

    /** 关键节点数少于这个值，指纹不可信（空界面 / UI 树没读出来） */
    const val MIN_KEYS = 3

    /** 来源 token 里 i: 的占比达到它，才认为是 id 档 */
    const val ID_RATIO_THRESHOLD = 0.5f

    /** id 档的匹配阈值：id 是结构，容忍度可以紧一点 */
    const val THRESHOLD_ID = 0.80f

    /** cls 档的匹配阈值：结构 token 会漂，得松一点，但只用于同会话 */
    const val THRESHOLD_CLS = 0.70f

    /** 落库时的节点分隔符（控制字符，UI 文案里不会出现） */
    private const val KEY_SEP = '\u0001'

    private const val TOKEN_ID = "i:"
    private const val TOKEN_CLS = "c:"
    private const val TOKEN_TEXT = "t:"

    /**
     * 从 UI 树 JSON 里抽关键节点。
     *
     * `UiTreeParser` 输出的是扁平数组，元素形如
     * `{"text":"搜索","type":"button","center":[..],"dep":4,"sib":2,"id":"search"}`。
     * 这里按 `},{` 切开逐个元素看，而不是全文正则 —— 只有按元素切，
     * 才能知道某个 text 是不是属于一个可编辑框、它的 dep/sib 是多少。
     */
    fun keysOf(uiJson: String?): List<String> {
        if (uiJson.isNullOrBlank() || uiJson == "[]") return emptyList()
        val out = LinkedHashSet<String>()
        val body = uiJson.trim().removePrefix("[").removeSuffix("]")
        if (body.isBlank()) return emptyList()
        var nodes = 0
        for (chunk in body.split("},{")) {
            if (nodes >= MAX_NODES) break
            nodes++

            val editable = EDITABLE_RE.containsMatchIn(chunk)

            // 1) 来源 token：有 id 用 id，没有就用结构坐标兜底
            val id = normalizeId(ID_RE.find(chunk)?.groupValues?.getOrNull(1).orEmpty())
            if (id.isNotEmpty()) {
                out.add(TOKEN_ID + id)
            } else {
                val dep = DEP_RE.find(chunk)?.groupValues?.getOrNull(1)
                val sib = SIB_RE.find(chunk)?.groupValues?.getOrNull(1)
                // 缺 dep/sib（老格式 JSON、手写测试数据）就不出 c:，
                // 宁可少一个 token，也不编一个假坐标出来
                if (dep != null && sib != null) {
                    val cls = normalize(TYPE_RE.find(chunk)?.groupValues?.getOrNull(1).orEmpty())
                        .ifBlank { "view" }
                    out.add("$TOKEN_CLS$cls#$dep.$sib")
                }
            }

            // 2) 文本 token：输入框的 text 是用户打的字，绝不进指纹
            if (!editable) {
                val t = normalize(TEXT_RE.find(chunk)?.groupValues?.getOrNull(1).orEmpty())
                if (t.isNotEmpty()) out.add(TOKEN_TEXT + t)
            }

            if (out.size >= MAX_NODES * 2) break
        }
        // 排序后截断：让结果与 UI 树里的节点顺序无关（列表重排不该换指纹）
        return out.sorted().take(MAX_NODES)
    }

    /**
     * 档位：来源 token 里 i: 占多数就是 id 档。
     *
     * 分母只数 `i:` + `c:`，不数 `t:` —— 文本 token 和"有没有 id"无关，
     * 把它算进去会让一个文字多的界面在 id 全空时被判成 id 档。
     */
    fun tierOf(keys: Collection<String>): String {
        var sources = 0
        var ids = 0
        for (k in keys) {
            when {
                k.startsWith(TOKEN_ID) -> {
                    sources++
                    ids++
                }
                k.startsWith(TOKEN_CLS) -> sources++
            }
        }
        if (sources == 0) return TIER_CLS
        return if (ids.toFloat() / sources >= ID_RATIO_THRESHOLD) TIER_ID else TIER_CLS
    }

    /**
     * 生成指纹：`fp-v1:<档位>#<12位hash>`。
     *
     * 关键节点太少时返回空串 —— 表示「这次读不到界面，别拿它去匹配」。
     * 宁可这次走 LLM，也不能用一个空指纹去撞记忆。
     *
     * 注意包名**参与哈希但不写进字符串**：两个 App 都有「确定/取消」时，
     * 纯 key 集合会算出同一个指纹，跨 App 误命中比漏命中贵得多。
     * 格式与协议示例一致（`fp-v1:id#a1b2c3d4e5f6`），对方无需知道内部加了包名。
     */
    fun of(pkg: String?, uiJson: String?): String {
        val keys = keysOf(uiJson)
        if (keys.size < MIN_KEYS) return ""
        val tier = tierOf(keys)
        val digest = md5("${pkg.orEmpty().ifBlank { "?" }}|${keys.joinToString("|")}").take(12)
        return "$VERSION:$tier#$digest"
    }

    /** 指纹的档位；老格式（无 `fp-v1:` 前缀）按 id 档处理，走保守阈值 */
    fun tierOfFingerprint(fingerprint: String?): String {
        val fp = fingerprint.orEmpty()
        if (!fp.startsWith("$VERSION:")) return TIER_ID
        return fp.removePrefix("$VERSION:").substringBefore('#').ifBlank { TIER_ID }
    }

    /** 是不是可落库的档位（cls 档不许进经验库） */
    fun isStorable(fingerprint: String?): Boolean =
        fingerprint.orEmpty().isNotBlank() && tierOfFingerprint(fingerprint) == TIER_ID

    /** 按档位取匹配阈值 */
    fun thresholdOf(fingerprint: String?): Float =
        if (tierOfFingerprint(fingerprint) == TIER_CLS) THRESHOLD_CLS else THRESHOLD_ID

    /**
     * 两个指纹是否同档。
     *
     * `changed` 判定必须先问这一句：跨档时 Jaccard 没有意义，
     * 按 0 算就等于把「不知道」说成「没变化」，调用方会去重试、再点一次。
     */
    fun sameTier(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return tierOfFingerprint(a) == tierOfFingerprint(b)
    }

    /**
     * 关键节点集合 → 落库字符串。
     *
     * **过滤掉 `c:` token**：cls 档的结构坐标只能用于同会话的 `changed` 判定，
     * 存进库里下次比对全新界面时，这些"看着像、位置不同"的 token 会把 Jaccard 拉低，
     * 反而伤到 id 档的命中率。查询侧同样经这里，两边 token 空间才对称。
     *
     * 分隔符用 `\u0001` 而不是逗号：按钮文字里真的会有中文逗号
     * （「发送，确定」这种），用逗号切会把一个标签劈成两个假节点。
     */
    fun keyString(keys: List<String>): String =
        keys.asSequence()
            .filterNot { it.startsWith(TOKEN_CLS) }
            .joinToString(KEY_SEP.toString()) { it.take(MAX_KEY_CHARS) }

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

    /**
     * id 的归一化。
     *
     * 和文本一样把数字抹掉：`item_1` / `item_2` 是同类控件，抹掉之后
     * 列表滚动不会让指纹变。代价是少量区分度，换来的是"同一界面永远同一指纹"。
     * （id 超长直接丢，那多半是动态生成的 id，下次就变了。）
     */
    private fun normalizeId(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        val n = s.replace(Regex("\\d+"), "#")
        if (n.length > MAX_KEY_CHARS) return ""
        return n
    }

    /** 指纹字符串里的包名（老格式才有；新格式不带，返回空） */
    fun pkgOf(fingerprint: String?): String {
        val fp = fingerprint.orEmpty()
        if (fp.startsWith("$VERSION:")) return ""
        return fp.substringBefore('#').takeIf { it.isNotBlank() && it != "?" }.orEmpty()
    }

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
    private val TYPE_RE = Regex("\"type\":\"([^\"]*)\"")
    private val DEP_RE = Regex("\"dep\":(\\d+)")
    private val SIB_RE = Regex("\"sib\":(\\d+)")
    private val EDITABLE_RE = Regex("\"editable\"\\s*:\\s*true")
}
