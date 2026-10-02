package com.nexus.agent.agent.tools

import android.util.Log
import org.json.JSONObject

/**
 * click_id —— 按资源 ID 精确点击控件。
 *
 * 通过 viewIdResourceName 精确匹配节点（如 "com.android.settings:id/switch_widget"），
 * 取 bounds 中心注入点击。找不到返回明确错误。
 */
object ClickIdTool : Tool {

    override val name = "click_id"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("resourceId", JSONObject().apply {
                put("type", "string")
                put("description", "控件资源 ID，如 com.android.settings:id/switch_widget")
            })
        })
        put("required", org.json.JSONArray().put("resourceId"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val rid = args["resourceId"]?.toString()?.trim().orEmpty()
        if (rid.isEmpty()) return "错误：缺少 resourceId 参数"

        val matches = ToolSupport.findNodes { node ->
            node.viewIdResourceName?.toString()?.let { id ->
                id == rid || id.endsWith("/$rid") || id.endsWith(rid)
            } == true
        }

        if (matches.isEmpty()) {
            return "未找到资源 ID 为\"$rid\"的控件"
        }

        val found = matches.first()
        val ok = ToolSupport.injectTap(found.centerX, found.centerY)
        return if (ok) {
            Log.i("NexusTool", "click_id 命中 $rid @ (${found.centerX.toInt()}, ${found.centerY.toInt()})")
            "成功点击 $rid @ (${found.centerX.toInt()}, ${found.centerY.toInt()})"
        } else {
            "点击失败：Shizuku 服务不可用"
        }
    }
}
