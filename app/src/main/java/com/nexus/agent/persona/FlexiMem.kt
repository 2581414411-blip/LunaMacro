package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.WeeklyHighlight
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FlexiMem —— 四层长记忆（0.9.0）。
 *
 * ## 为什么不用「向量库 + 全部塞进 Prompt」那一套
 *
 * 手机端跑本地 2B 模型，上下文窗口就那么点。把半年的聊天记录全检索出来塞进去，
 * 效果不是「记得更多」，而是「什么都记不清」——模型会把十天前的琐事当成刚发生的事。
 *
 * 所以这里按**时间跨度**分层，每层解决一种「忘」：
 *
 * | 层 | 存什么 | 存在哪 | 花不花 Token | 解决什么 |
 * |---|---|---|---|---|
 * | L1 原文档案 | 每一轮原话，一问一答一行 | `filesDir/persona/archive/YYYY-MM-DD.jsonl` | **0** | 「上个月那句原话到底怎么说的」 |
 * | L2 检索召回 | 按关键词从 L1 里捞 3 条 | 运行时现捞 | 少量 | 「他提的那个事我们聊过」 |
 * | L3 周记 | 每周 6 条关键记忆（**规则**抽，不调模型） | Room `weekly_highlight` | 少量 | 「上周大概在忙什么」 |
 * | L4 滚动摘要 + 20 轮滑窗 | 更早的压缩成摘要，最近的留原话 | `persona/long_memory.json` | 中 | 「刚才说到哪了」 |
 *
 * L1 是**零成本**的：只是把已经产生过的文本再写一份到磁盘，不经过任何模型。
 * 它存在的意义是「可回溯」——上面三层都是**有损**的，只有 L1 能回答
 * 「他上周三到底怎么说的」这种问题。
 *
 * ## 线程模型
 *
 * - 写档案：单线程 executor（顺序就是上下文，并发写会乱序）；
 * - 检索/周记生成：调用方所在线程（[com.nexus.agent.copilot.PersonaEngine.respond]
 *   本身跑在 IO 协程里），只在读文件，不碰 UI；
 * - 周记滚动：同一时刻只允许一次（[rolling]），并按小时节流。
 */
object FlexiMem {

    private const val TAG = "FlexiMem"

    /** 档案目录：`filesDir/persona/archive` */
    private const val PERSONA_DIR = "persona"
    private const val ARCHIVE_DIR = "archive"

    /** 一次召回最多几条原话（再多就会盖过「这一轮到底在说什么」） */
    const val RECALL_LIMIT = 3

    /** 检索回溯天数（只翻最近两周，再往前交给周记） */
    private const val SEARCH_DAYS = 14

    /** 档案保留天数：超过就删（一天几十 KB，留四个月足够回溯） */
    private const val KEEP_DAYS = 120

    /** 单日档案的字节上限：到了就停写，避免某天刷屏把磁盘写满 */
    private const val MAX_FILE_BYTES = 1_500_000L

    /** 检索时最多扫多少行（防某天特别长的文件拖慢一句回复） */
    private const val MAX_SCAN_LINES = 4000

    /** 召回原话截断长度 */
    private const val RECALL_SNIPPET_MAX = 50

    private const val DAY_MS = 24L * 3600 * 1000
    private const val WEEK_MS = 7 * DAY_MS

    /** 周记滚动检查的节流间隔（每小时最多查一次库） */
    private const val ROLL_CHECK_INTERVAL_MS = 3600_000L

    /** 周记最多保留几周 */
    private const val KEEP_WEEKS = 52

    /** 档案里的一轮（和 [LocalDigitalBeing.Turn] 字段一致，但额外带上日期，便于检索时显示「哪天说的」） */
    data class ArchiveTurn(val user: String, val assistant: String, val at: Long, val day: String)

    @Volatile
    private var appCtx: Context? = null

