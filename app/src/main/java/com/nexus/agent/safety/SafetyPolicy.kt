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

    /** 敏感界面（正在用支付宝/银行）里想代替用户操作时的固定回复 */
    const val SENSITIVE_UI_MSG = "检测到敏感支付界面，我无法代为操作，请手动完成"

    /** 危险应用关键词（open_app 目标命中即拦截） */
    private val DANGEROUS_APP_KEYWORDS = listOf(
        "银行", "支付", "云闪付", "证券", "股票", "基金", "理财", "保险",
        "贷款", "金融", "借贷", "钱包", "支付宝", "财付通", "同花顺", "东方财富"
    )

    /**
     * 敏感界面判定用：包名关键词。
     *
     * 只放"钱袋子"类，宁少不多 —— 误判会让副驾驶在普通应用里也拒绝干活。
     */
    private val SENSITIVE_PACKAGE_KEYWORDS = listOf(
        "alipay", "unionpay", "upwallet", "wallet", "bank", "cmb", "icbc",
        "ccb", "abchina", "boc", "spdb", "cmbc", "cebbank", "psbc",
        "stock", "securities", "fund", "insurance"
    )

    /** 敏感界面判定用：应用中文名关键词 */
    private val SENSITIVE_LABEL_KEYWORDS = listOf(
        "支付宝", "银行", "云闪付", "证券", "股票", "基金", "理财",
        "保险", "借贷", "钱包", "信用卡", "支付"
    )

    /** 参数里出现这些词，说明这次调用可能替用户"确认/付款" */
    private val SENSITIVE_ACTION_WORDS = listOf(
        "确认支付", "立即支付", "付款", "转账", "汇款", "提现", "充值",
        "输入密码", "支付密码", "验证码", "免密", "收款码", "付款码"
    )

    /** 敏感界面里仍然允许的"无害"工具（只读/导航类） */
    private val SENSITIVE_UI_ALLOWED_TOOLS = setOf(
        "press_back", "press_home", "wait", "list_macros"
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

    /**
     * 当前前台是不是「钱袋子」类应用（支付宝 / 银行 / 证券 / 钱包）。
     *
     * @param packageName 前台包名（可从无障碍服务拿；null 表示拿不到 → 判定为非敏感，不误伤）
     */
    fun isSensitiveUi(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // 自己的界面永远不算敏感
        if (packageName == context.packageName) return false

        val lower = packageName.lowercase()
        if (SENSITIVE_PACKAGE_KEYWORDS.any { lower.contains(it) }) return true

        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            ""
        }
        return label.isNotEmpty() && SENSITIVE_LABEL_KEYWORDS.any { label.contains(it) }
    }

    /**
     * 副驾驶执行 `type:"action"` 前的总闸（路线：敏感界面 → 危险目标 → 敏感参数）。
     *
     * 顺序有讲究：
     *  1. **敏感界面**优先 —— 用户在支付宝里说"帮我点一下"，哪怕点的是普通按钮也要拒，
     *     因为模型看不到金额语义，代点风险不可控；
     *  2. 再走原有的 [checkToolCall]（open_app 打开银行类应用）；
     *  3. 最后查参数里的"付款/验证码"等词，兜住模型把危险动作塞进参数的情况。
     *
     * 安全策略总开关关闭时一律放行（高级用户自主选择）。
     */
    fun checkCopilotAction(
        context: Context,
        toolName: String,
        args: Map<String, Any?>,
        foregroundPackage: String?
    ): BlockResult {
        if (!isEnabled(context)) return BlockResult(false)

        // 1. 敏感界面：除只读/导航工具外全部拒绝
        if (isSensitiveUi(context, foregroundPackage) &&
            toolName.lowercase().trim() !in SENSITIVE_UI_ALLOWED_TOOLS
        ) {
            Log.w(TAG, "copilot_blocked reason=sensitive_ui, tool=$toolName, fg=$foregroundPackage")
            return BlockResult(true, SENSITIVE_UI_MSG, foregroundPackage)
        }

        // 2. 危险应用目标
        val byApp = checkToolCall(toolName, args)
        if (byApp.blocked) return byApp

        // 3. 参数里的危险动作词
        val joined = args.values.joinToString(" ") { it?.toString().orEmpty() }
        val word = SENSITIVE_ACTION_WORDS.firstOrNull { joined.contains(it) }
        if (word != null) {
            val reason = "操作内容涉及「$word」，请手动完成"
            Log.w(TAG, "copilot_blocked reason=sensitive_param, tool=$toolName, word=$word")
            return BlockResult(true, reason, null)
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
