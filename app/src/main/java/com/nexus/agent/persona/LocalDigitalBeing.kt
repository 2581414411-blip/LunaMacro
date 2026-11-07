package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.util.MiniJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地长记忆引擎（0.8.12）—— 「滑动窗口 + 滚动摘要」，全部跑在 App 内，不依赖任何外部服务。
 *
 * ## 之前的问题
 *
 * 0.8.0 的记忆是**一条 10 轮的环形缓冲**：聊满 10 轮，最早那句就永久没了。
 * 后来加的 `MemoryExtractor`（抽事实存 `long_term_memory`）只解决「用户是谁」这类
 * 静态信息，解决不了「上周我们商量到第几步了」这种**过程性记忆**。
 *
 * ## 现在怎么记（0.9.0 起由 [FlexiMem] 补上 L1~L3，本类管 L4）
 *
 * ```
 *  L1 原文档案  filesDir/persona/archive/YYYY-MM-DD.jsonl   每轮都写，0 Token
 *  L2 检索召回  FlexiMem.recall(用户这句)                    →【回忆】3 条
 *  L3 周记      Room.weekly_highlight                        →【本周印象】6 条/周
 *  ┌──────────── L4 rollingSummary（≤100 字/段，总长 ≤400 字）────────────┐
 *  │  更早的对话被压成几段摘要，一直往后叠                                 │
 *  └───────────────────────────────────────────────────────────────────┘
 *  ┌────────────── 滑动窗口（最多 20 轮原话）──────────────────────────────┐
 *  │  ... 第 11 轮 → 第 12 轮 → ... → 第 30 轮                           │
 *  └───────────────────────────────────────────────────────────────────┘
 * ```
 *
 * 触发规则（和「DigitalBeing」那套一致，只是搬进了 App）：
 *  - 窗口 **超过 [MAX_TURNS]（20）轮** → 取**最旧的 [EVICT_TURNS]（10）轮**；
 *  - 调一次**便宜的模型**（优先 `deepseek-chat`，没 Key 就跟随主 provider）
 *    压成 **100 字以内**的摘要，追加到 [rollingSummary]；
 *  - 摘要成功后才把这 10 轮移出窗口 —— 摘要失败就原地保留，下一轮再试，
 *    宁可窗口暂时胖一点，也不能把话丢了还不留痕。
 *
 * ## 为什么用 JSON 文件而不是 Room
 *
 * 这份数据只有「一段摘要 + 最多 20 轮」，读写都是一次全量替换，没有按条件查询的需求；
 * 塞进 Room 要为它加表 + 加版本 + 写迁移，而迁移写错会直接 `Room cannot verify`。
 * 所以放在 `filesDir/persona/long_memory.json`，**先写临时文件再 rename**，断电也不会写坏半截。
 * 聊天流水本身仍然在 Room（[com.nexus.agent.data.db.ChatHistory]），两者互不干扰。
 *
 * ## 线程模型
 *
 * - [chat] / [recordTurn] 可以在任意线程调用，窗口操作全部 `synchronized(window)`；
 * - 落盘和摘要都在 [scope]（IO）里异步跑，**绝不拖慢这一轮回复**；
 * - 摘要同一时刻只允许一次（[summarizing]），连发消息不会堆一队请求。
 */
object LocalDigitalBeing {

    private const val TAG = "LocalDigitalBeing"

    /** 滑动窗口保留的轮数（一问一答 = 1 轮） */
    const val MAX_TURNS = 20

    /** 触发摘要时一次性移出窗口的轮数 */
    const val EVICT_TURNS = 10

    /** 单段摘要的字数上限（要求模型「100 字以内」，这里再兜一道） */
    const val SUMMARY_MAX_CHARS = 100

    /** 滚动摘要的总长上限：超出就从头砍，只留最近的（越近越有用） */
    internal const val SUMMARY_TOTAL_MAX = 400

    /**
     * 窗口的硬上限。
     *
     * 摘要模型一直不可用（没 Key / 一直超时）时窗口会只增不减，
     * 到 [HARD_CAP] 就强行丢掉最旧的一半 —— 丢记忆总比把 prompt 撑爆好，
     * 而且会打 WARN 日志，不会静默。
     */
    private const val HARD_CAP = MAX_TURNS + 20

    /** 摘要请求超时：压不出来就算了，不能挂住 */
    private const val SUMMARY_TIMEOUT_MS = 20_000L

