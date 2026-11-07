package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.MemoryEntity
import com.nexus.agent.data.db.TopicSeed
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 长期记忆提取器（0.8.0）。
 *
 * 0.7.12 只把 `long_term_memory` 这张表建好了，没有任何东西往里写 ——
 * 于是「记忆」实际上还是 [com.nexus.agent.copilot.PersonaEngine] 里那 10 轮环形窗口，
 * 一旦聊满 10 轮，最前面说过的话就永久丢了。
 *
 * 这里补上缺的那一环：**每聊完一轮，让便宜模型顺手做一次信息抽取**，
 * 把「用户名叫老张」「用户是程序员」这类长期有用的事实提炼出来存库，
 * 组装提示词时再召回。窗口负责「刚说过什么」，这张表负责「永远记得什么」。
 *
 * ## 为什么用独立的小请求
 *  - 不污染主对话的上下文（抽取结果只有 JSON，不需要留给用户看）
 *  - 主对话换模型/换话术都不影响抽取逻辑
 *  - 失败完全静默：抽不出来顶多记不住，绝不能让聊天因为这事儿报错
 *
 * 抽取用的模型：优先 `deepseek-chat`（便宜），没配 Key 就跟随当前主 provider。
 */
object MemoryExtractor {

    private const val TAG = "MemoryExtractor"

    /** 首选的抽取模型 provider（便宜、够用） */
    private const val PREFERRED_PROVIDER = "deepseek"

    /** 一次最多收几条（LLM 有时候会一口气吐出十几条，会淹掉真正的关键信息） */
    private const val MAX_FACTS_PER_CALL = 5

    /** 单条事实最长字数（要塞进 prompt，太长就成负担了） */
    private const val MAX_FACT_LEN = 60

    /** 太短的话没有信息量，直接跳过（省一次请求） */
    private const val MIN_USER_LEN = 4

    /** 一次最多收几个话题种子（多了会攒一堆过期话题，反而天天问旧事） */
    private const val MAX_TOPICS_PER_CALL = 3

    /** 抽取请求超时：抽不到就算了，不能挂住 */
    private const val TIMEOUT_MS = 25_000L

    /** 表里最多留多少条事实（超了按权重裁掉最没用的） */
    private const val MAX_MEMORIES = 200

    /** 去重时检查的历史条数 */
    private const val EXISTING_SCAN = 200

    /**
     * 解析时不该被当成事实的 key（模型偶尔把数组写成对象）
     */
    private val KEY_NAMES = setOf(
        "fact", "content", "text", "category", "value", "memory", "weight", "facts", "topics"
    )

    /**
     * 抽取提示词（0.9.4 起一次调用抽两样东西）。
     *
     * 为什么不用两次请求：每轮对话都跑一次抽取，两次请求等于把成本和延迟翻倍，
     * 而「抽事实」和「抽话题」看的是同一段原文 —— 没理由让模型读两遍。
     *
     * 为什么要 `topics`：0.9.3 的心跳只会「时间到了就找句话说说」，
     * 于是她永远在问「在忙吗」，而你昨天说的「明天体检」她一个字都不记得。
     */
    private const val PROMPT = """你是一个信息抽取器。分析下面这段对话，输出一个 JSON 对象，包含 facts 和 topics 两个数组。

facts —— 长期有用的个人信息：
- 姓名、称呼、昵称
- 喜好与厌恶（喜欢什么、讨厌什么）
- 习惯与作息（每天几点起、爱熬夜、常去哪儿）
- 人际关系（家人、同事、宠物）
- 身份职业与技能（做什么工作、学什么专业、会什么）
每条以「用户」开头，不超过 30 字，最多 5 条。

topics —— 他正在经历或即将发生的具体事件，适合过一阵子主动关心一句。只收两类：
- 计划：明天体检、下周面试、后天出差、周末搬家、快考试了、要交报告
- 身体或情绪状况：头疼、发烧、失眠、加班到很晚、心情不好、跟人吵架了
每条 4~15 字，直接写是什么事，不要「用户」前缀，最多 3 条。
一次性的事（吃了饭、看了电影、打游戏赢了）不要收。

不值得记住的：寒暄、情绪感叹（「在吗」「谢谢」「哈哈哈」）、一次性指令（「打开设置」）、
助手自己说的话里的信息。

只输出 JSON，不要解释、不要 markdown 代码块。示例：
{"facts":["用户名叫老张","用户是程序员"],"topics":["明天要体检","最近头疼"]}
没有可提取的信息就输出 {"facts":[],"topics":[]}"""

