package com.nexus.agent.agent

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.game.LocalScreenRecognizer
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 本地动作决策器（子目标内的「底层执行脑」）。
 *
 * 分层执行模型：
 * - **高层（云端 Planner）**：只产出子目标（要什么）
 * - **底层（这里）**：拿当前截图 + 子目标，决定下一步点哪儿（怎么做）
 *
 * 用本地 Qwen2-VL-2B 推理，不花 token、不依赖网络。模型输出格式不可强求，
 * 所以这里做两级解析：
 * 1. 严格格式 `ACTION: click_text | text=全部领取`
 * 2. 兜底从自然语言里抠意图（「点击全部领取」→ click_text）
 *
 * 解析不出来就返回 null，由调用方降级到云端视觉/文本决策 —— 绝不因为本地模型
 * 不会按格式说话就让任务停下来。
 */
object LocalActionPlanner {

    private const val TAG = "LocalActionPlanner"

    /** 单次本地推理超时（2B 模型在手机上大概几秒，给足余量） */
    private const val INFER_TIMEOUT_MS = 20_000L

    /** 本地决策只允许这些「不需要精确坐标」的工具 */
    private val ALLOWED_TOOLS = setOf(
        "click_text", "input_text", "press_back", "press_home", "wait", "scroll", "click_id"
    )

    data class LocalAction(
        val tool: String,
        val args: Map<String, Any?>,
        /** 模型原始输出（调试/审计用） */
        val raw: String
    )

    /**
     * 提议下一步动作。
     *
     * @return null 表示本地模型不可用 / 超时 / 输出无法解析（调用方应降级）
     */
    suspend fun propose(
        goal: SubGoal,
        stateDesc: String,
        bitmap: Bitmap
    ): LocalAction? {
        val prompt = buildString {
            append("你是手机操作助手。目标：")
            append(goal.description)
            if (goal.successCriteria.isNotBlank()) append("（完成标准：${goal.successCriteria}）")
            append("\n")
            if (stateDesc.isNotBlank()) append("$stateDesc\n")
            append("根据这张截图，给出下一步【一个】操作，只输出一行，从下面五种格式里选一个：\n")
            append("ACTION: click_text | text=要点的按钮文字\n")
            append("ACTION: input_text | text=要输入的文字（含空格时用英文双引号包住）\n")
            append("ACTION: press_back\n")
            append("ACTION: scroll | direction=down\n")
            append("ACTION: wait | seconds=2\n")
            append("不要解释，不要输出其它内容。如果目标已经达成，输出：ACTION: done")
        }

        val raw = withTimeoutOrNull(INFER_TIMEOUT_MS) {
            runCatching { LocalScreenRecognizer.infer(bitmap, prompt) }.getOrNull()
        }
        if (raw.isNullOrBlank()) {
            Log.d(TAG, "本地模型无输出（未就绪或超时），降级云端")
            return null
        }

        val action = parse(raw)
        if (action == null) {
            Log.i(TAG, "本地输出无法解析，降级云端：${raw.take(80).replace("\n", " ")}")
        } else {
            Log.i(TAG, "本地决策：${action.tool} ${action.args}（raw=${raw.take(60).replace("\n", " ")}）")
        }
        return action
    }

    /**
     * 解析模型输出。
     *
     * 先按严格格式找 `ACTION: xxx | k=v`，找不到再从自然语言里猜意图。
     */
    fun parse(raw: String): LocalAction? {
        val text = raw.trim()

        // 1) 严格格式
        val strict = Regex(
            "ACTION\\s*[:：]\\s*([a-zA-Z_]+)\\s*(?:\\|\\s*([^\\n]*))?",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (strict != null) {
            val tool = strict.groupValues[1].lowercase()
            if (tool == "done" || tool == "finish") return null  // 达成由执行器自己判断
            val argText = strict.groupValues.getOrNull(2).orEmpty()
            val args = parseArgs(argText)
            if (tool in ALLOWED_TOOLS) return LocalAction(tool, args, text)
        }

        // 2) 自然语言兜底
        if (Regex("(返回|退回|返回键)").containsMatchIn(text)) {
            return LocalAction("press_back", emptyMap(), text)
        }
        if (Regex("(等待|加载中|稍等|等一会)").containsMatchIn(text)) {
            return LocalAction("wait", mapOf("seconds" to 2), text)
        }
        if (Regex("(下滑|向下滑|往下划|滚动)").containsMatchIn(text)) {
            return LocalAction("scroll", mapOf("direction" to "down"), text)
        }
        val click = Regex("[点击按]\\s*[「\\\"']?([^」\\\"'，。,\\n]{1,12})[」\\\"']?").find(text)
        if (click != null) {
            val target = click.groupValues[1].trim()
            if (target.isNotEmpty() && target.length <= 12) {
                return LocalAction("click_text", mapOf("text" to target), text)
            }
        }
        return null
    }

    private fun parseArgs(argText: String): Map<String, Any?> {
        if (argText.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Any?>()
        // 同时支持 "text=全部领取" 与 "text=\"全部领取\""
        Regex("([a-zA-Z_]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s,;|]+)").findAll(argText).forEach { m ->
            val key = m.groupValues[1]
            val value = m.groupValues[2].trim().trim('"', '\'')
            if (value.isEmpty()) return@forEach
            out[key] = when (key.lowercase()) {
                "seconds", "duration", "count" -> value.toIntOrNull() ?: 2
                else -> value
            }
        }
        return out
    }
}
