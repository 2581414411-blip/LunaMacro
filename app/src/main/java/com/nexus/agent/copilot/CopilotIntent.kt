package com.nexus.agent.copilot

import android.util.Log
import org.json.JSONObject

/**
 * 副驾驶一次回复的语义意图。
 *
 * 大模型被要求输出 `{"type":"chat|action|task", ...}`，但**任何模型都可能不听话**：
 * 本地 2B 会漏 JSON、云端偶尔会包 ```json 代码块、老模型会直接说人话。
 * 因此解析分三级：严格 JSON → 正则识别指令 → 整段当聊天。
 */
sealed class CopilotIntent {

    /** 要对用户说的话（灵动岛弹幕 / 主控台气泡都显示它） */
    abstract val reply: String

    /** 纯聊天、吐槽、回答问题 */
    data class Chat(override val reply: String) : CopilotIntent()

    /** 单步工具调用 */
    data class Action(
        val tool: String,
        val params: Map<String, Any?>,
        override val reply: String
    ) : CopilotIntent()

    /** 多步任务，交给 AgentRunner（Planner + Executor） */
    data class Task(
        val goal: String,
        override val reply: String
    ) : CopilotIntent()
}

/**
 * 模型输出 → [CopilotIntent]。
 */
object CopilotIntentParser {

    private const val TAG = "CopilotIntent"

    /** 弹幕/气泡最大字数（超长截断，避免岛上显示不下） */
    private const val MAX_REPLY_CHARS = 80

    /**
     * 解析模型输出。
     *
     * @param raw         模型原文（可能是 JSON、带代码块的 JSON、或纯文本）
     * @param userText    用户原话（JSON 解析失败时用来做指令兜底）
     * @param knownTools  已注册工具名集合（用来校验 / 纠正模型瞎编的工具名）
     */
    fun parse(raw: String?, userText: String, knownTools: Set<String>): CopilotIntent {
        val text = raw?.trim().orEmpty()

        // ---- 1. 严格 JSON ----
        val json = extractJsonObject(text)
        if (json != null) {
            val fromJson = fromJson(json, knownTools)
            if (fromJson != null) return fromJson
        }

        // ---- 2. 正则兜底：用户话里有明确动作意图 ----
        regexAction(userText, knownTools)?.let {
            Log.i(TAG, "JSON 缺失，按指令正则兜底：${it.tool} ${it.params}")
            return it
        }

        // ---- 3. 整段当聊天 ----
        val cleaned = cleanChat(text)
        return CopilotIntent.Chat(
            cleaned.ifBlank { "我没太看懂，再说一遍？" }
        )
    }

    /**
     * 从 JSON 对象构造意图；结构不认识返回 null（继续走兜底）。
     */
    private fun fromJson(obj: JSONObject, knownTools: Set<String>): CopilotIntent? {
        val type = obj.optString("type").lowercase()
        val reply = obj.optString("reply").ifBlank { obj.optString("say") }.trim()

        val tool = obj.optString("tool").ifBlank { obj.optString("action") }.trim()
        val goal = obj.optString("goal").ifBlank { obj.optString("task") }.trim()

        // type 缺失时按字段猜
        val resolvedType = when {
            type.isNotBlank() -> type
            tool.isNotBlank() && tool.lowercase() in knownTools -> "action"
            goal.isNotBlank() -> "task"
            else -> "chat"
        }

        return when (resolvedType) {
            "action", "tool", "act" -> {
                if (tool.isBlank()) return null
                val normalized = normalizeTool(tool, knownTools)
                val params = readParams(obj, normalized)
                if (normalized !in knownTools) {
                    Log.w(TAG, "模型给了未知工具「$normalized」，交给引擎报不支持")
                }
                CopilotIntent.Action(
                    tool = normalized,
                    params = params,
                    reply = capReply(reply.ifBlank { "好的，我来办" })
                )
            }

            "task", "agent", "goal", "plan" -> {
                val g = goal.ifBlank { reply }
                if (g.isBlank()) return null
                CopilotIntent.Task(g, capReply(reply.ifBlank { "好，我去弄" }))
            }

            "chat", "reply", "text", "say" -> {
                val r = cleanChat(reply.ifBlank { obj.optString("content") })
                if (r.isBlank()) return null
                CopilotIntent.Chat(capReply(r))
            }

            else -> null
        }
    }

