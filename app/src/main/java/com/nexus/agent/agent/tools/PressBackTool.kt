package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * press_back —— 按返回键。
 *
 * 通过 Shizuku execCommand 发送 `input keyevent 4`（KEYCODE_BACK）。
 */
object PressBackTool : Tool {

    override val name = "press_back"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override fun execute(args: Map<String, Any?>): String {
        val out = ToolSupport.execCommand("input keyevent 4")
        return if (out != null) "已按返回键" else "返回键失败：Shizuku 服务不可用"
    }
}
