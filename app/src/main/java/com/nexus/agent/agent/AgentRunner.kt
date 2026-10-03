package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.coroutines.coroutineContext

/**
 * AgentRunner —— 分层编排入口（Planner + Executor）。
 *
 * 新流程：
 * 1. 先 Observe 一次，拿到初始屏幕状态
 * 2. 调用 Planner.plan(task, initialState) 生成步骤列表
 * 3. 对每个步骤：
 *    a. 调用 Executor.execute(step)
 *    b. SUCCESS → 进入下一步
 *    c. NEED_REPLAN → 把已完成步骤 + 当前状态发给 Planner.replan()，替换剩余步骤
 *    d. FAILED → 中止并报告
 * 4. 全部完成 → 调用 LLM 生成最终结果摘要
 * 5. Replan 最多 [MAX_REPLANS] 次，超过则中止
 *
 * 关键参数：
 * - 总步骤上限 8（Planner.MAX_STEPS）
 * - 单步骤内动作上限 8（Executor.MAX_ACTIONS_PER_STEP）
 * - 全局动作上限 40（防止意外死循环）
 * - 循环检测：同一步骤内连续 3 次 (screenHash, toolCall) 相同 → replan
 */
object AgentRunner {

    private const val TAG = "AgentRunner"

    /** 重规划次数上限 */
    private const val MAX_REPLANS = 2

    /** 全局动作上限 */
    private const val GLOBAL_ACTION_LIMIT = 40

    /** 当前运行任务的 Job（供 [stop] 取消） */
    @Volatile
    private var currentJob: Job? = null

    /**
     * 停止当前正在运行的任务。
     *
     * 取消当前 collect 的协程，让 runTask 流提前结束。
     */
    fun stop() {
        currentJob?.cancel()
        currentJob = null
    }

    /**
     * 日志事件：按层级标签区分 Planner / Executor / Agent。
     */
    data class LogEvent(
        val level: String,     // "Planner" / "Executor" / "Agent"
        val message: String
    )

