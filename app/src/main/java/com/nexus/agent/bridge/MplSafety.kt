package com.nexus.agent.bridge

import android.content.Context
import com.nexus.agent.safety.SafetyPolicy

/**
 * MPL 请求的服务端风控（协议 §3）。
 *
 * ## 为什么不信任客户端报的 `risk`
 *
 * 协议 §3 写得很直白：**宏月按「action + args + 当前前台」自己重算，取更严者。**
 * 理由不是"不信任 MPL 这个作者"，而是"信任链上多一个环节就多一处可被绕过的地方"——
 * 提示注入、模型幻觉、版本错配，任何一个都可能让对面报出 `risk: "safe"`。
 * 而 `reason` 字段只进日志和弹窗文案，**不参与任何判断**。
 *
 * ## 为什么这里不碰记忆
 *
 * 协议 §0 第 4 条：记忆只由 MPL 写。桥接层执行的每个动作都**不**进
 * `LocalDigitalBeing.recordTurn` —— 否则"她"会记得一堆自己没做过、只是别人让她做的事。
 */
object MplSafety {

    /** 被风控拒绝：带协议错误码，路由器直接转成响应 */
    class Denied(
        val code: String,
        override val message: String,
        val reason: String? = null,
        val retryAfterMs: Long? = null
    ) : Exception(message)

    /** action → 宏月工具名。0.9.31 只开到 open_app，其余为 0.9.32 预留 */
    private fun toolOf(action: String): String = when (action) {
        "read_screen" -> "read_screen"
        "list_macros" -> "list_macros"
        "open_app" -> "open_app"
        "click_text" -> "click_text"
        "input_text" -> "input_text"
        "run_macro" -> "play_macro"
        else -> action
    }

    /**
     * 执行前判定。通过返回 Unit，否则抛 [Denied]。
     *
     * 顺序：总闸 → 敏感界面/否决词 → 确认要求。
     * **总闸放最前**：关掉自动化就是"一点都不能动"，不该先算一遍风险再告诉用户"其实我能做"。
     */
    fun check(ctx: Context, action: String, args: Map<String, Any?>, confirm: Boolean, foreground: String?) {
        // 1. 自动化总闸（设置 → 自动化）：关掉时连只读也不做
        if (!com.nexus.agent.system.AutomationPolicy.allowsAction(ctx)) {
            throw Denied(MplProtocol.Code.FORBIDDEN, "自动化工具已关闭，这次没有执行")
        }

        // 2. 只读动作：只判敏感界面（读屏要脱敏，不当错误）
        if (MplProtocol.isReadOnly(action)) {
            if (action == "open_app") {
                denyIfForbidden(ctx, toolOf(action), args, foreground)
            }
            return
        }

        // 3. 写动作：完整风险判定 + 确认要求
        denyIfForbidden(ctx, toolOf(action), args, foreground)
        if (!confirm) {
            throw Denied(MplProtocol.Code.NEEDS_CONFIRM, "这个动作会动到屏幕上的东西，需要先确认")
        }
    }

    private fun denyIfForbidden(ctx: Context, tool: String, args: Map<String, Any?>, foreground: String?) {
        val sensitive = SafetyPolicy.isSensitiveUi(ctx, foreground)
        val verdict = SafetyPolicy.assess(tool, args, foreground, sensitive)
        if (verdict.risk == SafetyPolicy.Risk.FORBIDDEN) {
            throw Denied(MplProtocol.Code.FORBIDDEN, verdict.reason)
        }
    }

    /** 敏感界面下的读屏：返回 true 表示要脱敏（`ok:true` + `redacted:true`，不报错） */
    fun shouldRedact(ctx: Context, foreground: String?): Boolean =
        SafetyPolicy.isSensitiveUi(ctx, foreground)

    /** 无障碍没绑定 / 锁屏 时统一从这里出错误码，客户端按 `reason` 给不同文案 */
    fun permissionDenied(reason: String, message: String): Denied =
        Denied(MplProtocol.Code.PERMISSION_DENIED, message, reason)
}