    /** 应用上下文（由 [init] 注入） */
    @Volatile
    private var appCtx: Context? = null

    /** 同一时刻只允许一次抽取在跑，避免连发消息时堆一队请求 */
    private val busy = AtomicBoolean(false)

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * 抽取并落库。
     *
     * 0.9.4 起一次调用抽两样：长期事实（[MemoryEntity]）和话题种子（[TopicSeed]）。
     * 前者回答「他是谁」，后者回答「她该为了什么开口」—— 后者才是这一版的重点。
     *
     * @return 本次新增的条数（事实 + 话题，0 表示没有新信息 / 没配 Key / 失败）
     */
    suspend fun extractAndSave(userMsg: String, aiReply: String): Int {
        val ctx = appCtx ?: return 0
        val user = userMsg.trim()
        val reply = aiReply.trim()
        if (user.length < MIN_USER_LEN) return 0
        // 「在吗」「你好」这类问完就完，没有可抽取的内容
        if (reply.isEmpty()) return 0
        // 指令类消息不值钱，还会让抽取模型把「用户要打开设置」当成长期事实
        if (looksLikeCommand(user)) return 0

        if (!busy.compareAndSet(false, true)) {
            Log.d(TAG, "上一次抽取还没结束，跳过本轮")
            return 0
        }
        try {
            val provider = pickProvider(ctx)
            val (key, _, model) = LlmClientFactory.getConfig(ctx, provider)
            // Ollama 本地不需要 Key，其他家没 Key 就别白跑一趟
            if (key.isBlank() && provider != "ollama") {
                Log.i(TAG, "未配置 $provider 的 API Key，跳过长期记忆抽取")
                return 0
            }

            val client = LlmClientFactory.create(provider, ctx)
            val raw = withTimeoutOrNull(TIMEOUT_MS) {
                client.chat(
                    listOf(
                        LlmClient.Message("system", PROMPT),
                        LlmClient.Message("user", "用户说：$user\n助手回复：$reply")
                    )
                )
            }
            if (raw.isNullOrBlank()) {
                Log.i(TAG, "抽取超时或无返回（provider=$provider model=$model）")
                return 0
            }

            val extraction = parseExtraction(raw)
            if (extraction.facts.isEmpty() && extraction.topics.isEmpty()) {
                Log.d(TAG, "本轮没有可长期记忆的信息 / 也没有值得关心的事")
                return 0
            }
            val savedFacts = if (extraction.facts.isEmpty()) 0 else save(ctx, extraction.facts)
            val savedTopics = if (extraction.topics.isEmpty()) 0 else saveTopics(ctx, extraction.topics)
            Log.i(
                TAG,
                "抽取到事实 ${extraction.facts.size} 条（新增 $savedFacts）、" +
                    "话题 ${extraction.topics.size} 条（新增 $savedTopics）：${extraction.topics}"
            )
            return savedFacts + savedTopics
        } catch (e: Throwable) {
            // 记忆抽取是增强功能，失败静默 —— 绝不能因为它让聊天报错
            Log.w(TAG, "长期记忆抽取失败：${e.javaClass.simpleName} ${e.message}")
            return 0
        } finally {
            busy.set(false)
        }
    }

    // ---------------------------------------------------------------- 解析

    /** 一次抽取的产物：长期事实 + 可主动关心的话题 */
    internal data class Extraction(val facts: List<String>, val topics: List<String>)

