package com.nexus.agent.agent.tools

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * wait —— 等待指定毫秒数，用于界面加载。
 *
 * ms 默认 1000，上限 5000（避免 LLM 滥用过长等待拖慢流程）。
 */
object WaitTool : Tool {

    override val name = "wait"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("ms", JSONObject().apply {
                put("type", "integer")
                put("description", "等待毫秒数，默认 1000，上限 5000")
            })
        })
    }

    override fun execute(args: Map<String, Any?>): String {
        val ms = ((args["ms"] as? Number)?.toLong() ?: 1000L).coerceIn(0L, 5000L)
        // 注意：execute 在协程上下文（IO）里被调用时可安全 delay；
        // 若在非协程环境被调用，delay 是挂起函数会导致编译期要求 suspend。
        // 这里用 runBlocking 兜底，确保任意上下文都能等待。
        kotlinx.coroutines.runBlocking { delay(ms) }
        return "已等待 ${ms}ms"
    }
}
