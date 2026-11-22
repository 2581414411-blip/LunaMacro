package com.nexus.agent.safety

import android.content.Context
import android.util.Log

/**
 * 危险操作安全策略。
 *
 * ## 三层
 *
 * 1. 任务文本级 [checkTaskText] —— 拦截「卸载 / 删除 / 转账 / 支付」等危险意图
 * 2. 工具级 [checkToolCall] —— **按风险分级判定**，18 个工具全覆盖（0.9.31）
 * 3. 副驾驶总闸 [checkCopilotAction] —— 敏感界面 → 否决词 → 参数，三重独立兜底
 *
 * ## 0.9.31 这次改了什么，为什么
 *
 * 原来 [checkToolCall] 第一行是 `if (toolName != "open_app") return BlockResult(false)`——
 * 也就是**只拦 open_app 一个工具，其余 17 个直接放行**。对话路径还有 [checkCopilotAction]
 * 兜着，但 `Executor.executeTool` 是「游戏托管」「插件」「MPL 桥」共用的一条路，
 * 只要不经过对话，`click_text("确认支付")` 就一点拦阻都没有。
 *
 * 现在改成 [riskOf]：**风险不是工具的属性，是「工具 + 参数 + 当前前台」的函数。**
 * 同一个 `click_text`，在微信点「发送」是 CAUTION（可以代做，但要确认），
 * 在支付宝点任何东西、或者目标文字是「确认支付」，都是 FORBIDDEN（一步都不许走）。
 *
 * 判定顺序固定：**敏感界面 → 危险目标 → 否决词 → 工具基线**。
 * 前面任一命中即 FORBIDDEN，后面的不再看 —— 先判最严重的，文案才说得清楚。
 *
 * 开关默认开启，高级用户可在「设置 → 安全策略」关闭（需二次确认）。
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

    /**
     * 一票否决词（协议 v1 §3.1，两边各自维护，客户端不得据此推断）。
     *
     * 只对**写动作**生效。`web_search` / `list_macros` 这类只读工具不扫这段词，
     * 否则用户问"怎么改密码"会把搜索也拦掉 —— 拦的是"替他去改"，不是"聊到这个词"。
     */
    private val SENSITIVE_ACTION_WORDS = listOf(
        // 钱
        "支付", "付款", "转账", "红包", "充值", "提现", "购买", "下单", "结算", "还款",
        // 账号与凭据
        "卸载", "注销", "删除账号", "退出登录", "改密码", "密码", "验证码", "免密",
        // 历史遗留（模型常把整句按钮文字塞进参数）
        "确认支付", "立即支付", "汇款", "输入密码", "支付密码", "收款码", "付款码"
    )

    /** 敏感界面里仍然允许的"无害"工具（只读/导航类） */
    private val SENSITIVE_UI_ALLOWED_TOOLS = setOf(
        "press_back", "press_home", "wait", "list_macros"
    )

    /** 只读 / 导航：不改界面状态，永远 SAFE */
    private val SAFE_TOOLS = setOf(
        "wait", "list_macros", "web_search", "press_back", "press_home", "stop_game_auto"
    )

    /** 会点、会输、会滑：CAUTION（可以代做，但必须确认） */
    private val CAUTION_TOOLS = setOf(
        "click_text", "click_id", "double_tap", "long_press", "scroll", "drag", "multi_touch",
        "input_text", "play_macro", "start_game_auto", "schedule_task"
    )

    /** 敏感任务文本（任务意图命中即拦截） */
    private val SENSITIVE_TASK_PATTERNS = listOf(
        Regex("卸载|禁用|停用|删除|格式化|恢复出厂|清除.{0,6}数据|清空"),
        Regex("转账|付款|汇款|充值|提现|支付|还款|借款|贷款"),
        Regex("修改.{0,6}(密码|账户|账号)|重置.{0,6}密码"),
        Regex("下单|购买|付款码|收款码|免密支付")
    )

    // ---------------------------------------------------------------- 风险分级

    /**
     * 工具风险等级。
     *
     * - [SAFE] 只读 / 导航，不改变用户数据 → 允许 `confirm=false`
     * - [CAUTION] 会点击或输入 → 必须确认；MPL 侧 `confirm=false` 时回 `NEEDS_CONFIRM`
     * - [FORBIDDEN] 命中一票否决 → 不执行、不产生候选、不重试
     */
    enum class Risk { SAFE, CAUTION, FORBIDDEN }

    /** 一次判定的完整结论（风险 + 给用户看的文案 + 命中的目标应用） */
    data class RiskVerdict(
        val risk: Risk,
        val reason: String = "",
        val targetApp: String? = null
    )

    /**
     * 判定一次工具调用的风险。**纯函数，不碰 Android API** —— 桥接层和单测都靠它。
     *
     * @param toolName          工具名（大小写不敏感）
     * @param args              调用参数
     * @param foregroundPackage 当前前台包名（null = 拿不到 → 不误判为敏感界面）
     * @param sensitiveUi       前台是否敏感界面。有 Context 的调用方传
     *                          `isSensitiveUi(ctx, pkg)` 的结果（能查到应用中文名，更准）；
     *                          没有 Context 时留默认值，只按包名关键词判。
     */
    fun riskOf(
        toolName: String,
        args: Map<String, Any?>,
        foregroundPackage: String?,
        sensitiveUi: Boolean = isSensitivePackage(foregroundPackage)
    ): Risk = assess(toolName, args, foregroundPackage, sensitiveUi).risk

    /** [riskOf] 的完整版：多带回原因与目标应用，供 [checkToolCall] / 桥接层写日志。 */
    fun assess(
        toolName: String,
        args: Map<String, Any?>,
        foregroundPackage: String?,
        sensitiveUi: Boolean = isSensitivePackage(foregroundPackage)
    ): RiskVerdict {
        val name = toolName.lowercase().trim()

        // 1. 敏感界面：除了只读/导航，一律不做
        if (sensitiveUi && name !in SENSITIVE_UI_ALLOWED_TOOLS) {
            return RiskVerdict(Risk.FORBIDDEN, SENSITIVE_UI_MSG, foregroundPackage)
        }

        // 2. 危险目标应用
        if (name == "open_app") {
            val app = args["appName"]?.toString()?.trim().orEmpty()
            val hit = DANGEROUS_APP_KEYWORDS.firstOrNull { app.contains(it) }
            if (hit != null) {
                return RiskVerdict(Risk.FORBIDDEN, "目标应用「$app」涉及敏感操作", app)
            }
            return RiskVerdict(Risk.SAFE)
        }

        // 3. 基线：只读工具不扫否决词（"怎么改密码" 这种搜索不该被拦）
        val base = baseRisk(name)
        if (base == Risk.SAFE) return RiskVerdict(Risk.SAFE)

        // 4. 否决词：模型/外部客户端把危险动作塞进参数的情况
        val joined = args.values.joinToString(" ") { it?.toString().orEmpty() }
        val word = SENSITIVE_ACTION_WORDS.firstOrNull { joined.contains(it) }
        if (word != null) {
            return RiskVerdict(Risk.FORBIDDEN, "操作内容涉及「$word」，请手动完成", null)
        }

        return RiskVerdict(base)
    }

    /** 工具自身的基线风险。**未知工具按 CAUTION 处理**（宁可多问一句，不默认放行）。 */
    fun baseRisk(toolName: String): Risk {
        val name = toolName.lowercase().trim()
        return when {
            name in SAFE_TOOLS -> Risk.SAFE
            name in CAUTION_TOOLS -> Risk.CAUTION
            name == "open_app" -> Risk.SAFE
            else -> Risk.CAUTION
        }
    }

    /** 全部 18 个工具的基线风险表（设置页展示 + 单测断言用） */
    fun riskTable(): Map<String, Risk> = buildMap {
        SAFE_TOOLS.forEach { put(it, Risk.SAFE) }
        CAUTION_TOOLS.forEach { put(it, Risk.CAUTION) }
        put("open_app", Risk.SAFE)
    }

    /** 只按包名关键词判敏感界面（不需要 Context，供纯 JVM 单测与桥接层用） */
    fun isSensitivePackage(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val lower = packageName.lowercase()
        return SENSITIVE_PACKAGE_KEYWORDS.any { lower.contains(it) }
    }

    // ---------------------------------------------------------------- 开关

    /** 安全策略是否开启（默认开启） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ---------------------------------------------------------------- 拦截入口

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
     * 工具级拦截（老签名：拿不到前台，只按工具 + 参数判）。
     *
     * 保留它是为了让已有调用点不改也能编译；**新代码请用带前台的重载**。
     */
    fun checkToolCall(toolName: String, args: Map<String, Any?>): BlockResult =
        checkToolCall(toolName, args, null, false)

    /**
     * 工具级拦截（0.9.31：按 [riskOf] 判定，18 个工具全覆盖）。
     *
     * 只有 [Risk.FORBIDDEN] 会被拦下；[Risk.CAUTION] 放行 ——
     * "要不要确认"是交互层的事（副驾驶弹确认、MPL 要求 `confirm=true`），
     * 安全层只负责"能不能做"。
     */
    fun checkToolCall(
        toolName: String,
        args: Map<String, Any?>,
        foregroundPackage: String?,
        sensitiveUi: Boolean = isSensitivePackage(foregroundPackage)
    ): BlockResult {
        val v = assess(toolName, args, foregroundPackage, sensitiveUi)
        if (v.risk != Risk.FORBIDDEN) return BlockResult(false)
        Log.w(TAG, "blocked_reason=${v.reason}, tool=$toolName, fg=$foregroundPackage")
        return BlockResult(true, v.reason, v.targetApp)
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
        if (isSensitivePackage(packageName)) return true

        val label = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            ""
        }
        return label.isNotEmpty() && SENSITIVE_LABEL_KEYWORDS.any { label.contains(it) }
    }

    /**
     * 副驾驶执行 `type:"action"` 前的总闸。判定顺序与 [assess] 完全一致，
     * 区别只是它多拿了一个 Context（能查到应用中文名），并且受总开关控制。
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
        val sensitive = isSensitiveUi(context, foregroundPackage)
        val v = assess(toolName, args, foregroundPackage, sensitive)
        if (v.risk != Risk.FORBIDDEN) return BlockResult(false)
        Log.w(TAG, "copilot_blocked reason=${v.reason}, tool=$toolName, fg=$foregroundPackage")
        return BlockResult(true, v.reason, v.targetApp)
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
