package com.nexus.agent.agent.tools

import org.json.JSONArray
import org.json.JSONObject

/**
 * multi_touch —— 多指手势。
 *
 * 参数 points 是一个事件序列，每项含 x、y、action（down/up/move）。
 * 按顺序执行，支持双指缩放、双指点击等多指手势。
 *
 * 示例（双指点击）：
 * [{"x":200,"y":1500,"action":"down"},{"x":800,"y":800,"action":"down"},
 *  {"x":200,"y":1500,"action":"up"},{"x":800,"y":800,"action":"up"}]
 */
object MultiTouchTool : Tool {

    override val name = "multi_touch"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("points", JSONObject().apply {
                put("type", "array")
                put("description", "多指事件序列，每项含 x、y、action(down/up/move)")
            })
        })
        put("required", JSONArray().put("points"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val pointsArg = args["points"] ?: return "错误：缺少 points 参数"

        val list = mutableListOf<String>()
        try {
            when (pointsArg) {
                is JSONArray -> {
                    for (i in 0 until pointsArg.length()) {
                        list.add(pointsArg.getJSONObject(i).toString())
                    }
                }
                is List<*> -> {
                    pointsArg.forEach { item ->
                        when (item) {
                            is Map<*, *> -> {
                                val obj = JSONObject()
                                item.forEach { (k, v) -> obj.put(k.toString(), v) }
                                list.add(obj.toString())
                            }
                            else -> list.add(item.toString())
                        }
                    }
                }
                else -> return "错误：points 参数格式不正确"
            }
        } catch (e: Throwable) {
            return "错误：解析 points 失败：${e.message}"
        }

        if (list.isEmpty()) return "错误：points 为空"

        val ok = ToolSupport.injectMultiTouch(list)
        return if (ok) {
            "多指手势完成（${list.size} 个事件点）"
        } else {
            "多指手势失败：Shizuku 服务不可用"
        }
    }
}
