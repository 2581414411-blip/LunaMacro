package com.nexus.agent.safety

import android.content.Context
import android.util.Log

/**
 * 危险操作安全策略。
 *
 * 两层拦截：
 * 1. 任务文本级 [checkTaskText] —— 拦截「卸载/删除/格式化/转账/支付」等危险意图
 * 2. 工具级 [checkToolCall] —— 拦截 open_app 打开银行/支付/证券类应用
 *
 * 开关默认开启，高级用户可在「设置 → 安全策略」关闭（需二次确认）。
 * 拦截结果用 [BlockResult] 返回结构化信息，并写结构化日志。
 */
object SafetyPolicy {

    private const val TAG = "SafetyPolicy"
    private const val PREFS = "nexus_safety"
    private const val KEY_ENABLED = "enabled"

    /** 拦截统一提示文案 */
    const val BLOCKED_MSG = "该操作已被安全策略拦截"

    /** 危险应用关键词（open_app 目标命中即拦截） */
    private val DANGEROUS_APP_KEYWORDS = listOf(
        "银行", "支付", "云闪付", "证券", "股票", "基金", "理财", "保险",
        "贷款", "金融", "借贷", "钱包", "支付宝", "财付通", "同花顺", "东方财富"
    )

    /** 敏感任务文本（任务意图命中即拦截） */
    private val SENSITIVE_TASK_PATTERNS = listOf(
        Regex("卸载|禁用|停用|删除|格式化|恢复出厂|清除.{0,6}数据|清空"),
        Regex("转账|付款|汇款|充值|提现|支付|还款|借款|贷款"),
        Regex("修改.{0,6}(密码|账户|账号)|重置.{0,6}密码"),
        Regex("下单|购买|付款码|收款码|免密支付")
    )

    /** 安全策略是否开启（默认开启） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 任务文本级拦截。命中返回 [BlockResult]，否则返回 blocked=false。
     */
    fun checkTaskText(task: String): BlockResult {
        if (task.isBlank()) return BlockResult(false)
        for (p in SENSITIVE_TASK_PATTERNS) {
            if (p.containsMatchIn(task)) {
                val reason = "指令包含敏感操作"
                Log.w(TAG, "blocked_reason=$reason, target_app=null, task_text=$task")
                return BlockResult(true, reason, null)
            }
        }
        return BlockResult(false)
    }

    /**
     * 工具级拦截。命中返回 [BlockResult]，否则返回 blocked=false。
     *
     * 目前仅拦截 open_app 打开危险应用；点击/滑动等中性工具不在此层拦截
     * （危险组合意图由 [checkTaskText] 在任务入口统一拦截）。
     */
    fun checkToolCall(toolName: String, args: Map<String, Any?>): BlockResult {
        if (toolName != "open_app") return BlockResult(false)
        val app = args["appName"]?.toString()?.trim().orEmpty()
        if (app.isEmpty()) return BlockResult(false)
        val hit = DANGEROUS_APP_KEYWORDS.firstOrNull { app.contains(it) }
        if (hit != null) {
            val reason = "目标应用「$app」涉及敏感操作"
            Log.w(TAG, "blocked_reason=$reason, target_app=$app, task_text=null")
            return BlockResult(true, reason, app)
        }
        return BlockResult(false)
    }
}

/**
 * 拦截结果（结构化）。
 *
 * @param blocked   是否被拦截
 * @param reason    拦截原因（可读文案）
 * @param targetApp 命中的目标应用名（工具级拦截时有值，文本级为 null）
 */
data class BlockResult(
    val blocked: Boolean,
    val reason: String = "",
    val targetApp: String? = null
)