    /** 写档案专用单线程：顺序敏感 */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "fleximem-archive").apply { isDaemon = true }
    }

    /** 周记生成互斥 */
    private val rolling = AtomicBoolean(false)

    /** 上次检查周记的时间（节流用） */
    @Volatile
    private var lastRollCheck = 0L

    /** 周记缓存：注入 Prompt 是同步路径，不能现查库 */
    @Volatile
    private var weeklyCache: List<String> = emptyList()

    @Volatile
    private var weeklyLoaded = false

    /** 今日已经写满上限，别每条都去 stat 一次 */
    @Volatile
    private var todayFull = false

    // ---------------------------------------------------------------- 生命周期

    /** 注入上下文：建目录、清旧档案、载入周记缓存（幂等） */
    fun init(context: Context) {
        if (appCtx != null) return
        val ctx = context.applicationContext
        appCtx = ctx
        io.execute {
            runCatching { pruneOldArchives(ctx) }
                .onFailure { Log.w(TAG, "清理旧档案失败：${it.javaClass.simpleName}") }
        }
        loadWeeklyAsync()
        maybeRollWeeklyAsync()
    }

    /** 「清空聊天」时连档案与周记一起清 —— 否则用户会觉得根本没清干净 */
    fun clearAll(context: Context) {
        val dir = archiveDir(context)
        runCatching {
            dir.listFiles()?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "清空档案失败：${it.message}") }
        weeklyCache = emptyList()
        weeklyLoaded = true
        io.execute {
            runCatching {
                runBlocking { AppDatabase.get(context).weeklyHighlightDao().clear() }
            }.onFailure { Log.w(TAG, "清空周记失败：${it.message}") }
        }
        Log.i(TAG, "原文档案与周记已清空")
    }

    // ---------------------------------------------------------------- L1 原文档案

    /**
     * 追加一轮原话到 `archive/YYYY-MM-DD.jsonl`。
     *
     * **每轮都写，和是否触发摘要无关** —— 摘要会把十轮压成一句话，
     * 那份压缩是不可逆的；档案是唯一能还原「当时到底怎么说」的东西。
     *
     * 写失败只打日志：记忆丢一条远不如让聊天卡住糟糕。
     */
    fun appendTurn(user: String, assistant: String, at: Long = System.currentTimeMillis()) {
        val ctx = appCtx ?: return
        val u = user.trim()
        val a = assistant.trim()
        if (u.isEmpty() || a.isEmpty()) return
        io.execute {
            runCatching {
                val f = File(archiveDir(ctx), "${dayKey(at)}.jsonl")
                if (f.length() > MAX_FILE_BYTES) {
                    if (!todayFull) {
                        todayFull = true
                        Log.w(TAG, "今日档案已达 ${MAX_FILE_BYTES / 1024}KB 上限，停止写入（${f.name}）")
                    }
                    return@runCatching
                }
                f.appendText(encodeLine(u, a, at) + "\n", Charsets.UTF_8)
            }.onFailure { Log.w(TAG, "写档案失败：${it.javaClass.simpleName} ${it.message}") }
        }
    }

    /** 这一天的一轮原话（纯 IO，调用方保证不在主线程） */
    internal fun readDay(dir: File, day: String): List<ArchiveTurn> {
        val f = File(dir, "$day.jsonl")
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines(Charsets.UTF_8).mapNotNull { decodeLine(it, day) }
        }.onFailure {
            Log.w(TAG, "读档案失败（${f.name}）：${it.javaClass.simpleName}")
        }.getOrDefault(emptyList())
    }

    // ---------------------------------------------------------------- L2 检索召回

    /**
     * 按这一句用户原话去档案里捞**相关的老原话**。
     *
     * 做法很土但很稳：提关键词 → 逐行数命中 → 命中最多的几条返回。
     * 为什么不上向量：本地算 embedding 要再挂一个模型，而召回 3 条短句
     * 用关键词已经够用；漏召回只是「少记起一件事」，误召回才会让模型串台。
     *
     * @return 形如 `11月03日 你说：「周末去看电影吗」` 的短句，最多 [RECALL_LIMIT] 条
     */
    fun recall(userText: String, limit: Int = RECALL_LIMIT): List<String> {
        val ctx = appCtx ?: return emptyList()
        val keywords = extractKeywords(userText)
        if (keywords.isEmpty()) return emptyList()
        val dir = archiveDir(ctx)
        val today = System.currentTimeMillis()

        // 从今天往回翻 [SEARCH_DAYS] 天；今天自己那几轮不召回（就是刚才说的话）
        val scored = ArrayList<Pair<Int, ArchiveTurn>>()
        var scanned = 0
        for (i in 1..SEARCH_DAYS) {
            if (scanned >= MAX_SCAN_LINES) break
            val day = dayKey(today - i * DAY_MS)
            val turns = readDay(dir, day)
            for (t in turns.asReversed()) {
                if (scanned++ >= MAX_SCAN_LINES) break
                val score = scoreTurn(t, keywords)
                if (score > 0) scored.add(score to t)
            }
        }
        if (scored.isEmpty()) return emptyList()

        // 命中多的优先；同分时新的优先（越近越可能是「他说的那件事」）
        val picked = scored
            .sortedWith(compareByDescending<Pair<Int, ArchiveTurn>> { it.first }.thenByDescending { it.second.at })
            .map { it.second }
        val out = ArrayList<String>(limit)
        for (t in picked) {
            val line = formatRecall(t)
            // 同一句话不重复召回（用户复读、或同一句被记了两遍）
            if (out.any { it.contains(t.user.take(8)) }) continue
            out.add(line)
            if (out.size >= limit) break
        }
        if (out.isNotEmpty()) {
            Log.i(TAG, "召回 ${out.size} 条（关键词：${keywords.joinToString("/")}）")
        }
        return out
    }

    internal fun scoreTurn(t: ArchiveTurn, keywords: List<String>): Int {
        var score = 0
        for (k in keywords) {
            // 长词命中更值钱：「马拉松」比「跑」更能说明是同一件事
            val w = if (k.length >= 3) 2 else 1
            if (t.user.contains(k)) score += w
            else if (t.assistant.contains(k)) score += w - 1
        }
        return score
    }

    internal fun formatRecall(t: ArchiveTurn): String {
        val who = if (t.user.length > RECALL_SNIPPET_MAX) t.user.take(RECALL_SNIPPET_MAX) + "…" else t.user
        return "${friendlyDay(t.at)} 你说：「$who」"
    }

    /**
     * 提关键词（纯函数，可单测）。
     *
     * 中文没有空格，所以按**滑窗 n-gram** 切：中文串里取 2-gram 与 3-gram，
     * 英文/数字取整词。再滤掉高频虚词，剩下的才是最像「专有名词」的那几个。
     *
     * 排序不看首现位置，而看**这串里有多少个实词字**：
     * 「我们这」（三个虚字）排最后，「马拉松」（三个实字）排最前 ——
     * 这是没有分词词典时最接近「哪个更像一个词」的判据。
     *
     * 为什么不到分词库：一个中文分词器要带几 MB 词典，而这里的目标只是
     * 「别把『我们』『这个』当关键词」，n-gram + 停用词表已经足够。
     */
    internal fun extractKeywords(text: String, max: Int = 4): List<String> {
        val raw = text.trim()
        if (raw.isEmpty()) return emptyList()

        val candidates = LinkedHashMap<String, Int>()
        fun add(token: String, weight: Int) {
            val t = token.trim()
            if (t.length < 2) return
            if (t in STOP_WORDS) return
            if (t.none { it !in STOP_CHARS }) return
            candidates[t] = (candidates[t] ?: 0) + weight
        }

        // 中文按连续片段切，片段内取 2/3-gram
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
        // 英文 / 数字整词：专有名词（ComfyUI、deepseek）几乎总是最该被召回的关键词
        Regex("[A-Za-z0-9_]{2,20}").findAll(raw).forEach { add(it.value.lowercase(Locale.ROOT), 8) }

        return candidates.entries
            .filter { it.value >= 2 || it.key.length >= 3 }
            // 先比「实词含量」——英文整词直接压过所有中文 n-gram；再比长度、再比出现次数
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { keywordRank(it.key) }
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

    /**
     * 关键词的排序分。
     *
     * 英文/数字整词 +100：`ComfyUI`、`deepseek` 这类几乎不会误召回，
     * 命中一次就足够说明是同一件事；中文按实词字数量。
     */
    internal fun keywordRank(token: String): Int {
        val ascii = token.all { it.code < 128 }
        if (ascii) return 100 + token.length
        return token.count { it !in STOP_CHARS }
    }

    // ---------------------------------------------------------------- L3 周记

    /**
     * 最近一周的周记（注入 Prompt 用）。
     *
     * 缓存是因为拼 Prompt 是同步函数，不能在里面查库；
     * 缓存由 [init] 和每次滚动成功后刷新。
     */
    fun weeklyDigest(): List<String> = weeklyCache

    /** 从库里重读周记缓存（后台线程） */
    fun loadWeeklyAsync() {
        val ctx = appCtx ?: return
        io.execute {
            runCatching {
                runBlocking {
                    val dao = AppDatabase.get(ctx).weeklyHighlightDao()
                    val last = dao.lastWeekStart() ?: return@runBlocking emptyList()
                    dao.byWeek(last).map { it.content }
                }
            }.onSuccess {
                weeklyCache = it
                weeklyLoaded = true
                Log.i(TAG, "周记已载入 ${it.size} 条")
            }.onFailure {
                Log.w(TAG, "读取周记失败：${it.javaClass.simpleName} ${it.message}")
            }
        }
    }

    /**
     * 该做周记就做一次（节流 + 互斥，随时可调）。
     *
     * 「每 7 天」的判定不看上次生成过了多久，而看**这一周的周记做没做**：
     * 以周一 00:00 为周界，本周还没做过就为「上周」生成一份。
     * 这样用户周三点开一次、周日再点开一次，不会各生成一份重复的。
     */
    fun maybeRollWeeklyAsync(force: Boolean = false) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRollCheck < ROLL_CHECK_INTERVAL_MS) return
        lastRollCheck = now
        if (!rolling.compareAndSet(false, true)) return
        io.execute {
            try {
                val target = weekStart(now) - WEEK_MS
                val last = runBlocking {
                    runCatching { AppDatabase.get(ctx).weeklyHighlightDao().lastWeekStart() }.getOrNull()
                }
                if (!force && last != null && last >= target) {
                    // 这一周的周记已经做过了
                    return@execute
                }
                val days = (0 until 7).map { dayKey(target + it * DAY_MS) }
                val utterances = ArrayList<String>()
                for (d in days) {
                    readDay(archiveDir(ctx), d).forEach { utterances.add(it.user) }
                }
                if (utterances.isEmpty()) {
                    Log.i(TAG, "上周档案为空，跳过周记生成")
                    return@execute
                }
                val picks = pickHighlights(utterances, WeeklyHighlight.PER_WEEK)
                if (picks.isEmpty()) return@execute

                runBlocking {
                    val dao = AppDatabase.get(ctx).weeklyHighlightDao()
                    picks.forEach { content ->
                        dao.insert(
                            WeeklyHighlight(
                                weekStart = target,
                                content = content,
                                source = dayKey(target)
                            )
                        )
                    }
                    dao.trimWeeks(KEEP_WEEKS)
                }
                Log.i(TAG, "周记已生成 ${picks.size} 条（覆盖 ${days.first()} ~ ${days.last()}）")
                loadWeeklyAsync()
            } catch (e: Throwable) {
                Log.w(TAG, "周记生成失败：${e.javaClass.simpleName} ${e.message}")
            } finally {
                rolling.set(false)
            }
        }
    }

    /**
     * 从一周的用户原话里用**规则**挑出 6 条（不调任何模型）。
     *
     * 打分逻辑（都是「哪句话更像一件事」的经验）：
     *  - 出现「决定 / 说好 / 以后 / 下次 / 打算」这类**承诺与计划**：+4
     *  - 出现「喜欢 / 讨厌 / 习惯 / 每天」这类**稳定偏好**：+3
     *  - 出现「难过 / 开心 / 累 / 烦」这类**情绪**：+2
     *  - 句子本身够长（信息量大）：+1~2
     * 短句、纯指令（「打开微信」）得 0~1 分，永远进不了周记。
     */
    internal fun pickHighlights(utterances: List<String>, max: Int = WeeklyHighlight.PER_WEEK): List<String> {
        data class C(val text: String, val score: Int, val order: Int)

        val scored = utterances.mapIndexedNotNull { idx, raw ->
            val text = raw.trim()
            if (text.length < 6) return@mapIndexedNotNull null
            val score = scoreUtterance(text)
            if (score <= 1) return@mapIndexedNotNull null
            C(trimSentence(text, WeeklyHighlight.CONTENT_MAX), score, idx)
        }
        val out = ArrayList<String>(max)
        // 分高的先，同分按出现顺序（早的优先，读起来像时间线）
        for (c in scored.sortedWith(compareByDescending<C> { it.score }.thenBy { it.order })) {
            if (out.any { it.contains(c.text.take(6)) || c.text.contains(it.take(6)) }) continue
            out.add(c.text)
            if (out.size >= max) break
        }
        return out
    }

    /** 一句话的「像不像一件值得记住的事」打分（纯函数） */
    internal fun scoreUtterance(text: String): Int {
        var s = 0
        if (DECISION_WORDS.any { text.contains(it) }) s += 4
        if (PREFERENCE_WORDS.any { text.contains(it) }) s += 3
        if (EMOTION_WORDS.any { text.contains(it) }) s += 2
        s += (text.length / 20).coerceAtMost(2)
        return s
    }

    // ---------------------------------------------------------------- 工具

    /** `filesDir/persona/archive` */
    private fun archiveDir(context: Context): File =
        File(File(context.filesDir, PERSONA_DIR), ARCHIVE_DIR).apply { mkdirs() }

    /** 删掉超过 [KEEP_DAYS] 的档案 */
    private fun pruneOldArchives(context: Context) {
        val dir = archiveDir(context)
        val cutoff = dayKey(System.currentTimeMillis() - KEEP_DAYS * DAY_MS)
        var n = 0
        dir.listFiles()?.forEach { f ->
            val name = f.name.removeSuffix(".jsonl")
            if (name.length == 10 && name < cutoff) {
                if (f.delete()) n++
            }
        }
        if (n > 0) Log.i(TAG, "已清理 $n 个过期档案文件")
    }

    internal fun dayKey(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(at))

    /** 「11月03日」这种给模型看的短日期 */
    internal fun friendlyDay(at: Long): String =
        SimpleDateFormat("M月d日", Locale.CHINA).format(Date(at))

    /** 一周的周一 00:00（本地时区）。minSdk 24 不能用 java.time，所以走 Calendar */
    internal fun weekStart(at: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = at
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        // Calendar 里 SUNDAY=1 … SATURDAY=7；要「周一为一周之始」，换算成 0..6
        val delta = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_MONTH, -delta)
        return c.timeInMillis
    }

    /** 截到第一个句末标点，避免周记是半截话 */
    internal fun trimSentence(text: String, max: Int): String {
        val s = text.trim().replace(Regex("\\s+"), " ")
        if (s.length <= max) return s
        val head = s.take(max)
        val cut = head.indexOfLast { it in "。！？!?，,；;" }
        return if (cut >= max / 2) head.take(cut).trim() else head.trim() + "…"
    }

    internal fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF

    internal fun encodeLine(user: String, assistant: String, at: Long): String =
        buildString {
            append("{\"u\":").append(quote(user))
            append(",\"a\":").append(quote(assistant))
            append(",\"t\":").append(at).append('}')
        }

    internal fun decodeLine(line: String, day: String): ArchiveTurn? {
        val s = line.trim()
        if (s.isEmpty()) return null
        val m = MiniJson.parseObject(s) ?: return null
        val u = (m["u"] as? String)?.trim().orEmpty()
        val a = (m["a"] as? String)?.trim().orEmpty()
        if (u.isEmpty() || a.isEmpty()) return null
        val at = (m["t"] as? Number)?.toLong() ?: System.currentTimeMillis()
        return ArchiveTurn(u, a, at, day)
    }

    /** JSON 字符串字面量（手写：只为档案服务，且要能在纯 JVM 单测里跑） */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append("%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // ---------------------------------------------------------------- 词表

    /** 高频虚词：这些当关键词只会把「任何一句话」都召回 */
    private val STOP_WORDS = setOf(
        "我们", "你们", "他们", "她们", "什么", "这个", "那个", "这么", "那么",
        "就是", "不是", "可以", "没有", "还是", "怎么", "为什么", "因为", "所以",
        "但是", "然后", "一个", "一下", "知道", "觉得", "时候", "东西", "事情",
        "现在", "今天", "明天", "昨天", "已经", "还有", "自己", "这样", "那样",
        "如果", "应该", "需要", "真的", "确实", "可能", "或者", "而且", "不过"
    )

    /**
     * 虚字表：只由这些字组成的 n-gram 一定是语法碎片（「我们这」「这个周」），
     * 留着只会让任何两句都「有关键词重合」。
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

    /** 承诺 / 计划 / 决定 —— 最能代表「一件事」 */
    private val DECISION_WORDS = listOf(
        "决定", "说好", "约定", "约好", "以后", "下次", "计划", "打算",
        "准备", "要开始", "必须", "记住", "别再", "周五", "周末", "安排", "提醒我"
    )

    /** 稳定偏好 —— 记住一次可以长期用 */
    private val PREFERENCE_WORDS = listOf(
        "喜欢", "讨厌", "爱吃", "不吃", "习惯", "每天", "每周", "总是",
        "从来不", "最怕", "最烦", "想要", "想买", "想学"
    )

    /** 情绪高点 —— 值得回味的片段 */
    private val EMOTION_WORDS = listOf(
        "难过", "开心", "高兴", "生气", "委屈", "累了", "好累", "烦",
        "焦虑", "紧张", "害怕", "幸福", "感动", "失望"
    )
}
