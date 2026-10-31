package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.MemoryEntity
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
        "fact", "content", "text", "category", "value", "memory", "weight", "facts"
    )

    private const val PROMPT = """你是一个信息抽取器。分析下面这段对话，判断用户是否透露了「长期有用」的个人信息。

值得记住的：
- 姓名、称呼、昵称
- 喜好与厌恶（喜欢什么、讨厌什么）
- 习惯与作息（每天几点起、爱熬夜、常去哪儿）
- 人际关系（家人、同事、宠物）
- 身份职业与技能（做什么工作、学什么专业、会什么）
- 生活事件与计划（最近在备考、下周要出差、生日快到了）

不值得记住的：
- 寒暄、情绪感叹（「在吗」「谢谢」「哈哈哈」）
- 一次性指令（「打开设置」「设个闹钟」）
- 你（助手）说的话里的信息

只输出一个 JSON 数组，不要解释、不要 markdown 代码块。
示例：["用户名叫老张","用户是程序员","用户喜欢狗"]
没有可用信息就输出 []

要求：每条一句话，以「用户」开头，不超过 30 字，只写用户明确说过的，不要推测，最多 5 条。"""

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
     * @return 本次新增的记忆条数（0 表示没有新信息 / 没配 Key / 失败）
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

            val facts = parseFacts(raw)
            if (facts.isEmpty()) {
                Log.d(TAG, "本轮没有可长期记忆的信息")
                return 0
            }
            val saved = save(ctx, facts)
            Log.i(TAG, "抽取到 ${facts.size} 条，新增 $saved 条：$facts")
            return saved
        } catch (e: Throwable) {
            // 记忆抽取是增强功能，失败静默 —— 绝不能因为它让聊天报错
            Log.w(TAG, "长期记忆抽取失败：${e.javaClass.simpleName} ${e.message}")
            return 0
        } finally {
            busy.set(false)
        }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 从模型输出里抠出事实列表。
     *
     * 真实的模型经常不听话：包 ```json 代码块、前面写「好的，提取结果如下：」、
     * 甚至该给数组却给了 `[{"fact":"…"}]`。这里一律先找第一个 `[` 到最后一个 `]`，
     * 再逐个扫双引号字面量 —— 为什么不直接 `JSONArray`：纯 JVM 单测里 `org.json`
     * 是 android.jar 的空壳（方法全返回默认值），会让这个最需要测的函数没法测。
     * 对象形式时靠 [KEY_NAMES] 把 key 过滤掉，只留 value。
     */
    internal fun parseFacts(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val body = text.substring(start + 1, end)

        val out = ArrayList<String>(MAX_FACTS_PER_CALL)
        var i = 0
        while (i < body.length && out.size < MAX_FACTS_PER_CALL) {
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
