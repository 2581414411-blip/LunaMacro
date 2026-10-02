package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject

/**
 * Agent 主循环核心 —— 把「看、想、做」串成闭环。
 *
 * 单步流程：
 * 1. 看：读屏（UI 树可交互元素）+ 前台包名；必要时截图
 * 2. 想：把观察结果喂给 LLM，要求返回一个「工具调用」JSON
 * 3. 做：解析工具名 + 参数，交 ToolRegistry 执行
 * 4. 重复，直到 LLM 返回 finish 或达到最大步数
 *
 * Observe 策略（observeMode）：
 * - ui_only（默认）：只用 UI 树，不截图，payload 小、响应快
 * - ui_and_screenshot：当 UI 树可交互元素 < 3 时才额外截图（Flutter/WebView 兜底）
 *
 * 通过 [runTask] 返回 [Flow]，逐步骤吐进度，方便 UI 实时展示。
 */
object AgentController {

    private const val TAG = "AgentController"
    private const val OBSERVE_TAG = "NexusObserve"

    /** 默认最大步数，防止死循环 */
    private const val MAX_STEPS = 15

    /** 多轮对话里保留的最近消息条数（system+目标之外的往返） */
    private const val MAX_HISTORY_MESSAGES = 8

    /** UI 树可交互元素少于该数量时才额外截图 */
    private const val MIN_ELEMENTS_FOR_UI_ONLY = 3

    /** 单步观察 + 工具调用记录 */
    data class Step(
        val index: Int,
        val packageName: String?,
        val thought: String,
        val tool: String?,
        val args: Map<String, Any?>?,
        val result: String?,
        val done: Boolean
    )

    /**
     * 运行一个任务，逐步骤产出进度。
     *
     * @param goal 用户目标（自然语言指令）
     * @param provider LLM provider（传给 LlmClientFactory）
     * @param maxSteps 最大步数
     * @param onThinking 思考过程回调（在 LLM 决策前/后触发，供浮窗等实时展示）
     * @return 逐步进度流；最后一步 done=true 表示结束
     */
    fun runTask(
        goal: String,
        provider: String = "deepseek",
        maxSteps: Int = MAX_STEPS,
        onThinking: ((String) -> Unit)? = null
    ): Flow<Step> = flow {
        val client = LlmClientFactory.create(provider, ShizukuManager.appContext())

        // 对话历史：system + 用户目标（这两条永远保留）
        val messages = mutableListOf<LlmClient.Message>(
            LlmClient.Message("system", PromptBuilder.build()),
            LlmClient.Message("user", "任务：$goal")
        )

        for (i in 1..maxSteps) {
            Log.i(TAG, "=== step $i ===")

            // 1. 看：读屏 + 包名 + 按需截图
            val pkg = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
            val observeStart = System.currentTimeMillis()
            val observe = observe()
            val observeElapsed = System.currentTimeMillis() - observeStart

            Log.i(
                OBSERVE_TAG,
                "mode=${observe.mode}, elements=${observe.elementCount}, " +
                    "payload_bytes=${observe.payloadBytes}, elapsed_ms=$observeElapsed"
            )

            // 2. 想：构造本轮观察消息
            val observation = buildString {
                append("第 $i 步观察：\n")
                append("前台包名：${pkg ?: "未知"}\n")
                append("可交互元素：\n${observe.payload}\n")
                append("请给出下一步工具调用 JSON。")
            }

            val userContent: Any = if (observe.screenshot != null) {
                listOf(
                    LlmClient.ContentPart("text", text = observation),
                    LlmClient.ContentPart(
                        "image_url",
                        imageUrl = "data:image/jpeg;base64,${observe.screenshot}"
                    )
                )
            } else {
                observation
            }

            messages.add(LlmClient.Message("user", userContent))
            trimHistory(messages)

            onThinking?.invoke("第 $i 步：观察屏幕中…")

            // 3. 想：调用 LLM
            val raw = try {
                client.chat(messages)
            } catch (e: Throwable) {
                Log.e(TAG, "LLM chat failed", e)
                emit(
                    Step(i, pkg, "LLM 调用失败：${e.message}", null, null,
                        e.message ?: "unknown", true)
                )
                return@flow
            }

            val call = parseToolCall(raw)
            if (call == null) {
                emit(Step(i, pkg, "无法解析 LLM 输出：$raw", null, null, "工具解析失败", true))
                return@flow
            }

            val (toolName, args) = call
            onThinking?.invoke("第 $i 步：${raw.substringAfter("\"thought\":").take(40)} → $toolName")

            // 4. 做：执行工具
            val done = toolName == "finish"
            val result: String? = if (done) {
                (args["thought"] as? String) ?: "任务完成"
            } else {
                executeTool(toolName, args)
            }
            onThinking?.invoke("第 $i 步：${result ?: ""}")

            emit(Step(i, pkg, raw, toolName, args, result, done))

            // 记录 assistant 的回复，保持多轮上下文
            messages.add(LlmClient.Message("assistant", raw))

            if (done) return@flow

            delay(1200)
        }