    /**
     * 解析模型输出。
     *
     * 三种形态都要能吃下：
     *  1. 新格式 `{"facts":[...],"topics":[...]}` —— 正常情况；
     *  2. 裸数组 `["…","…"]` —— 模型偷懒 / 沿用旧习惯，全当事实；
     *  3. 只有 facts 没有 topics —— 这一轮确实没什么可关心的。
     */
    internal fun parseExtraction(raw: String): Extraction {
        if (raw.isBlank()) return Extraction(emptyList(), emptyList())
        val text = unwrapFence(raw)
        val factsIdx = text.indexOf("\"facts\"")
        val topicsIdx = text.indexOf("\"topics\"")
        if (factsIdx < 0 && topicsIdx < 0) {
            // 没按格式来：整段当事实数组解析，话题留空
            return Extraction(parseFacts(text), emptyList())
        }
        val facts = if (factsIdx >= 0) {
            parseQuotedStrings(arraySegment(text, factsIdx).orEmpty(), MAX_FACTS_PER_CALL)
        } else {
            emptyList()
        }
        val topics = if (topicsIdx >= 0) {
            parseQuotedStrings(arraySegment(text, topicsIdx).orEmpty(), MAX_TOPICS_PER_CALL)
        } else {
            emptyList()
        }
        return Extraction(facts, topics)
    }

    /**
     * 从模型输出里抠出事实列表（老接口，保留给单测与裸数组形态）。
     *
     * 真实的模型经常不听话：包 ```json 代码块、前面写「好的，提取结果如下：」、
     * 甚至该给数组却给了 `[{"fact":"…"}]`。对象形式时靠 [KEY_NAMES] 把 key 过滤掉，只留 value。
     */
    internal fun parseFacts(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val seg = arraySegment(unwrapFence(raw), 0) ?: return emptyList()
        return parseQuotedStrings(seg, MAX_FACTS_PER_CALL)
    }