    /** 读参数表：兼容 params / arguments / args 三种写法 */
    private fun readParams(obj: JSONObject, tool: String): Map<String, Any?> {
        val container = obj.optJSONObject("params")
            ?: obj.optJSONObject("arguments")
            ?: obj.optJSONObject("args")

        val map = mutableMapOf<String, Any?>()
        if (container != null) {
            container.keys().forEach { k ->
                map[k] = container.opt(k)
            }
        }

        // open_app 常见变体：把应用名写在顶层
        if (tool == "open_app" && map["appName"] == null) {
            val alt = obj.optString("app")
                .ifBlank { obj.optString("appName") }
                .ifBlank { obj.optString("target") }
            if (alt.isNotBlank()) map["appName"] = alt
        }
        return map
    }

    /**
     * 工具名纠偏：模型偶尔会输出中文名或别名（"打开应用" / "launch_app"）。
     */
    private fun normalizeTool(raw: String, knownTools: Set<String>): String {
        val t = raw.trim().lowercase()
        if (t in knownTools) return t

        val alias = when (t) {
            "打开应用", "启动应用", "launch_app", "start_app", "openapp", "open" -> "open_app"
            "点击文字", "clicktext", "tap_text", "click" -> "click_text"
            "点击id", "clickid" -> "click_id"
            "返回", "back", "pressback" -> "press_back"
            "回桌面", "桌面", "home", "presshome" -> "press_home"
            "滑动", "scroll", "swipe" -> "scroll"
            "等待", "wait", "sleep" -> "wait"
            "长按", "longpress" -> "long_press"
            "双击", "doubletap" -> "double_tap"
            "拖拽", "drag" -> "drag"
            "宏", "回放宏", "playmacro" -> "play_macro"
            else -> return t
        }
        return if (alias in knownTools) alias else t
    }

    /**
     * 指令正则兜底。
     *
     * 只处理最常见的几个确定性命令，命中即返回 Action；
     * 命中不了返回 null（交给聊天路径，绝不硬猜）。
     */
    private fun regexAction(userText: String, knownTools: Set<String>): CopilotIntent.Action? {
        val t = userText.trim()
        if (t.isBlank()) return null

        // 打开 / 启动 / 运行 某应用
        if ("open_app" in knownTools) {
            val m = Regex("(?:帮我|给我|请)?\\s*(?:打开|启动|运行|进入)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,12})")
                .find(t)
            if (m != null) {
                val app = m.groupValues[1]
                    .trim()
                    .removeSuffix("吧").removeSuffix("啊").removeSuffix("一下")
                if (app.isNotBlank() && app !in listOf("一下", "吧", "啊")) {
                    return CopilotIntent.Action(
                        tool = "open_app",
                        params = mapOf("appName" to app),
                        reply = "好的，正在为你打开$app"
                    )
                }
            }
        }

        if ("press_back" in knownTools && Regex("^(返回|后退|退回去|返回上一页)").containsMatchIn(t)) {
            return CopilotIntent.Action("press_back", emptyMap(), "好，返回")
        }
        if ("press_home" in knownTools && Regex("(回桌面|回到桌面|回主屏|回到主页)").containsMatchIn(t)) {
            return CopilotIntent.Action("press_home", emptyMap(), "回桌面")
        }
        if ("scroll" in knownTools) {
            if (Regex("(下滑|往下滑|翻下去|看下面|滑到下面)").containsMatchIn(t)) {
                return CopilotIntent.Action("scroll", mapOf("direction" to "down"), "往下翻")
            }
            if (Regex("(上滑|往上滑|翻上去|看上面)").containsMatchIn(t)) {
                return CopilotIntent.Action("scroll", mapOf("direction" to "up"), "往上翻")
            }
        }
        return null
    }

    /**
     * 从任意文本里薅出第一个 JSON 对象（容忍 ```json 围栏与前后废话）。
     */
    fun extractJsonObject(text: String): JSONObject? {
        if (text.isBlank()) return null
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        val slice = text.substring(start, end + 1)
        return try {
            JSONObject(slice)
        } catch (e: Throwable) {
            Log.d(TAG, "JSON 解析失败：${e.message}")
            null
        }
    }

    /** 聊天文本清洗：去围栏、去前缀、压缩空白 */
    private fun cleanChat(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        listOf("回答：", "回答:", "回复：", "回复:", "AI：", "AI:").forEach { s = s.removePrefix(it) }
        s = s.replace("\r", "").replace("\n", " ").trim()
        s = s.trim('"', '\'', '“', '”', '「', '」', ' ')
        // 模型把 JSON 写坏了、只剩半截的情况：别把大括号丢给用户看
        if (s.startsWith("{") && s.endsWith("}") && s.length > 4) {
            s = s.removeSurrounding("{", "}")
        }
        return s
    }

    private fun capReply(s: String): String =
        if (s.length > MAX_REPLY_CHARS) s.take(MAX_REPLY_CHARS) else s
}
