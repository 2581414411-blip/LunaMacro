package com.nexus.agent.agent

/**
 * 子目标（Sub-goal）—— 分层执行的核心单位。
 *
 * 与旧的「原子步骤」区别：
 * - 原子步骤：`点击 WLAN`、`点击 WLAN 第一个开关` —— 云端要把每一步都想好，
 *   任何界面变化都会让后续计划全错，长流程必然崩。
 * - 子目标：`打开 WLAN 并连上已知网络` —— 云端只说"要什么"，具体点哪儿、
 *   点几次、遇到弹窗怎么处理，全部由本地执行器在循环里自己看图决定。
 *
 * 这样云端 token 消耗大幅下降（一个任务 3~5 个子目标 vs 8~20 个原子步），
 * 而且界面变了不需要重规划 —— 本地执行器会自己找路。
 *
 * @param description 子目标描述（自然语言，如「打开委托界面」）
 * @param successCriteria 达成标准（如「看到每日委托列表」），交给执行器自检
 * @param targetState 期望到达的状态机状态 id（可选，命中即可视为达成）
 */
data class SubGoal(
    val description: String,
    val successCriteria: String = "",
    val targetState: String = ""
) {
    /** 给日志/审计用的一行描述 */
    fun brief(): String =
        if (successCriteria.isBlank()) description else "$description（达成标准：$successCriteria）"
}
