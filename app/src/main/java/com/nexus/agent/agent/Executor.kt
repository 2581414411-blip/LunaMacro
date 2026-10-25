package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser
import com.nexus.agent.agent.state.TaskStateMachine
import com.nexus.agent.audit.AuditEntry
import com.nexus.agent.audit.AuditLogger
import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.agent.tools.ToolSupport
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Executor —— 单步执行层。
 *
 * 负责把一个"步骤"（如"点击 WLAN"）通过内部循环执行完成：
 *
 *   Observe → Think → Act → Verify
 *
 * 单步内最多 [MAX_ACTIONS_PER_STEP] 个动作。每轮都用最新的 Observe 结果，
 * 不累积历史截图（只保留最近一条截图，用完即弃）。
 *
 * 本地历史只保留操作序列的文字形式（压缩），用于：
 * 1. 循环检测 —— 连续 3 次 (screenHash, toolCall) 相同 → NEED_REPLAN
 * 2. 上下文压缩 —— 只保留最近 5 条，更早的合并为"前 N 步已完成"
 *
 * 输出 [StepResult]：SUCCESS / FAILED / NEED_REPLAN + 摘要。
 */
object Executor {

    private const val TAG = "Executor"

    /** 当前步骤描述（审计日志用） */
    @Volatile
    private var currentStepDesc = ""

    /** 审计动作序号（工具调用计数） */
    @Volatile
    private var auditActionCount = 0

    /** 单步内最多动作数（分层模式下一个子目标可能要 10+ 个动作） */
    const val MAX_ACTIONS_PER_STEP = 20

    /** 子目标内本地模型最多尝试几次，失败就别再等它，直接降级云端 */
    private const val MAX_LOCAL_ATTEMPTS = 2

    /** 循环检测：连续相同 (screenHash, toolCall) 次数达到该值则 NEED_REPLAN */
    private const val LOOP_DETECT_COUNT = 3

    /** 压缩历史保留的最近条数 */
    private const val HISTORY_KEEP = 5

    /** UI 元素过少时才补截图 */
    private const val MIN_ELEMENTS_FOR_UI_ONLY = 3

    /** 连续多少个动作被状态机拦下 → 判定计划有误，请求重新规划 */
    private const val MAX_BLOCKED_ACTIONS = 3

    /** 本地视觉「界面描述」的缓存时间（本地模型慢，别每轮都跑） */
    private const val VISION_HINT_TTL_MS = 5_000L

    /** 让本地视觉模型用一句话描述当前界面（用于状态识别） */
    private const val STATE_HINT_PROMPT =
        "用一句很短的中文描述这是什么界面，并列出屏幕上最明显的 3 个按钮或文字。" +
            "例如：委托界面，可见 全部领取、返回、每日委托。不要解释，直接给结论。"

    /**
     * 单步执行结果。
     */
    enum class Status { SUCCESS, FAILED, NEED_REPLAN }

    data class StepResult(
        val status: Status,
        val summary: String,
        /** 本步执行过程中产出的操作序列（文字形式） */
        val trace: List<String> = emptyList(),
        /** 本步结束后的屏幕状态快照（供 AgentRunner 传给 Planner） */
        val finalState: String = ""
    )