    /** 摘要连续失败几次就退回环形窗口（1 次可能只是网络抖，2 次基本就是没配 Key / 模型不对） */
    private const val MAX_SUMMARY_FAILURES = 2

    /** 落盘目录（相对 filesDir） */
    private const val DIR_NAME = "persona"
    private const val FILE_NAME = "long_memory.json"

    /** 同一条记录在 5 秒内被重复写入视为重复记账（respond 与调用方各记一次的保险） */
    private const val DEDUPE_WINDOW_MS = 5_000L

    /** 一轮对话（只存文本：历史里带图会让每轮请求体积翻几倍） */
    data class Turn(
        val user: String,
        val assistant: String,
        val at: Long = System.currentTimeMillis()
    )

    /**
     * 这一轮要额外塞进 Prompt 的东西（0.9.0）。
     *
     * 为什么不让 [Brain] 自己去拿：聊天、语音、定时问候走的是同一个 [chat]，
     * 但只有主对话有屏幕、只有主对话需要回忆。把这些做成**入参**而不是全局状态，
     * 才能保证「语音问候时不会莫名其妙描述屏幕」。
     *
     * @param screenNote 本地视觉模型对当前屏幕的一句话描述（0.9.0 批次一）
     * @param recall     FlexiMem 检索召回的历史原话（批次二）
     * @param weekly     FlexiMem 周记里的关键记忆（批次二）
     */
    data class Extras(
        val screenNote: String? = null,
        val recall: List<String> = emptyList(),
        val weekly: List<String> = emptyList()
    ) {
        val isEmpty: Boolean
            get() = screenNote.isNullOrBlank() && recall.isEmpty() && weekly.isEmpty()

        companion object {
            val NONE = Extras()
        }
    }

    /**
     * 「用谁来说话」—— 引擎只管窗口和摘要，说什么、什么语气由外部决定。
     *
     * 这样 [com.nexus.agent.copilot.PersonaEngine] 可以把人格提示词、画面描述、
     * 工具执行结果全部包进 [reply] 的实现里，本类不需要认识人设。
     */
    interface Brain {
        /**
         * @param history 窗口内的原话（已按时间正序，最早的在前）
         * @param summary 滚动摘要（可能为空）
         * @return 助手回复；null = 这条链路不可用（没 Key / 超时 / 空回复）
         */
        suspend fun reply(history: List<Turn>, summary: String): String?

        /**
         * 带附加上下文的版本（0.9.0）。
         *
         * 默认实现直接退回 [reply] —— 语音、定时任务这些没有屏幕/回忆的调用方
         * 不需要关心它；只有主对话（[com.nexus.agent.copilot.PersonaEngine.respond]）
         * 覆写它，把画面描述与回忆拼进 Prompt。
         *
         * 为什么不直接改 [reply] 的签名：那个接口已经被单测和别的调用方实现，
         * 加一个带默认实现的方法就不会逼着所有实现跟着改。
         */
        suspend fun replyWith(history: List<Turn>, summary: String, extras: Extras): String? =
            reply(history, summary)
    }

    /** 默认大脑（不给 [chat] 传 brain 时用它；由 PersonaEngine 注册） */
    @Volatile
    private var defaultBrain: Brain? = null

    /** 滑动窗口（最早的在前） */
    private val window = ArrayDeque<Turn>()

    /** 滚动摘要：窗口之外还记着的东西 */
    @Volatile
    private var rollingSummary: String = ""

    @Volatile
    private var appCtx: Context? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 摘要互斥：上一次没跑完就不再叠一次 */
    private val summarizing = AtomicBoolean(false)

    /** 连续摘要失败次数（达到 [MAX_SUMMARY_FAILURES] 就退回纯环形窗口） */
    @Volatile
    private var summaryFailures = 0

    /**
     * 降级标志：摘要模型彻底不可用时为 true。
     *
     * 此时窗口按**老行为**当环形缓冲用（超过 20 轮丢最旧的一条）。
     * 为什么不一直攒着等摘要：没 Key 的用户会看着窗口无限长、prompt 越来越贵，
     * 而他这辈子也等不到那一次摘要成功。
     */
    @Volatile
    private var degraded = false

    // ---------------------------------------------------------------- 生命周期

