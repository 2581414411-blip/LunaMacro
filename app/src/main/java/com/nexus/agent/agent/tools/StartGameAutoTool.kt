package com.nexus.agent.agent.tools

import android.util.Log
import com.nexus.agent.game.AutoPilotEvent
import com.nexus.agent.game.GameAutoPilot
import com.nexus.agent.game.GameKnowledgeStore
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.speech.TtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * start_game_auto —— 启动零配置游戏托管。
 *
 * 用户只说一句话（游戏名 + 任务描述），自动识别游戏、加载知识库、规划步骤并执行。
 */
object StartGameAutoTool : Tool {

    override val name = "start_game_auto"

    private const val TAG = "StartGameAutoTool"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("gameName", JSONObject().apply {
                put("type", "string")
                put("description", "游戏名，如\"原神\"、\"崩坏：星穹铁道\"")
            })
            put("task", JSONObject().apply {
                put("type", "string")
                put("description", "任务描述，如\"做每日任务\"、\"刷材料\"")
            })
        })
        put("required", JSONArray().put("gameName").put("task"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val gameName = args["gameName"]?.toString()?.trim().orEmpty()
        val task = args["task"]?.toString()?.trim().orEmpty()
        if (gameName.isEmpty() || task.isEmpty()) {
            return "错误：需要 gameName 和 task 两个参数"
        }

        val ctx = ShizukuManager.appContext() ?: return "App 未初始化"

        // 从知识库查找游戏
        val knowledge = GameKnowledgeStore.findByName(ctx, gameName)
        if (knowledge == null) {
            return "暂不支持该游戏：$gameName，请等待后续版本"
        }

        // 异步启动托管流程（execute 是同步的，不能阻塞）
        scope.launch {
            try {
                GameAutoPilot.start(task).collect { event ->
                    when (event) {
                        is AutoPilotEvent.Planning -> Log.i(TAG, "正在规划…")
                        is AutoPilotEvent.Planned -> Log.i(TAG, "规划完成：${event.steps.size} 步")
                        is AutoPilotEvent.Executing ->
                            Log.i(TAG, "执行 ${event.stepIndex + 1}/${event.total}: ${event.step}")
                        is AutoPilotEvent.ScreenRecognized ->
                            Log.i(TAG, "识别：${event.screenType}（${event.buttons} 按钮）")
                        is AutoPilotEvent.ActionExecuted -> Log.i(TAG, "动作：${event.action}")
                        is AutoPilotEvent.StepCompleted -> Log.i(TAG, "步骤 ${event.stepIndex + 1} 完成")
                        is AutoPilotEvent.Replanning -> Log.i(TAG, "卡住，重新规划…")
                        is AutoPilotEvent.Completed -> {
                            Log.i(TAG, "任务全部完成")
                            TtsSpeaker(ctx).speak("游戏任务已完成")
                        }
                        is AutoPilotEvent.Failed -> {
                            Log.e(TAG, "任务失败：${event.reason}")
                            TtsSpeaker(ctx).speak("任务失败：${event.reason}")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "托管流程异常", e)
            }
        }

        return "已启动游戏托管：${knowledge.gameName}（$task）"
    }
}