    /**
     * 执行单个子目标 / 步骤。
     *
     * @param step     步骤描述（子目标模式下就是子目标描述）
     * @param subGoal  子目标（分层模式传入；null 表示旧的原子步骤模式）
     * @param onLog    内部日志回调（供 UI 逐行展示 [Executor] 观察/执行/结果）
     * @param onThinking 思考过程回调（供浮窗展示）
     * @return StepResult
     */
    suspend fun execute(
        step: String,
        subGoal: SubGoal? = null,
        onLog: (suspend (String) -> Unit)? = null,
        onThinking: ((String) -> Unit)? = null
    ): StepResult {
        currentStepDesc = step
        // 本地操作序列（文字），用于压缩上下文
        val history = mutableListOf<String>()

        // 循环检测：记录最近的 (screenHash, toolCall) 对
        var repeatCount = 0
        var lastScreenHash: Int? = null
        var lastToolCall: String? = null

        var finalState = ""

        // ---- 状态机 / 反思：上一轮动作的结算上下文 ----
        var prevSnapshot: TaskStateMachine.Snapshot? = null
        var prevTool: String? = null
        var prevArgs: Map<String, Any?> = emptyMap()
        var prevOk = false
        var blockedCount = 0
        var visionHintAt = 0L
        var visionHint: String? = null
        // 本地动作决策的失败次数：超过 MAX_LOCAL_ATTEMPTS 就整个子目标不再试本地模型
        var localAttempts = 0
        var localHits = 0

        for (a in 1..MAX_ACTIONS_PER_STEP) {
            // 1. Observe
            val observe = observe()
            finalState = buildStateSummary(observe)
            onLog?.invoke("  观察：${finalState}")
            onThinking?.invoke("[$step] 第 $a 次观察屏幕…")

            // 1.5 状态识别（状态机约束的前提）：UI 文本优先，游戏界面用本地视觉补一句描述
            val snapshot = TaskStateMachine.refresh(
                packageName = observe.packageName,
                visibleTexts = observe.visibleTexts
            ) {
                val now = System.currentTimeMillis()
                if (now - visionHintAt < VISION_HINT_TTL_MS) return@refresh visionHint
                visionHintAt = now
                visionHint = captureStateHint()
                visionHint
            }
            if (TaskStateMachine.isActive()) {
                onLog?.invoke(
                    "  状态：${snapshot.stateName}（${snapshot.stateId}）" +
                        if (snapshot.abnormal) " ⚠️${snapshot.reason}" else ""
                )
            }

            // 1.6 结算上一轮动作：界面有没有按预期转移
            prevSnapshot?.let { prev ->
                val outcome = TaskStateMachine.onAction(prev, snapshot, prevTool.orEmpty(), prevArgs, prevOk)
                if (outcome.expected != null && !outcome.moved) {
                    onLog?.invoke("  ⚠️ 状态未按预期转移（期望 → ${outcome.expected}，实际 = ${outcome.actual}）")
                }
                if (outcome.stuck) {
                    onLog?.invoke("  ⚠️ 状态停滞：连续动作没推动界面，请求重新规划")
                    onThinking?.invoke("[$step] 界面卡住了，重新规划")
                    return StepResult(
                        Status.NEED_REPLAN,
                        "状态停滞：连续动作未推动界面（停在「${snapshot.stateName}」）",
                        history,
                        finalState
                    )
                }
            }
            prevSnapshot = snapshot

            // 子目标声明的目标状态已达成 → 直接收工，不用等模型自报 finish
            if (subGoal != null && subGoal.targetState.isNotBlank() &&
                snapshot.stateId.equals(subGoal.targetState, ignoreCase = true)
            ) {
                onLog?.invoke("  达成目标状态：${snapshot.stateName}")
                return StepResult(
                    Status.SUCCESS,
                    "已到达目标界面「${snapshot.stateName}」",
                    history,
                    finalState
                )
            }

            // 2. Think：决定下一步动作
            // 视觉模式判定：UI 树可交互元素 < 3 时，改走视觉模型从截图找目标
            val isVision = observe.screenshot != null

            // 2a. 本地优先：子目标模式 + 截图场景 + 本地模型可用 → 本地决策，不花 token
            var localTool: String? = null
            var localArgs: Map<String, Any?> = emptyMap()
            if (isVision && subGoal != null && localAttempts < MAX_LOCAL_ATTEMPTS) {
                val bmp = screenshotBitmap(observe)
                if (bmp != null) {
                    onThinking?.invoke("[$step] 本地模型判断下一步…")
                    val local = LocalActionPlanner.propose(
                        goal = subGoal,
                        stateDesc = TaskStateMachine.describe(),
                        bitmap = bmp
                    )
                    if (!bmp.isRecycled) bmp.recycle()
                    if (local != null) {
                        localTool = local.tool
                        localArgs = local.args
                        localHits++
                    } else {
                        localAttempts++
                    }
                } else {
                    localAttempts++
                }
            }

            val toolName: String
            val args: Map<String, Any?>
            if (localTool != null) {
                toolName = localTool
                args = localArgs
            } else if (isVision) {
                val v = thinkWithVision(step, observe) ?: run {
                    return StepResult(
                        Status.FAILED,
                        "视觉模式推理失败：请确认已在「设置→配置 LLM」中填写 qwen-vl 的 API Key",
                        history, finalState
                    )
                }
                toolName = v.first
                args = v.second
            } else {
                val client = LlmClientFactory.create(
                    LlmClientFactory.getProvider(ShizukuManager.appContext()),
                    ShizukuManager.appContext()
                )
                val goalHint = subGoal?.let {
                    if (it.successCriteria.isBlank()) "" else "达成标准：${it.successCriteria}"
                }.orEmpty()
                val userContent: Any =
                    buildThinkContent(step, observe, history, TaskStateMachine.describe(), goalHint)
                val systemPrompt = if (subGoal != null) {
                    PromptBuilder.buildSubGoalExecutorPrompt()
                } else {
                    PromptBuilder.buildExecutorPrompt()
                }
                val messages = mutableListOf(
                    LlmClient.Message("system", systemPrompt),
                    LlmClient.Message("user", userContent)
                )
                onThinking?.invoke("[$step] 思考下一步…")
                val raw = try {
                    client.chat(messages)
                } catch (e: Throwable) {
                    Log.e(TAG, "executor LLM failed", e)
                    return StepResult(Status.FAILED, "LLM 调用失败：${e.message}", history, finalState)
                }
                val call = parseToolCall(raw) ?: run {
                    Log.e(TAG, "无法解析 LLM 输出：$raw")
                    return StepResult(Status.FAILED, "无法解析 LLM 输出", history, finalState)
                }
                toolName = call.first
                args = call.second
            }
            // 2.5 状态机约束：当前界面不允许的工具，执行前就拦下（不做无效操作）
            if (toolName != "finish" && !TaskStateMachine.allowTool(toolName)) {
                blockedCount++
                val reason = "当前界面「${snapshot.stateName}」不允许 $toolName"
                onLog?.invoke("  ⛔ 状态机拦截：$reason")
                onThinking?.invoke("[$step] 拦下不合理动作：$toolName")
                history.add("观察(界面:${snapshot.stateId}) → 拦截($toolName) → $reason")
                prevTool = toolName
                prevArgs = args
                prevOk = false
                if (blockedCount >= MAX_BLOCKED_ACTIONS) {
                    return StepResult(
                        Status.NEED_REPLAN,
                        "连续 $blockedCount 次动作被状态机拦下（$reason）",
                        history,
                        finalState
                    )
                }
                delay(400)
                continue
            }

            onThinking?.invoke("[$step] 执行 $toolName ${args}")

            // 3. Act
            val isFinish = toolName == "finish"
            val resultText: String = when {
                isFinish -> args["thought"]?.toString() ?: "完成"
                isVision && toolName == "tap" -> executeVisionTap(args, observe)
                else -> executeTool(toolName, args)
            }
            onLog?.invoke("  执行：$toolName${if (args.isNotEmpty()) " $args" else ""}")
            onLog?.invoke("  结果：$resultText")
            onThinking?.invoke("[$step] $resultText")

            // 4. 记录操作序列
            val traceEntry = buildTraceEntry(observe, toolName, args, resultText, isFinish)
            history.add(traceEntry)

            // 留给下一轮做状态转移结算
            prevTool = toolName
            prevArgs = args
            prevOk = isSuccessResult(resultText)

            // 完成：finish 工具即本步成功
            if (isFinish) {
                onLog?.invoke("  完成：$resultText")
                return StepResult(Status.SUCCESS, resultText, history, finalState)
            }

            // 循环检测：(screenHash, toolCall) 连续相同
            val screenHash = observe.hash
            val toolCallKey = "$toolName:${args.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            if (screenHash == lastScreenHash && toolCallKey == lastToolCall) {
                repeatCount++
            } else {
                repeatCount = 1
            }
            lastScreenHash = screenHash
            lastToolCall = toolCallKey

            if (repeatCount >= LOOP_DETECT_COUNT) {
                onLog?.invoke("  检测到死循环（连续 $LOOP_DETECT_COUNT 次相同观察+动作），触发重规划")
                return StepResult(
                    Status.NEED_REPLAN,
                    "检测到死循环：连续重复 $toolName",
                    history,
                    finalState
                )
            }

            delay(800)
        }

