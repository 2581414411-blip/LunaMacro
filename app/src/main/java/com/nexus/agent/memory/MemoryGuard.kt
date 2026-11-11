package com.nexus.agent.memory

import com.nexus.agent.data.db.ActionMemory

/**
 * 回放前的最后一道闸（0.9.13）。
 *
 * 这一层不判断「用户想不想」，那由用户那句话决定；它判断的是
 * **「这东西能不能被自动重放」**。两者不是一回事：
 * 用户说「帮我把购物车清一下」是明确意图，但把「删除」录成肌肉记忆、
 * 下次在相似界面上自动点一次，就是事故。
 */
object MemoryGuard {

    /**
     * 允许进入肌肉记忆的工具白名单。
     *
     * 只收「导航类」动作：看得见、可撤销、不产生对外副作用。
     * 刻意排除：
     *  - `input_text`：录下来的是**用户当时打的字**，很可能是密码、验证码、
     *    收货地址、聊天内容。重放别人的输入内容既危险又没意义（每次要输的不一样）；
     *  - `schedule` / `web_search` / `start_game_auto`：会拉起后台任务或产生外部请求；
     *  - `multi_touch` / `play_macro`：会把别的宏/多指手势卷进来，路径不可控。
     */
    private val REPLAYABLE_TOOLS = setOf(
        "click_text", "click_id", "scroll", "press_back", "press_home",
        "wait", "open_app", "long_press", "double_tap", "drag"
    )

    /**
     * 一票否决词。
     *
     * 与 0.9.12 习惯挖掘那份是同一套判据，但这里拦的是**动作参数和任务原话**：
     * 「立即购买」这种按钮可能出现在任何购物 App 里，那些应用不在免采名单内。
     * 一个会自动点「立即购买」的助手不是助手，是账单。
     */
    private val BLOCK_WORDS = listOf(
        "支付", "付款", "转账", "汇款", "红包", "充值", "提现", "打赏",
        "购买", "下单", "结算", "提交订单", "确认订单", "立即抢购",
        "卸载", "注销", "解绑", "恢复出厂", "格式化", "清空数据", "删除账号",
        "退出登录", "退出帐号", "退出账号", "删除聊天", "撤回",
        "密码", "验证码", "身份证", "银行卡"
    )

    /** 会改动内容、但撤销得掉的动作：允许回放，日志里标红 */
    private val CAUTION_WORDS = listOf(
        "发送", "发布", "分享", "提交", "删除", "移除", "退订",
        "拉黑", "举报", "拨号", "呼叫", "群发", "评论", "点赞", "关注"
    )

    /** 这些工具出现在轨迹里，这条记忆就永远不能被自动重放 */
    fun hasUnreplayableStep(steps: List<ActionStep>): Boolean =
        steps.any { it.tool !in REPLAYABLE_TOOLS }

    /** 轨迹里出现一票否决词 */
    fun isBlocked(steps: List<ActionStep>): Boolean =
        steps.any { s -> BLOCK_WORDS.any { w -> haystackOf(s).contains(w) } }

    /** 任务原话里出现一票否决词（整条不拦，交给上层安全策略） */
    fun taskBlocked(task: String): Boolean =
        task.isNotBlank() && BLOCK_WORDS.any { task.contains(it) }

    /** 这次回放里有没有需要提醒用户「这是会改东西的一步」 */
    fun isCaution(steps: List<ActionStep>): Boolean =
        steps.any { s -> CAUTION_WORDS.any { w -> haystackOf(s).contains(w) } }

    /** 找出第一处需要提醒的步骤文字（日志与弹窗用） */
    fun cautionHit(steps: List<ActionStep>): String {
        for (s in steps) {
            if (CAUTION_WORDS.any { w -> haystackOf(s).contains(w) }) return haystackOf(s).trim()
        }
        return ""
    }

    /**
     * 判定一条轨迹能不能变成可回放记忆。
     *
     * @return 空串 = 可以；非空 = 拒绝原因（会写进日志，便于排查「为什么它没学会」）
     */
    fun rejectReason(steps: List<ActionStep>): String = when {
        steps.isEmpty() -> "轨迹为空"
        steps.size > ActionMemory.MAX_STEPS -> "步骤 ${steps.size} 步，超过 ${ActionMemory.MAX_STEPS} 步上限"
        isBlocked(steps) -> "命中一票否决词（支付/账号/密码类）"
        hasUnreplayableStep(steps) -> "含不可回放动作（输入文字/外部请求/多指手势）"
        else -> ""
    }

    private fun haystackOf(step: ActionStep): String {
        if (step.args.isEmpty()) return ""
        val sb = StringBuilder()
        for (v in step.args.values) sb.append(v).append(' ')
        return sb.toString()
    }
}
