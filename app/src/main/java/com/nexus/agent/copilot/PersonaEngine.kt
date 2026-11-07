package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.data.db.ChatRecord
import com.nexus.agent.data.db.PersonaStore
import com.nexus.agent.data.db.PersonaProfile
import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.data.db.RoomPersonaStore
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.game.VisionHub
import com.nexus.agent.persona.LocalDigitalBeing
import com.nexus.agent.persona.MemoryExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

/**
 * 人格引擎 —— 负责「它是什么样的人」这件事。
 *
 * 与 [CopilotEngine] 的分工：
 *  - [CopilotEngine] 管**怎么做事**（截图、调模型、执行工具）
 *  - [PersonaEngine] 管**怎么说话**（人设提示词、对话记忆、聊天记录）
 *
 * ## 0.7.12 存储变更
 *
 * 之前聊天记录和记忆窗口全在 `SharedPreferences("luna_persona")` 的一坨 JSON 里：
 * 只能整存整取、每次 append 都要重写整串、到几百条就顶到 prefs 的 1MB 上限，
 * 也没法做「最近 N 轮」这种查询。现在持久化整体交给 Room（[PersonaStore]），
 * 本类只保留**内存工作集**：
 *  - [items] 给 UI 的聊天流（最多 [MAX_ITEMS] 条）
 *  - 记忆窗口交给 [LocalDigitalBeing]（0.8.12：滑动窗口 20 轮 + 滚动摘要）
 *
 * 启动时 [init] 会异步从库里读回档案 + 聊天流 + 最近 [LOAD_TURNS] 轮对话，
 * 读回来之前界面是空的，读完由 [onChanged] 通知刷新。
 */
object PersonaEngine {

    private const val TAG = "PersonaEngine"

    /**
     * 送进 prompt 的记忆窗口（一问一答算 1 轮）。
     *
     * 0.8.12 起真正的窗口归 [LocalDigitalBeing] 管（20 轮 + 滚动摘要），
     * 这里保留常量是给 UI / 测试一个稳定入口，避免别处再硬编码数字。
     */
    const val MAX_TURNS = LocalDigitalBeing.MAX_TURNS

    /** 启动时从库里读的轮数（就是窗口大小，多读没意义） */
    private const val LOAD_TURNS = MAX_TURNS

    /**
     * 给**本地小模型**的历史轮数。
     *
     * 窗口放宽到 20 轮后不能全塞给 2B 模型（20×2×60 字 ≈ 2400 字，会把上下文挤爆），
     * 云端走 messages 数组不受影响，只有 [historyText] 这一条要收着点。
     */
    private const val LOCAL_HISTORY_TURNS = 8

    /**
     * 给**角色 LLM** 的历史轮数（0.8.19）。
     *
     * 窗口有 20 轮，全塞进去会带来典型的「串台」：聊了二十轮之后，
     * 模型会把「上周说的那件事」和「刚才这句话」混成一段回答。
     * 真正早于窗口的内容本来就由滚动摘要兜着了，所以这里只喂最近的 [REPLY_HISTORY_TURNS] 轮。
     */
    private const val REPLY_HISTORY_TURNS = 12

    /** UI 聊天记录条数上限（含动作气泡），超出丢最旧 */
    private const val MAX_ITEMS = 120

    /** 长期记忆注入 prompt 的条数（再多会把上下文挤爆，也捞不到有用的） */
    private const val MEMORY_PROMPT_LIMIT = 10

    /** 名字/身份设定的兜底长度上限（UI 已经拦过，这里防其它入口） */
    private const val NAME_MAX_CHARS = 12
    private const val IDENTITY_MAX_CHARS = 60

    /** 角色常量：和 [com.nexus.agent.data.db.ChatHistory] 里的定义保持一致 */
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_ACTION = "action"

    /** 0.7.11 及之前的老存档位置，只为一次性迁移 */
    private const val LEGACY_PREFS = "luna_persona"
    private const val LEGACY_KEY_ITEMS = "chat_items"
    private const val LEGACY_KEY_MIGRATED = "room_migrated"

    /** 一轮对话的类型见 [LocalDigitalBeing.Turn]（0.8.12 起窗口数据结构由长记忆引擎定义） */

    /** 聊天流里的一条记录（UI 用，含工具动作气泡） */
    sealed class ChatItem {
        abstract val at: Long

        data class User(val text: String, override val at: Long = System.currentTimeMillis()) : ChatItem()
        data class Assistant(val text: String, override val at: Long = System.currentTimeMillis()) : ChatItem()

        /**
         * 系统动作气泡：「已打开微信」「正在点击…」。
         *
         * 与人设回复分开展示，让用户一眼看出哪句是 AI 说的、哪句是它真的动手了。
         */
        data class Action(
            val label: String,
            val detail: String = "",
            val ok: Boolean = true,
            override val at: Long = System.currentTimeMillis()
        ) : ChatItem()
    }

    /** 模型用的历史（纯文本对）由 [LocalDigitalBeing] 持有，本类不再自带环形缓冲 */

    /** UI 用的聊天记录 */
    private val items = mutableListOf<ChatItem>()

    /** 档案内存副本（真身在 Room 的 persona_profile，这里只做读缓存） */
    @Volatile
    private var profile = ProfileSnapshot(
        name = "宏月",
        soul = "",
        identity = "",
        affinity = 50,
        mood = "平静"
    )

    /** 持久化实现；纯 JVM 单测里为 null，所有落库操作自动跳过 */
    @Volatile
    private var store: PersonaStore? = null

    /**
     * 长期记忆缓存（`long_term_memory` 权重最高的 [MEMORY_PROMPT_LIMIT] 条）。
     *
     * 为什么要缓存：组装 System Prompt 的 [systemPrompt] 是同步函数，而 DAO 是 suspend，
     * 没法在里面直接查库。所以启动时读一次、每轮抽取完刷新一次，prompt 只读这份快照。
     */
    @Volatile
    private var memories: List<String> = emptyList()

    /** 后台协程作用域（记忆抽取用）。纯 JVM 单测里不会被触发，见 [recordTurn] 的 appCtx 判断 */
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 应用上下文（由 [init] 注入，老存档迁移要用） */
    @Volatile
    private var appCtx: Context? = null