        // 动作用完仍未完成
        onLog?.invoke("  单步内达到动作上限 $MAX_ACTIONS_PER_STEP，未完成")
        return StepResult(
            Status.NEED_REPLAN,
            "单步内 $MAX_ACTIONS_PER_STEP 个动作未完成",
            history,
            finalState
        )
    }

    /**
     * Observe 结果（含 hash 用于循环检测）。
     */
    private data class ObserveResult(
        val hash: Int,
        val packageName: String?,
        val payload: String,
        val elementCount: Int,
        val screenshot: String?,
        val screenshotWidth: Int = 0,
        val screenshotHeight: Int = 0,
        /** 当前界面可见文本（状态机识别用） */
        val visibleTexts: List<String> = emptyList()
    )

    /**
     * 观察屏幕：读 UI 树可交互元素，元素过少时补截图。
     */
    private fun observe(): ObserveResult {
        val pkg = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
        val uiJson = captureUiTree()
        val elementCount = countElements(uiJson)
        val texts = extractVisibleTexts(uiJson)

        return if (uiJson != null && elementCount >= MIN_ELEMENTS_FOR_UI_ONLY) {
            val key = "${pkg.orEmpty()}|$uiJson"
            ObserveResult(key.hashCode(), pkg, uiJson, elementCount, null, visibleTexts = texts)
        } else {
            val shot = ScreenCapture.captureBase64WithSize()
            val payload = uiJson ?: "[]"
            val key = "${pkg.orEmpty()}|$payload"
            ObserveResult(
                hash = key.hashCode(),
                packageName = pkg,
                payload = payload,
                elementCount = elementCount,
                screenshot = shot?.base64,
                screenshotWidth = shot?.width ?: 0,
                screenshotHeight = shot?.height ?: 0,
                visibleTexts = texts
            )
        }
    }

    /**
     * 从 UI 树 JSON 里抽出可见文本（状态识别用）。
     *
     * 只取 text 字段：UiTreeParser 已把 text 为空时的 contentDescription 填进来。
     */
    private fun extractVisibleTexts(uiJson: String?): List<String> {
        if (uiJson.isNullOrBlank()) return emptyList()
        return try {
            Regex("\"text\":\"([^\"]+)\"").findAll(uiJson)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(40)
                .toList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * 让本地 Qwen2-VL 用一句话描述当前界面，补 UI 树读不到文本的场景（游戏）。
     *
     * 模型未就绪时直接返回 null，状态机会据 UI 文本尽力识别，不会因此报错。
     */
    private suspend fun captureStateHint(): String? {
        val bmp = try {
            ScreenCapture.captureBitmap(1280)
        } catch (e: Throwable) {
            Log.w(TAG, "状态识别截图失败：${e.message}")
            null
        } ?: return null
        return try {
            LocalScreenRecognizer.infer(bmp, STATE_HINT_PROMPT)
        } catch (e: Throwable) {
            Log.w(TAG, "本地视觉界面描述失败：${e.message}")
            null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }
    }

    /** 工具结果是否算成功（沿用 executeTool 内部那套前缀判断） */
    private fun isSuccessResult(result: String): Boolean =
        !(result.startsWith("失败") || result.startsWith("未知") ||
            result.startsWith("工具") || result.startsWith("未找到") || result.startsWith("错误"))

    /** 把 observe 里的 base64 截图还原成 Bitmap（本地决策用） */
    private fun screenshotBitmap(observe: ObserveResult): android.graphics.Bitmap? {
        val b64 = observe.screenshot ?: return null
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Throwable) {
            Log.w(TAG, "截图解码失败：${e.message}")
            null
        }
    }

    /**
     * 构造发送给 LLM 的 user 内容：压缩历史 + 当前观察。
     */
    private fun buildThinkContent(
        step: String,
        observe: ObserveResult,
        history: List<String>,
        stateDesc: String,
        goalHint: String = ""
    ): Any {
        val text = buildString {
            append("当前要完成的步骤：$step\n\n")
            if (goalHint.isNotBlank()) append("$goalHint\n\n")
            append("$stateDesc\n\n")
            append("已执行历史：\n")
            append(compressHistory(history))
            append("\n")
            append("当前屏幕观察：\n")
            append("前台包名：${observe.packageName ?: "未知"}\n")
            append("可交互元素：\n${observe.payload}\n")
            append("请判断本步骤是否已完成，或给出下一步工具调用 JSON。")
        }

        return if (observe.screenshot != null) {
            listOf(
                LlmClient.ContentPart("text", text = text),
                LlmClient.ContentPart(
                    "image_url",
                    imageUrl = "data:image/jpeg;base64,${observe.screenshot}"
                )
            )
        } else {
            text
        }
    }

    /**
     * 压缩历史：只保留最近 [HISTORY_KEEP] 条，更早的合并为"前 N 步已完成"。
     */
    private fun compressHistory(history: List<String>): String {
        if (history.isEmpty()) return "（无）"
        val overflow = history.size - HISTORY_KEEP
        val sb = StringBuilder()
        if (overflow > 0) {
            sb.append("前 $overflow 条已完成\n")
            history.takeLast(HISTORY_KEEP).forEach { sb.append("- $it\n") }
        } else {
            history.forEach { sb.append("- $it\n") }
        }
        return sb.toString()
    }

    /**
     * 构造单条操作序列文字。
     */
    private fun buildTraceEntry(
        observe: ObserveResult,
        tool: String,
        args: Map<String, Any?>,
        result: String,
        isFinish: Boolean
    ): String {
        val iface = observe.packageName ?: "未知界面"
        val changed = !isFinish && !result.contains("未找到") && !result.contains("失败")
        val action = if (isFinish) "完成" else {
            val argStr = args.entries.firstOrNull()?.value?.toString() ?: ""
            "$tool:$argStr"
        }
        return "观察(界面:$iface, 元素:${observe.elementCount}) → 执行($action) → 结果(变化:${if (changed) "是" else "否"})"
    }

    /**
     * 构建屏幕状态摘要（供 AgentRunner 传给 Planner）。
     */
    private fun buildStateSummary(observe: ObserveResult): String {
        val iface = observe.packageName ?: "未知界面"
        return "界面=$iface, 元素=${observe.elementCount}"
    }

    /**
     * 解析 LLM 返回的工具调用 JSON。
     */
    private fun parseToolCall(raw: String): Pair<String, Map<String, Any?>>? {
        return try {
            val jsonStr = extractJson(raw) ?: return null
            val obj = JSONObject(jsonStr)
            val tool = obj.optString("tool").lowercase().trim()
            if (tool.isEmpty()) return null
            // 兼容 "args"（语义工具）与 "params"（视觉模式 tap 坐标）两种字段名
            val argsObj = obj.optJSONObject("args") ?: obj.optJSONObject("params") ?: JSONObject()
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
        // 安全策略：工具级拦截（默认开启，命中则拒绝执行）
        val ctx = ShizukuManager.appContext()
        if (ctx != null && SafetyPolicy.isEnabled(ctx)) {
            val block = SafetyPolicy.checkToolCall(toolName, args)
            if (block.blocked) {
                recordAudit(toolName, args, block.reason, "failed", System.currentTimeMillis(), 0)
                return block.reason
            }
        }
        val tool = ToolRegistry.get(toolName)
        val startMs = System.currentTimeMillis()
        val result = if (tool == null) {
            "未知工具：$toolName"
        } else {
            try {
                tool.execute(args)
            } catch (e: Throwable) {
                Log.e(TAG, "tool $toolName failed", e)
                "工具 $toolName 执行失败：${e.message}"
            }
        }
        val ok = !(result.startsWith("失败") || result.startsWith("未知") || result.startsWith("工具") || result.startsWith("未找到") || result.startsWith("错误"))
        recordAudit(toolName, args, result, if (ok) "success" else "failed", startMs, System.currentTimeMillis() - startMs)
        return result
    }

    /**
     * 记录一条审计日志。
     */
    private fun recordAudit(
        toolName: String,
        args: Map<String, Any?>,
        resultDetail: String,
        result: String,
        startMs: Long,
        durationMs: Long
    ) {
        try {
            val x = (args["x"] as? Number)?.toFloat()
            val y = (args["y"] as? Number)?.toFloat()
            val entry = AuditEntry(
                timestamp = startMs,
                stepIndex = auditActionCount++,
                stepDescription = currentStepDesc,
                actionType = toolName,
                actionParams = args.mapValues { it.value?.toString() ?: "" },
                targetElement = (args["text"] ?: args["id"] ?: args["appName"])?.toString(),
                coordinates = if (x != null && y != null) Pair(x, y) else null,
                result = result,
                resultDetail = resultDetail.take(200),
                screenshotPath = AuditLogger.saveScreenshot(),
                reasoning = null,
                screenType = null,
                durationMs = durationMs
            )
            AuditLogger.recordStep(entry)
        } catch (e: Throwable) {
            Log.e(TAG, "记录审计失败", e)
        }
    }

    /**
     * 视觉模式思考：用视觉模型（qwen-vl）从截图找目标，返回 (toolName, args)。
     *
     * 这是唯一允许 LLM 从图片推算坐标的场景——UI 树读不到足够可交互元素时
     * （如游戏 / Canvas 渲染界面），退化为「看图找位置 + 坐标点击」。
     */
    private suspend fun thinkWithVision(
        step: String,
        observe: ObserveResult
    ): Pair<String, Map<String, Any?>>? {
        val startMs = System.currentTimeMillis()
        // base64 字符数 * 3/4 得到近似字节数
        val imageKb = (observe.screenshot?.length ?: 0) * 3 / 4 / 1024

        val ctx = ShizukuManager.appContext()
        val visionProvider = LlmClientFactory.getVisionProvider(ctx)
        // 预检查：视觉 provider 未配置 key 时给出明确提示
        val (apiKey, _, _) = if (ctx != null) LlmClientFactory.getConfig(ctx, visionProvider)
            else Triple("", "", "")
        if (apiKey.isBlank()) {
            Log.e(TAG, "vision provider $visionProvider 未配置 API Key")
            return null
        }
        val visionClient = LlmClientFactory.create(visionProvider, ctx)

        val prompt = "这是当前屏幕截图。请找到「$step」的目标位置，输出 JSON: " +
            "{\"tool\": \"tap\", \"params\": {\"x\": 数字, \"y\": 数字}}"

        val messages = listOf(
            LlmClient.Message("system", PromptBuilder.buildVisionPrompt()),
            LlmClient.Message(
                "user",
                listOf(
                    LlmClient.ContentPart("text", text = prompt),
                    LlmClient.ContentPart(
                        "image_url",
                        imageUrl = "data:image/jpeg;base64,${observe.screenshot}"
                    )
                )
            )
        )

        val raw = try {
            visionClient.chat(messages)
        } catch (e: Throwable) {
            Log.e(TAG, "vision LLM failed", e)
            return null
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        Log.i("NexusVision", "vision_mode=true, image_kb=$imageKb, elapsed_ms=$elapsedMs")

        return parseToolCall(raw)
    }

    /**
     * 视觉模式的坐标点击（LLM 从截图推算的坐标）。
     *
     * 关键：视觉模型在「降采样截图」上推算坐标，需按比例换算回「真实屏幕坐标」。
     */
    private fun executeVisionTap(args: Map<String, Any?>, observe: ObserveResult): String {
        val x = (args["x"] as? Number)?.toFloat()
        val y = (args["y"] as? Number)?.toFloat()
        if (x == null || y == null) return "视觉模式 tap 缺少坐标 x/y"

        // 降采样截图坐标 → 真实屏幕坐标
        val real = ToolSupport.screenSize()
        val scaleX = if (observe.screenshotWidth > 0) real.width.toFloat() / observe.screenshotWidth else 1f
        val scaleY = if (observe.screenshotHeight > 0) real.height.toFloat() / observe.screenshotHeight else 1f
        val realX = x * scaleX
        val realY = y * scaleY

        val ok = ToolSupport.injectTap(realX, realY)
        return if (ok) "视觉模式点击 (${realX.toInt()}, ${realY.toInt()})" else "点击失败：Shizuku 服务不可用"
    }

    /**
     * 统计 UI 树 JSON 里的元素个数。
     */
    private fun countElements(uiJson: String?): Int {
        if (uiJson == null) return 0
        return Regex("\"center\"").findAll(uiJson).count()
    }

    /**
     * 读取当前 UI 树并解析为精简 JSON。
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
