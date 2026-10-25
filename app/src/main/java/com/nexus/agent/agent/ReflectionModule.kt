package com.nexus.agent.agent

import android.util.Log
import com.nexus.agent.agent.state.TaskStateMachine
import kotlinx.coroutines.delay

/**
 * 反思模块（Reflection）。
 *
 * 两层作用：
 * 1. **行动前一致性检查（TAC Check，Thought-Action Consistency）**
 *    在工具真正执行前，用规则判断「这个动作在当前屏幕上合不合理」。
 *    典型拦截：屏幕上根本没有要点的文字、前台已经跑到别的应用、
 *    同一个无效动作连点两次、界面含支付/密码类敏感内容。
 *    这一步是纯本地规则，零延迟零成本 —— 比调用模型反思快几个数量级。
 *
 * 2. **异常状态恢复**
 *    状态机判定异常（网络异常界面 / 用户切走应用 / 连续动作不推进）时：
 *    a) 回滚：返回 → 再返回 → 回主页并重新打开目标应用，尝试回到稳定态
 *    b) 报告：通过悬浮窗告诉用户「任务遇到意外，正在尝试恢复…」
 *    c) 失败：返回 false，由 AgentRunner 把上下文发回云端重新规划
 */
object ReflectionModule {

    private const val TAG = "Reflection"

    /** 敏感界面关键词（第二道防线，SafetyPolicy 之外再兜一次） */
    private val SENSITIVE_WORDS = listOf(
        "支付", "密码", "验证码", "转账", "银行卡", "充值", "购买",
        "指纹", "面容", "付款", "确认支付", "免密"
    )

    /** 恢复时允许使用的工具（只做「退」和「重进」，绝不乱点） */
    private val RECOVERY_TOOLS = setOf(
        "press_back", "press_home", "open_app", "wait"
    )

    /** 一次动作的上下文（TAC 检查输入） */
    data class ActionContext(
        val tool: String,
        val args: Map<String, Any?>,
        val goal: SubGoal?,
        val visibleTexts: List<String>,
        val elementCount: Int,
        val packageName: String?,
        val state: TaskStateMachine.Snapshot?,
        /** 最近执行过的动作 key（[actionKey] 生成） */
        val recentActions: List<String>
    )

    /** TAC 检查结论 */
    data class Verdict(
        val allow: Boolean,
        val reason: String = "",
        val suggestion: String = ""
    ) {
        companion object {
            val ALLOW = Verdict(true)
        }
    }

