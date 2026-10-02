package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * double_tap —— 双击控件或坐标。
 *
 * 通过 text 定位控件（或直接用 x/y 坐标），连续两次 tap，间隔 100ms。
 * 用于触发双击放大、双击点赞等场景。
 */
object DoubleTapTool : Tool {

    override val name = "double_tap"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要双击的文本（部分匹配，忽略大小写）")
            })
            put("x", JSONObject().apply {
                put("type", "number")
                put("description", "双击 X 坐标（未指定 text 时用）")
            })
            put("y", JSONObject().apply {
                put("type", "number")
                put("description", "双击 Y 坐标（未指定 text 时用）")
            })
        })
    }

    override fun execute(args: Map<String, Any?>): String {
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

        // 第一次 tap
        if (!ToolSupport.injectTap(x, y)) return "双击失败：Shizuku 服务不可用"
        // 间隔 100ms
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // 第二次 tap
        if (!ToolSupport.injectTap(x, y)) return "双击失败：第二次点击失败"

        return "双击 @ (${x.toInt()}, ${y.toInt()})"
    }
}