        emit(Step(maxSteps, null, "达到最大步数 $maxSteps，任务中止", null, null, null, true))
    }.flowOn(Dispatchers.IO)

    /** Observe 结果 */
    private data class ObserveResult(
        val mode: String,
        val payload: String,
        val payloadBytes: Int,
        val elementCount: Int,
        val screenshot: String?
    )

    /**
     * 观察屏幕：读 UI 树可交互元素，元素过少时补截图。
     */
    private fun observe(): ObserveResult {
        val uiJson = captureUiTree()
        val elementCount = countElements(uiJson)

        return if (uiJson != null && elementCount >= MIN_ELEMENTS_FOR_UI_ONLY) {
            ObserveResult(
                mode = "ui_only",
                payload = uiJson,
                payloadBytes = uiJson.toByteArray().size,
                elementCount = elementCount,
                screenshot = null
            )
        } else {
            // UI 树元素不足（可能是 Flutter/WebView/游戏），补截图兜底
            val shot = ScreenCapture.captureBase64()
            val payload = uiJson ?: "[]"
            ObserveResult(
                mode = "ui_and_screenshot",
                payload = payload,
                payloadBytes = payload.toByteArray().size,
                elementCount = elementCount,
                screenshot = shot
            )
        }
    }

    /**
     * 统计 UI 树 JSON 里的元素个数（粗略：数 "center" 出现次数）。
     */
    private fun countElements(uiJson: String?): Int {
        if (uiJson == null) return 0
        return Regex("\"center\"").findAll(uiJson).count()
    }

    /**
     * 解析 LLM 返回的工具调用 JSON。
     *
     * @return (工具名, 参数) 对；解析失败返回 null
     */
    private fun parseToolCall(raw: String): Pair<String, Map<String, Any?>>? {
        return try {
            val jsonStr = extractJson(raw) ?: return null
            val obj = JSONObject(jsonStr)
            val tool = obj.optString("tool").lowercase().trim()
            if (tool.isEmpty()) return null
            val argsObj = obj.optJSONObject("args") ?: JSONObject()
            val args = mutableMapOf<String, Any?>()
            argsObj.keys().forEach { k -> args[k] = argsObj.opt(k) }
            tool to args
        } catch (e: Throwable) {
            Log.e(TAG, "parseToolCall failed", e)
            null
        }
    }

    /**
     * 提取原始文本中的第一个 JSON 块。
     */
    private fun extractJson(raw: String): String? {
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.getOrNull(1)
        if (fenced != null) return fenced
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    /**
     * 执行工具并返回结果字符串。
     */
    private fun executeTool(toolName: String, args: Map<String, Any?>): String {
        val tool = ToolRegistry.get(toolName)
        return if (tool == null) {
            "未知工具：$toolName"
        } else {
            try {
                tool.execute(args)
            } catch (e: Throwable) {
                Log.e(TAG, "tool $toolName failed", e)
                "工具 $toolName 执行失败：${e.message}"
            }
        }
    }

    /**
     * 裁剪对话历史：始终保留最前面的 system 与任务描述两条，
     * 之后只保留最近 [MAX_HISTORY_MESSAGES] 条。
     */
    private fun trimHistory(messages: MutableList<LlmClient.Message>) {
        if (messages.size <= MAX_HISTORY_MESSAGES + 2) return
        val head = messages.take(2)
        val tail = messages.takeLast(MAX_HISTORY_MESSAGES)
        messages.clear()
        messages.addAll(head)
        messages.addAll(tail)
    }

    /**
     * 通过无障碍服务读取当前 UI 树并解析为精简 JSON。
     *
     * 注意：UiTreeParser.parse 内部已 recycle root，这里不再重复 recycle。
     *
     * @return UI 树 JSON；读屏不可用时返回 null
     */
    private fun captureUiTree(): String? {
        val service = NexusAccessibilityService.getInstance() ?: return null
        val root = service.getRootNode() ?: return null
        return try {
            UiTreeParser.parse(root)
        } catch (e: Throwable) {
            Log.e(TAG, "captureUiTree failed", e)
            null
        }
    }
}
