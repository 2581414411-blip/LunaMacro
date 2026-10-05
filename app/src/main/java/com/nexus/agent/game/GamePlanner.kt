package com.nexus.agent.game

import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.input.ShizukuManager
import org.json.JSONArray

/**
 * 主模型游戏规划器。
 *
 * 基于游戏知识库 + 用户指令，用云端主模型把任务拆成步骤列表（每步一句话，≤8 步）。
 * 只在开局和异常时调用，不参与毫秒级决策。
 */
object GamePlanner {

    private const val TAG = "GamePlanner"

    /**
     * 生成任务步骤列表。
     *
     * @param userCommand 用户指令（如"做每日任务"）
     * @param knowledge 游戏知识库
     * @param currentScreen 当前画面（识别结果或描述）
     * @return 步骤列表；失败返回空列表
     */
    suspend fun plan(userCommand: String, knowledge: GameKnowledge, currentScreen: String): List<String> {
        val ctx = ShizukuManager.appContext() ?: return emptyList()
        val provider = LlmClientFactory.getProvider(ctx)
        val client = LlmClientFactory.create(provider, ctx)

        val screenTypes = knowledge.screenTypes.joinToString("、") { it.name }

        val systemPrompt = buildString {
            append("你是游戏任务规划器。基于以下信息，把用户的任务拆成步骤列表。\n\n")
            append("游戏：${knowledge.gameName}\n")
            append("标准流程：${knowledge.flowDescription}\n")
            append("已知界面类型：$screenTypes\n")
            append("当前画面：$currentScreen\n\n")
            append("输出要求：\n")
            append("- 步骤列表，每步一句话，不超过 8 步\n")
            append("- 每步应该是\"到达某个界面 + 执行某个动作\"\n")
            append("- 只输出 JSON 数组，不要解释")
        }

        val userPrompt = "用户指令：$userCommand"

        return try {
            val reply = client.chat(
                listOf(
                    LlmClient.Message("system", systemPrompt),
                    LlmClient.Message("user", userPrompt)
                )
            )
            Log.i(TAG, "plan reply: $reply")
            parseSteps(reply)
        } catch (e: Throwable) {
            Log.e(TAG, "规划失败", e)
            emptyList()
        }
    }

    /**
     * 解析 LLM 返回的 JSON 数组为步骤列表。
     */
    private fun parseSteps(reply: String): List<String> {
        val trimmed = reply.trim()
        // 直接解析 JSON 数组
        try {
            val arr = JSONArray(trimmed)
            val steps = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                steps.add(arr.getString(i))
            }
            return steps
        } catch (_: Throwable) {
            // 尝试提取 [...] 部分（LLM 可能包了说明文字）
        }
        val match = Regex("\\[[\\s\\S]*\\]").find(trimmed) ?: return emptyList()
        return try {
            val arr = JSONArray(match.value)
            val steps = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                steps.add(arr.getString(i))
            }
            steps
        } catch (e: Throwable) {
            Log.e(TAG, "解析步骤 JSON 失败: $reply", e)
            emptyList()
        }
    }
}
