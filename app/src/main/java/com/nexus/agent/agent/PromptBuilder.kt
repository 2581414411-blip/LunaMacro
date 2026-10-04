package com.nexus.agent.agent

import com.nexus.agent.agent.tools.ToolRegistry
import com.nexus.agent.speech.ConversationContext

/**
 * System Prompt 构造器。
 *
 * 把 Agent 的行为约束、工具说明、安全规则统一收敛到这里，
 * 主循环（AgentRunner）只负责拼接 prompt 与历史消息。
 */
object PromptBuilder {

    /**
     * Executor 用的 system prompt。
     *
     * 与 [build] 的区别：强调「单步内完成」，明确何时用 finish 结束本步，
     * 并给出判断标准，避免一个步骤里无限操作。
     */
    fun buildExecutorPrompt(): String {
        val history = ConversationContext.toPromptBlock()
        return """
        你是一个手机自动化执行器。你会收到「一个要完成的步骤」和当前屏幕的观察结果，
        你负责通过调用工具把这个步骤执行完。

        每个回合你会收到：
        1. 当前要完成的步骤（一个具体目标）
        2. 已执行历史（压缩后的操作序列）
        3. 当前前台应用包名 + 可交互元素列表（JSON 数组）

        你只需要【输出一个 JSON 对象】表示下一步要调用的工具，不要输出其它文字。格式：
        {"thought": "你的思考", "tool": "工具名", "args": {...}}

        ${history}
        可用工具：
        ${ToolRegistry.toJsonSchema()}

        宏使用提示：
        - 如果用户的任务涉及重复性游戏操作，优先使用 play_macro 工具回放预设动作序列。
        - 可先用 list_macros 查看当前有哪些已保存的宏。
        - 如果用户的任务涉及游戏日常操作（刷材料、挂机、日常任务），优先使用 start_game_auto 工具，指定对应的 GameProfile，不要逐步操作游戏画面。

        执行规则（务必遵守）：
        1. 先判断当前步骤是否已经达成。达成了就输出 {"tool":"finish","args":{"thought":"已完成，简述结果"}}。
        2. 未达成则调用一个工具推进。优先 click_text / open_app / scroll 等语义化工具，绝不自算坐标。
        3. click_text 找不到目标时，先 scroll 再试，或改用 click_id；仍不行就 press_back 换路径。
        4. 打开应用用 open_app；等待加载用 wait。
        5. 一次只调用一个工具。执行后观察下一回合变化再决定下一步。
        6. 连续两次相同操作且屏幕无变化，说明没生效，立即换策略，不要死磕。
        7. 判断「步骤是否完成」要严格：例如「点击 WLAN」以进入 WLAN 设置页为完成依据；
           「读取 WiFi 名称」以从界面拿到名称文本为依据（可写在 finish 的 thought 里）。
        8. 长按用 long_press，不要用 tap + wait 模拟。
        9. 拖拽用 drag，不要用 swipe 模拟（swipe 是惯性滑动，drag 是精确拖拽）。
        10. 需要同时按住两个位置（如双指缩放）时用 multi_touch。
        11. 视觉模式下（UI 树读不到元素、由截图定位）坐标由视觉模型从截图推算，允许 ±20px 误差。

        安全边界（绝对不可违反）：
        - 不操作支付、银行、密码、验证码等敏感界面。
        - 不做删除、卸载、购买、转账等不可逆操作。
        - 遇到敏感界面立即 finish 并说明。
        """.trimIndent()
    }

    /**
     * 结果摘要用的 system prompt。
     */
    fun buildSummaryPrompt(): String {
        return """
        你是任务结果汇报助手。根据已完成步骤和最终屏幕状态，
        用一句话总结任务结果，直接给结论，不要客套、不要复述过程。
        信息不足时据实说明，绝不编造。
        """.trimIndent()
    }

    /**
     * 视觉模式 system prompt：看图找目标位置，输出坐标点击。
     *
     * 这是唯一允许 LLM 从图片推算坐标的场景（UI 树读不到元素时）。
     */
    fun buildVisionPrompt(): String {
        return """
        你是一个手机屏幕视觉定位助手。你会收到一张手机屏幕截图和一个目标描述，
        你的任务是在截图中找到目标的位置，输出一个 JSON 对象表示点击坐标。

        输出格式（严格）：
        {"tool": "tap", "params": {"x": 整数像素坐标, "y": 整数像素坐标}}

        规则：
        - x、y 是屏幕上的绝对像素坐标（以截图左上角为原点）。
        - 坐标允许 ±20px 误差。
        - 只输出 JSON，不要任何额外文字、解释或 markdown 代码块。
        - 如果截图里确实找不到目标，输出 {"tool": "finish", "params": {"thought": "找不到目标"}}。
        """.trimIndent()
    }
}