    /**
     * 运行任务，逐条产出日志事件。
     *
     * @param task      用户任务（自然语言）
     * @param onThinking 思考过程回调（供浮窗）
     * @return 日志事件流；最后一条为最终结果摘要
     */
    fun runTask(
        task: String,
        onThinking: ((String) -> Unit)? = null
    ): Flow<LogEvent> = flow {
        val flowJob = coroutineContext[Job]
        currentJob = flowJob
        try {
        // 安全策略：任务文本级拦截（默认开启，命中则拒绝整个任务）
        val safetyCtx = ShizukuManager.appContext()
        if (safetyCtx != null && SafetyPolicy.isEnabled(safetyCtx)) {
            SafetyPolicy.checkTaskText(task)?.let { reason ->
                emit(LogEvent("Agent", "🛡️ $reason"))
                onThinking?.invoke(reason)
                return@flow
            }
        }
        var globalActions = 0
        var replans = 0

        // 1. 首次 Observe，拿初始状态
        emit(LogEvent("Agent", "开始任务：$task"))
        onThinking?.invoke("观察初始屏幕…")
        val initialState = observeInitial()
        emit(LogEvent("Agent", "初始状态：$initialState"))

        // 2. Planner 生成步骤
        emit(LogEvent("Planner", "生成步骤…"))
        var steps = Planner.plan(task, initialState)
        if (steps.isEmpty()) {
            emit(LogEvent("Agent", "❌ 规划失败：无法生成步骤列表"))
            return@flow
        }
        emitSteps(steps, this)

        // 3. 逐步执行
        val completed = mutableListOf<String>()
        var stepIndex = 0

        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            emit(LogEvent("Executor", "执行步骤 ${stepIndex + 1}：$step"))

            val result = Executor.execute(
                step,
                onLog = { line -> emit(LogEvent("Executor", line)) },
                onThinking = onThinking
            )

            // 累计全局动作数
            globalActions += result.trace.size
            if (globalActions >= GLOBAL_ACTION_LIMIT) {
                emit(LogEvent("Agent", "❌ 全局动作数达到上限 $GLOBAL_ACTION_LIMIT，中止防止死循环"))
                return@flow
            }

            when (result.status) {
                Executor.Status.SUCCESS -> {
                    completed.add(step)
                    emit(LogEvent("Planner", "步骤 ${stepIndex + 1} 完成，进入步骤 ${stepIndex + 2}"))
                    stepIndex++
                }

                Executor.Status.FAILED -> {
                    emit(LogEvent("Agent", "❌ 步骤「$step」失败：${result.summary}"))
                    emit(LogEvent("Agent", "任务中止。"))
                    return@flow
                }

                Executor.Status.NEED_REPLAN -> {
                    if (replans >= MAX_REPLANS) {
                        emit(LogEvent("Agent", "❌ 重规划已达上限 $MAX_REPLANS 次，任务中止"))
                        return@flow
                    }
                    replans++
                    emit(LogEvent("Planner", "步骤「$step」需要重规划（第 $replans 次）…"))
                    onThinking?.invoke("重新规划剩余步骤…")

                    val remaining = steps.subList(stepIndex, steps.size)
                    val newSteps = Planner.replan(task, completed + remaining, result.finalState)
                    if (newSteps.isEmpty()) {
                        // replan 返回空，说明任务已完成
                        emit(LogEvent("Agent", "重规划返回空：任务已完成"))
                        steps = emptyList()
                        break
                    }
                    emit(LogEvent("Planner", "重规划生成 ${newSteps.size} 个新步骤："))
                    emitSteps(newSteps, this)
                    steps = newSteps
                    stepIndex = 0
                    // 注意：completed 保留原已完成步骤，供后续 replan 上下文使用
                }
            }
        }

        // 4. 生成最终结果摘要
        emit(LogEvent("Agent", "生成结果摘要…"))
        val summary = summarize(task, completed, observeInitial())
        emit(LogEvent("Agent", "任务完成：$summary"))
        } finally {
            if (currentJob === flowJob) currentJob = null
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 把步骤列表按序号 emit 出去。
     */
    private suspend fun emitSteps(steps: List<String>, scope: kotlinx.coroutines.flow.FlowCollector<LogEvent>) {
        scope.emit(LogEvent("Planner", "生成步骤："))
        steps.forEachIndexed { i, s ->
            scope.emit(LogEvent("Planner", "${i + 1}. $s"))
        }
    }

    /**
     * 首次观察：读前台包名 + 可交互元素，返回精简摘要。
     */
    private fun observeInitial(): String {
        val pkg = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
        val uiJson = captureUiTree()
        val elementCount = if (uiJson == null) 0 else Regex("\"center\"").findAll(uiJson).count()
        return "界面=${pkg ?: "未知"}, 元素=$elementCount"
    }

    /**
     * 调用 LLM 生成最终结果摘要。
     */
    private suspend fun summarize(task: String, completed: List<String>, finalState: String): String {
        val client = LlmClientFactory.create(
            LlmClientFactory.getProvider(ShizukuManager.appContext()),
            ShizukuManager.appContext()
        )
        val doneText = if (completed.isEmpty()) "（无）" else completed.joinToString(" → ")
        val prompt = buildString {
            append("你是结果汇报助手。请根据已完成步骤，用一句话总结任务结果。\n\n")
            append("原始任务：$task\n")
            append("已完成步骤：$doneText\n")
            append("最终屏幕状态：$finalState\n\n")
            append("要求：直接给出结果结论，例如「当前连接 WiFi 为 XXXXX」。")
            append("如果信息不足，据实说明已完成到哪一步、缺什么。不要编造。\n")
        }
        val messages = listOf(
            LlmClient.Message("system", PromptBuilder.buildSummaryPrompt()),
            LlmClient.Message("user", prompt)
        )
        return try {
            client.chat(messages).trim()
        } catch (e: Throwable) {
            Log.e(TAG, "summarize LLM failed", e)
            "任务流程结束（完成 ${completed.size} 步：$doneText）"
        }
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