    /** 注入上下文并异步读回磁盘上的长记忆（幂等） */
    fun init(context: Context) {
        if (appCtx != null) return
        val ctx = context.applicationContext
        appCtx = ctx
        // 0.9.0 FlexiMem：L1 原文档案 + L3 周记。init 幂等，重复调用不会重复建目录。
        FlexiMem.init(ctx)
        scope.launch {
            runCatching { load(ctx) }
                .onFailure { Log.w(TAG, "读取长记忆失败：${it.javaClass.simpleName} ${it.message}") }
        }
    }

    /** 注册默认大脑（PersonaEngine 初始化时调；单测里可以不注册） */
    fun registerBrain(brain: Brain?) {
        defaultBrain = brain
    }

    // ---------------------------------------------------------------- 主入口

    /**
     * 以长记忆为上下文，跟用户聊一句。
     *
     * 流程：**取窗口 + 摘要 → 交给 [brain] 生成 → 记账（含触发摘要）**。
     *
     * @param userInput  用户原话
     * @param brain      这一轮用哪个大脑；null = 用 [registerBrain] 注册的默认大脑
     * @param screenNote 本地视觉模型看到的画面（0.9.0）。非空时会以
     *                  「【当前看到的屏幕】xxx」的形式进入本轮 Prompt，
     *                  这是「人格聊天能看见屏幕」的唯一入口。
     * @return 助手回复；null = 大脑不可用 / 空输入 / 模型没答上来（调用方应降级）
     */
    suspend fun chat(
        userInput: String,
        brain: Brain? = null,
        screenNote: String? = null
    ): String? {
        val b = brain ?: defaultBrain ?: run {
            Log.i(TAG, "没有可用的大脑，本轮不记账")
            return null
        }
        val text = userInput.trim()
        if (text.isEmpty()) return null

        val history = windowSnapshot()
        val summary = rollingSummary
        // 0.9.0 FlexiMem：L2 检索召回 + L3 周记。都是纯本地读，不调模型、不花钱。
        // 只有真的接上了上下文（appCtx != null）才做 —— 纯 JVM 单测里直接跳过。
        val recall = if (appCtx != null) FlexiMem.recall(text) else emptyList()
        val weekly = if (appCtx != null) FlexiMem.weeklyDigest() else emptyList()
        val extras = Extras(
            screenNote = screenNote?.trim()?.ifBlank { null },
            recall = recall,
            weekly = weekly
        )
        // 0.9.0 批次一：这一行是「画面描述到底有没有进到长记忆引擎」的凭据。
        // 主对话日志里看到「本轮注入画面=0 字」就说明上游 describeScreenInWorker 没给东西。
        Log.d(
            "PersonaVision",
            "长记忆引擎收到本轮上下文：画面=${extras.screenNote?.length ?: 0} 字，" +
                "回忆=${recall.size} 条，周记=${weekly.size} 条，" +
                "历史=${history.size} 轮，摘要=${summary.length} 字"
        )
        val answer = try {
            b.replyWith(history, summary, extras)
        } catch (e: Throwable) {
            // 大脑挂了是「降级信号」，不是崩溃信号：窗口原封不动，下一轮还能用
            Log.w(TAG, "生成回复失败：${e.javaClass.simpleName} ${e.message}")
            null
        }?.trim()
        if (answer.isNullOrEmpty()) return null

        recordTurn(text, answer)
        return answer
    }

    // ---------------------------------------------------------------- 窗口 / 摘要

    /** 记忆当前内容（副本，最早的在前） */
    fun windowSnapshot(): List<Turn> = synchronized(window) { window.toList() }

    fun windowSize(): Int = synchronized(window) { window.size }

    /** 滚动摘要（拼进 System Prompt 用） */
    fun summaryText(): String = rollingSummary

    /**
     * 记一轮对话并落盘。
     *
     * @return true = 真的记进去了（空内容 / 5 秒内重复记账返回 false）
     */
    fun recordTurn(user: String, assistant: String): Boolean {
        val u = user.trim()
        val a = assistant.trim()
        if (u.isEmpty() || a.isEmpty()) return false

        val needSummary: Boolean
        var stamp = System.currentTimeMillis()
        synchronized(window) {
            val last = window.peekLast()
            val now = System.currentTimeMillis()
            // 同一条内容在极短时间内重复写入 = 两条链路各记了一次（respond 内部 + 调用方），只留一条
            if (last != null && last.user == u && last.assistant == a &&
                now - last.at in 0..DEDUPE_WINDOW_MS
            ) {
                return false
            }
            window.addLast(Turn(u, a, now))
            needSummary = window.size > MAX_TURNS
            stamp = now
        }
        // 0.9.0 L1：无条件写原文档案。和「要不要触发摘要」完全无关 ——
        // 摘要是有损压缩，档案是唯一能还原原话的那一层，一次模型都不调（0 Token）。
        FlexiMem.appendTurn(u, a, stamp)
        // 0.9.0 L3：顺手看看该不该滚一次周记（内部按小时节流 + 互斥，成本接近 0）
        FlexiMem.maybeRollWeeklyAsync()
        persistAsync()
        if (needSummary) {
            // 摘要跑不了（没 init / 已判定不可用）就直接当环形缓冲：宁可丢旧的，也不无限长
            if (degraded || appCtx == null) trimToWindow() else summarizeAsync()
        }
        return true
    }

