package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.accessibility.UiTreeParser
import com.nexus.agent.agent.state.TaskStateMachine
import com.nexus.agent.audit.AuditLogger
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
    private const val MAX_REPLANS = 3

    /** 全局动作上限（分层模式下一个子目标动作更多，上限相应放宽） */
    private const val GLOBAL_ACTION_LIMIT = 100

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
        var finalStatus = "failed"
        var finalSummary: String? = null
        AuditLogger.startTask(task, null)
        try {
        // 安全策略：任务文本级拦截（默认开启，命中则拒绝整个任务）
        val safetyCtx = ShizukuManager.appContext()
        if (safetyCtx != null && SafetyPolicy.isEnabled(safetyCtx)) {
            val block = SafetyPolicy.checkTaskText(task)
            if (block.blocked) {
                emit(LogEvent("Agent", "🛡️ ${block.reason}"))
                onThinking?.invoke(SafetyPolicy.BLOCKED_MSG)
                // 以「任务完成」前缀传递拦截结果，供语音层识别并 TTS 播报
                emit(LogEvent("Agent", "任务完成：${SafetyPolicy.BLOCKED_MSG}"))
                finalStatus = "cancelled"
                return@flow
            }
        }
        var globalActions = 0
        var replans = 0

        // 0. 挂载状态机（按前台包名 / 任务文本匹配；匹配不到则旁路，行为与旧版一致）
        val fgPkg = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
        val profile = TaskStateMachine.attach(ShizukuManager.appContext(), fgPkg, task)
        if (profile != null) {
            emit(LogEvent("Agent", "状态机已启用：${profile.name}（${profile.states.size} 个状态）"))
        } else {
            Log.i(TAG, "未匹配状态机配置，按通用模式执行")
        }

        // 1. 首次 Observe，拿初始状态
        emit(LogEvent("Agent", "开始任务：$task"))
        onThinking?.invoke("观察初始屏幕…")
        val initialState = observeInitial()
        emit(LogEvent("Agent", "初始状态：$initialState"))

        // 2. Planner 生成子目标（分层：云端只说「要什么」，不说怎么点）
        emit(LogEvent("Planner", "拆解子目标…"))
        var goals = Planner.planSubGoals(task, initialState, TaskStateMachine.describe())
        if (goals.isEmpty()) {
            emit(LogEvent("Agent", "❌ 规划失败：无法生成子目标列表"))
            return@flow
        }
        AuditLogger.setPlannedSteps(goals.map { it.brief() })
        emitGoals(goals, this)

        // 3. 逐个子目标执行：具体操作由本地执行器在子目标内自主完成
        val completed = mutableListOf<String>()
        var goalIndex = 0

        while (goalIndex < goals.size) {
            val goal = goals[goalIndex]
            emit(LogEvent("Executor", "子目标 ${goalIndex + 1}/${goals.size}：${goal.description}"))

            val result = Executor.execute(
                step = goal.description,
                subGoal = goal,
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
                    completed.add(goal.description)
                    emit(LogEvent("Planner", "子目标 ${goalIndex + 1} 完成 → ${result.summary}"))
                    goalIndex++
                }

                Executor.Status.FAILED -> {
                    // 配置类失败（没配 LLM / 没配视觉模型）重规划也救不回来，直接中止
                    if (result.summary.contains("LLM") || result.summary.contains("API Key")) {
                        emit(LogEvent("Agent", "❌ 子目标「${goal.description}」失败：${result.summary}"))
                        emit(LogEvent("Agent", "任务中止。"))
                        return@flow
                    }
                    if (replans >= MAX_REPLANS) {
                        emit(LogEvent("Agent", "❌ 子目标「${goal.description}」反复失败，重规划已达上限，任务中止"))
                        return@flow
                    }
                    replans++
                    emit(LogEvent("Planner", "子目标「${goal.description}」失败（${result.summary}），请求云端重新规划（第 $replans 次）…"))
                    onThinking?.invoke("执行失败，重新规划剩余目标…")

                    val remaining = goals.drop(goalIndex).map { it.description }
                    val newGoals = Planner.replanSubGoals(
                        task = task,
                        completed = completed + remaining,
                        currentState = result.finalState,
                        stuckReason = result.summary
                    )
                    if (newGoals.isEmpty()) {
                        emit(LogEvent("Agent", "重规划返回空：任务已完成"))
                        break
                    }
                    emitGoals(newGoals, this)
                    goals = newGoals
                    goalIndex = 0
                }

                Executor.Status.NEED_REPLAN -> {
                    if (replans >= MAX_REPLANS) {
                        emit(LogEvent("Agent", "❌ 重规划已达上限 $MAX_REPLANS 次，任务中止"))
                        return@flow
                    }
                    replans++
                    emit(LogEvent("Planner", "子目标「${goal.description}」卡住（${result.summary}），请求云端重新规划（第 $replans 次）…"))
                    onThinking?.invoke("本地执行卡住，重新规划剩余目标…")

                    val remaining = goals.drop(goalIndex).map { it.description }
                    val newGoals = Planner.replanSubGoals(
                        task = task,
                        completed = completed + remaining,
                        currentState = result.finalState,
                        stuckReason = result.summary
                    )
                    if (newGoals.isEmpty()) {
                        emit(LogEvent("Agent", "重规划返回空：任务已完成"))
                        break
                    }
                    emitGoals(newGoals, this)
                    goals = newGoals
                    goalIndex = 0
                }
            }
        }

        // 4. 生成最终结果摘要
        emit(LogEvent("Agent", "生成结果摘要…"))
        val summary = summarize(task, completed, observeInitial())
        emit(LogEvent("Agent", "任务完成：$summary"))
        finalStatus = "completed"
        finalSummary = summary
        } finally {
            AuditLogger.endTask(finalStatus, finalSummary)
            if (currentJob === flowJob) currentJob = null
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 把子目标列表按序号 emit 出去。
     */
    private suspend fun emitGoals(
        goals: List<SubGoal>,
        scope: kotlinx.coroutines.flow.FlowCollector<LogEvent>
    ) {
        scope.emit(LogEvent("Planner", "子目标："))
        goals.forEachIndexed { i, g ->
            scope.emit(LogEvent("Planner", "${i + 1}. ${g.brief()}"))
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
