package com.nexus.agent.copilot

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 人格引擎 —— 负责「它是什么样的人」这件事。
 *
 * 与 [CopilotEngine] 的分工：
 *  - [CopilotEngine] 管**怎么做事**（截图、调模型、执行工具）
 *  - [PersonaEngine] 管**怎么说话**（人设提示词、对话记忆、聊天记录）
 *
 * 0.7.11 之前人设是硬编码在 [CopilotVision] 里的两段常量，没法改也没法记上下文；
 * 这里把「人设」和「记忆」抽出来，[CopilotEngine] 每轮来取一次。
 *
 * 记忆长度固定 [MAX_TURNS] 轮（一问一答算一轮），超出后丢最旧的。
 * 不做全量历史的原因：本地 2B 模型上下文窗口很小，塞多了反而答得更差，
 * 而且每轮都带全量历史会让云端 token 成本线性上涨。
 */
object PersonaEngine {

    private const val TAG = "PersonaEngine"

    /** 保留的对话轮数（一问一答为 1 轮） */
    const val MAX_TURNS = 6

    /** UI 聊天记录条数上限（含动作气泡），超出丢最旧 */
    private const val MAX_ITEMS = 120

    private const val PREFS = "luna_persona"
    private const val KEY_ITEMS = "chat_items"

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
     * 记录可能来自 CopilotEngine 的 IO 协程，而 Compose 的状态必须回主线程改，
     * 所以这里统一切一次线程，调用方不用关心自己在哪个线程。
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
    }

    /**
     * 组装本轮的系统提示词。
     *
     * 复用 [CopilotVision.buildFullSystemPrompt]（身份 + 工具 Schema + JSON 契约），
     * 再叠加人设与记忆摘要，保证「能干活」和「像个人」同时成立。
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
            val memo = historyText()
            if (memo.isNotBlank()) {
                append("\n【前几轮聊过什么（供你衔接，不用复述）】\n")
                append(memo)
            }
        }
    }

    // ---------------------------------------------------------------- 记忆

    /** 最近一轮的助手回复（用来判断"再来一次"这类指代） */
    fun lastAssistantReply(): String = turns.lastOrNull()?.assistant.orEmpty()

    /**
     * 转成云端接口要的 messages（user/assistant 交替）。
     *
     * 只带文本，不带图——历史截图对当前问题没有价值，还会让请求体积暴涨。
     */
    fun historyMessages(): List<LlmClient.Message> {
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
     * 每条截断到 60 字：本地模型上下文短，长历史会把它挤爆。
     */
    fun historyText(): String {
        if (turns.isEmpty()) return ""
        return turns.joinToString("\n") { t ->
            "用户：${t.user.take(60)}\n你：${t.assistant.take(60)}"
        }
    }

    /** 记一轮对话（[assistant] 为空则只记用户，比如工具执行失败时） */
    fun recordTurn(user: String, assistant: String) {
        val u = user.trim()
        if (u.isEmpty()) return
        if (assistant.isBlank()) {
            // 没回复就不算完整一轮，避免历史里出现「你：」后面啥都没有
            return
        }
        turns.addLast(Turn(u, assistant.trim()))
        while (turns.size > MAX_TURNS) turns.removeFirst()
    }

    fun turnCount(): Int = turns.size

    /** 清空模型记忆（不影响 UI 聊天记录） */
    fun clearMemory() {
        turns.clear()
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

    private fun appendItem(item: ChatItem) {
        synchronized(items) {
            items.add(item)
            while (items.size > MAX_ITEMS) items.removeAt(0)
        }
        persistAll()
        notifyChanged()
    }

    /** 清空聊天流（同时清记忆，否则会出现"失忆但界面还有记录"的矛盾状态） */
    fun clearAll(context: Context) {
        synchronized(items) { items.clear() }
        turns.clear()
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_ITEMS).apply()
        notifyChanged()
        Log.i(TAG, "聊天记录与记忆已清空")
    }

    /** 启动时把上次的聊天记录读回来，避免切 Tab / 杀进程后一片空白 */
    fun load(context: Context) {
        if (items.isNotEmpty()) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "") ?: return
        if (raw.isBlank()) return
        try {
            val arr = JSONArray(raw)
            synchronized(items) {
                items.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val at = o.optLong("at", System.currentTimeMillis())
                    items.add(
                        when (o.optString("type")) {
                            "user" -> ChatItem.User(o.optString("text"), at)
                            "assistant" -> ChatItem.Assistant(o.optString("text"), at)
                            else -> ChatItem.Action(
                                label = o.optString("label"),
                                detail = o.optString("detail"),
                                ok = o.optBoolean("ok", true),
                                at = at
                            )
                        }
                    )
                }
            }
            Log.i(TAG, "已恢复聊天记录 ${items.size} 条")
        } catch (e: Throwable) {
            // 存档格式坏了就当没有，别让首页因为一段 JSON 打不开
            Log.w(TAG, "聊天记录解析失败，已忽略：${e.message}")
        }
    }

    private fun persistAll() {
        val ctx = appCtx ?: return
        try {
            val arr = JSONArray()
            synchronized(items) {
                for (it in items) {
                    val o = JSONObject()
                    when (it) {
                        is ChatItem.User -> {
                            o.put("type", "user")
                            o.put("text", it.text)
                        }
                        is ChatItem.Assistant -> {
                            o.put("type", "assistant")
                            o.put("text", it.text)
                        }
                        is ChatItem.Action -> {
                            o.put("type", "action")
                            o.put("label", it.label)
                            o.put("detail", it.detail)
                            o.put("ok", it.ok)
                        }
                    }
                    o.put("at", it.at)
                    arr.put(o)
                }
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ITEMS, arr.toString()).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "聊天记录保存失败：${e.message}")
        }
    }

    /** 供 persistAll 用的应用上下文（由 [init] 注入，避免每个写入口都要传 Context） */
    @Volatile
    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        load(context)
    }
}
