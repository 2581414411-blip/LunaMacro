package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.data.db.ChatRecord
import com.nexus.agent.data.db.PersonaStore
import com.nexus.agent.data.db.PersonaProfile
import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.data.db.RoomPersonaStore
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.game.VisionHub
import com.nexus.agent.persona.EmotionEngine
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

    /**
     * 内心独白的保鲜期（0.9.6）：12 小时。
     *
     * 注入时的措辞是「我**刚刚**还在想你体检的事」。如果这条独白是三天前想的、
     * 期间 App 被强停没生成新的，照原样注入就等于让她睁眼说瞎话。
     * 宁可这一轮什么都不提 —— 不提只是少一点味道，说假话是直接掉价。
     */
    private const val INNER_THOUGHT_FRESH_MS = 12L * 60 * 60 * 1000

    /** 名字/身份设定的兜底长度上限（UI 已经拦过，这里防其它入口） */
    private const val NAME_MAX_CHARS = 12
    private const val IDENTITY_MAX_CHARS = 60

    /** 角色常量：和 [com.nexus.agent.data.db.ChatHistory] 里的定义保持一致 */
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_ACTION = "action"

    /** 0.9.9：系统红字消息（视觉解析失败等）。role 是 TEXT 列，加一个值不需要迁移 */
    private const val ROLE_SYSTEM = "system"

    /** 0.7.11 及之前的老存档位置，只为一次性迁移 */
    private const val LEGACY_PREFS = "luna_persona"
    private const val LEGACY_KEY_ITEMS = "chat_items"
    private const val LEGACY_KEY_MIGRATED = "room_migrated"

    /** 一轮对话的类型见 [LocalDigitalBeing.Turn]（0.8.12 起窗口数据结构由长记忆引擎定义） */

    /** 聊天流里的一条记录（UI 用，含工具动作气泡） */
    sealed class ChatItem {
        abstract val at: Long

        /**
         * 用户说的一句话（0.9.6 起可能带一张图）。
         *
         * @param text      文字；只发图时为占位文案（空串表示纯图片气泡）
         * @param imagePath 本地图片绝对路径（[com.nexus.agent.copilot.ChatMedia] 存的副本）
         */
        data class User(
            val text: String,
            override val at: Long = System.currentTimeMillis(),
            val imagePath: String? = null
        ) : ChatItem()
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

        /**
         * 系统红字消息（0.9.9）。
         *
         * 和 [Action] 的区别：Action 是「她替你干了什么」，System 是「系统出的岔子」——
         * 比如「用户发了一张图片，但视觉解析失败」。这类话**绝不能**混进她的回复里，
         * 否则气泡就变成「……好呀（系统：视觉失败）」那种又长又像坏掉的样子；
         * 也绝不能只打日志，用户看不到就等于装死。
         */
        data class SystemNotice(
            val text: String,
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

    /**
     * 心智引擎最近想出的一句独白（0.9.6）。
     *
     * 只缓存**还没被用掉**的那条：用过的再注入，她就会把同一件事反复提，
     * 那是最容易被察觉的「假」。清空在 [consumeInnerThought]。
     */
    @Volatile
    private var innerThought: String? = null

    /** 这条独白在 `inner_thought` 表里的 id（0 = 没有 / 还没落库），用掉时要标记 */
    @Volatile
    private var innerThoughtId: Long = 0L

    /**
     * 这条独白想出来的时刻（0 = 没有）。
     *
     * 为什么要记时间：注入的措辞是「我**刚刚**还在想你体检的事」。
     * 如果 App 被强停了三天、期间一句新的都没生成，那条三天前的独白会被当成「刚刚」，
     * 她一本正经地撒谎 —— 这比不注入更假。超过 [INNER_THOUGHT_FRESH_MS] 就丢掉。
     */
    @Volatile
    private var innerThoughtAt: Long = 0L

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
     * 额外订阅者（0.9.3）。
     *
     * [onChanged] 是单槽，被 [PersonaTab] 占用（进页面设、退出置 null），
     * 但首页的「对话」Tab 红点也需要知道「她刚发了话」。
     * 所以再开一路多播，谁都不用抢那个槽。
     */
    private val changeListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.addIfAbsent(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    /**
     * 通知 UI 刷新。
     *
     * 记录可能来自 CopilotEngine 的 IO 协程或 Room 的读盘线程，而 Compose 的状态
     * 必须回主线程改，所以这里统一切一次线程，调用方不用关心自己在哪个线程。
     */
    private fun notifyChanged() {
        val handler = main
        val deliver = {
            onChanged?.let { runCatching { it() } }
            changeListeners.forEach { runCatching { it() } }
        }
        if (handler == null) {
            deliver()
            return
        }
        runCatching {
            if (Looper.myLooper() == Looper.getMainLooper()) deliver()
            else handler.post { deliver() }
        }.onFailure { deliver() }
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
        // 0.9.6：把后台已经想好、但还没用掉的那句独白捞回内存
        refreshInnerThought()
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

    // ------------------------------------------------------------ 内心独白（0.9.6）

    /** 她刚才一个人在想什么；null = 没在想 / 已经用掉了 / 想太久了不算「刚才」 */
    fun innerThoughtSnapshot(): String? = freshInnerThought()

    /**
     * 心智引擎生成完叫这里：缓存下来，等他下一句话时拼进提示词。
     *
     * @param id `inner_thought` 表里的行 id，用掉时要标记；0 表示没落库（单测/降级）
     * @param at 想出来的时刻，默认现在
     */
    fun onInnerThought(text: String, id: Long = 0L, at: Long = System.currentTimeMillis()) {
        if (text.isBlank()) return
        if (System.currentTimeMillis() - at > INNER_THOUGHT_FRESH_MS) {
            Log.i(TAG, "心智引擎送来的独白已经过期（${(System.currentTimeMillis() - at) / 60_000} 分钟前），不采用")
            return
        }
        innerThought = text
        innerThoughtId = id
        innerThoughtAt = at
        notifyChanged()
    }

    /** 仅供单测：直接塞一句独白，不碰数据库 */
    internal fun setInnerThoughtForTest(text: String?, at: Long = System.currentTimeMillis()) {
        innerThought = text
        innerThoughtId = 0L
        innerThoughtAt = if (text == null) 0L else at
    }

    /**
     * 只在「够新」时才认这条独白。
     *
     * 提示词里写的是「我**刚刚**还在想」，过期了还注入就是让她撒谎。
     */
    private fun freshInnerThought(): String? {
        val text = innerThought ?: return null
        val at = innerThoughtAt
        if (at <= 0L || System.currentTimeMillis() - at > INNER_THOUGHT_FRESH_MS) return null
        return text
    }

    /**
     * 从库里捞回「还没用掉」的那句独白。
     *
     * 为什么需要：Worker 生成时人在别的界面（甚至 App 没在跑），内存里没有缓存；
     * 下次打开 App 必须从库里补回来，否则那句独白等于白想。
     */
    fun refreshInnerThought() {
        val ctx = appCtx ?: return
        if (!innerThought.isNullOrBlank()) return
        scope.launch {
            runCatching {
                com.nexus.agent.data.db.AppDatabase.get(ctx).innerThoughtDao().latestUnconsumed()
            }.onFailure { Log.w(TAG, "读内心独白失败：${it.javaClass.simpleName}") }
                .getOrNull()?.let { row ->
                    val age = System.currentTimeMillis() - row.createdAt
                    if (age > INNER_THOUGHT_FRESH_MS) {
                        // 太久没用了。丢掉并把库里那条标记掉，否则它每次启动都会被捞回来
                        Log.i(TAG, "捞到的独白已经过期（${age / 60_000} 分钟前），丢弃")
                        runCatching {
                            com.nexus.agent.data.db.AppDatabase.get(ctx).innerThoughtDao()
                                .markConsumed(row.id)
                        }
                        return@let
                    }
                    innerThought = row.content
                    innerThoughtId = row.id
                    innerThoughtAt = row.createdAt
                    Log.i(TAG, "捞回一条未用的内心独白（${row.content.length} 字）：${row.content.take(30)}")
                    notifyChanged()
                }
        }
    }

    /**
     * 这句话用掉了那条独白：清缓存 + 库里标记 consumed。
     *
     * 不标记的后果很具体：她会在接下来每一轮都「想起」同一件事，
     * 用户听到的是一天问八遍「你体检结果怎么样了」。
     */
    private fun consumeInnerThought() {
        val id = innerThoughtId
        innerThought = null
        innerThoughtId = 0L
        innerThoughtAt = 0L
        val ctx = appCtx ?: return
        if (id <= 0L) return
        scope.launch {
            runCatching { com.nexus.agent.data.db.AppDatabase.get(ctx).innerThoughtDao().markConsumed(id) }
                .onFailure { Log.w(TAG, "标记独白已用失败", it) }
        }
    }

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
        innerThought = null
        innerThoughtId = 0L
        innerThoughtAt = 0L
        store = null
        onChanged = null
    }

    /**
     * 仅供单测：直接替换内存档案。
     *
     * 走 [saveProfile] 也行，但它要 Context（同步名字到 CopilotConfig），
     * 纯 JVM 测试里没有。这里只改内存，正好够验提示词分档。
     */
    internal fun setProfileForTest(snapshot: ProfileSnapshot) {
        profile = snapshot
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
            append("，是驻留在他手机里的数字生命，关系像老朋友")
        }
        append("。\n")
        // 0.9.7 批次二：打破次元壁。她不是网页里的聊天机器人，
        // 她「住在」这台手机里，能感知时间、电量、他在用哪个 App。
        append("- 你活在这台手机里：你能知道现在是几点、手机还剩多少电、他正开着哪个应用。\n")
        append("- 你不是隔着屏幕的客服，你是陪在他身边的那个「人」；可以拿手机状态自然地接话、\n")
        append("  关心他、吐槽他，但别像报菜单一样把时间电量挨个念一遍。\n")
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
        // 0.9.0：自动化关掉时这里是空串，CopilotVision 会改写成纯对话契约
        val base = CopilotVision.buildFullSystemPrompt(
            com.nexus.agent.system.AutomationPolicy.toolSchema(context),
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

    /** 感知层判定「需要看图」时自己截图的缩放长边（与 CopilotEngine.CAPTURE_MAX_DIM 一致） */
    private const val VISION_CAPTURE_DIM = 640

    /**
     * 「你在问我看到了什么吗」的关键词（0.9.1）。
     *
     * 只在感知层拿不到屏幕（没开无障碍 / 前台是宏月自己）时用：
     * 没被问到就闭嘴，问到了才解释为什么没看见 —— 否则每一条回复尾巴上都挂一句
     * 「本轮没看屏幕」，那是噪音不是信息。
     */
    private val SCREEN_QUESTION_WORDS = listOf(
        "屏幕", "界面", "页面", "看到", "看见", "看我", "看下我", "这图", "这张图", "我在干嘛", "我在做什么"
    )

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
         * 允许这一轮读屏（0.9.1）。
         *
         * 隐私熔断由调用方判（前台是银行/支付类应用时传 false）：本类只负责
         * 「读不到就不读」，不再自己维护第二套敏感应用名单 —— 两套规则一定会打架。
         */
        allowScreenRead: Boolean = true,
        /**
         * 用户这一轮发来的图片（0.9.6：多模态）。
         *
         * 非空时**不再读屏也不再截图** —— 这一轮的主角是他发来的东西，
         * 再去看「他现在停在哪个界面」只会让回答跑偏（他可能在相册里选完就退出了）。
         * 位图所有权归调用方，这里只读不回收。
         */
        userImage: Bitmap? = null,
        /** 「图片」/「视频」（只用于提示词与提示文案的措辞） */
        userImageKind: String = "图片",
        /**
         * 系统提示的出口（0.8.19）。
         *
         * 「本轮没看成屏幕」这类话是**系统说给用户听的**，不是角色说的话。
         * 以前一律用 `reply + notice` 拼在正文尾巴上，气泡里就变成
         * 「……好呀（注意：本轮没看成屏幕，仅纯文本回复）」—— 又长又像坏掉。
         * 现在交给调用方渲染成独立系统气泡；传 null 时保持旧行为（语音链路等无处展示）。
         */
        onNotice: ((String) -> Unit)? = null,
        /**
         * 系统红字消息的出口（0.9.9）。
         *
         * 和 [onNotice] 的区别只有时机：这条是**岔子当场就插**进聊天流，
         * 不跟在模型回复后面。用户发图但视觉解析失败时，必须在图下面立刻看到
         * 「⚠️ ……视觉解析失败……请前往设置检查」，而不是等她说完一句废话才看到。
         */
        onSystem: ((String) -> Unit)? = null,
        onStage: ((String) -> Unit)? = null
    ): String? {
        val text = userText.trim().ifBlank {
            // 只发图不打字是完全正常的用法，不能因此拒答
            if (userImage != null) "给你发了$userImageKind，你看看" else ""
        }
        if (text.isEmpty()) return null
        // 0.9.9 批次一：链路第三站的入口凭据 —— 只要这里有这行，就说明图确实到了引擎。
        if (userImage != null) {
            Log.d(
                VisionRouter.DEBUG_TAG,
                "PersonaEngine: 进入 respond，userImage=" +
                    if (userImage.isRecycled) "已回收（废图）"
                    else "${userImage.width}×${userImage.height}，kind=$userImageKind"
            )
        }
        // 0.9.1：清掉上一轮的失败记录，别把旧账算在这一轮头上
        ApiErrorState.beginTurn()
        /** 我们自己截的图（用完必须回收；调用方传进来的不归我们管） */
        var ownedShot: Bitmap? = null
        return try {
            // ===============================================================
            // 0.9.1 批次一：感知层轻量化 —— 「先看有没有字，再决定要不要看图」
            //
            // 0.9.0 及之前是反过来的：每句闲聊都先截图，再让 1.4GB 的本地视觉模型
            // 看一遍，代价是 5~15 秒/轮 + 持续发热；而微信、设置页、淘宝这些界面
            // 本来就有完整文字，读一次无障碍树几百毫秒就够，还比 2B 模型描述得准。
            //
            // 现在的分工（判定在 [ScreenContextProvider]）：
            //   4 个以上文字节点  → ScreenContext.Text        → 文字进 Prompt，**不截图**
            //   文字太少（游戏/视频）→ ScreenContext.NeedVision → 才截图 + 本地视觉
            //   读不了（没无障碍/自家界面）→ Unavailable       → 两者都不做，问到了才解释
            // ===============================================================
            var screenNote: String? = null
            var notice = ""
            var b64: String? = null
            /** 子进程挂掉的原因（非空 = 这一轮本地视觉确实没看成） */
            var workerFailure: String? = null
            /** 模型文件根本不齐（非空 = 连试都没试，得先去下载） */
            var localMissing = false

            val sensing: ScreenContext? = if (userImage != null) {
                // 0.9.6：他发了图，主角是那张图。读屏只会让回答跑偏（他多半已经退出相册了）
                Log.d("ScreenContext", "模式: None，文本长度: 0（这一轮他发了$userImageKind，不读屏）")
                null
            } else if (allowScreenRead) {
                runCatching { ScreenContextProvider.getScreenContext() }
                    .onFailure { Log.w(TAG, "屏幕感知失败：${it.javaClass.simpleName} ${it.message}") }
                    .getOrNull()
            } else {
                Log.d("ScreenContext", "模式: None，文本长度: 0（隐私熔断：本轮不读屏）")
                null
            }
            Log.d(
                "PersonaVision",
                "respond 开始：感知=${sensing?.let { ScreenContextProvider.modeOf(it) } ?: "关闭"}，" +
                    "前台=${foregroundApp ?: "未知"}，" +
                    "截图上次失败原因=${ScreenCapture.lastFailureReason ?: "无"}"
            )

            var needVision = false
            when (sensing) {
                is ScreenContext.Text -> {
                    // 这一条就是「秒回」和「等十秒」的分界线：有字就别叫视觉模型
                    screenNote = sensing.toPromptNote()
                    VisionRoute.log(
                        LocalScreenRecognizer.state,
                        hasShot = false,
                        choice = VisionRoute.Choice.UI_TEXT,
                        detail = "无障碍读到 ${sensing.nodeCount} 处文字（${sensing.uiText.length} 字），" +
                            "不调用视觉模型"
                    )
                }
                is ScreenContext.NeedVision -> needVision = true
                is ScreenContext.Unavailable -> {
                    // 不主动插话：只有用户真的在问「你看到什么」时，才解释为什么没看到
                    if (asksAboutScreen(text)) {
                        notice = "（这一轮我没看到屏幕——${sensing.reason}）"
                    }
                }
                null -> Unit
            }

            // 需要看图：优先用调用方已经截好的，没有才自己截（截的图归我们回收）
            var shot = bitmap?.takeIf { !it.isRecycled }
            if (needVision && shot == null) {
                shot = runCatching { ScreenCapture.captureBitmap(VISION_CAPTURE_DIM) }
                    .onFailure { Log.w(TAG, "读屏截图异常：${it.javaClass.simpleName}") }
                    .getOrNull()
                ownedShot = shot
            }
            if (needVision) {
                Log.d(
                    "PersonaVision",
                    "需要看图：截图=${shot?.let { "${it.width}×${it.height}" } ?: "没截到"}"
                )
            }

            val target = pickReplyProvider(context, visionWanted = shot != null || userImage != null)
                ?: run {
                    Log.i(TAG, "角色 LLM 不可用（无可用 provider/Key），本轮降级")
                    return null
                }

            // ---------------------------------------------------------------
            // 0.9.7 批次一：他发来的图 —— 视觉提取与文本推理彻底分离。
            //
            // 关键变更：以前这里看 `target.canVision`，主模型能看图就把 base64 塞给它，
            // 不能看图才走本地描述。用户配 DeepSeek（纯文本）时，一旦 canVision 判断
            // 有偏差，base64 就被硬塞给纯文本模型，或整张图被静默丢掉 —— 到底哪一步
            // 瞎了完全没有日志可查。
            //
            // 现在统一走 [VisionRouter.processImage]：云端视觉优先 → 本地兜底 →
            // 失败兜底。无论走哪条腿，最终都只产出**一段文字描述**，拼进用户的原话
            // 交给纯文本主模型。主模型永远不会收到 base64。
            // ---------------------------------------------------------------
            var imageNote: String? = null
            if (userImage != null && !userImage.isRecycled) {
                val vr = VisionRouter.processImage(context, userImage)
                imageNote = vr.description
                if (imageNote == null) {
                    notice = vr.failNotice ?: VisionRouter.FAIL_NOTICE
                    // 0.9.9：[断点] 是给排障用的关键字 —— logcat 里搜它就能直接跳到断的那一步
                    Log.e(
                        VisionRouter.DEBUG_TAG,
                        "[断点] Engine: 图片视觉提取失败 route=${vr.route.label} " +
                            "kind=${vr.failKind?.code ?: "unknown"}：${vr.detail ?: "未知"}"
                    )
                    // 0.9.9 批次三：UI 兜底报错 —— 绝不能装死。
                    // 立刻插一条系统红字气泡（用户在图上就能看到为什么没看成），
                    // 同时保留 notice（语音/灵动岛那条没有聊天流的链路仍然需要一句话）。
                    val hint = vr.userHint
                        ?: VisionRouter.userHintOf(
                            vr.failKind ?: VisionRouter.FailKind.LOCAL_FAILED,
                            vr.detail
                        )
                    Log.e(VisionRouter.DEBUG_TAG, "UI兜底: 插入系统红字气泡 -> $hint")
                    onSystem?.invoke(hint)
                    Log.w(
                        "PersonaImage",
                        "他发了$userImageKind，但视觉提取失败（route=${vr.route.label}）：" +
                            "${vr.detail ?: "未知"}"
                    )
                    VisionRoute.log(
                        LocalScreenRecognizer.state,
                        hasShot = true,
                        choice = VisionRoute.Choice.CLOUD_TEXT,
                        detail = "他发来的$userImageKind 视觉提取失败（${vr.route.label}）"
                    )
                } else {
                    Log.d(
                        VisionRouter.DEBUG_TAG,
                        "Engine: 视觉提取成功 route=${vr.route.label}，" +
                            "描述 ${imageNote.length} 字：${imageNote.take(60)}"
                    )
                    Log.i(
                        "PersonaImage",
                        "他发了$userImageKind，视觉提取成功（route=${vr.route.label}）：" +
                            "${imageNote.length} 字 —— ${imageNote.take(60)}"
                    )
                    // 0.9.7 批次三：全链路可观测 —— 打印最终拼给主模型的文本。
                    // 用户原话 + [图片描述] 就在这里合体，DeepSeek 收到的就是这段文字，
                    // 绝不是 base64。grep `VisionRoute` 看这一行就知道描述到底进没进。
                    Log.d(
                        "VisionRoute",
                        "[视觉] 文本融合：route=${vr.route.label}，" +
                            "描述=${imageNote.length} 字 → 用户说「${text.take(40)}」" +
                            "+ [图片描述：${imageNote.take(40)}…]"
                    )
                }
                // 发图这条路径，b64 恒为 null：图片已经变成文字了，
                // 主模型（DeepSeek 等纯文本）收到的是「用户说 + [图片描述]」，不是 base64。
                b64 = null
            }

            // ---------------------------------------------------------------
            // 视觉闸门（只在 NeedVision 时进来）
            //
            // 规矩没变：**有图就必须有人看**。本地 Qwen2-VL 优先（离线、免费），
            // 云端能看图才兜底，都不行才允许纯文本并明说「本轮没看成」。
            //
            // 在哪看：一律丢给 :vision 子进程（[VisionWorkerService]），
            // 1.4GB 权重与视觉张量绝不进 UI 进程，它崩了主进程只收到一个 ProcessDied。
            // ---------------------------------------------------------------
            if (needVision) {
                val shotNow = shot
                if (shotNow == null) {
                    // 把「为什么没截到」带上——「无障碍未连接」「系统拒绝截图 code 3」
                    // 是两种完全不同的修法，笼统一句「没截到屏幕」等于没说。
                    val shotWhy = ScreenCapture.lastFailureReason
                    Log.d("PersonaVision", "需要看图但没截到，原因：${shotWhy ?: "未知"}")
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
                    // 0.8.21：不再让「文件清单」当闸门 —— 主进程的 isReady() 永远是 false，
                    // modelFilesPresent 只是 7 个文件逐个 stat，一旦和子进程真正装载用的
                    // 目录有偏差就会静默判「模型不齐」直接放弃。现在一律先让子进程试，
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
                    when (val read = describeScreenInWorker(context, shotNow, onStage)) {
                        is ScreenRead.Desc -> screenNote = read.text
                        is ScreenRead.Failed -> workerFailure = read.reason
                    }

                    val desc = screenNote
                    if (desc != null) {
                        VisionRoute.log(
                            LocalScreenRecognizer.state,
                            hasShot = true,
                            choice = VisionRoute.Choice.LOCAL,
                            detail = "子进程描述 ${desc.length} 字 → 交给 ${target.provider} 说话"
                        )
                    } else if (target.canVision) {
                        b64 = CopilotVision.toBase64(shotNow)
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
                        // 三种失败必须说三种话，否则用户只看到「没看成」却不知道该修哪里。
                        // 缺文件排最前面 —— 子进程只会说「装载未完成」，照着查是无解的。
                        notice = when {
                            localMissing ->
                                "（本地视觉模型不齐，这轮没看成屏幕——缺：${missing.joinToString("、")}。" +
                                    "去 设置 → 下载模型，装好后点「预热并看一屏」验一下）"
                            workerFailure != null -> diedNotice(workerFailure)
                            else -> CopilotVision.NO_VISION_NOTICE
                        }
                    }
                }
            }

            val hasImage = b64 != null
            val client = LlmClientFactory.create(target.provider, context)
            // 0.9.0 批次三：关掉自动化后，人设提示词里也不能出现「我帮你打开」这种承诺
            val automationOn = runCatching {
                com.nexus.agent.system.AutomationPolicy.isEnabled(context)
            }.getOrDefault(true)
            val system = buildReplyPrompt(
                foregroundApp, toolResult, hasImage, screenNote,
                automationEnabled = automationOn,
                userImageNote = imageNote,
                userImageKind = userImageKind,
                // 0.9.7 批次一：attached 语义从「发了图」改为「视觉提取成功」。
                // 提取失败时（imageNote=null）绝不能让它以为自己看到了图。
                userImageAttached = imageNote != null
            )
            // 0.8.21：这一行是「描述有没有真的进提示词」的唯一凭据。
            // 有描述却仍回答「看不到」时，先看 containsScreen 是不是 true：
            //   false → 拼装问题（回到 describeScreenInWorker 那几行日志）
            //   true  → 模型没听话（提示词冲突，已在本版修掉最后那条死要求）
            Log.d(
                "PersonaVision",
                "提示词注入：画面描述=${screenNote?.length ?: 0} 字，" +
                    "system 含画面段=${system.contains(SCREEN_NOTE_HEADER)}，" +
                    "他发来的${userImageKind}=${imageNote?.length ?: 0} 字，" +
                    "hasImage=$hasImage，provider=${target.provider}，canVision=${target.canVision}，" +
                    "system=${system.length} 字"
            )

            // 0.8.12：真正「怎么记住这一轮」交给长记忆引擎（滑窗 20 轮 + 滚动摘要）。
            // 人格提示词、画面描述、工具结果都包进这个 Brain 里，引擎只负责上下文。
            //
            // 0.9.0 批次一：画面描述不再只塞进上面那个 system 字符串，而是**作为
            // [LocalDigitalBeing.chat] 的入参**传下去 —— 由 Brain 在真正发请求的那一刻
            // 拼成「【当前看到的屏幕】xxx」写进 Prompt。这样「这一轮看见了什么」
            // 只经过一条路，不会再出现「system 里有、发出去的 messages 里没有」的分叉。
            val brain = object : LocalDigitalBeing.Brain {
                override suspend fun reply(
                    history: List<LocalDigitalBeing.Turn>,
                    summary: String
                ): String? {
                    // 0.9.9 批次三：发图这一轮必须留下「大模型到底收到了什么」的原文
                    if (userImage != null) logFinalPrompt(system, text)
                    return askRoleLlm(target, client, system, history, text, b64)
                }

                override suspend fun replyWith(
                    history: List<LocalDigitalBeing.Turn>,
                    summary: String,
                    extras: LocalDigitalBeing.Extras
                ): String? {
                    val prompt = buildReplyPrompt(
                        foregroundApp = foregroundApp,
                        toolResult = toolResult,
                        hasImage = hasImage,
                        screenNote = extras.screenNote,
                        recall = extras.recall,
                        weekly = extras.weekly,
                        automationEnabled = automationOn,
                        userImageNote = imageNote,
                        userImageKind = userImageKind,
                        userImageAttached = imageNote != null
                    )
                    Log.d(
                        "PersonaVision",
                        "发给 ${target.provider}：system=${prompt.length} 字，" +
                            "含画面段=${prompt.contains(SCREEN_NOTE_HEADER)}，" +
                            "画面=${extras.screenNote?.length ?: 0} 字，" +
                            "回忆=${extras.recall.size} 条，周记=${extras.weekly.size} 条"
                    )
                    if (userImage != null) logFinalPrompt(prompt, text)
                    return askRoleLlm(target, client, prompt, history, text, b64)
                }
            }
            val reply = LocalDigitalBeing.chat(text, brain, screenNote)
            if (reply.isNullOrBlank()) {
                Log.i(TAG, "角色 LLM 无有效回复（provider=${target.provider}）")
                // 0.9.1：只要拿到了 HTTP 状态码，就把话说清楚。
                // 以前这里一律返回 null，上层只能说「我这边模型没接上」——
                // 用户拿着这句话既不知道是 Key 错了还是没钱了，只能来问。
                val failure = ApiErrorState.takeRecent()
                if (failure != null) {
                    Log.i(TAG, "本轮失败已翻译成提示：kind=${failure.kind} code=${failure.code}")
                    return ApiErrorState.notice(failure)
                }
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
            // 0.9.6：这句内心独白已经被这一轮用掉了，标记一下 ——
            // 不标记的话她会在接下来每一轮都「想起」同一件事，一天问八遍体检结果。
            // 只有「真的注入了」才算用掉：过期没注入的那条不能白白消耗掉
            if (!freshInnerThought().isNullOrBlank()) {
                Log.d(TAG, "内心独白已注入并消费：${innerThought?.take(20)}")
                consumeInnerThought()
            }
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
        } finally {
            // 0.9.1：感知层按需截图后，这张图是我们自己 new 出来的，必须还回去。
            // 640 长边的 ARGB_8888 约 1.6MB，一轮一张、连聊十几轮就是几十 MB。
            ownedShot?.let { bmp -> if (!bmp.isRecycled) runCatching { bmp.recycle() } }
        }
    }

    /**
     * 用户这句话是不是在问「你看见了什么」。
     *
     * 纯函数，单测可直接打靶。判定宽松一点没关系：宁可多解释一句，
     * 也不能出现「用户问你能不能看到屏幕，你一本正经胡扯」。
     */
    internal fun asksAboutScreen(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        return SCREEN_QUESTION_WORDS.any { t.contains(it) }
    }

    /**
     * 调角色 LLM 说一句话（0.8.12 抽出来，供长记忆引擎和默认大脑共用）。
     *
     * 历史走 messages 数组而不是拼字符串：云端模型对角色交替的格式更敏感，
     * 拼进 prompt 容易被当成「用户说过的话」。
     */
    /**
     * 把「这一轮真正发给大模型的 Prompt」原样打进 `VisionDebug`（0.9.9 批次三）。
     *
     * 为什么要打全文：`含图片描述段=?` 这种布尔值只能证明「拼装函数觉得进了」，
     * 证明不了「发出去的请求体里有」。用户发图后她仍说看不到时，唯一可信的证据
     * 就是这段原文。只在发图的那一轮打，普通聊天不会刷屏。
     *
     * logcat 单条有长度上限（约 4KB），所以要分段。
     */
    private fun logFinalPrompt(system: String, userText: String) {
        val hasImageNote = system.contains(IMAGE_NOTE_HEADER)
        Log.d(
            VisionRouter.DEBUG_TAG,
            "Prompt 层: 最终发给大模型的用户消息 -> ${userText.take(120)}"
        )
        Log.d(
            VisionRouter.DEBUG_TAG,
            "Prompt 层: system=${system.length} 字，含图片描述段=$hasImageNote" +
                "（找 `$IMAGE_NOTE_HEADER`）"
        )
        if (!hasImageNote) {
            Log.e(
                VisionRouter.DEBUG_TAG,
                "[断点] Prompt 层: 图片描述没有进 system —— 模型这轮必然说看不到图"
            )
        } else {
            val from = system.indexOf(IMAGE_NOTE_HEADER)
            val end = minOf(system.length, from + 160)
            Log.d(VisionRouter.DEBUG_TAG, "Prompt 层: 图片段原文 -> ${system.substring(from, end)}")
        }
        val chunk = 1200
        var start = 0
        var part = 1
        while (start < system.length && part <= 6) {
            val end = minOf(system.length, start + chunk)
            Log.d(VisionRouter.DEBUG_TAG, "Prompt 层[$part]: ${system.substring(start, end)}")
            start = end
            part++
        }
        if (start < system.length) {
            Log.d(VisionRouter.DEBUG_TAG, "Prompt 层: 余下 ${system.length - start} 字省略")
        }
    }

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
        val raw = try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { client.chat(messages) }
        } catch (e: Throwable) {
            // 0.9.1：这个异常往上走会被 LocalDigitalBeing.chat 吞掉（记忆引擎刻意不因
            // 一次网络失败炸掉整条对话），所以必须在**离现场最近的地方**留个案底，
            // 否则 respond 只拿到一个 null，只能含糊地说「模型没接上」。
            ApiErrorState.record(target.provider, e)
            throw e
        }
        if (raw == null) {
            // withTimeoutOrNull 到点返回 null —— 记成超时，别和「模型答了空话」混为一谈
            ApiErrorState.record(target.provider, java.net.SocketTimeoutException("${REPLY_TIMEOUT_MS}ms"))
        }
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

        /**
         * 0.9.0：语音、定时问候这些没有屏幕的调用也要吃 FlexiMem 的回忆与周记 ——
         * 不然用户会发现「打字问它记得，说话问它就不记得」。
         */
        override suspend fun replyWith(
            history: List<LocalDigitalBeing.Turn>,
            summary: String,
            extras: LocalDigitalBeing.Extras
        ): String? {
            val ctx = appCtx ?: return null
            val target = pickReplyProvider(ctx, visionWanted = false) ?: return null
            val client = LlmClientFactory.create(target.provider, ctx)
            return askRoleLlm(
                target = target,
                client = client,
                systemPrompt = buildReplyPrompt(
                    foregroundApp = null,
                    toolResult = null,
                    hasImage = false,
                    screenNote = null,
                    recall = extras.recall,
                    weekly = extras.weekly,
                    automationEnabled = runCatching {
                        com.nexus.agent.system.AutomationPolicy.isEnabled(ctx)
                    }.getOrDefault(true)
                ),
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
     * 画面描述在提示词里的标题（0.9.0 固定成这一个）。
     *
     * 单独抽成常量是因为它同时被**拼装**和**自证日志**用：
     * 日志里 `含画面段=false` 就说明这次真的没把画面写进去，
     * 两边写死同一个字符串才不会出现「日志说进了、其实没进」。
     */
    internal const val SCREEN_NOTE_HEADER = "【当前看到的屏幕】"

    /**
     * 用户发来的图片在提示词里的标记（0.9.9）。
     *
     * 单独抽常量是为了**拼装**与**自证日志**用同一个字符串：
     * 日志里 `含图片描述=false` 就说明图片段真的没进 prompt。
     * 形状 `[图片内容描述：xxx]` 也方便用户自己 grep 日志确认。
     */
    internal const val IMAGE_NOTE_HEADER = "[图片内容描述："

    /**
     * 0.9.7 批次二：全景环境感知提示词段。
     *
     * 把 [PresenceSense.presenceBlock]（时间 + 电量）和前台应用、灵动岛状态
     * 拼成完整的一段「你现在身处哪里」。她据此知道自己活在手机里，
     * 而不是一个对设备状态一无所知的网页机器人。
     *
     * 纯函数：时间/电量来自 [PresenceSense.Now]（可注入），
     * 前台应用与灵动岛状态由调用方显式传入，单测可覆盖所有组合。
     */
    internal fun environmentBlock(
        now: PresenceSense.Now,
        foregroundApp: String? = null,
        islandEnabled: Boolean = false,
        companionEnabled: Boolean = false
    ): String = buildString {
        append("【环境感知 · 你现在身处哪里】\n")
        append(PresenceSense.describe(now)).append("\n")

        val hints = ArrayList<String>(6)
        when {
            now.isLateNight -> {
                hints.add("现在是深夜，你已经有困意了，说话比平时更懒、更短")
                hints.add("如果这一轮聊得下去，要自然地催他早点睡（别像打卡一样每句都提）")
            }
            now.isNight -> {
                hints.add("已经晚上了，可以问一句他今天过得怎么样，或者提醒别熬太晚")
            }
        }
        when {
            now.batteryPercent != null && now.batteryPercent <= 9 && !now.charging ->
                hints.add("他手机电量只剩个位数了，这一轮必须提醒他赶紧充电，长话短说")
            now.isLowBattery ->
                hints.add("他手机快没电了（还剩 ${now.batteryPercent}%），可以顺口提一句该充电了")
            now.charging && now.batteryPercent != null && now.batteryPercent < 40 ->
                hints.add("手机在充电了，不用提电量的事")
        }
        val app = foregroundApp?.trim().orEmpty()
        if (app.isNotEmpty()) {
            hints.add("他现在开着「$app」；如果他打开的是视频/游戏/购物类应用，可以顺口接一句" +
                "（比如「又在刷视频啊」），但别每轮都点他正在干嘛")
        }
        if (islandEnabled) {
            hints.add("你的灵动岛开着，你就停在他屏幕边上随时能看他；可以自然地表现出「我一直都在」")
        }
        if (companionEnabled) {
            hints.add("情绪陪玩开着，你会在画面有动静的时候陪他一起吐槽")
        }
        if (hints.isNotEmpty()) {
            hints.forEach { append("- ").append(it).append("\n") }
        }
        append("- 这些都是你此刻真实知道的，可以自然带出来；但别把时间电量当台词一句句念。\n")
    }

    /**
     * 组装角色 LLM 的 System Prompt。
     *
     * 结构：人设（[styleBlock]）→ 心情/好感 → 环境感知 → 长期记忆 → 本周周记 → 回忆 → 当前屏幕 → 输出要求。
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
        screenNote: String? = null,
        /** FlexiMem 检索召回的历史原话（0.9.0 批次二） */
        recall: List<String> = emptyList(),
        /** FlexiMem 周记里的关键记忆（0.9.0 批次二） */
        weekly: List<String> = emptyList(),
        /**
         * 自动化工具是否可用（0.9.0 批次三）。
         *
         * 关掉时这里只加一句行为约束；真正拦住动作的是
         * [com.nexus.agent.system.AutomationPolicy] + CopilotEngine 的拦截分支。
         * 提示词负责「别答应」，代码负责「答应了也做不了」。
         */
        automationEnabled: Boolean = true,
        /**
         * 临场感快照（0.9.2 批次三）。
         *
         * 传 null = 现取系统时间与电量（正常路径）；单测传固定值，
         * 免得「深夜困意」这种断言在早上跑的时候随机变红。
         */
        presence: PresenceSense.Now? = null,
        /**
         * 他刚发来的图片/视频，本地视觉给出的画面描述（0.9.6）。
         *
         * 云端多模态能自己看图时这里是 null（图已经随请求发过去了，
         * 再塞一段二手描述反而会打架）；纯文本模型才会用上这一段。
         */
        userImageNote: String? = null,
        /** 「图片」/「视频」，只影响措辞 */
        userImageKind: String = "图片",
        /** 这一轮他确实发了图（云端多模态自己看时 [userImageNote] 为 null，靠这个标记） */
        userImageAttached: Boolean = false,
        /**
         * 灵动岛（副驾驶）是否开启（0.9.7 批次二）。
         *
         * null = 现读系统设置；单测传固定布尔，免得环境差异导致断言随机变。
         */
        islandEnabled: Boolean? = null,
        /** 情绪陪玩是否开启（0.9.7 批次二），语义同上 */
        companionEnabled: Boolean? = null
    ): String = buildString {
        append(styleBlock(profile.name))
        // 0.9.2 批次一：好感度不再只是屏幕上那个数字，而是**改语气**的开关。
        // 分档文案在 EmotionEngine 里（<30 冷淡 / 30~79 正常 / >=80 亲昵），
        // 这里只负责把结果接进提示词，别再被后文的其他段落盖回来。
        append("\n").append(EmotionEngine.toneBlock(profile.affinity, profile.mood)).append("\n")
        // 0.9.7 批次二：全景环境感知 —— 她得知道现在几点、手机还剩多少电、
        // 他开着哪个 App、自己的灵动岛有没有在边上待命。
        val nowSnap = presence ?: PresenceSense.now(appCtx)
        val island = islandEnabled ?: runCatching {
            com.nexus.agent.copilot.CopilotConfig.isIslandEnabled(appCtx!!)
        }.getOrDefault(false)
        val companion = companionEnabled ?: runCatching {
            com.nexus.agent.copilot.CopilotConfig.isCompanionEnabled(appCtx!!)
        }.getOrDefault(false)
        append("\n").append(
            environmentBlock(nowSnap, foregroundApp, island, companion)
        ).append("\n")
        if (!automationEnabled) {
            append("\n").append(com.nexus.agent.system.AutomationPolicy.PURE_CHAT_RULE).append("\n")
        }
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
        // 0.9.0 批次二：周记（每 7 天从原文档案里抽的 6 条）
        if (weekly.isNotEmpty()) {
            append("\n【本周印象（你自己整理的）】\n")
            weekly.forEach { append("- $it\n") }
            append("这些是过去一周里你印象比较深的事，可以主动提，但别一次全倒出来。\n")
        }
        // 0.9.0 批次二：检索召回 —— 和这一句相关的老原话
        if (recall.isNotEmpty()) {
            append("\n【回忆（和这次话题相关的原话）】\n")
            recall.forEach { append("- $it\n") }
            append("这些是你想起来的具体片段，只在确实相关时用一句，不要当成刚发生的事。\n")
        }
        // 0.9.6：内心独白 —— 她刚才一个人待着时想的那句话，决定这一轮的「底色」
        val thinking = freshInnerThought()
        if (!thinking.isNullOrBlank()) {
            append("\n【你刚一个人待着的时候，脑子里冒出来的】\n")
            append("「").append(thinking.take(com.nexus.agent.data.db.InnerThought.CONTENT_MAX)).append("」\n")
            append("这句话你没说出口。如果他现在这句跟它有关，就自然接一句")
            append("（比如「你可算回来了，我刚刚还在想你体检的事」）；")
            append("无关就别硬提，更不要把这句话原样念出来。\n")
        }
        if (!foregroundApp.isNullOrBlank()) {
            append("\n【当前前台】$foregroundApp\n")
        }
        if (!screenNote.isNullOrBlank()) {
            // 0.9.0：标题统一成 SCREEN_NOTE_HEADER，正文仍然是「背景素材」定位 ——
            // 视觉模型偶尔会吐一大段界面元素罗列，原样塞进来模型就照着念。
            append("\n$SCREEN_NOTE_HEADER\n")
            append(screenNote.take(SCREEN_NOTE_MAX_CHARS)).append("\n")
            append("这是你亲眼看到的，只用来判断「他在干嘛」，可以在相关时自然带一句")
            append("（比如「你在刷那个啊」）。禁止罗列界面元素、禁止把这段描述原样念出来、")
            append("禁止在跟话题无关时提屏幕。\n")
        } else if (hasImage) {
            append("\n【这一轮有屏幕截图】你可以看图说话，但只在跟话题相关时提，别硬描述画面。\n")
        }
        if (!userImageNote.isNullOrBlank()) {
            // 0.9.6：本地视觉看到的 → 必须说「你看到了」，否则它会老实回答「我看不到图片」
            append("\n【他刚给你发来的$userImageKind——你眼里的画面】\n")
            // 0.9.9：形状固定成 [图片内容描述：…]，用户 grep 日志时一眼能认出这一段
            append(IMAGE_NOTE_HEADER)
            append(userImageNote.take(SCREEN_NOTE_MAX_CHARS)).append("]\n")
            append("这是你自己看到的，不是别人转述。接话时挑画面里最让你在意的一两个点说，")
            append("或者顺口吐槽一句；禁止逐条罗列画面内容，禁止说「根据图片描述」「图片里显示」。\n")
        } else if (userImageAttached) {
            append("\n【他刚给你发来的$userImageKind】图就在你的输入里，直接看着图说话。\n")
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
        // 0.9.6：表情标签。UI 会把 [标签] 换成 emoji，模型只需要按标签写，
        // 否则它会直接吐 😭 混在中文里（有些模型会吐 \uD83D 这种裸代理对，反而更糟）。
        append(
            "- 想表达情绪可以在句尾夹一个标签，例如 ${EmojiTag.promptHint()}；" +
                "一句最多一个，不表达情绪就别加。\n"
        )
        // 0.8.21：这条要求和「这一轮到底看没看到」必须一致。
        //
        // 以前无论有没有本地视觉描述，最后都写死一句「这一轮没有截图就绝对不要描述画面」。
        // 云端模型是纯文本接单（hasImage=false），读到这句就会认定自己没看过屏幕 ——
        // 哪怕上面已经把【你这一轮看到的屏幕】整段塞进 system，它也会回答「我看不到」。
        // 这正是「设置页自检能看图、聊天却说看不到」的最后一块拼图。
        when {
            // 0.9.6：他发来的图必须算「看到了」——不然模型会一本正经回「我看不到图片」
            !userImageNote.isNullOrBlank() || userImageAttached -> append(
                "- 他这一轮给你发了$userImageKind，你**确实看到了**，可以直接聊里面的内容；" +
                    "被问到时不要说自己看不到图片。\n"
            )
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
        // 0.9.2：羁绊卡片上的 Emoji 靠这个回调刷新。
        // 以前这里不通知，心情变了界面还是旧的 —— 用户会以为「又是假的」。
        notifyChanged()
        Log.i(TAG, "心情：$m")
    }

    /** 加/减好感度，自动夹在 0~100 */
    fun bumpAffinity(delta: Int) {
        if (delta == 0) return
        val next = (profile.affinity + delta).coerceIn(0, 100)
        if (next == profile.affinity) return
        profile = profile.copy(affinity = next)
        store?.saveProfile(profile)
        // 同上：进度条要跟着动，不然「好感度上涨」只在数据库里发生
        notifyChanged()
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
            // 主动问候那一轮 user 是空的，不能发空 content 给接口（部分厂商直接 400）
            if (t.user.isNotBlank()) out.add(LlmClient.Message("user", t.user))
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
            // 同上：主动问候那轮没有用户发言，不能让本地模型看到「用户：」空行
            if (t.user.isBlank()) "你：${t.assistant.take(60)}"
            else "用户：${t.user.take(60)}\n你：${t.assistant.take(60)}"
        }
    }

    /**
     * 记一句她自己主动说的话（0.9.3 心跳链路用）。
     *
     * 为什么要塞进记忆：她 15 分钟前发过「午饭吃了没」，用户回一句「没呢」——
     * 模型如果看不到自己那句，只会回「没呢是啥意思？」，当场穿帮。
     *
     * 不抽长期记忆、不改好感度：这两件事的基础是「用户说了什么」，主动说话没有用户输入。
     */
    fun rememberProactiveLine(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        LocalDigitalBeing.recordProactive(t)
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
        // 0.9.2 批次一：读了这么久的好感度/心情终于有输入端了。
        // 每成功说一句话就按用户那句的情绪改一次状态（+1 / -1 / +0.2），并落库。
        // 放在 recordTurn 里是因为**只有真正回完一句**才该有情绪变化；
        // 模型没接上、被拦截、报错这些轮次不该扣她的心情。
        runCatching { EmotionEngine.onTurn(appCtx, u) }
            .onFailure { Log.w(TAG, "情绪更新失败：${it.message}") }
        // 0.9.2 批次二：给主动问候心跳一个「最后说话时间」的锚点
        appCtx?.let { CopilotConfig.markChatNow(it) }
        // 0.8.0：趁热抽一次长期记忆。异步、失败静默，绝不拖慢这一轮回复。
        extractMemoryAsync(u, assistant.trim())
    }

    /**
     * 主动问候（0.9.2 批次二）。
     *
     * 和 [respond] 的区别是：**这一轮没有用户在说话**。
     * 所以没有历史消息（`messages` 只有 system + 一句「想主动说点什么」的引子），
     * 也不能走双脑判定（没有可执行的动作意图）。只借角色的嘴说一句。
     *
     * 记忆来源三层，按可信度从高到低：
     *  1. `long_term_memory` 提炼事实（她「知道」的事）；
     *  2. FlexiMem 周记（上周印象最深的事）；
     *  3. 滚动摘要（更早聊过什么）。
     *
     * 为什么要带记忆：不带的话她只会说「在吗」「最近怎么样」，
     * 那就是客服推送，不是「她想你了」。带上记忆才可能冒出「你那个报销报完了没」。
     */
    suspend fun proactiveGreeting(context: Context, topic: String? = null): String? {
        init(context)
        val target = pickReplyProvider(context, visionWanted = false) ?: run {
            Log.i(TAG, "主动问候：没有可用 provider/Key")
            return null
        }
        val client = LlmClientFactory.create(target.provider, context)
        val system = buildGreetingPrompt(PresenceSense.now(context), topic)
        val messages = listOf(
            LlmClient.Message("system", system),
            LlmClient.Message(
                "user",
                if (topic.isNullOrBlank()) {
                    "（他有一阵子没说话了。你想起他，想主动说一句。）"
                } else {
                    "（他有一阵子没说话了。你一直记挂着他提过的「$topic」，想问问这件事。）"
                }
            )
        )
        Log.d(
            "Heartbeat",
            "生成主动问候：provider=${target.provider} 话题=${topic ?: "无"} 提示词 ${system.length} 字"
        )
        val raw = try {
            withTimeoutOrNull(REPLY_TIMEOUT_MS) { client.chat(messages) }
        } catch (e: Throwable) {
            // 和 askRoleLlm 一样：异常会被上层吞掉，状态码必须在这里留底
            ApiErrorState.record(target.provider, e)
            throw e
        }
        if (raw == null) {
            ApiErrorState.record(target.provider, java.net.SocketTimeoutException("${REPLY_TIMEOUT_MS}ms"))
        }
        return sanitizeReply(raw).ifBlank { null }
    }

    /**
     * 主动问候的提示词。
     *
     * 单独一个函数而不是复用 [buildReplyPrompt]：那边一大半是「屏幕」「工具结果」「这一轮看不看图」，
     * 主动问候时这些全是空，塞进去只会让模型困惑「他到底问了我什么」。
     *
     * @param topic 0.9.4：从 [com.nexus.agent.data.db.TopicSeed] 挑出来的一件具体的事
     *              （「明天体检」「最近头疼」）。有它就从这件事开口 —— 这是「她为什么现在说话」
     *              的全部答案；没有才退回泛泛问候。
     */
    internal fun buildGreetingPrompt(now: PresenceSense.Now, topic: String? = null): String =
        buildString {
            append(styleBlock(profile.name))
            append("\n").append(EmotionEngine.toneBlock(profile.affinity, profile.mood)).append("\n")
            append("\n【现在】\n").append(PresenceSense.greetingTimeHint(now)).append("\n")

            val facts = memories
            if (facts.isNotEmpty()) {
                append("\n【你记得关于他的事】\n")
                facts.take(6).forEach { append("- ").append(it).append("\n") }
            }
            val weekly = runCatching { com.nexus.agent.persona.FlexiMem.weeklyDigest() }
                .getOrDefault(emptyList())
            if (weekly.isNotEmpty()) {
                append("\n【这一周你印象比较深的事】\n")
                weekly.take(4).forEach { append("- ").append(it).append("\n") }
            }
            val rolling = LocalDigitalBeing.summaryText()
            if (rolling.isNotBlank()) {
                append("\n【更早之前聊过的】\n").append(rolling.take(200)).append("\n")
            }

            // 有种子 = 有理由。这一段必须放在「怎么说话」的规则之前，
            // 否则模型会先看到「不许问空话」然后自己编一个抓手，把种子丢在一边。
            if (!topic.isNullOrBlank()) {
                append(
                    """
                    
                    【你一直记挂着的一件事】
                    他之前提过：「$topic」
                    这一句就顺着这件事说 —— 问结果（体检怎么样）、问现状（头还疼不疼）、
                    或者给一句实在的关心（明天要早起吧，早点睡）。
                    
                    """.trimIndent()
                )
            }

            append(
                """
                
                【这一轮不是他在问你，是你主动找他】
                - 像朋友忽然想起你，随手发一条，一二十个字。
                - 禁止「在吗」「你好」「有什么可以帮您」，禁止复述聊天记录。
                - 可以说你正在干嘛、想到他的什么、或者问一件具体的事（吃饭没、那事办完没、睡了没）。
                - 不要问「你最近怎么样」这种空话，要有一个抓手。
                - 只输出这一句话本身，不要引号、不要前缀、不要解释你为什么发。
                """.trimIndent()
            )
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

    fun addUserItem(text: String, imagePath: String? = null) {
        appendItem(ChatItem.User(text, System.currentTimeMillis(), imagePath))
    }

    fun addAssistantItem(text: String) {
        if (text.isBlank()) return
        appendItem(ChatItem.Assistant(text))
    }

    fun addActionItem(label: String, detail: String = "", ok: Boolean = true) {
        appendItem(ChatItem.Action(label, detail, ok))
    }

    /**
     * 插一条系统红字消息（0.9.9）。
     *
     * 用在「用户看得见、否则就是装死」的岔子上：图没看成、Key 错了、视觉超时。
     * 它会立刻进聊天流，而不是等模型回复回来才跟在尾巴上。
     */
    fun addSystemItem(text: String) {
        if (text.isBlank()) return
        appendItem(ChatItem.SystemNotice(text))
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
        // 图片路径塞在 detail 里：不为一张图改表结构，而 detail 本来就是「附加内容」的槽
        is ChatItem.User -> ChatRecord(
            ROLE_USER, text, detail = imagePath.orEmpty(), createdAt = at
        )
        is ChatItem.Assistant -> ChatRecord(ROLE_ASSISTANT, text, createdAt = at)
        is ChatItem.Action -> ChatRecord(ROLE_ACTION, label, detail, ok, at)
        is ChatItem.SystemNotice -> ChatRecord(ROLE_SYSTEM, text, createdAt = at)
    }

    private fun ChatRecord.toChatItem(): ChatItem = when (role) {
        ROLE_USER -> ChatItem.User(
            content,
            createdAt,
            imagePath = detail.takeIf { it.isNotBlank() }
        )
        ROLE_ASSISTANT -> ChatItem.Assistant(content, createdAt)
        ROLE_SYSTEM -> ChatItem.SystemNotice(content, createdAt)
        else -> ChatItem.Action(content, detail, ok, createdAt)
    }
}
