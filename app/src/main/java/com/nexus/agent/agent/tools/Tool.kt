package com.nexus.agent.agent.tools

import org.json.JSONObject

/**
 * 语义化工具统一接口。
 *
 * 与早期"像素级动作"（tap(x,y)）不同，语义化工具让 LLM 用
 * 「文本 / 资源ID / 应用名」等高层意图来操作，坐标由工具层从
 * UI 树自动解析，LLM 不再自己算坐标。
 *
 * 每个工具：
 * - [name] 工具名（LLM 调用时引用）
 * - [schema] 参数 JSON Schema（供 system prompt 描述）
 * - [execute] 执行并返回结果字符串（含成功/失败/候选等明确信息）
 */
interface Tool {

    /** 工具名，如 "click_text" */
    val name: String

    /** 参数 JSON Schema（JSONObject 形式，供 PromptBuilder 拼装） */
    val schema: JSONObject

    /**
     * 执行工具。
     *
     * @param args 调用参数（键值对）
     * @return 执行结果描述（成功含坐标/包名等，失败含明确原因）
     */
    fun execute(args: Map<String, Any?>): String
}
