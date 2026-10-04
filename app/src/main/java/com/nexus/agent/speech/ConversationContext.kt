package com.nexus.agent.speech

/**
 * 语音会话上下文（多轮对话记忆）。
 *
 * 记录最近 N 轮「用户指令 → 执行结果」，供：
 * 1. 指令澄清：把「再打开一个」这类指代改写成完整指令
 * 2. Planner / Executor 的 prompt 注入历史，让 Agent 理解上下文
 */
data class Turn(
    val timestamp: Long,
    val userCommand: String,
    val agentResult: String,
    val success: Boolean
)

object ConversationContext {

    /** 历史轮数上限（超过则移除最旧） */
    private const val MAX_TURNS = 10

    private val turns = mutableListOf<Turn>()

    /** 追加一轮对话（超上限则移除最旧） */
    fun addTurn(userCommand: String, agentResult: String, success: Boolean) {
        turns.add(Turn(System.currentTimeMillis(), userCommand, agentResult, success))
        if (turns.size > MAX_TURNS) {
            turns.removeAt(0)
        }
    }

    /** 取最近 n 轮（默认 5） */
    fun getRecentTurns(n: Int = 5): List<Turn> = turns.takeLast(n)

    /**
     * 格式化为历史文本块（注入 prompt 用）。空历史返回空字符串。
     *
     * 格式：
     * 【本次会话历史】
     * 1. 用户：打开设置 → 结果：成功打开设置应用
     * 2. 用户：把亮度调到50% → 结果：已调整亮度
     */
    fun toPromptBlock(): String {
        if (turns.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【本次会话历史】\n")
        getRecentTurns(5).forEachIndexed { i, turn ->
            sb.append("${i + 1}. 用户：${turn.userCommand} → 结果：${turn.agentResult}\n")
        }
        return sb.toString().trimEnd()
    }

    /** 清空历史（会话结束） */
    fun clear() {
        turns.clear()
    }
}
