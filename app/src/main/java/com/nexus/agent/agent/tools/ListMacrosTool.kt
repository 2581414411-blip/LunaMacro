package com.nexus.agent.agent.tools

import com.nexus.agent.macro.MacroStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * list_macros —— 列出所有已保存的宏。
 *
 * 供 LLM 在回放前查看有哪些可用宏。
 */
object ListMacrosTool : Tool {

    override val name = "list_macros"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override fun execute(args: Map<String, Any?>): String {
        val macros = runBlocking { MacroStore.listAll() }
        if (macros.isEmpty()) return "暂无已保存的宏"

        val arr = JSONArray()
        macros.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("id", m.id)
                    put("name", m.name)
                    put("description", m.description)
                    put("targetPackage", m.targetPackage ?: "")
                    put("stepCount", m.steps.size)
                }
            )
        }
        return arr.toString()
    }
}
