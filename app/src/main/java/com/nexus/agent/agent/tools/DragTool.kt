package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * drag —— 精确拖拽。
 *
 * 从 (fromX, fromY) 精确拖到 (toX, toY)，匀速插值，约 10ms 一步。
 * 与 scroll（惯性滑动）不同：drag 用于精确移动控件（如拖动图标、进度条、
 * 拼图滑块等），起点终点精确可控。
 */
object DragTool : Tool {

    override val name = "drag"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("fromX", JSONObject().apply { put("type", "number"); put("description", "起点 X 坐标") })
            put("fromY", JSONObject().apply { put("type", "number"); put("description", "起点 Y 坐标") })
            put("toX", JSONObject().apply { put("type", "number"); put("description", "终点 X 坐标") })
            put("toY", JSONObject().apply { put("type", "number"); put("description", "终点 Y 坐标") })
            put("duration", JSONObject().apply {
                put("type", "integer")
                put("description", "拖拽持续时间毫秒，默认 500")
            })
        })
        put("required", org.json.JSONArray().put("fromX").put("fromY").put("toX").put("toY"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val fromX = (args["fromX"] as? Number)?.toFloat()
        val fromY = (args["fromY"] as? Number)?.toFloat()
        val toX = (args["toX"] as? Number)?.toFloat()
        val toY = (args["toY"] as? Number)?.toFloat()
        val duration = (args["duration"] as? Number)?.toInt() ?: 500

        if (fromX == null || fromY == null || toX == null || toY == null) {
            return "错误：需要 fromX/fromY/toX/toY 四个坐标"
        }

        val ok = ToolSupport.injectDrag(fromX, fromY, toX, toY, duration)
        return if (ok) {
            "已拖拽 (${fromX.toInt()},${fromY.toInt()}) → (${toX.toInt()},${toY.toInt()})，${duration}ms"
        } else {
            "拖拽失败：Shizuku 服务不可用"
        }
    }
}
