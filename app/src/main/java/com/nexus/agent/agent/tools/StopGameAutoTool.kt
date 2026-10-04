package com.nexus.agent.agent.tools

import com.nexus.agent.game.GameRuleRunner
import org.json.JSONObject

/**
 * stop_game_auto —— 停止游戏自动化。
 */
object StopGameAutoTool : Tool {

    override val name = "stop_game_auto"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override fun execute(args: Map<String, Any?>): String {
        val wasRunning = GameRuleRunner.isRunning
        GameRuleRunner.stop()
        return if (wasRunning) "已停止游戏自动化" else "游戏自动化当前未运行"
    }
}
