package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * press_home —— 按 Home 键回到桌面。
 *
 * 通过 Shizuku execCommand 发送 `input keyevent 3`（KEYCODE_HOME）。
 */
object PressHomeTool : Tool {

    override val name = "press_home"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override fun execute(args: Map<String, Any?>): String {
        val out = ToolSupport.execCommand("input keyevent 3")
        return if (out != null) "已回到桌面" else "回到桌面失败：Shizuku 服务不可用"
    }
}
