package com.nexus.agent.game

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.Executor
import com.nexus.agent.agent.tools.OpenAppTool
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 零配置游戏托管事件。
 */
sealed class AutoPilotEvent {
    object Planning : AutoPilotEvent()
    data class Planned(val steps: List<String>) : AutoPilotEvent()
    data class Executing(val stepIndex: Int, val total: Int, val step: String) : AutoPilotEvent()
    data class ScreenRecognized(val screenType: String, val buttons: Int) : AutoPilotEvent()
    data class ActionExecuted(val action: String) : AutoPilotEvent()
    data class StepCompleted(val stepIndex: Int) : AutoPilotEvent()
    object Replanning : AutoPilotEvent()
    object Completed : AutoPilotEvent()
    data class Failed(val reason: String) : AutoPilotEvent()
}

/**
 * 零配置游戏托管编排器。
 *
 * 用户只说一句话，自动完成：识别游戏 → 加载知识库 → 启动游戏 → 规划步骤 →
 * 循环（截图 → 识别 → 执行步骤）→ 卡住则重规划 → 完成。
 */
object GameAutoPilot {

    private const val TAG = "GameAutoPilot"
    private const val STEP_TIMEOUT_MS = 60_000L

    /**
     * 启动零配置托管流程。
     *
     * @param userCommand 用户指令（如"帮我做原神每日任务"）
     * @return 事件流，供 UI / TTS 消费
     */
    suspend fun start(userCommand: String): Flow<AutoPilotEvent> = flow {
        val ctx = ShizukuManager.appContext()
        if (ctx == null) {
            emit(AutoPilotEvent.Failed("App 未初始化"))
            return@flow
        }

        // 1. 识别游戏 + 加载知识库
        val knowledge = findGame(ctx, userCommand)
        if (knowledge == null) {
            emit(AutoPilotEvent.Failed("未识别出游戏，暂不支持该游戏"))
            return@flow
        }
        Log.i(TAG, "识别游戏：${knowledge.gameName}（${knowledge.packageName}）")

        // 2. 规划步骤
        emit(AutoPilotEvent.Planning)
        var steps = GamePlanner.plan(userCommand, knowledge, "未知").toMutableList()
        if (steps.isEmpty()) {
            emit(AutoPilotEvent.Failed("规划失败，请检查 LLM 配置"))
            return@flow
        }
        emit(AutoPilotEvent.Planned(steps.toList()))

        // 3. 启动游戏
        OpenAppTool.execute(mapOf("appName" to knowledge.gameName))
        kotlinx.coroutines.delay(3000) // 等游戏启动

        // 4. 执行循环
        var stepIndex = 0
        while (stepIndex < steps.size) {
            val step = steps[stepIndex]
            emit(AutoPilotEvent.Executing(stepIndex, steps.size, step))

            // 识别当前画面（本地模型，失败降级云端由 Executor 内部处理）
            val frame = GamePerceptionLoop.getLatestFrame()
            if (frame != null) {
                val analysis = LocalScreenRecognizer.recognize(frame)
                if (analysis != null) {
                    emit(AutoPilotEvent.ScreenRecognized(analysis.screenType, analysis.buttons.size))
                }
            }

            // 执行步骤（复用 Executor，60s 超时）
            val result = withTimeoutOrNull(STEP_TIMEOUT_MS) {
                Executor.execute(step)
            }

            if (result == null || result.status == Executor.Status.FAILED) {
                // 超时或失败 → 升级主模型重新规划
                emit(AutoPilotEvent.Replanning)
                val newSteps = GamePlanner.plan(
                    "上一步「$step」未完成，请重新规划剩余步骤",
                    knowledge,
                    "未知"
                )
                if (newSteps.isEmpty()) {
                    emit(AutoPilotEvent.Failed("步骤执行失败且重新规划失败"))
                    return@flow
                }
                // 用新步骤替换剩余步骤
                val remaining = steps.drop(stepIndex + 1)
                steps = (newSteps + remaining).toMutableList()
                continue // 不递增 index，重试当前（新）步骤
            }

            emit(AutoPilotEvent.ActionExecuted(result.summary))
            emit(AutoPilotEvent.StepCompleted(stepIndex))
            stepIndex++
        }

        emit(AutoPilotEvent.Completed)
    }

    /**
     * 从用户指令中识别游戏（匹配知识库的游戏名/游戏 id）。
     */
    private fun findGame(context: Context, command: String): GameKnowledge? {
        val presets = GameKnowledgeStore.listPresetIds(context)
            .mapNotNull { GameKnowledgeStore.loadPresetFromAssets(context, it) }
        return presets.firstOrNull { k ->
            command.contains(k.gameName) || command.contains(k.gameId)
        }
    }
}
