package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * long_press —— 长按控件或坐标。
 *
 * 通过 text 定位控件（或直接用 x/y 坐标），注入 ACTION_DOWN 后保持
 * duration 毫秒再 ACTION_UP，用于触发长按菜单、上下文菜单等。
 */
object LongPressTool : Tool {

    override val name = "long_press"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要长按的文本（部分匹配，忽略大小写）")
            })
            put("x", JSONObject().apply {
                put("type", "number")
                put("description", "长按 X 坐标（未指定 text 时用）")
            })
            put("y", JSONObject().apply {
                put("type", "number")
                put("description", "长按 Y 坐标（未指定 text 时用）")
            })
            put("duration", JSONObject().apply {
                put("type", "integer")
                put("description", "按压时长毫秒，默认 800")
            })
        })
    }

    override fun execute(args: Map<String, Any?>): String {
        val duration = (args["duration"] as? Number)?.toInt() ?: 800

        var x: Float
        var y: Float
        val text = args["text"]?.toString()?.trim().orEmpty()

        if (text.isNotEmpty()) {
            val matches = ToolSupport.findNodes { node ->
                val t = node.text?.toString().orEmpty()
                val d = node.contentDescription?.toString().orEmpty()
                t.contains(text, ignoreCase = true) || d.contains(text, ignoreCase = true)
            }
            if (matches.isEmpty()) return "未找到包含\"$text\"的控件"
            x = matches.first().centerX
            y = matches.first().centerY
        } else {
            val px = (args["x"] as? Number)?.toFloat()
            val py = (args["y"] as? Number)?.toFloat()
            if (px == null || py == null) return "错误：需要 text 或 x/y 坐标"
            x = px
            y = py
        }

        val ok = ToolSupport.injectLongPress(x, y, duration)
        return if (ok) {
            "长按 ${duration}ms @ (${x.toInt()}, ${y.toInt()})"
        } else {
            "长按失败：Shizuku 服务不可用"
        }
    }
}
