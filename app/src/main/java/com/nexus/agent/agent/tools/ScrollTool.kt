package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * scroll —— 按方向滑动屏幕。
 *
 * distance 表示滑动距离占屏幕尺寸的比例（默认 0.5）。
 * 起止点按方向 + 屏幕中心计算：
 * - up：从下方 60% 滑到上方 40%（手指向上滑，内容向上滚）
 * - down：反向
 * - left / right：水平方向
 */
object ScrollTool : Tool {

    override val name = "scroll"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("direction", JSONObject().apply {
                put("type", "string")
                put("enum", org.json.JSONArray().put("up").put("down").put("left").put("right"))
                put("description", "滑动方向")
            })
            put("distance", JSONObject().apply {
                put("type", "number")
                put("description", "滑动距离占屏幕比例，默认 0.5")
            })
        })
        put("required", org.json.JSONArray().put("direction"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val dir = args["direction"]?.toString()?.lowercase()?.trim() ?: "up"
        val ratio = (args["distance"] as? Number)?.toFloat() ?: 0.5f
        val ratioSafe = ratio.coerceIn(0.1f, 0.9f)

        val size = ToolSupport.screenSize()
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = size.width * ratioSafe
        val dy = size.height * ratioSafe

        var x1 = cx; var y1 = cy; var x2 = cx; var y2 = cy
        when (dir) {
            "up" -> { y1 = cy + dy / 2; y2 = cy - dy / 2 }
            "down" -> { y1 = cy - dy / 2; y2 = cy + dy / 2 }
            "left" -> { x1 = cx + dx / 2; x2 = cx - dx / 2 }
            "right" -> { x1 = cx - dx / 2; x2 = cx + dx / 2 }
            else -> return "错误：未知方向 $dir（支持 up/down/left/right）"
        }

        val ok = ToolSupport.injectSwipe(x1, y1, x2, y2, 300)
        return if (ok) "已向 $dir 滑动" else "滑动失败：Shizuku 服务不可用"
    }
}