    /**
     * 把库里读回来的窗口填进内存。
     *
     * 只在内存窗口为空时生效：冷启动后用户可能在读盘完成前就说了话，
     * 那种情况下以内存里的新记录为准（和 PersonaEngine.restore 的思路一致）。
     */
    fun restoreWindow(turns: List<Turn>) {
        if (turns.isEmpty()) return
        synchronized(window) {
            if (window.isNotEmpty()) return
            window.addAll(turns.takeLast(MAX_TURNS))
        }
        persistAsync()
        Log.i(TAG, "窗口已从存档恢复 ${windowSize()} 轮")
    }

    /** 清空窗口与摘要（「清空聊天」时调用，否则会出现「界面空了但它还记得」的矛盾） */
    fun clear() {
        synchronized(window) { window.clear() }
        rollingSummary = ""
        // 0.9.0：FlexiMem 的两层也要清 —— 不然用户点了「清空聊天」，
        // 下一句还是能被检索召回捞到"已经删掉"的原话，那是最像 bug 的体验。
        appCtx?.let { FlexiMem.clearAll(it) }
        persistAsync()
        Log.i(TAG, "长记忆已清空")
    }

    /**
     * 触发一次压缩，**同步执行**（调用方自己决定要不要放后台）。
     *
     * @param request 摘要生成器（默认走 [requestSummary]；单测可注入假实现）
     * @return true = 摘要成功并已移出窗口
     */
    internal suspend fun summarizeOnce(
        request: suspend (List<Turn>) -> String? = { requestSummary(it) }
    ): Boolean {
        val batch = synchronized(window) {
            if (window.size <= MAX_TURNS) return false
            window.take(EVICT_TURNS)
        }
        if (batch.size < EVICT_TURNS) return false

        val summary = request(batch)
        if (summary.isNullOrBlank()) {
            summaryFailures++
            Log.w(
                TAG,
                "摘要失败（连续 $summaryFailures 次），${batch.size} 轮先留在窗口里，下轮再试"
            )
            if (summaryFailures >= MAX_SUMMARY_FAILURES) {
                if (!degraded) {
                    degraded = true
                    Log.w(
                        TAG,
                        "摘要模型连续 ${MAX_SUMMARY_FAILURES} 次不可用，窗口退化为 $MAX_TURNS 轮环形缓冲"
                    )
                }
                trimToWindow()
            }
            hardCapIfNeeded()
            return false
        }
        summaryFailures = 0
        degraded = false
        return applySummary(batch, summary)
    }

    /** 纯环形窗口：超过 [MAX_TURNS] 就丢最旧的（摘要不可用时的降级行为） */
    private fun trimToWindow() {
        var dropped = 0
        synchronized(window) {
            while (window.size > MAX_TURNS) {
                window.removeFirst()
                dropped++
            }
        }
        if (dropped > 0) Log.d(TAG, "窗口已按环形缓冲裁掉最旧的 $dropped 轮")
    }

    /**
     * 把摘要并进 [rollingSummary] 并移出这批轮次。
     *
     * 纯内存操作 + 落盘，抽出来是为了能单测（不依赖 LLM）。
     */
    internal fun applySummary(evicted: List<Turn>, summary: String): Boolean {
        if (evicted.isEmpty()) return false
        val merged = mergeSummary(rollingSummary, summary)
        synchronized(window) {
            val n = minOf(evicted.size, window.size)
            repeat(n) { window.removeFirst() }
            rollingSummary = merged
        }
        persistAsync()
        Log.i(
            TAG,
            "已压缩 ${evicted.size} 轮 → 摘要 ${summary.length} 字，窗口剩 ${windowSize()} 轮，" +
                "滚动摘要共 ${merged.length} 字"
        )
        return true
    }