    /** 动作签名：工具名 + 排序后的参数，用于识别「重复同一个动作」 */
    fun actionKey(tool: String, args: Map<String, Any?>): String =
        tool.lowercase() + ":" + args.entries
            .sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }

    /**
     * 执行前的思考-行动一致性检查。
     *
     * 只有明确不合理才拦截；拿不准一律放行 —— 反思模块不能成为新的失败点。
     */
    fun preActionCheck(ctx: ActionContext): Verdict {
        val tool = ctx.tool.lowercase()

        // 1. 前台已经不在目标应用（用户切走了/游戏被杀）→ 别在这个界面上点
        val st = ctx.state
        if (st != null && st.foreign &&
            tool !in setOf("open_app", "press_home", "press_back", "wait", "finish")
        ) {
            return Verdict(
                allow = false,
                reason = "当前前台是外部应用（${st.stateName}），不是任务目标界面",
                suggestion = "先用 open_app 回到目标应用再操作"
            )
        }

        // 2. 敏感界面：绝不在支付/密码界面点任何东西
        if (tool in setOf("click_text", "tap", "click_id", "long_press") &&
            SENSITIVE_WORDS.any { w -> ctx.visibleTexts.any { it.contains(w) } }
        ) {
            return Verdict(
                allow = false,
                reason = "当前界面含支付/密码/验证码内容，禁止自动操作",
                suggestion = "立即 finish 并把界面交给用户手动处理"
            )
        }

        // 3. click_text 的目标根本不在屏幕上 → 点了也是白点
        if (tool == "click_text") {
            val target = ctx.args["text"]?.toString()?.trim().orEmpty()
            if (target.isNotEmpty() && ctx.visibleTexts.isNotEmpty()) {
                val hit = ctx.visibleTexts.any { it.contains(target) || target.contains(it) }
                if (!hit) {
                    return Verdict(
                        allow = false,
                        reason = "屏幕上找不到「$target」",
                        suggestion = "先 scroll 往下找，或 press_back 回上一层换入口"
                    )
                }
            }
        }

        // 4. 坐标类工具：参数必须有效
        if (tool in setOf("tap", "long_press", "double_tap")) {
            val x = (ctx.args["x"] as? Number)?.toDouble()
            val y = (ctx.args["y"] as? Number)?.toDouble()
            if (x == null || y == null || x < 0 || y < 0) {
                return Verdict(
                    allow = false,
                    reason = "$tool 坐标缺失或无效（x=$x, y=$y）",
                    suggestion = "重新观察屏幕后给出有效坐标"
                )
            }
        }

        // 5. 连续两次同一个动作 → 说明没生效，换策略
        if (tool !in setOf("wait", "scroll") && ctx.recentActions.size >= 2) {
            val key = actionKey(ctx.tool, ctx.args)
            val tail = ctx.recentActions.takeLast(2)
            if (tail.all { it == key }) {
                return Verdict(
                    allow = false,
                    reason = "连续执行了两次同样的动作且没有推进",
                    suggestion = "换完全不同的做法：滚动找 / 返回上层 / 换入口"
                )
            }
        }

        return Verdict.ALLOW
    }

    /**
     * 异常恢复：尝试回到稳定状态。
     *
     * 顺序按代价从小到大：返回 → 再返回 → 回主页 + 重新打开目标应用。
     * 每步之后都重新识别一次状态，一旦脱离异常立即返回成功。
     *
     * @param executeTool 执行工具（由 Executor 传入，内部已含 SafetyPolicy 拦截）
     * @param refreshState 重新识别状态
     * @param onLog 详细日志（任务面板）
     * @param onNotify 悬浮窗短提示
     * @return true = 已恢复；false = 恢复失败（调用方应请求重规划）
     */
    suspend fun recover(
        packageName: String?,
        appLabel: String?,
        executeTool: suspend (String, Map<String, Any?>) -> String,
        refreshState: suspend () -> TaskStateMachine.Snapshot?,
        onLog: (suspend (String) -> Unit)? = null,
        onNotify: ((String) -> Unit)? = null
    ): Boolean {
        onNotify?.invoke("任务遇到意外，正在尝试恢复…")
        onLog?.invoke("  🔄 进入异常恢复流程（回滚到稳定界面）")
        Log.w(TAG, "开始恢复：pkg=$packageName app=$appLabel")

        val plan = TaskStateMachine.recoveryPlan(packageName, appLabel)
        for ((i, step) in plan.withIndex()) {
            val (tool, args) = step
            if (tool !in RECOVERY_TOOLS) continue
            onLog?.invoke("  恢复动作 ${i + 1}/${plan.size}：$tool${if (args.isEmpty()) "" else " $args"}")
            try {
                executeTool(tool, args)
            } catch (e: Throwable) {
                Log.w(TAG, "恢复动作 $tool 失败：${e.message}")
            }
            delay(1_200)

            val snap = refreshState()
            val recovered = snap != null && !snap.abnormal && !snap.unknown && !snap.foreign
            if (recovered) {
                onLog?.invoke("  ✅ 已恢复到「${snap!!.stateName}」")
                TaskStateMachine.clearAbnormal()
                onNotify?.invoke("已恢复，继续执行")
                return true
            }
            onLog?.invoke("  仍在异常界面（${snap?.stateName ?: "未知"}），继续下一步恢复动作")
        }

        onLog?.invoke("  ❌ 自动恢复失败，需要重新规划")
        onNotify?.invoke("恢复失败，正在请求重新规划…")
        TaskStateMachine.markAbnormal("自动恢复失败")
        return false
    }
}
