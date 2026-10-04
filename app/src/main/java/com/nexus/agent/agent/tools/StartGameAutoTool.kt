package com.nexus.agent.agent.tools

import android.os.Handler
import android.os.Looper
import com.nexus.agent.game.GameProfileStore
import com.nexus.agent.game.GameRuleRunner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * start_game_auto —— 启动游戏自动化。
 *
 * 按名称加载 GameProfile，加载规则并启动感知+规则循环。
 * 可指定 durationMinutes 自动停止。
 */
object StartGameAutoTool : Tool {

    override val name = "start_game_auto"

    private val handler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("profileName", JSONObject().apply {
                put("type", "string")
                put("description", "游戏配置（GameProfile）名称")
            })
            put("durationMinutes", JSONObject().apply {
                put("type", "integer")
                put("description", "运行时长（分钟），0 表示手动停止")
            })
        })
        put("required", JSONArray().put("profileName"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val profileName = args["profileName"]?.toString()?.trim().orEmpty()
        if (profileName.isEmpty()) return "错误：缺少 profileName 参数"

        val duration = (args["durationMinutes"] as? Number)?.toInt() ?: 0

        val profile = runBlocking {
            GameProfileStore.listAll().firstOrNull { it.name.equals(profileName, ignoreCase = true) }
        } ?: return "未找到名为\"$profileName\"的游戏配置"

        GameRuleRunner.setGameProfile(profile)
        GameRuleRunner.start()

        // 时长限制：到时自动停止
        stopRunnable?.let { handler.removeCallbacks(it) }
        if (duration > 0) {
            val r = Runnable { GameRuleRunner.stop() }
            stopRunnable = r
            handler.postDelayed(r, duration * 60_000L)
        }

        val durationNote = if (duration > 0) "，运行 $duration 分钟" else ""
        return "已启动游戏自动化：${profile.name}（${profile.rules.size} 条规则）$durationNote"
    }
}