    /** 摘要失败且窗口胀到硬上限：强行丢掉最旧的一半并留痕 */
    private fun hardCapIfNeeded() {
        var dropped = 0
        synchronized(window) {
            if (window.size > HARD_CAP) {
                dropped = window.size - MAX_TURNS
                repeat(dropped) { window.removeFirst() }
            }
        }
        if (dropped <= 0) return
        Log.w(TAG, "摘要一直失败，窗口超过 $HARD_CAP 轮，强行丢弃最旧的 $dropped 轮")
        persistAsync()
    }

    private fun summarizeAsync() {
        if (appCtx == null) {
            // 纯 JVM 单测 / 未 init：不发起网络请求，窗口就按 20 轮兜着
            return
        }
        if (!summarizing.compareAndSet(false, true)) return
        scope.launch {
            try {
                summarizeOnce()
            } catch (e: Throwable) {
                Log.w(TAG, "摘要任务异常：${e.javaClass.simpleName} ${e.message}")
            } finally {
                summarizing.set(false)
            }
        }
    }

    /**
     * 调便宜模型压一段摘要。
     *
     * provider 选择和 [MemoryExtractor] 一致：优先 `deepseek-chat`（便宜、快），
     * 没配 Key 就跟随主 provider。都没有就返回 null（摘要失败，下轮再试）。
     */
    private suspend fun requestSummary(batch: List<Turn>): String? {
        val ctx = appCtx ?: return null
        val provider = pickProvider(ctx)
        val (key, _, model) = LlmClientFactory.getConfig(ctx, provider)
        if (key.isBlank() && provider != "ollama") {
            Log.i(TAG, "未配置 $provider 的 API Key，本轮跳过长记忆摘要")
            return null
        }
        val client = LlmClientFactory.create(provider, ctx)
        val body = batch.joinToString("\n") { "用户：${it.user}\n助手：${it.assistant}" }
        val raw = withTimeoutOrNull(SUMMARY_TIMEOUT_MS) {
            client.chat(
                listOf(
                    LlmClient.Message("system", SUMMARY_PROMPT),
                    LlmClient.Message("user", body)
                )
            )
        }
        if (raw.isNullOrBlank()) {
            Log.i(TAG, "摘要无返回（provider=$provider model=$model）")
            return null
        }
        return sanitizeSummary(raw).ifBlank { null }
    }

    // ---------------------------------------------------------------- 纯函数（可单测）

    /**
     * 清洗摘要输出。
     *
     * 小模型爱加「摘要：」「好的，以下是摘要」这类前缀，也会写超 100 字，
     * 统一剥前缀 + 压空白 + 截断。
     */
    internal fun sanitizeSummary(raw: String): String {
        var s = raw.trim()
        listOf(
            "摘要：", "摘要:", "总结：", "总结:", "对话摘要：", "对话摘要:",
            "好的，", "好的,", "以下是摘要：", "以下是摘要:"
        ).forEach { s = s.removePrefix(it) }
        s = s.replace(Regex("\\s+"), " ").trim()
        s = s.trim('"', '\'', '“', '”', '「', '」', '：', ':')
        if (s.length > SUMMARY_MAX_CHARS) s = s.take(SUMMARY_MAX_CHARS)
        return s.trim()
    }

