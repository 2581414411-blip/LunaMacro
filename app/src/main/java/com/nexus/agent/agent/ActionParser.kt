package com.nexus.agent.agent

import android.util.Log
import org.json.JSONObject

/**
 * Agent 动作定义与解析。
 *
 * LLM 被要求返回一个 JSON 动作对象，形如：
 * ```
 * {"thought": "我需要点击搜索框", "action": "click", "x": 630, "y": 1821}
 * ```
 *
 * 支持的动作类型：
 * - click   单击（x, y 为屏幕坐标）
 * - swipe   滑动（x1,y1 起点，x2,y2 终点，duration 毫秒）
 * - input   在聚焦的输入框输入文字（text 为要输入的内容）
 * - launch  直接启动某个应用（text 为包名）
 * - home    回到桌面
 * - back    返回键
 * - finish  任务完成（thought 作为最终答复）
 */
object ActionParser {

    private const val TAG = "ActionParser"

    /** 单步动作 */
    data class Action(
        val type: String,          // click / swipe / input / launch / home / back / finish
        val x: Float = 0f,
        val y: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        val duration: Int = 300,
        val text: String = "",     // input 的文字 / launch 的包名
        val thought: String = ""
    )

    /**
     * 从 LLM 原始输出中解析动作。
     *
     * 容错策略：LLM 可能在 JSON 前后夹带说明文字，先用正则提取
     * 第一个 `{...}` 块，再尝试 JSONObject 解析；失败则退回正则逐字段提取。
     *
     * @param raw LLM 返回的原始文本
     * @return 解析出的动作；无法识别时返回 null
     */
    fun parse(raw: String): Action? {
        val jsonStr = extractJsonBlock(raw) ?: return null

        return try {
            val obj = JSONObject(jsonStr)
            val type = obj.optString("action").lowercase().trim()
            when (type) {
                "click" -> Action(
                    type = "click",
                    x = obj.optDouble("x", 0.0).toFloat(),
                    y = obj.optDouble("y", 0.0).toFloat(),
                    thought = obj.optString("thought", "")
                )
                "swipe" -> Action(
                    type = "swipe",
                    x = obj.optDouble("x1", obj.optDouble("x", 0.0)).toFloat(),
                    y = obj.optDouble("y1", obj.optDouble("y", 0.0)).toFloat(),
                    x2 = obj.optDouble("x2", 0.0).toFloat(),
                    y2 = obj.optDouble("y2", 0.0).toFloat(),
                    duration = obj.optInt("duration", 300),
                    thought = obj.optString("thought", "")
                )
                "input" -> Action(
                    type = "input",
                    text = obj.optString("text", ""),
                    thought = obj.optString("thought", "")
                )
                "launch" -> Action(
                    type = "launch",
                    text = obj.optString("text", ""),
                    thought = obj.optString("thought", "")
                )
                "home" -> Action(type = "home", thought = obj.optString("thought", ""))
                "back" -> Action(type = "back", thought = obj.optString("thought", ""))
                "finish" -> Action(type = "finish", thought = obj.optString("thought", ""))
                else -> {
                    Log.w(TAG, "unknown action type: $type")
                    null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "JSON parse failed, trying regex fallback", e)
            parseFallback(jsonStr)
        }
    }

    /**
     * 提取原始文本中第一个 `{...}` JSON 块。
     */
    private fun extractJsonBlock(raw: String): String? {
        // 优先找 ```json ... ``` 代码块
        val fenced = Regex("```(?:json)?\\s*(\\{.*?\\})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.getOrNull(1)
        if (fenced != null) return fenced

        // 否则找第一个 { 到最后一个 } 之间的内容
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) {
            return raw.substring(start, end + 1)
        }
        return null
    }

    /**
     * 正则逐字段兜底解析。
     */
    private fun parseFallback(json: String): Action? {
        val type = field(json, "action") ?: return null
        return when (type.lowercase()) {
            "click" -> Action(
                type = "click",
                x = field(json, "x")?.toFloatOrNull() ?: 0f,
                y = field(json, "y")?.toFloatOrNull() ?: 0f,
                thought = field(json, "thought") ?: ""
            )
            "swipe" -> Action(
                type = "swipe",
                x = field(json, "x1")?.toFloatOrNull() ?: field(json, "x")?.toFloatOrNull() ?: 0f,
                y = field(json, "y1")?.toFloatOrNull() ?: field(json, "y")?.toFloatOrNull() ?: 0f,
                x2 = field(json, "x2")?.toFloatOrNull() ?: 0f,
                y2 = field(json, "y2")?.toFloatOrNull() ?: 0f,
                duration = field(json, "duration")?.toIntOrNull() ?: 300,
                thought = field(json, "thought") ?: ""
            )
            "back" -> Action(type = "back", thought = field(json, "thought") ?: "")
            "input" -> Action(type = "input", text = field(json, "text") ?: "", thought = field(json, "thought") ?: "")
            "launch" -> Action(type = "launch", text = field(json, "text") ?: "", thought = field(json, "thought") ?: "")
            "home" -> Action(type = "home", thought = field(json, "thought") ?: "")
            "finish" -> Action(type = "finish", thought = field(json, "thought") ?: "")
            else -> null
        }
    }

    /**
     * 提取 JSON 中某个字符串/数字字段的值（去引号）。
     */
    private fun field(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\"|-?\\d+(?:\\.\\d+)?|true|false)")
        return regex.find(json)?.groupValues?.getOrNull(1)?.trim('"')
    }
}