    /** 去掉 ```json 围栏与首尾空白 */
    private fun unwrapFence(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```")
        .trim()

    /**
     * 从 [from] 起找第一个 JSON 数组，返回**含括号的完整片段**。
     *
     * 必须做括号配对而不是「找到第一个 ] 就收」：字符串里完全可能出现 `]`
     * （「用户喜欢[海贼王]」），按第一个 `]` 截会把内容切成半句。
     * 引号内的括号不计数，反斜杠转义的字符跳过。
     */
    private fun arraySegment(text: String, from: Int): String? {
        if (from < 0 || from >= text.length) return null
        val start = text.indexOf('[', from)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            when {
                inStr && c == '\\' -> i++          // 跳过被转义的字符
                c == '"' -> inStr = !inStr
                !inStr && c == '[' -> depth++
                !inStr && c == ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /** 扫一个数组片段里的双引号字面量：去重、过滤 key、限长 */
    private fun parseQuotedStrings(segment: String, max: Int): List<String> {
        if (segment.length < 2) return emptyList()
        val body = segment.substring(1, segment.length - 1)
        val out = ArrayList<String>(max)
        var i = 0
        while (i < body.length && out.size < max) {
            if (body[i] != '"') {
                i++
                continue
            }
            i++ // 跳过起始引号
            val sb = StringBuilder()
            var closed = false
            while (i < body.length) {
                val ch = body[i]
                when {
                    ch == '\\' && i + 1 < body.length -> {
                        when (val esc = body[i + 1]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'u' -> i += 4 // \uXXXX 共 6 位，下面还会再 += 2
                            else -> sb.append(esc)
                        }
                        i += 2
                    }
                    ch == '"' -> {
                        i++
                        closed = true
                        break
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            if (!closed) break
            val s = sb.toString().trim().trim('「', '」', '。', '，', ',', '.', ' ')
            if (s.isEmpty()) continue
            if (KEY_NAMES.contains(s.lowercase())) continue
            val fact = if (s.length > MAX_FACT_LEN) s.take(MAX_FACT_LEN) else s
            if (out.none { it == fact }) out.add(fact)
        }
        return out
    }

    // ---------------------------------------------------------------- 话题种子

    /** 一小时毫秒数 */
    private const val HOUR_MS = 3_600_000L

    /** 「计划」类关键词：说明这件事有明确的将来时 */
    private val PLAN_WORDS = listOf(
        "体检", "面试", "考试", "考研", "答辩", "汇报", "开会", "出差", "搬家",
        "手术", "复查", "住院", "约会", "生日", "旅行", "交报告", "截止", "报名",
        "签证", "婚礼", "入职", "离职", "开庭", "比赛", "表演", "回老家"
    )

    /** 「身体/情绪」类关键词：过两天要是不疼了，再问就是尴尬 */
    private val BODY_WORDS = listOf(
        "疼", "痛", "难受", "发烧", "感冒", "咳嗽", "失眠", "睡不着", "头晕",
        "拉肚子", "胃", "嗓子", "过敏", "受伤", "加班", "熬夜", "压力", "焦虑",
        "烦", "难过", "心情不好", "吵架", "崩溃", "累"
    )

    /**
     * 未来时间词：有它基本就说明是「还没发生」的事。
     *
     * 特意**不含**「今天」「昨天」这类中性/过去时间词 —— 含了的话「今天吃了火锅」
     * 会被当成待办，三天后她突然问「火锅好吃吗」。
     */
    private val FUTURE_WORDS = listOf(
        "明天", "后天", "下周", "下个星期", "下礼拜", "周末", "这周",
        "本周", "月底", "月初", "下个月", "快", "马上", "待会", "今晚"
    )

    /** 已完成的标志：「了 / 过 / 刚 / 已经」出现通常意味着这事讲完了 */
    private val PAST_WORDS = listOf("了", "过", "刚", "已经", "昨天", "前天")

    /** 和「过 / 了」同时出现时说明是持续状态而非完成态（过敏、过去一周都在疼） */
    private val ONGOING_WORDS = listOf("过敏", "睡不着", "没过", "受不了")

    /**
     * 这条话题值不值得存成种子。
     *
     * 模型经常把「今天吃了火锅」也塞进 topics，所以本地要再拦一道。
     * 判定顺序就是「宁可漏一条，也不要天天问火锅好吃吗」：
     *
     *  1. 命中未来时间词（明天/下周/待会）→ 存；
     *  2. 命中计划词（体检/面试/出差）→ 存；
     *  3. 讲的是已经结束的一次性事件（有「了/刚/过」且不是持续状态）→ 丢；
     *  4. 命中身体/情绪词（头疼/加班/失眠）→ 存，这类是持续状态，隔天关心正合适；
     *  5. 其余一律不存。
     */
    internal fun looksLikeSeed(topic: String): Boolean {
        val t = topic.trim()
        if (t.length < 2) return false
        if (FUTURE_WORDS.any { t.contains(it) }) return true
        if (PLAN_WORDS.any { t.contains(it) }) return true
        if (isFinishedEvent(t)) return false
        return BODY_WORDS.any { t.contains(it) }
    }

    /** 已经讲完的一次性事件：「今天吃了火锅」「刚看完一部电影」 */
    private fun isFinishedEvent(t: String): Boolean {
        val past = PAST_WORDS.any { t.contains(it) }
        if (!past) return false
        // 「最近过敏」「睡不着」带「过/着」但不是完成态
        return ONGOING_WORDS.none { t.contains(it) }
    }

    /**
     * 这条种子什么时候过期。
     *
     * 阈值不是拍脑袋：小病小痛隔天就该好了，问晚了显得她根本不听人说话；
     * 「下周面试」拖到两周后才问，人早就面完了。
     */
    internal fun expireAtFor(topic: String, nowMs: Long): Long {
        val t = topic
        val hours = when {
            t.contains("后天") -> 60L
            t.contains("明天") || t.contains("今晚") -> 36L
            t.contains("下周") || t.contains("下个星期") || t.contains("下礼拜") ||
                t.contains("下个月") -> 192L
            t.contains("周末") || t.contains("这周") || t.contains("本周") -> 96L
            t.contains("生日") -> 168L
            PLAN_WORDS.any { t.contains(it) } -> 72L
            BODY_WORDS.any { t.contains(it) } -> 24L
            else -> 48L
        }
        return nowMs + hours * HOUR_MS
    }

    private suspend fun saveTopics(ctx: Context, topics: List<String>): Int {
        val dao = AppDatabase.get(ctx).topicSeedDao()
        val now = System.currentTimeMillis()
        var saved = 0
        for (raw in topics) {
            val content = raw.trim().trim('「', '」', '。', '，', ',', '.', ' ', '"')
                .take(TopicSeed.CONTENT_MAX)
            if (!looksLikeSeed(content)) {
                Log.d(TAG, "话题不够具体，丢弃：$content")
                continue
            }
            val row = TopicSeed(
                content = content,
                source = TopicSeed.SOURCE_CHAT,
                createdAt = now,
                expireAt = expireAtFor(content, now)
            )
            // 唯一索引 + IGNORE：同一条话题被反复提到时返回 -1，不算新增
            val id = runCatching { dao.insert(row) }.getOrDefault(-1L)
            if (id > 0) saved++ else Log.d(TAG, "话题已存在，跳过：$content")
        }
        runCatching {
            dao.purgeExpired(now)
            dao.trimTo(TopicSeed.MAX_SEEDS)
        }
        return saved
    }

    /**
     * 粗分类。只按关键词猜，猜错也不影响召回（`top()` 只看权重和时间），
     * 留着是为了以后做「按类召回」和记忆管理界面。
     */
    internal fun classify(fact: String): String = when {
        fact.containsAny("名叫", "名字", "叫", "称呼", "昵称") -> MemoryEntity.CATEGORY_RELATION
        fact.containsAny("喜欢", "讨厌", "爱吃", "爱好", "偏好", "不喜欢") -> MemoryEntity.CATEGORY_PREFERENCE
        fact.containsAny("每天", "习惯", "总是", "经常", "每晚", "作息") -> MemoryEntity.CATEGORY_HABIT
        fact.containsAny("生日", "明天", "下周", "最近", "昨天", "今天", "打算", "计划", "考上", "面试")
            -> MemoryEntity.CATEGORY_EVENT
        else -> MemoryEntity.CATEGORY_GENERAL
    }

    /** 权重：身份/关系类事实最值钱，事件类折旧最快 */
    internal fun weightOf(fact: String, category: String = classify(fact)): Float = when (category) {
        MemoryEntity.CATEGORY_RELATION -> 1.0f
        MemoryEntity.CATEGORY_PREFERENCE -> 0.9f
        MemoryEntity.CATEGORY_HABIT -> 0.8f
        MemoryEntity.CATEGORY_EVENT -> 0.6f
        else -> 0.7f
    }

    // ---------------------------------------------------------------- 落库

    private suspend fun save(ctx: Context, facts: List<String>): Int {
        val dao = AppDatabase.get(ctx).memoryDao()
        val existing = runCatching { dao.top(EXISTING_SCAN) }.getOrDefault(emptyList())
        val known = existing.associateBy { it.fact }
        val now = System.currentTimeMillis()
        var saved = 0
        for (f in facts) {
            val hit = known[f]
            if (hit != null) {
                // 又提到了同一件事：刷新命中时间，让遗忘曲线能看出它还被记得
                runCatching { dao.touch(hit.id, now) }
                continue
            }
            runCatching {
                val category = classify(f)
                dao.insert(
                    MemoryEntity(
                        fact = f,
                        category = category,
                        weight = weightOf(f, category),
                        createdAt = now,
                        lastHitAt = now
                    )
                )
                saved++
            }.onFailure { Log.w(TAG, "写入记忆失败：$f") }
        }
        // 表不能无限长：按权重裁掉最没用的
        runCatching { dao.trimTo(MAX_MEMORIES) }
        return saved
    }

    // ---------------------------------------------------------------- 工具

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) PREFERRED_PROVIDER else LlmClientFactory.getProvider(ctx)
    }

    /** 是不是「打开 X / 帮我点 / 设个闹钟」这类一次性指令 */
    private fun looksLikeCommand(text: String): Boolean {
        val t = text.trim()
        if (t.length > 24) return false
        val verbs = listOf("打开", "关掉", "帮我点", "设个", "定个", "提醒我", "截屏", "滑一下")
        return verbs.any { t.contains(it) }
    }

    private fun String.containsAny(vararg keys: String): Boolean = keys.any { contains(it) }
}
