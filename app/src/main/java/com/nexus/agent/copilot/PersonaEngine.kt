package com.nexus.agent.copilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.data.db.ChatRecord
import com.nexus.agent.data.db.PersonaStore
import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.data.db.RoomPersonaStore
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
        migrateLegacyIfNeeded(s)
        s.load(MAX_ITEMS, LOAD_TURNS) { snapshot, itemRows, dialogueRows ->
            restore(snapshot, itemRows, dialogueRows)
        }
    }

    /** 供测试注入假存档（生产环境走 [init] 里的 Room 实现） */
    internal fun useStoreForTest(fake: PersonaStore?) {
        store = fake
    }

    /** 仅供单测：把内存工作集与外部依赖恢复到初始态 */
    internal fun resetForTest() {
        synchronized(items) { items.clear() }
        synchronized(turns) { turns.clear() }
        profile = ProfileSnapshot("宏月", "", "", 50, "平静")
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
     */
    private fun styleBlock(name: String): String = buildString {
        append("【你的人设】\n")
        append("你叫$name，是用户手机里的私人助理，关系像老朋友。\n")
        append("说话自然、直接、有主见，偶尔可以吐槽，但不油腻、不谄媚。\n")
        append("- 用口语，别用书面语；能一句话说完就别拆成三段。\n")
        append("- 不要用 Markdown 列表、加粗、标题，这些在聊天气泡里看着很怪。\n")
        append("- 不要说「作为 AI」「我是人工智能」「有什么可以帮您」这类客服话。\n")
        append("- 不确定就直说不知道，别编。\n")
        append("- 记住上下文：用户说「它」「那个」「再来一次」时，指的是前面聊过的事。\n")
        if (profile.soul.isNotBlank()) {
            append("- 你的底色：${profile.soul}\n")
        }
        if (profile.identity.isNotBlank()) {
            append("- 你和用户的关系：${profile.identity}\n")
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
            val memo = historyText()
            if (memo.isNotBlank()) {
                append("\n【前几轮聊过什么（供你衔接，不用复述）】\n")
                append(memo)
            }
        }
    }

    /** 当前档案快照（好感度 / 心情 / 名字） */
    fun profileSnapshot(): ProfileSnapshot = profile

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
        store?.clearChat()
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
