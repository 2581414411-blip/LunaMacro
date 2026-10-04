package com.nexus.agent.game

import android.util.Log
import com.nexus.agent.agent.tools.ClickTextTool
import com.nexus.agent.agent.tools.PlayMacroTool
import com.nexus.agent.agent.tools.ToolSupport
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.overlay.ThinkingOverlay
import kotlinx.coroutines.delay

/**
 * 游戏规则。
 */
data class GameRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val condition: String = "",                 // 如 "healthPercent < 30"
    val action: RuleAction,                     // 满足条件时的动作
    val actionParams: Map<String, Any> = emptyMap(),
    val cooldownMs: Long = 0,                   // 冷却（两次触发最小间隔）
    val priority: Int = 0                       // 优先级（越大越先执行）
)

/**
 * 规则动作类型。
 */
enum class RuleAction {
    TAP,           // 坐标点击
    TAP_TEXT,      // 按文本点击
    PLAY_MACRO,    // 回放宏
    WAIT,          // 等待
    NOTIFY         // 悬浮窗提示
}

/**
 * 规则引擎。
 *
 * 条件表达式支持：
 * - 比较：`healthPercent < 30`、`resources.gold >= 100`
 * - 复合：`healthPercent < 30 && resources.potion > 0`
 * - 存在性：`boss_alive`（customFields 中存在该 key 且非 false）
 */
object RuleEngine {

    private const val TAG = "RuleEngine"

    private val COMPARE_REGEX = Regex("""([\w.]+)\s*(<=|>=|==|!=|<|>)\s*(\d+)""")

    /** 规则上次触发时间（用于冷却） */
    private val lastTriggered = mutableMapOf<String, Long>()

    /**
     * 评估所有规则，返回命中（且冷却已过）的规则列表，按优先级降序。
     */
    fun evaluate(state: GameState, rules: List<GameRule>): List<GameRule> {
        val now = System.currentTimeMillis()
        return rules.filter { rule ->
            if (!rule.enabled) return@filter false
            val last = lastTriggered[rule.id] ?: 0L
            if (now - last < rule.cooldownMs) return@filter false
            evaluateCondition(rule.condition, state)
        }.sortedByDescending { it.priority }
    }

    /**
     * 执行规则动作。失败仅记录日志，不抛出（保证循环不中断）。
     */
    suspend fun execute(rule: GameRule) {
        try {
            when (rule.action) {
                RuleAction.TAP -> {
                    val x = (rule.actionParams["x"] as? Number)?.toFloat() ?: return
                    val y = (rule.actionParams["y"] as? Number)?.toFloat() ?: return
                    ToolSupport.injectTap(x, y)
                }
                RuleAction.TAP_TEXT -> {
                    val text = rule.actionParams["text"]?.toString() ?: return
                    ClickTextTool.execute(mapOf("text" to text))
                }
                RuleAction.PLAY_MACRO -> {
                    val macroName = rule.actionParams["macroName"]?.toString() ?: return
                    PlayMacroTool.execute(mapOf("macroName" to macroName))
                }
                RuleAction.WAIT -> {
                    val ms = (rule.actionParams["ms"] as? Number)?.toLong() ?: return
                    delay(ms)
                }
                RuleAction.NOTIFY -> {
                    val msg = rule.actionParams["message"]?.toString() ?: return
                    ShizukuManager.appContext()?.let { ThinkingOverlay.showTemporary(it, msg, 3000) }
                }
            }
            lastTriggered[rule.id] = System.currentTimeMillis()
            Log.i(TAG, "规则触发: ${rule.name} (${rule.action})")
        } catch (e: Throwable) {
            Log.e(TAG, "规则执行失败: ${rule.name}", e)
        }
    }

    /**
     * 解析并求值条件表达式。
     */
    fun evaluateCondition(condition: String, state: GameState): Boolean {
        val trimmed = condition.trim()
        if (trimmed.isEmpty()) return true
        return trimmed.split("&&").all { part ->
            evaluateSimpleCondition(part.trim(), state)
        }
    }

    private fun evaluateSimpleCondition(expr: String, state: GameState): Boolean {
        val match = COMPARE_REGEX.find(expr)
        if (match == null) {
            // 存在性：customFields 中有该 key 且值非 false
            return state.customFields[expr]?.let { it != false } ?: false
        }
        val field = match.groupValues[1]
        val op = match.groupValues[2]
        val value = match.groupValues[3].toIntOrNull() ?: return false
        val actual = getField(state, field) ?: return false
        return when (op) {
            "<" -> actual < value
            ">" -> actual > value
            "<=" -> actual <= value
            ">=" -> actual >= value
            "==" -> actual == value
            "!=" -> actual != value
            else -> false
        }
    }

    private fun getField(state: GameState, path: String): Int? {
        return when {
            path == "healthPercent" -> state.healthPercent
            path.startsWith("skillCooldowns.") -> state.skillCooldowns[path.removePrefix("skillCooldowns.")]
            path.startsWith("resources.") -> state.resources[path.removePrefix("resources.")]
            else -> (state.customFields[path] as? Number)?.toInt()
        }
    }
}