    /**
     * 追加式合并摘要。
     *
     * 旧摘要在前、新摘要在后（时间顺序），总长超 [SUMMARY_TOTAL_MAX] 时**从头砍**：
     * 越早的事越该被稀释，这和人类记忆的衰减方式一致。
     */
    internal fun mergeSummary(old: String, fresh: String): String {
        val f = sanitizeSummary(fresh)
        if (f.isEmpty()) return old
        val joined = if (old.isBlank()) f else "$old $f"
        return if (joined.length <= SUMMARY_TOTAL_MAX) joined else joined.takeLast(SUMMARY_TOTAL_MAX)
    }

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) PREFERRED_PROVIDER else LlmClientFactory.getProvider(ctx)
    }

    // ---------------------------------------------------------------- 持久化

    /** 摘要提示词（要求一段话、≤100 字、只留长期有用的信息） */
    private const val SUMMARY_PROMPT = """你是一个对话压缩器。把下面这段对话压成一段不超过 100 字的中文摘要。

只保留长期有用的：
- 用户透露的个人信息（名字、职业、喜好、习惯、家人宠物）
- 用户正在做的事、做出的决定、达成的约定、未完成的待办
- 双方约定过的说法或称呼

不要写：
- 寒暄、情绪感叹、一次性的操作指令
- 你自己的身份说明

要求：用第三人称陈述（「用户……」），一段话，不分点，不加标题，不用 markdown，不超过 100 字。"""

    /** 便宜的摘要模型（和 MemoryExtractor 保持一致） */
    private const val PREFERRED_PROVIDER = "deepseek"

    private fun file(context: Context): File =
        File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    private fun persistAsync() {
        val ctx = appCtx ?: return
        scope.launch {
            runCatching { save(ctx) }
                .onFailure { Log.w(TAG, "长记忆落盘失败：${it.javaClass.simpleName} ${it.message}") }
        }
    }

    /**
     * 落盘：先写 `.tmp` 再 rename。
     *
     * 直接覆写原文件的话，写到一半被杀进程会留下半截 JSON，
     * 下次启动解析失败 = 长记忆全丢。rename 在同一分区上是原子的。
     */
    private fun save(context: Context) {
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(encode(), Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "旧存档删不掉，仍尝试覆盖：${target.absolutePath}")
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun load(context: Context) {
        val f = file(context)
        if (!f.isFile) {
            Log.i(TAG, "没有长记忆存档，从零开始")
            return
        }
        val text = f.readText(Charsets.UTF_8)
        if (!decode(text)) {
            Log.w(TAG, "长记忆存档解析失败（${text.length} 字节），本轮忽略")
            return
        }
        Log.i(TAG, "长记忆已载入：摘要 ${rollingSummary.length} 字 / 窗口 ${windowSize()} 轮")
    }

    /** 序列化（手写：只为本类服务，且要能在纯 JVM 单测里跑） */
    internal fun encode(): String = buildString {
        append("{\"summary\":").append(quote(rollingSummary))
        append(",\"turns\":[")
        windowSnapshot().forEachIndexed { i, t ->
            if (i > 0) append(',')
            append("{\"user\":").append(quote(t.user))
            append(",\"assistant\":").append(quote(t.assistant))
            append(",\"at\":").append(t.at).append('}')
        }
        append("],\"savedAt\":").append(System.currentTimeMillis()).append('}')
    }

    /**
     * 反序列化。
     *
     * @return true = 解析成功（哪怕窗口是空的）
     */
    internal fun decode(text: String): Boolean {
        val root = MiniJson.parseObject(text) ?: return false
        val summary = (root["summary"] as? String).orEmpty()
        val rawTurns = root["turns"] as? List<*> ?: emptyList<Any?>()
        val restored = rawTurns.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val u = (m["user"] as? String)?.trim().orEmpty()
            val a = (m["assistant"] as? String)?.trim().orEmpty()
            if (u.isEmpty() || a.isEmpty()) return@mapNotNull null
            val at = (m["at"] as? Number)?.toLong() ?: System.currentTimeMillis()
            Turn(u, a, at)
        }.takeLast(MAX_TURNS)

        synchronized(window) {
            rollingSummary = sanitizeSummarySafely(summary)
            // 不再整体覆盖内存：解码通常是异步的，可能晚于用户刚说的那句话
            if (window.isEmpty()) window.addAll(restored)
        }
        return true
    }

    private fun sanitizeSummarySafely(raw: String): String =
        if (raw.length <= SUMMARY_TOTAL_MAX) raw.trim() else raw.takeLast(SUMMARY_TOTAL_MAX).trim()

    /** JSON 字符串字面量（只处理本类会出现的字符） */
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

    // ---------------------------------------------------------------- 测试

    /** 仅供单测：恢复初始状态（窗口、摘要、大脑、上下文全部清掉） */
    internal fun resetForTest() {
        synchronized(window) { window.clear() }
        rollingSummary = ""
        appCtx = null
        defaultBrain = null
        summarizing.set(false)
        summaryFailures = 0
        degraded = false
    }

    /** 仅供单测：当前是否已退化为环形窗口 */
    internal fun isDegradedForTest(): Boolean = degraded

    /** 仅供单测：直接塞一轮（跳过 appCtx 判断，不落盘） */
    internal fun addTurnForTest(user: String, assistant: String) {
        synchronized(window) { window.addLast(Turn(user, assistant)) }
    }
}
