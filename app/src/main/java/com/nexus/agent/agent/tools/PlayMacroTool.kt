package com.nexus.agent.agent.tools

import com.nexus.agent.macro.MacroPlayer
import com.nexus.agent.macro.MacroStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * play_macro —— 回放一个已保存的宏。
 *
 * 让 LLM 在「重复性游戏操作」等场景下，直接回放预设动作序列，
 * 而不是逐步推理每一步操作。
 */
object PlayMacroTool : Tool {

    override val name = "play_macro"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("macroName", JSONObject().apply {
                put("type", "string")
                put("description", "要回放的宏名称")
            })
            put("repeatCount", JSONObject().apply {
                put("type", "integer")
                put("description", "重复回放次数，默认 1，最大 100")
            })
        })
        put("required", JSONArray().put("macroName"))
    }

    override fun execute(args: Map<String, Any?>): String {
        val name = args["macroName"]?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return "错误：缺少 macroName 参数"

        val repeat = (args["repeatCount"] as? Number)?.toInt() ?: 1
        val count = repeat.coerceIn(1, 100)

        val macro = runBlocking { MacroStore.findByName(name) }
            ?: return "未找到名为\"$name\"的宏"

        val results = mutableListOf<String>()
        for (i in 1..count) {
            val r = runBlocking { MacroPlayer.play(macro) }
            results.add(r.summary())
            if (r.aborted) break
        }

        return if (count == 1) {
            "宏\"${macro.name}\"：${results.first()}"
        } else {
            "宏\"${macro.name}\"重复 $count 次：${results.joinToString("；")}"
        }
    }
}
