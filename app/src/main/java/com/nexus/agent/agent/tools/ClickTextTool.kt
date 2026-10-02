package com.nexus.agent.agent.tools

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * click_text —— 按文本点击控件。
 *
 * 从 UI 树找 text 或 contentDescription 包含目标文本的节点（忽略大小写），
 * 取中心坐标注入点击。坐标由工具层从 UI 树解析，LLM 无需算坐标。
 */
object ClickTextTool : Tool {

    override val name = "click_text"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要点击的文本（部分匹配，忽略大小写）")
            })
            put("index", JSONObject().apply {
                put("type", "integer")
                put("description", "多个匹配时指定第几个（从 0 开始），默认 0")
            })
        })
        put("required", org.json.JSONArray().put("text"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val target = args["text"]?.toString()?.trim().orEmpty()
        if (target.isEmpty()) return "错误：缺少 text 参数"

        val index = (args["index"] as? Number)?.toInt() ?: 0

        val matches = ToolSupport.findNodes { node ->
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            (t.contains(target, ignoreCase = true) || d.contains(target, ignoreCase = true))
        }

        if (matches.isEmpty()) {
            return "未找到包含\"$target\"的控件"
        }

        if (index < 0 || index >= matches.size) {
            return "找到 ${matches.size} 个匹配，但 index=$index 越界（有效范围 0..${matches.size - 1}）"
        }

        val found = matches[index]
        val ok = ToolSupport.injectTap(found.centerX, found.centerY)
        val label = found.text.ifBlank { target }
        return if (ok) {
            Log.i("NexusTool", "click_text 命中 \"$label\" @ (${found.centerX.toInt()}, ${found.centerY.toInt()})")
            "成功点击\"$label\" @ (${found.centerX.toInt()}, ${found.centerY.toInt()})"
        } else {
            "点击失败：Shizuku 服务不可用"
        }
    }
}