    /** 记录变化回调（PersonaTab 订阅它刷新列表） */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /**
     * 主线程 Handler。
     *
     * 用 lazy + runCatching：纯 JVM 单元测试里没有 Looper，构造函数会抛
     * `RuntimeException: Stub!`，不能让它把整个 object 初始化搞崩。
     */
    private val main: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }

    /**
     * 通知 UI 刷新。
     *
     * 记录可能来自 CopilotEngine 的 IO 协程或 Room 的读盘线程，而 Compose 的状态
     * 必须回主线程改，所以这里统一切一次线程，调用方不用关心自己在哪个线程。
     */
    private fun notifyChanged() {
        val cb = onChanged ?: return
        val handler = main
        if (handler == null) {
            cb()
            return
        }
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) cb() else handler.post { cb() }
        }.onFailure { cb() }
    }

    // ---------------------------------------------------------------- 初始化

    /**
     * 注入上下文并异步读回存档。**幂等**：PersonaTab 每次进入都会调一次。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appCtx = app
        // 0.8.12：长记忆（滑动窗口 + 滚动摘要）自己管上下文与落盘，幂等
        LocalDigitalBeing.init(app)
        LocalDigitalBeing.registerBrain(defaultBrain())
        if (store != null) {
            // 已经初始化过：直接把当前内存状态推给 UI，不要重复读盘
            notifyChanged()
            return
        }
        val s = RoomPersonaStore(app)
        store = s
        MemoryExtractor.init(app)
        migrateLegacyIfNeeded(s)
        s.load(MAX_ITEMS, LOAD_TURNS) { snapshot, itemRows, dialogueRows ->
            restore(snapshot, itemRows, dialogueRows)
        }
        reloadMemories()
    }

    /**
     * 重新读一次长期记忆缓存。
     *
     * 启动时调一次，每轮抽取完再调一次 —— 这样下一句话就已经能用到刚记住的事实，
     * 不用等下次冷启动。
     */
    fun reloadMemories() {
        store?.loadMemories(MEMORY_PROMPT_LIMIT) { facts ->
            memories = facts
            Log.i(TAG, "长期记忆已载入 ${facts.size} 条")
            notifyChanged()
        }
    }

    /** 当前召回的长期记忆（设置页/排障用） */
    fun memoriesSnapshot(): List<String> = memories

    /** 供测试注入假存档（生产环境走 [init] 里的 Room 实现） */
    internal fun useStoreForTest(fake: PersonaStore?) {
        store = fake
    }

    /** 仅供单测：把内存工作集与外部依赖恢复到初始态 */
    internal fun resetForTest() {
        synchronized(items) { items.clear() }
        LocalDigitalBeing.resetForTest()
        profile = ProfileSnapshot("宏月", "", "", 50, "平静")
        memories = emptyList()
        store = null
        onChanged = null
    }

    /**
     * 把 Room 里读回来的东西填进内存。
     *
     * 处理一个真实存在的竞态：冷启动后用户可能在读盘完成前就发了一句话。
     * 这时内存里已经有记录，不能直接清空覆盖 —— 把库里的旧记录垫在前面，保留新的。
     */
    internal fun restore(
        snapshot: ProfileSnapshot,
        itemRows: List<ChatRecord>,
        dialogueRows: List<ChatRecord>
    ) {
        profile = snapshot

        val restoredItems = itemRows.map { it.toChatItem() }
        synchronized(items) {
            val inMemory = items.toList()
            if (inMemory.isEmpty()) {
                items.clear()
                items.addAll(restoredItems)
            } else {
                val room = (MAX_ITEMS - inMemory.size).coerceAtLeast(0)
                items.clear()
                items.addAll(restoredItems.takeLast(room))
                items.addAll(inMemory)
            }
        }

        val restoredTurns = pairTurns(dialogueRows)
        // 窗口归长记忆引擎管；它自己会判断内存里是不是已经有新记录（不覆盖）
        LocalDigitalBeing.restoreWindow(restoredTurns.takeLast(MAX_TURNS))

        Log.i(
            TAG,
            "Room 存档已恢复：名字=${profile.name} 好感=${profile.affinity} 心情=${profile.mood}；" +
                "聊天 ${items.size} 条 / 记忆 ${LocalDigitalBeing.windowSize()} 轮"
        )
        notifyChanged()
    }

    /**
     * 把「user → assistant」的流水两两配对成轮。
     *
     * 落单的用户消息（问了没答上来）直接丢掉：记忆里存「用户：xxx / 你：（空）」
     * 只会让模型学着答一半。
     */
    private fun pairTurns(rows: List<ChatRecord>): List<LocalDigitalBeing.Turn> {
        val out = ArrayList<LocalDigitalBeing.Turn>(rows.size / 2 + 1)
        var pendingUser: String? = null
        for (r in rows) {
            when (r.role) {
                ROLE_USER -> pendingUser = r.content
                ROLE_ASSISTANT -> {
                    val u = pendingUser
                    if (!u.isNullOrBlank() && r.content.isNotBlank()) {
                        out.add(LocalDigitalBeing.Turn(u, r.content, r.createdAt))
                    }
                    pendingUser = null
                }
                // action 气泡不进记忆
                else -> Unit
            }
        }
        return out
    }

    /**
     * 一次性把旧版 SharedPreferences 里的聊天记录搬进 Room。
     *
     * 不做迁移的后果是「升级完聊天记录全没了」，而人设类产品的历史本身就是资产。
     * 迁移成功后清掉旧 key 并打标记，失败则保留原数据下次再试。
     */
    private fun migrateLegacyIfNeeded(target: PersonaStore) {
        val ctx = appCtx ?: return
        val prefs = ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_KEY_MIGRATED, false)) return
        val raw = prefs.getString(LEGACY_KEY_ITEMS, "") ?: ""
        if (raw.isBlank()) {
            prefs.edit().putBoolean(LEGACY_KEY_MIGRATED, true).apply()
            return
        }
        runCatching {
            val arr = JSONArray(raw)
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val at = o.optLong("at", System.currentTimeMillis())
                when (o.optString("type")) {
                    ROLE_USER -> target.appendChat(ChatRecord(ROLE_USER, o.optString("text"), createdAt = at))
                    ROLE_ASSISTANT -> target.appendChat(ChatRecord(ROLE_ASSISTANT, o.optString("text"), createdAt = at))
                    else -> target.appendChat(
                        ChatRecord(
                            ROLE_ACTION,
                            o.optString("label"),
                            o.optString("detail"),
                            o.optBoolean("ok", true),
                            at
                        )
                    )
                }
                n++
            }
            prefs.edit().putBoolean(LEGACY_KEY_MIGRATED, true).remove(LEGACY_KEY_ITEMS).apply()
            Log.i(TAG, "旧存档已迁移 $n 条到 Room")
        }.onFailure {
            Log.w(TAG, "旧存档迁移失败，原数据保留：${it.message}")
        }
    }

    // ---------------------------------------------------------------- 人设

    /**
     * 说话风格设定。
     *
     * 刻意写成「约束 + 反例」而不是空泛的形容词：小模型对「要自然」这种词几乎无感，
     * 但对「不要用列表」「不要说作为AI」这类禁令很敏感。
     *
     * 0.8.0 起不再硬编码人设：名字、身份、口癖、三个性格维度全部来自
     * [com.nexus.agent.data.db.PersonaProfile]（设置页可调），这里只负责把数值翻成人话。
     */
    private fun styleBlock(name: String): String = buildString {
        append("【你的人设】\n")
        append("你叫$name")
        if (profile.identity.isNotBlank()) {
            append("，${profile.identity}")
        } else {
            append("，是用户手机里的私人助理，关系像老朋友")
        }
        append("。\n")
        append("- 你的性格：${traitsSentence()}\n")
        if (profile.soul.isNotBlank()) {
            append("- 你的底色：${profile.soul}\n")
        }
        if (profile.catchphrase.isNotBlank()) {
            append("- 你的口癖是「${profile.catchphrase}」；偶尔带一下就好，")
            append("别每句话都加，也别硬贴在句尾。\n")
        }
        val hints = traitHints()
        if (hints.isNotEmpty()) {
            append("- 说话方式：${hints.joinToString("；")}。\n")
        }
        append("说话自然、直接、有主见，偶尔可以吐槽，但不油腻、不谄媚。\n")
        append("- 用口语，别用书面语；能一句话说完就别拆成三段。\n")
        append("- 不要用 Markdown 列表、加粗、标题，这些在聊天气泡里看着很怪。\n")
        append("- 不要说「作为 AI」「我是人工智能」「有什么可以帮您」这类客服话。\n")
        append("- 不确定就直说不知道，别编。\n")
        append("- 记住上下文：用户说「它」「那个」「再来一次」时，指的是前面聊过的事。\n")
    }

    /**
     * 把三个性格数值翻成一句自然语言，例如「极度外向、非常感性、带有一点叛逆」。
     *
     * 默认读当前档案；参数可覆盖，方便单测直接验文案。
     */
    internal fun traitsSentence(
        extroversion: Int = profile.extroversion,
        sensibility: Int = profile.sensibility,
        rebelliousness: Int = profile.rebelliousness
    ): String = listOf(
        describeExtroversion(extroversion),
        describeSensibility(sensibility),
        describeRebelliousness(rebelliousness)
    ).joinToString("、")

    internal fun describeExtroversion(v: Int): String = when {
        v <= 19 -> "极度内向"
        v <= 39 -> "偏内向"
        v <= 60 -> "内外平衡"
        v <= 79 -> "偏外向"
        else -> "极度外向"
    }

    internal fun describeSensibility(v: Int): String = when {
        v <= 19 -> "极度理性"
        v <= 39 -> "偏理性"
        v <= 60 -> "理性与感性兼顾"
        v <= 79 -> "非常感性"
        else -> "极度感性"
    }

    internal fun describeRebelliousness(v: Int): String = when {
        v <= 19 -> "极其顺从"
        v <= 39 -> "比较听话"
        v <= 60 -> "不卑不亢"
        v <= 79 -> "带有一点叛逆"
        else -> "非常叛逆"
    }

    /**
     * 性格数值对应的行为约束。
     *
     * 只给极端档（>=70 / <=30）加提示：中间档本来就是默认语气，
     * 说太多反而会把小模型的注意力从「回答用户」上分散走。
     */
    private fun traitHints(): List<String> = buildList {
        if (profile.extroversion >= 70) {
            add("话多一点、语气活泼，可以主动找话题")
        } else if (profile.extroversion <= 30) {
            add("话少、克制，别没话找话，问什么答什么")
        }
        if (profile.sensibility >= 70) {
            add("先接住情绪再讲道理，可以表达自己的感受")
        } else if (profile.sensibility <= 30) {
            add("就事论事，少渲染情绪，直接给结论")
        }
        if (profile.rebelliousness >= 70) {
            add("可以顶嘴、可以拒绝，别一味顺着用户")
        } else if (profile.rebelliousness <= 30) {
            add("配合度高，用户说什么就照办，不唱反调")
        }
    }

    /**
     * 组装本轮的系统提示词。
     *
     * 复用 [CopilotVision.buildFullSystemPrompt]（身份 + 工具 Schema + JSON 契约），
     * 再叠加人设、心情与记忆摘要，保证「能干活」和「像个人」同时成立。
     */
    fun systemPrompt(context: Context, foregroundApp: String?, chatty: Boolean): String {
        val name = CopilotConfig.assistantName(context)
        val base = CopilotVision.buildFullSystemPrompt(
            ToolRegistry.toJsonSchema(),
            foregroundApp,
            name,
            chatty
        )
        return buildString {
            append(base)
            append("\n\n")
            append(styleBlock(name))
            // 好感度/心情只用来说话，不让它报数字 —— 报出来就穿帮了
            append("\n【当前心情】${profile.mood}，好感度 ${profile.affinity}/100。")
            append("用它决定语气松紧，绝对不要向用户提这些数值。\n")
            // 长期记忆：窗口之外还记着的那些事（0.8.0 起由 MemoryExtractor 抽取）
            val facts = memories
            if (facts.isNotEmpty()) {
                append("\n【关于你的用户（长期记忆）】\n")
                facts.forEach { append("- $it\n") }
                append("这些是你本来就记得的事，自然带进对话就好，不要逐条复述、更不要说「根据我的记忆」。\n")
            }
            // 0.8.12：滚动摘要 —— 窗口之外还记着的那些事（由 LocalDigitalBeing 压缩）
            val rolling = LocalDigitalBeing.summaryText()
            if (rolling.isNotBlank()) {
                append("\n【更早之前聊过的事（压缩记忆）】\n")
                append(rolling).append("\n")
            }
            // 0.8.19：这里以前还会 append `historyText()`（最近 8 轮「用户：… / 你：…」）。
            // 但 CopilotEngine 已经把同样的历史作为 messages 数组带上了（user/assistant 交替），
            // 同一段对话进两遍，模型会把 system 里那份当成「待续写的文本」——
            // 表现就是复述、自问自答、把前几轮的事串到这一轮。历史只留 messages 这一份。
        }
    }

    // ---------------------------------------------------------------- 角色 LLM（双模型后半段）

    /** 角色回复超时：比探针宽松得多（要带人设、要多轮历史），但也不能无限等 */
    private const val REPLY_TIMEOUT_MS = 45_000L

    /** 一句话回复的字数上限（聊天气泡再长就没人看了） */
    private const val REPLY_MAX_CHARS = 400

    /** 画面描述进提示词的字数上限（视觉模型偶发长篇罗列，原样塞进来会被照着念） */
    private const val SCREEN_NOTE_MAX_CHARS = 240

    /**
     * 本地视觉子进程挂了时追加在回复末尾的提示（0.8.12）。
     *
     * 必须**明确说出来**：主进程活下来了，但它这一轮确实没看见屏幕，
     * 不能让模型顺着前文瞎编画面。
     */
    internal const val VISION_DIED_NOTICE = "（本地视觉进程开小差了，这轮我没看到屏幕）"

    /**
     * 带上死因的版本（0.8.13-alpha1）。
     *
     * 只写「开小差了」用户没法反馈，我也没法定位 —— 把 `VisionSteps` 记下的
     * 「死在②看图推理」这类信息截一段带出去，截图就能当排障线索。
     */
    internal fun diedNotice(reason: String?): String {
        val r = reason?.trim().orEmpty()
        if (r.isEmpty()) return VISION_DIED_NOTICE
        return "（本地视觉进程开小差了，这轮我没看到屏幕：${r.take(60)}）"
    }

    /**
     * 让**角色 LLM**以宏月的口吻说一句话（0.8.0 双模型架构的后半段）。
     *
     * 和 [systemPrompt] 的区别：
     *  - [systemPrompt] 是给「一个模型全包」的老路径用的（既要出 JSON 又要说话）；
     *  - 本函数**不要求任何 JSON**，只要求「说人话」。意图判断已经由
     *    [ProbeLLM] 做完了，工具也已经执行完了，这里唯一的任务是把结果
     *    用人格化语气交付出去。
     *
     * 提示词里刻意强调「不要报工具名、不要说『工具返回』」：模型看到结构化结果
     * 很容易照抄成技术汇报，那就白拆两个模型了。
     *
     * @param userText     用户原话
     * @param toolResult   工具/任务的执行结果（null = 纯聊天，没有动作）
     * @param bitmap       当前截图（可选）
     * @param foregroundApp 前台应用名（让「这个」「这里」有指代对象）
     * @param onStage      阶段回调（本地视觉模型装载/读屏时告诉用户别干等）
     * @return 助手回复；null = 这条链路不可用（没 Key / 超时 / 空回复），调用方应降级
     */
    suspend fun respond(
        context: Context,
        userText: String,
        toolResult: String? = null,
        bitmap: Bitmap? = null,
        foregroundApp: String? = null,
        /**
         * 系统提示的出口（0.8.19）。
         *
         * 「本轮没看成屏幕」这类话是**系统说给用户听的**，不是角色说的话。
         * 以前一律用 `reply + notice` 拼在正文尾巴上，气泡里就变成
         * 「……好呀（注意：本轮没看成屏幕，仅纯文本回复）」—— 又长又像坏掉。
         * 现在交给调用方渲染成独立系统气泡；传 null 时保持旧行为（语音链路等无处展示）。
         */
        onNotice: ((String) -> Unit)? = null,
        onStage: ((String) -> Unit)? = null
    ): String? {
        val text = userText.trim()
        if (text.isEmpty()) return null
        return try {
            val shot = bitmap?.takeIf { !it.isRecycled }
            val target = pickReplyProvider(context, visionWanted = shot != null)
                ?: run {
                    Log.i(TAG, "角色 LLM 不可用（无可用 provider/Key），本轮降级")
                    return null
                }

            // ---------------------------------------------------------------
            // 0.8.12 视觉闸门（P0 收口）
            //
            // 规矩没变：**有图就必须有人看**。本地 Qwen2-VL 优先（离线、免费），
            // 云端能看图才兜底，都不行才允许纯文本并明说「本轮没看成」。
            //
            // 变的是**在哪看**：0.8.2 ~ 0.8.10 是在主进程 `ensureLoaded()+infer()`，
            // 1.4GB 权重 + 视觉编码的张量全压在 UI 进程里，OOM 直接把整个 App 带走。
            // 现在一律丢给 :vision 子进程（[VisionWorkerService]），
            // 它崩了主进程只收到一个 ProcessDied 回调，还能在聊天里把话说清楚。
            // ---------------------------------------------------------------
            var screenNote: String? = null
            var notice = ""
            var b64: String? = null
            /** 子进程挂掉的原因（非空 = 这一轮本地视觉确实没看成） */
            var workerFailure: String? = null
            /** 模型文件根本不齐（非空 = 连试都没试，得先去下载） */
            var localMissing = false

            if (shot == null) {
                // 0.8.20：把「为什么没截到」带上——「无障碍未连接」「系统拒绝截图 code 3」
                // 是两种完全不同的修法，笼统一句「没截到屏幕」等于没说。
                val shotWhy = com.nexus.agent.agent.ScreenCapture.lastFailureReason
                Log.d("PersonaVision", "本轮无截图，原因：${shotWhy ?: "调用方没传图"}")
                VisionRoute.logShotFailed(shotWhy)
                notice = if (shotWhy.isNullOrBlank()) {
                    CopilotVision.NO_SHOT_NOTICE
                } else {
                    "（本轮没截到屏幕——$shotWhy）"
                }
                VisionRoute.log(
                    LocalScreenRecognizer.state,
                    hasShot = false,
                    choice = VisionRoute.Choice.CLOUD_TEXT,
                    detail = "角色 LLM provider=${target.provider}"
                )
            } else {
                // 模型文件齐不齐在主进程判（一次文件 stat，开销可忽略）；
                // 装载与推理本身绝不在主进程做。
                //
                // 0.8.21：不再让「文件清单」当闸门。
                //
                // 主进程的 isReady() 永远是 false（装载被禁止在主进程做），而 modelFilesPresent
                // 只是 7 个文件逐个 stat —— 它和子进程真正装载用的目录/清单一旦有偏差
                // （外部存储未就绪、旧包手动拷的模型、清单后来新增了文件），就会静默判
                // 「模型不齐」直接放弃，日志里连一次推理请求都没有。用户看到的现象正是
                // 「设置页自检能看图，聊天却说没看成」。
                //
                // 现在一律先让子进程试：它才是真正装载模型的地方，失败原因由它来说；
                // 缺文件只用来决定失败时给用户哪句人话。
                val missing = runCatching {
                    LocalScreenRecognizer.missingModelFiles(context)
                }.getOrDefault(emptyList())
                localMissing = missing.isNotEmpty()
                Log.d(
                    "PersonaVision",
                    "本地视觉尝试：缺文件=${missing.size}" +
                        if (missing.isEmpty()) "" else "（${missing.joinToString("、")}）"
                )
                when (val read = describeScreenInWorker(context, shot, onStage)) {
                    is ScreenRead.Desc -> screenNote = read.text
                    is ScreenRead.Failed -> workerFailure = read.reason
                }

                if (screenNote != null) {
                    VisionRoute.log(
                        LocalScreenRecognizer.state,
                        hasShot = true,
                        choice = VisionRoute.Choice.LOCAL,
                        detail = "子进程描述 ${screenNote.length} 字 → 交给 ${target.provider} 说话"
                    )
                } else if (target.canVision) {
                    b64 = CopilotVision.toBase64(shot)
                    VisionRoute.log(
                        LocalScreenRecognizer.state,
                        hasShot = true,
                        choice = VisionRoute.Choice.CLOUD_VISION,
                        detail = "角色 LLM provider=${target.provider}"
                    )
                } else {
                    VisionRoute.log(
                        LocalScreenRecognizer.state,
                        hasShot = true,
                        choice = VisionRoute.Choice.CLOUD_TEXT,
                        detail = "${target.provider} 不看图" +
                            (workerFailure?.let { "，本地视觉也没看成（$it）" } ?: "，本地视觉不可用")
                    )
                    // 0.8.20：三种失败必须说三种话，否则用户只看到「没看成」却不知道该修哪里。
                    // 模型文件不齐 ≠ 子进程崩 ≠ 跑起来了但没输出。
                    // 0.8.21：缺文件要排在最前面 —— 子进程的失败原因只会说「装载未完成」，
                    // 用户照着那句去查是无解的，真正该做的是去补文件。
                    notice = when {
                        localMissing ->
                            "（本地视觉模型不齐，这轮没看成屏幕——缺：${missing.joinToString("、")}。" +
                                "去 设置 → 下载模型，装好后点「预热并看一屏」验一下）"
                        workerFailure != null -> diedNotice(workerFailure)
                        else -> CopilotVision.NO_VISION_NOTICE
                    }
                }
            }

            val hasImage = b64 != null
            val client = LlmClientFactory.create(target.provider, context)
            val system = buildReplyPrompt(foregroundApp, toolResult, hasImage, screenNote)
            // 0.8.21：这一行是「描述有没有真的进提示词」的唯一凭据。
            // 有描述却仍回答「看不到」时，先看 containsScreen 是不是 true：
            //   false → 拼装问题（回到 describeScreenInWorker 那几行日志）
            //   true  → 模型没听话（提示词冲突，已在本版修掉最后那条死要求）
            Log.d(
                "PersonaVision",
                "提示词注入：画面描述=${screenNote?.length ?: 0} 字，" +
                    "system 含画面段=${system.contains("【你这一轮看到的屏幕")}，" +
                    "hasImage=$hasImage，provider=${target.provider}，canVision=${target.canVision}，" +
                    "system=${system.length} 字"
            )

            // 0.8.12：真正「怎么记住这一轮」交给长记忆引擎（滑窗 20 轮 + 滚动摘要）。
            // 人格提示词、画面描述、工具结果都包进这个 Brain 里，引擎只负责上下文。
            val brain = object : LocalDigitalBeing.Brain {
                override suspend fun reply(
                    history: List<LocalDigitalBeing.Turn>,
                    summary: String
                ): String? = askRoleLlm(target, client, system, history, text, b64)
            }
            val reply = LocalDigitalBeing.chat(text, brain)
            if (reply.isNullOrBlank()) {
                Log.i(TAG, "角色 LLM 无有效回复（provider=${target.provider}）")
                return null
            }
            Log.i(
                TAG,
                "角色 LLM 回复（${target.provider}，图=$hasImage，画面描述=${screenNote != null}，" +
                    "${reply.length} 字，窗口 ${LocalDigitalBeing.windowSize()} 轮，" +
                    "摘要 ${LocalDigitalBeing.summaryText().length} 字）"
            )
            // 0.8.12：窗口记账已由 chat() 做了，但「抽长期事实」是 PersonaEngine 的活，
            // 这里显式补一次（否则双脑链路会静默丢掉 long_term_memory 的写入）
            extractMemoryAsync(text, reply)
            if (notice.isNotEmpty() && onNotice != null) {
                onNotice(notice)
                reply
            } else {
                reply + notice
            }
        } catch (e: Throwable) {
            // 角色链路失败 = 降级信号，绝不把异常抛给对话主流程
            Log.w(TAG, "角色 LLM 失败：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /**
     * 调角色 LLM 说一句话（0.8.12 抽出来，供长记忆引擎和默认大脑共用）。
     *
     * 历史走 messages 数组而不是拼字符串：云端模型对角色交替的格式更敏感，
     * 拼进 prompt 容易被当成「用户说过的话」。
     */
    private suspend fun askRoleLlm(
        target: ReplyTarget,
        client: LlmClient,
        systemPrompt: String,
        history: List<LocalDigitalBeing.Turn>,
        userText: String,
        b64: String?
    ): String? {
        val recent = if (history.size > REPLY_HISTORY_TURNS) history.takeLast(REPLY_HISTORY_TURNS) else history
        if (recent.size != history.size) {
            Log.i(TAG, "历史 ${history.size} 轮超过上限，只带最近 ${recent.size} 轮（防串台）")
        }
        val messages = ArrayList<LlmClient.Message>(recent.size * 2 + 2)
        messages.add(LlmClient.Message("system", systemPrompt))
        recent.forEach {
            messages.add(LlmClient.Message("user", it.user))
            messages.add(LlmClient.Message("assistant", it.assistant))
        }
        messages.add(
            LlmClient.Message(
                "user",
                if (b64 != null) {
                    listOf(
                        LlmClient.ContentPart(type = "text", text = userText),
                        LlmClient.ContentPart(
                            type = "image_url",
                            imageUrl = "data:image/jpeg;base64,$b64"
                        )
                    )
                } else {
                    userText
                }
            )
        )
        val raw = withTimeoutOrNull(REPLY_TIMEOUT_MS) { client.chat(messages) }
        return sanitizeReply(raw).ifBlank { null }
    }

    /**
     * 默认大脑：不带截图、不带工具结果，只用人设 + 记忆聊一句。
     *
     * 给 [LocalDigitalBeing.chat] 的单参入口用（语音、定时问候这类没有屏幕上下文的调用）。
     * 主对话走 [respond]，那里会把画面描述和工具结果一起塞进去。
     */
    private fun defaultBrain(): LocalDigitalBeing.Brain = object : LocalDigitalBeing.Brain {
        override suspend fun reply(
            history: List<LocalDigitalBeing.Turn>,
            summary: String
        ): String? {
            val ctx = appCtx ?: return null
            val target = pickReplyProvider(ctx, visionWanted = false) ?: return null
            val client = LlmClientFactory.create(target.provider, ctx)
            return askRoleLlm(
                target = target,
                client = client,
                systemPrompt = buildReplyPrompt(null, null, hasImage = false, screenNote = null),
                history = history,
                userText = history.lastOrNull()?.user.orEmpty(),
                b64 = null
            )
        }
    }

    /** 一次「让子进程看一眼屏幕」的结果 */
    private sealed class ScreenRead {
        data class Desc(val text: String) : ScreenRead()
        data class Failed(val reason: String) : ScreenRead()
    }

    /**
     * 让 **:vision 子进程**看一眼屏幕，返回一句话画面描述。
     *
     * ## 为什么必须跨进程（0.8.12 P0）
     *
     * 0.8.2 ~ 0.8.10 是主进程里 `ensureLoaded()` + `infer()`：1.4GB 权重、KV cache、
     * 视觉编码的中间张量全在 UI 进程，任何一个超限都是**整机闪退**（Java 层 try/catch
     * 根本兜不住 SIGSEGV / OOM kill）。所以这里**一行本地推理都不写**，
     * 只把截好的图交给子进程，拿回文本或「它死了」。
     *
     * 失败一律返回 [ScreenRead.Failed]，绝不抛异常 —— 主进程要绝对活着。
     */
    private suspend fun describeScreenInWorker(
        context: Context,
        shot: Bitmap,
        onStage: ((String) -> Unit)?
    ): ScreenRead {
        onStage?.invoke("本地视觉模型看屏幕中")
        Log.d(
            "PersonaVision",
            "发往 :vision：${shot.width}×${shot.height}，isRecycled=${shot.isRecycled}"
        )
        return try {
            // 0.8.18：统一走 VisionHub（与副驾/陪玩/Agent 同一条实现）。
            // 它负责两件以前这里没有的事：
            //   ① 把图统一压到 640 长边、宽高对齐 8 —— 聊天链路原来自己截 720（324×720），
            //      而真机日志里出结果的两次都是 288×640；
            //   ② 输出为空时用保底 prompt 重试一次 —— 模型偶尔会对「不要输出 JSON」这类约束直接 EOS。
            val look = VisionHub.look(
                context = context,
                shot = shot,
                prompt = VisionRoute.SCREEN_DESCRIBE_PROMPT
            )
            if (!look.ok) {
                val why = look.reason ?: "推理返回空"
                VisionRoute.logLocalVisionFailed("$why（${look.size}）")
                Log.w(TAG, "聊天读屏失败（${look.size}）：$why")
                Log.w("PersonaVision", "子进程看屏失败（${look.size}）：$why，重试过=${look.retried}")
                return ScreenRead.Failed(why)
            }
            val desc = VisionRoute.cleanScreenDescription(look.text.orEmpty())
            if (desc == null) {
                VisionRoute.logLocalVisionFailed("模型有输出但清理后为空（${look.size}）")
                Log.w("PersonaVision", "子进程有输出但清理后为空（${look.size}）")
                return ScreenRead.Failed("模型有输出但不像是画面描述")
            }
            Log.i(TAG, "聊天读屏成功（${look.size}${if (look.retried) "/保底重试" else ""}）：${desc.length} 字")
            Log.d("PersonaVision", "子进程看屏成功：${look.size}，${desc.length} 字，重试=${look.retried}")
            ScreenRead.Desc(desc)
        } catch (e: Throwable) {
            Log.w(TAG, "视觉子进程调用异常：${e.javaClass.simpleName} ${e.message}")
            ScreenRead.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}")
        }
    }

    /**
     * 角色 LLM 的 provider 选型。
     *
     * - 有截图：优先视觉 provider（能看图才配得上截图），但它没 Key 时退回主 provider；
     * - 无截图：直接用主 provider（纯文本，最便宜最快）；
     * - 全都没 Key：返回 null（调用方降级）。
     */
    private fun pickReplyProvider(context: Context, visionWanted: Boolean): ReplyTarget? {
        val main = LlmClientFactory.getProvider(context)
        val mainKey = LlmClientFactory.getConfig(context, main).first
        if (visionWanted) {
            val vision = LlmClientFactory.getEffectiveVisionProvider(context)
            val visionKey = LlmClientFactory.getConfig(context, vision).first
            val visionModel = LlmClientFactory.getConfig(context, vision).third
            if (visionKey.isNotBlank() || vision == "ollama") {
                return ReplyTarget(vision, LlmClientFactory.supportsVision(vision, visionModel))
            }
        }
        if (mainKey.isNotBlank() || main == "ollama") {
            val model = LlmClientFactory.getConfig(context, main).third
            return ReplyTarget(main, LlmClientFactory.supportsVision(main, model))
        }
        return null
    }

    /** 角色 LLM 的一次选型结果 */
    private data class ReplyTarget(val provider: String, val canVision: Boolean)

    /**
     * 组装角色 LLM 的 System Prompt。
     *
     * 结构：人设（[styleBlock]）→ 心情/好感 → 长期记忆 → 刚才干了什么 → 输出要求。
     * 人设放最前面：小模型的注意力更吃开头，人设被挤到后面就只剩客服腔。
     *
     * 参数可覆盖，方便单测直接验文案（不依赖真实档案）。
     */
    internal fun buildReplyPrompt(
        foregroundApp: String?,
        toolResult: String?,
        hasImage: Boolean,
        /**
         * 本地视觉模型给出的画面描述（0.8.2）。
         *
         * 云端看不懂图时，这一行就是角色 LLM 唯一的「视觉输入」；
         * 为空表示这轮确实没看到画面，提示词必须明确禁止编造。
         */
        screenNote: String? = null
    ): String = buildString {
        append(styleBlock(profile.name))
        append("\n【当前心情】${profile.mood}（好感度 ${profile.affinity}/100，绝对不要说出这些数字）\n")
        val facts = memories
        if (facts.isNotEmpty()) {
            append("\n【关于你的用户（长期记忆）】\n")
            facts.forEach { append("- $it\n") }
            append("自然地用，不要复述、别说「根据我的记忆」。\n")
        }
        // 0.8.12：窗口之外的历史被压缩成摘要，这是「你还记得上周聊了啥」的唯一来源
        val rolling = LocalDigitalBeing.summaryText()
        if (rolling.isNotBlank()) {
            append("\n【更早之前聊过的事（压缩记忆）】\n")
            append(rolling).append("\n")
            append("这些是你本来就记得的事，需要时自然接着聊，不要逐句复述。\n")
        }
        if (!foregroundApp.isNullOrBlank()) {
            append("\n【当前前台】$foregroundApp\n")
        }
        if (!screenNote.isNullOrBlank()) {
            append("\n【你这一轮看到的屏幕（背景素材，不是要你转述的内容）】\n")
            // 0.8.19：视觉模型偶尔会吐一大段界面元素罗列，原样塞进来模型就照着念，
            // 用户看到的就是「每句话都在报屏幕上有什么」。截断 + 明确禁止复述。
            append(screenNote.take(SCREEN_NOTE_MAX_CHARS)).append("\n")
            append("这是你亲眼看到的，只用来判断「他在干嘛」，可以在相关时自然带一句")
            append("（比如「你在刷那个啊」）。禁止罗列界面元素、禁止把这段描述原样念出来、")
            append("禁止在跟话题无关时提屏幕。\n")
        } else if (hasImage) {
            append("\n【这一轮有屏幕截图】你可以看图说话，但只在跟话题相关时提，别硬描述画面。\n")
        }
        if (!toolResult.isNullOrBlank()) {
            append("\n【你刚替用户做完的事】\n")
            append("执行结果：${toolResult.take(300)}\n")
            append("用你自己的语气把结果讲给用户听，一两句话就够。\n")
            append("- 不许说「工具返回」「根据结果」「执行成功」这类汇报腔。\n")
            append("- 成功就交代办好了什么；失败就用你的性格吐槽一句再讲原因。\n")
        }
        append("\n【输出要求】\n")
        append("- 只输出你要说的那句话，不要 JSON、不要前缀、不要 markdown、不要括号里的动作描写。\n")
        append("- 口语，一到三句，别长篇大论。\n")
        append("- 同一句话只说一遍，不要复读，也不要自问自答。\n")
        append("- 只回答用户这一句问的事，别把前几轮聊过的内容混进来当结论。\n")
        append("- 不要说「作为 AI」「有什么可以帮您」。\n")
        // 0.8.21：这条要求和「这一轮到底看没看到」必须一致。
        //
        // 以前无论有没有本地视觉描述，最后都写死一句「这一轮没有截图就绝对不要描述画面」。
        // 云端模型是纯文本接单（hasImage=false），读到这句就会认定自己没看过屏幕 ——
        // 哪怕上面已经把【你这一轮看到的屏幕】整段塞进 system，它也会回答「我看不到」。
        // 这正是「设置页自检能看图、聊天却说看不到」的最后一块拼图。
        when {
            !screenNote.isNullOrBlank() -> append(
                "- 你这一轮**确实看到了**屏幕（就是上面那段画面描述），可以自然接话；" +
                    "被问到时直接说你看到了什么，禁止回答「看不到」「没有屏幕权限」，也不要罗列界面元素。\n"
            )
            hasImage -> append(
                "- 你这一轮拿到了屏幕截图，可以直接看图上说；被问到时不要说自己看不到。\n"
            )
            else -> append(
                "- 这一轮没有截图就绝对不要描述画面（不要编），也不要假装看到了。\n"
            )
        }
    }

    /**
     * 清洗角色 LLM 的原始输出。
     *
     * 模型偶尔仍然会：
     *  - 包一层 ```json 或引号；
     *  - 自报家门「宏月：」；
     *  - 输出一个 `{"type":"chat","reply":"..."}` 的 JSON（被老提示词带偏）。
     * 这里统一剥掉，避免把 JSON 糊到用户脸上。
     */
    internal fun sanitizeReply(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```").trim()
        }
        // 模型被 JSON 契约带偏时，把 reply 字段抠出来
        if (s.startsWith("{")) {
            val obj = com.nexus.agent.util.MiniJson.parseObject(s)
            val inner = obj?.let {
                (it["reply"] as? String) ?: (it["say"] as? String) ?: (it["content"] as? String)
            }
            if (!inner.isNullOrBlank()) s = inner.trim()
        }
        listOf("${profile.name}：", "${profile.name}:", "回答：", "回答:", "回复：", "回复:").forEach {
            s = s.removePrefix(it)
        }
        s = s.trim().trim('"', '\'', '“', '”', '「', '」', ' ')
        // 0.8.19 起清洗分四步，全部是「看起来像坏掉」的东西：
        //   ① markdown 痕迹（**加粗**、`代码`、行首 # 和列表符）—— 聊天气泡里全是符号；
        //   ② 句首/句尾的括号动作描写（「（笑）」）—— 以前是无差别删全文括号，
        //      会把「我（其实）不确定」切成「我不确定」，读起来像缺字；
        //   ③ 连续重复的句子（模型自问自答、复述上一句）；
        //   ④ 超长时在句末标点处收尾，而不是从中间硬切。
        val beforeBracket = s
        s = stripMarkdown(s)
        s = stripEdgeBrackets(s).ifBlank { beforeBracket }
        s = collapseRepeatedSentences(s)
        return truncateAtSentence(s, REPLY_MAX_CHARS).trim()
    }

    /** 清掉 markdown 痕迹：这些符号在聊天气泡里只会让人觉得「坏了」 */
    internal fun stripMarkdown(input: String): String {
        var s = input
        s = s.replace(Regex("\\*\\*(.+?)\\*\\*")) { it.groupValues[1] }
        s = s.replace(Regex("__(.+?)__")) { it.groupValues[1] }
        s = s.replace(Regex("`+([^`]*)`+")) { it.groupValues[1] }
        s = s.replace(Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*"), "")
        s = s.replace(Regex("(?m)^[ \\t]*[-*+][ \\t]+"), "")
        s = s.replace(Regex("(?m)^[ \\t]*\\d+[.、)）][ \\t]+"), "")
        s = s.replace(Regex("(?m)^[ \\t]*[-*_]{3,}[ \\t]*$"), "")
        return s
    }

    /**
     * 只删**句首 / 句尾**的短括号动作描写。
     *
     * 保留句中括号：「我（其实）不太确定」是正常口语，删掉反而更别扭。
     */
    internal fun stripEdgeBrackets(input: String): String {
        val head = Regex("^(（[^）]{0,10}）|\\([^)]{0,10}\\))+")
        val tail = Regex("(（[^）]{0,10}）|\\([^)]{0,10}\\))+$")
        return input.trim().replace(head, "").replace(tail, "").trim()
    }

    /**
     * 折叠**连续重复**的句子。
     *
     * 模型（尤其是上下文里历史很多时）会把自己刚说的话再说一遍，
     * 气泡里就出现「好呀。好呀。那我们走吧。」这种复读机效果。
     */
    internal fun collapseRepeatedSentences(input: String): String {
        val trimmed = input.trim()
        if (trimmed.length < 6) return trimmed
        val parts = trimmed.split(Regex("(?<=[。！？!?\\n])")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return trimmed
        val out = ArrayList<String>(parts.size)
        for (p in parts) {
            if (out.lastOrNull() != p) out.add(p)
        }
        return out.joinToString("")
    }

    /**
     * 在句末标点处截断。
     *
     * 硬切 `take(n)` 会把「我刚刚看了一下你的设置，发现那个开关其实」直接切断，
     * 用户读到的就是半截话。宁可短一点，也要说完一句。
     */
    internal fun truncateAtSentence(input: String, max: Int): String {
        val s = input.trim()
        if (s.length <= max) return s
        val head = s.take(max)
        val cut = head.indexOfLast { it in "。！？!?\n" }
        return if (cut >= max / 2) head.take(cut + 1).trim() else head.trim() + "…"
    }

    /** 当前档案快照（好感度 / 心情 / 名字 / 性格） */
    fun profileSnapshot(): ProfileSnapshot = profile

    /**
     * 保存人格设置（可视化调教界面点「保存」走这里）。
     *
     * 三件事：
     *  1. 夹取越界值（滑块理论上在 0~100，但口癖是手输的，长度得管住）；
     *  2. 落 Room，下次启动/下一句话立即生效；
     *  3. 把名字同步写回 [CopilotConfig] —— 灵动岛弹幕、非人格模式的提示词读的是那份，
     *     不同步就会出现「聊天里叫高总、弹幕里叫宏月」的精分现象。
     */
    fun saveProfile(context: Context, snapshot: ProfileSnapshot) {
        val next = sanitize(snapshot)
        profile = next
        store?.saveProfile(next)
        runCatching { CopilotConfig.setAssistantName(context, next.name) }
        notifyChanged()
        Log.i(
            TAG,
            "人格已保存：名字=${next.name} 身份=${next.identity.take(20)} " +
                "口癖=${next.catchphrase} 性格=${traitsSentence(next.extroversion, next.sensibility, next.rebelliousness)}"
        )
    }

    /**
     * 夹取越界值。
     *
     * 滑块理论上只会给 0~100，但口癖是手输的、身份设定也可能粘进来一大段，
     * 落库前统一收一遍口子，免得以后换入口（语音、导入配置）时漏拦。
     */
    internal fun sanitize(snapshot: ProfileSnapshot): ProfileSnapshot = snapshot.copy(
        name = snapshot.name.trim().take(NAME_MAX_CHARS),
        identity = snapshot.identity.trim().take(IDENTITY_MAX_CHARS),
        catchphrase = snapshot.catchphrase.trim().take(PersonaProfile.CATCHPHRASE_MAX),
        affinity = snapshot.affinity.coerceIn(
            PersonaProfile.AFFINITY_MIN, PersonaProfile.AFFINITY_MAX
        ),
        extroversion = snapshot.extroversion.coerceIn(
            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
        ),
        sensibility = snapshot.sensibility.coerceIn(
            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
        ),
        rebelliousness = snapshot.rebelliousness.coerceIn(
            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
        )
    )

    /** 改心情：内部状态变了就落库，不阻塞调用方 */
    fun updateMood(mood: String) {
        val m = mood.trim().ifBlank { return }
        if (m == profile.mood) return
        profile = profile.copy(mood = m)
        store?.saveProfile(profile)
        Log.i(TAG, "心情：$m")
    }

    /** 加/减好感度，自动夹在 0~100 */
    fun bumpAffinity(delta: Int) {
        if (delta == 0) return
        val next = (profile.affinity + delta).coerceIn(0, 100)
        if (next == profile.affinity) return
        profile = profile.copy(affinity = next)
        store?.saveProfile(profile)
        Log.i(TAG, "好感度：${profile.affinity - delta} → $next")
    }

    // ---------------------------------------------------------------- 记忆（0.8.12 起全部委托 LocalDigitalBeing）

    /** 最近一轮的助手回复（用来判断"再来一次"这类指代） */
    fun lastAssistantReply(): String =
        LocalDigitalBeing.windowSnapshot().lastOrNull()?.assistant.orEmpty()

    /**
     * 转成云端接口要的 messages（user/assistant 交替）。
     *
     * 只带文本，不带图——历史截图对当前问题没有价值，还会让请求体积暴涨。
     * 0.8.12 起窗口由 [LocalDigitalBeing] 管（20 轮），这份只是它的只读投影。
     */
    fun historyMessages(): List<LlmClient.Message> {
        val turns = LocalDigitalBeing.windowSnapshot()
        if (turns.isEmpty()) return emptyList()
        val out = ArrayList<LlmClient.Message>(turns.size * 2)
        for (t in turns) {
            out.add(LlmClient.Message("user", t.user))
            out.add(LlmClient.Message("assistant", t.assistant))
        }
        return out
    }

    /**
     * 文本化的历史摘要，给本地小模型用（它不吃 messages 数组，只能塞进 prompt）。
     *
     * 只取最近 [LOCAL_HISTORY_TURNS] 轮：窗口是 20 轮，全塞给 2B 模型会挤爆上下文；
     * 更早的内容已经由滚动摘要兜着了，不用在这儿重复。
     */
    fun historyText(): String {
        val turns = LocalDigitalBeing.windowSnapshot().takeLast(LOCAL_HISTORY_TURNS)
        if (turns.isEmpty()) return ""
        return turns.joinToString("\n") { t ->
            "用户：${t.user.take(60)}\n你：${t.assistant.take(60)}"
        }
    }

    /**
     * 记一轮对话（[assistant] 为空则忽略）。
     *
     * 窗口维护、超 20 轮触发摘要、落盘全在 [LocalDigitalBeing.recordTurn] 里，
     * 这里额外做一件事：趁热抽一次长期记忆（0.8.0 的 `long_term_memory`，抽的是判读性事实）。
     */
    fun recordTurn(user: String, assistant: String) {
        val u = user.trim()
        if (u.isEmpty()) return
        if (assistant.isBlank()) {
            // 没回复就不算完整一轮，避免历史里出现「你：」后面啥都没有
            return
        }
        if (!LocalDigitalBeing.recordTurn(u, assistant)) return
        // 0.8.0：趁热抽一次长期记忆。异步、失败静默，绝不拖慢这一轮回复。
        extractMemoryAsync(u, assistant.trim())
    }

    /**
     * 后台抽取长期记忆。
     *
     * 两道闸：
     *  - [appCtx] 为空（单测 / 未 init）直接跳过，免得纯 JVM 测试去发网络请求；
     *  - 抽到东西才刷新缓存，没抽到（大多数轮次）什么都不做。
     */
    private fun extractMemoryAsync(user: String, assistant: String) {
        if (appCtx == null) return
        scope.launch {
            val saved = runCatching {
                MemoryExtractor.extractAndSave(user, assistant)
            }.getOrDefault(0)
            if (saved > 0) reloadMemories()
        }
    }

    fun turnCount(): Int = LocalDigitalBeing.windowSize()

    /** 清空模型记忆（滑窗 + 滚动摘要一起清；不影响 UI 聊天记录，也不动数据库） */
    fun clearMemory() {
        LocalDigitalBeing.clear()
        Log.i(TAG, "对话记忆已清空")
    }

    // ---------------------------------------------------------------- 聊天记录（UI）

    fun items(): List<ChatItem> = synchronized(items) { items.toList() }

    fun addUserItem(text: String) {
        appendItem(ChatItem.User(text))
    }

    fun addAssistantItem(text: String) {
        if (text.isBlank()) return
        appendItem(ChatItem.Assistant(text))
    }

    fun addActionItem(label: String, detail: String = "", ok: Boolean = true) {
        appendItem(ChatItem.Action(label, detail, ok))
    }

    /**
     * 加一条聊天流记录：先更内存（UI 立刻看到），再异步落库（重启能恢复）。
     *
     * 落库失败只打日志不抛：聊天记录丢一条远不如让界面崩掉糟糕。
     */
    private fun appendItem(item: ChatItem) {
        synchronized(items) {
            items.add(item)
            while (items.size > MAX_ITEMS) items.removeAt(0)
        }
        store?.appendChat(item.toRecord())
        notifyChanged()
    }

    /** 清空聊天流（同时清记忆，否则会出现"失忆但界面还有记录"的矛盾状态） */
    fun clearAll(context: Context) {
        synchronized(items) { items.clear() }
        // 滑窗 + 滚动摘要 + 磁盘上的 long_memory.json 一起清
        LocalDigitalBeing.clear()
        memories = emptyList()
        // 注意是 clearAll 不是 clearChat：连长期记忆一起清，
        // 否则「清空聊天」之后 AI 还记得「你叫老张」，用户会觉得根本没清干净
        store?.clearAll()
        // 旧存档也顺手清掉：否则下次启动的迁移逻辑会把删掉的内容又塞回来
        runCatching {
            context.applicationContext
                .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(LEGACY_KEY_ITEMS)
                .putBoolean(LEGACY_KEY_MIGRATED, true)
                .apply()
        }
        notifyChanged()
        Log.i(TAG, "聊天记录与记忆已清空")
    }

    private fun ChatItem.toRecord(): ChatRecord = when (this) {
        is ChatItem.User -> ChatRecord(ROLE_USER, text, createdAt = at)
        is ChatItem.Assistant -> ChatRecord(ROLE_ASSISTANT, text, createdAt = at)
        is ChatItem.Action -> ChatRecord(ROLE_ACTION, label, detail, ok, at)
    }

    private fun ChatRecord.toChatItem(): ChatItem = when (role) {
        ROLE_USER -> ChatItem.User(content, createdAt)
        ROLE_ASSISTANT -> ChatItem.Assistant(content, createdAt)
        else -> ChatItem.Action(content, detail, ok, createdAt)
    }
}
