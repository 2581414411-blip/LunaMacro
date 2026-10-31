package com.nexus.agent.copilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.data.db.ChatRecord
import com.nexus.agent.data.db.PersonaStore
import com.nexus.agent.data.db.PersonaProfile
import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.data.db.RoomPersonaStore
import com.nexus.agent.persona.MemoryExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.ArrayDeque

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
 *  - [turns] 给模型的记忆窗口（最多 [MAX_TURNS] 轮）
 *
 * 启动时 [init] 会异步从库里读回档案 + 聊天流 + 最近 [LOAD_TURNS] 轮对话，
 * 读回来之前界面是空的，读完由 [onChanged] 通知刷新。
 */
object PersonaEngine {

    private const val TAG = "PersonaEngine"

    /** 送进 prompt 的记忆窗口（一问一答算 1 轮） */
    const val MAX_TURNS = 10

    /** 启动时从库里读的轮数（就是窗口大小，多读没意义） */
    private const val LOAD_TURNS = MAX_TURNS

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

    /**
     * 一轮对话：模型的输入与输出。
     *
     * 只存文本，不存截图——历史里带图会让每轮请求体积翻几倍，且旧截图对当前问题没用。
     */
    data class Turn(val user: String, val assistant: String)

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

    /** 模型用的历史（纯文本对），最近的在最后 */
    private val turns = ArrayDeque<Turn>()

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
        synchronized(turns) { turns.clear() }
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
        synchronized(turns) {
            if (turns.isEmpty()) {
                turns.addAll(restoredTurns.takeLast(MAX_TURNS))
            }
        }

        Log.i(
            TAG,
            "Room 存档已恢复：名字=${profile.name} 好感=${profile.affinity} 心情=${profile.mood}；" +
                "聊天 ${items.size} 条 / 记忆 ${turns.size} 轮"
        )
        notifyChanged()
    }

    /**
     * 把「user → assistant」的流水两两配对成轮。
     *
     * 落单的用户消息（问了没答上来）直接丢掉：记忆里存「用户：xxx / 你：（空）」
     * 只会让模型学着答一半。
     */
    private fun pairTurns(rows: List<ChatRecord>): List<Turn> {
        val out = ArrayList<Turn>(rows.size / 2 + 1)
        var pendingUser: String? = null
        for (r in rows) {
            when (r.role) {
                ROLE_USER -> pendingUser = r.content
                ROLE_ASSISTANT -> {
                    val u = pendingUser
                    if (!u.isNullOrBlank() && r.content.isNotBlank()) out.add(Turn(u, r.content))
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
            append("\n【当前心情】${profile.mood}（好感度 ${profile.affinity}/100。")
            append("用它决定语气松紧，绝对不要向用户提这些数值）\n")
            // 长期记忆：窗口之外还记着的那些事（0.8.0 起由 MemoryExtractor 抽取）
            val facts = memories
            if (facts.isNotEmpty()) {
                append("\n【关于你的用户（长期记忆）】\n")
                facts.forEach { append("- $it\n") }
                append("这些是你本来就记得的事，自然带进对话就好，不要逐条复述、更不要说「根据我的记忆」。\n")
            }
            val memo = historyText()
            if (memo.isNotBlank()) {
                append("\n【前几轮聊过什么（供你衔接，不用复述）】\n")
                append(memo)
            }
        }
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

    // ---------------------------------------------------------------- 记忆

    /** 最近一轮的助手回复（用来判断"再来一次"这类指代） */
    fun lastAssistantReply(): String = synchronized(turns) { turns.lastOrNull()?.assistant.orEmpty() }

    /**
     * 转成云端接口要的 messages（user/assistant 交替）。
     *
     * 只带文本，不带图——历史截图对当前问题没有价值，还会让请求体积暴涨。
     */
    fun historyMessages(): List<LlmClient.Message> = synchronized(turns) {
        if (turns.isEmpty()) return emptyList()
        val out = ArrayList<LlmClient.Message>(turns.size * 2)
        for (t in turns) {
            out.add(LlmClient.Message("user", t.user))
            out.add(LlmClient.Message("assistant", t.assistant))
        }
        out
    }

    /**
     * 文本化的历史摘要，给本地小模型用（它不吃 messages 数组，只能塞进 prompt）。
     *
     * 每条截断到 60 字：本地模型上下文短，长历史会把它挤爆。
     * 窗口从 6 轮放宽到 10 轮后这一条更重要 —— 10 轮 × 2 条 × 60 字 ≈ 1200 字，
     * 还在 2B 模型能吃下的范围内。
     */
    fun historyText(): String = synchronized(turns) {
        if (turns.isEmpty()) return ""
        turns.joinToString("\n") { t ->
            "用户：${t.user.take(60)}\n你：${t.assistant.take(60)}"
        }
    }

    /** 记一轮对话（[assistant] 为空则忽略） */
    fun recordTurn(user: String, assistant: String) {
        val u = user.trim()
        if (u.isEmpty()) return
        if (assistant.isBlank()) {
            // 没回复就不算完整一轮，避免历史里出现「你：」后面啥都没有
            return
        }
        val t = Turn(u, assistant.trim())
        synchronized(turns) {
            turns.addLast(t)
            while (turns.size > MAX_TURNS) turns.removeFirst()
        }
        // 0.8.0：趁热抽一次长期记忆。异步、失败静默，绝不拖慢这一轮回复。
        extractMemoryAsync(t.user, t.assistant)
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

    fun turnCount(): Int = synchronized(turns) { turns.size }

    /** 清空模型记忆（不影响 UI 聊天记录，也不动数据库） */
    fun clearMemory() {
        synchronized(turns) { turns.clear() }
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
        synchronized(turns) { turns.clear() }
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
