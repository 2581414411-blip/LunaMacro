package com.nexus.agent.habit

/**
 * 自我学习的守门员（0.9.11）。
 *
 * 这里是整条链路里**唯一有权决定「什么能留下」**的地方，所以刻意做成纯函数：
 * 不 import 任何 android.*，不碰文件、不碰网络，单测可以逐条打靶。
 * 上层（[HabitCollector]）只负责把 `AccessibilityNodeInfo` 上的原始字段读出来交进来。
 *
 * 三道闸，从粗到细：
 *  1. [isPackageAllowed] —— 金融/密码类应用整包不采（连打开过都不记）
 *  2. [isScreenSensitive] —— 界面标题命中敏感词，整屏跳过
 *  3. [sanitizeLabel] —— 单个控件标签脱敏，像验证码、邮箱、长正文一律丢弃
 *
 * 同一条铁律贯穿三层：**宁可少学，绝不多采**。漏采只是画像糙一点，
 * 多采一次验证码就是不可挽回的事故。
 */
object HabitPrivacy {

    // ---- InputType 常量（自己抄一份，不依赖 android.text.InputType）----
    // 纯 JVM 单测里 android 类是空壳，所有静态字段都是 0，
    // 直接引用 InputType 常量会让「密码框判定」这条测试永远绿得莫名其妙。
    private const val MASK_CLASS = 0x0000000F
    private const val MASK_VARIATION = 0x00000FF0
    private const val CLASS_TEXT = 0x00000001
    private const val CLASS_NUMBER = 0x00000002
    private const val VAR_TEXT_PASSWORD = 0x00000080
    private const val VAR_TEXT_VISIBLE_PASSWORD = 0x00000090
    private const val VAR_TEXT_WEB_PASSWORD = 0x000000E0
    private const val VAR_NUMBER_PASSWORD = 0x00000010

    /**
     * 整包跳过的应用关键词（包名或应用显示名命中即拒）。
     *
     * 比 [com.nexus.agent.copilot.CopilotEngine] 的 `PRIVATE_KEYWORDS` 更严：
     * 那边只用来决定「截不截图」，截错了最多是一张废图；这边一旦漏掉，
     * 就是「她记住了你在用哪个银行 App」。所以宁滥勿缺。
     */
    private val PACKAGE_DENY = listOf(
        // 金融
        "bank", "pay", "alipay", "unionpay", "wallet", "finance", "loan", "credit",
        "银行", "支付", "钱包", "支付宝", "云闪付", "证券", "基金", "理财",
        "保险", "贷款", "金融", "借贷", "信用卡",
        // 密码 / 身份
        "password", "keychain", "authenticator", "keystore", "onetoken", "otp",
        "密码", "安全中心", "身份验证", "实名",
        // 系统安全面板
        "com.android.settings", "com.android.keyguard", "com.android.systemui"
    )

    /** 界面标题 / 类名命中这些词，整屏跳过 */
    private val SCREEN_DENY = listOf(
        "密码", "验证码", "校验码", "动态码", "pin", "password", "passcode",
        "解锁", "指纹", "面容", "人脸", "图案锁",
        "支付", "付款", "转账", "收款", "账单", "银行卡", "卡号",
        "身份证", "身份", "实名", "绑定", "授权", "账户安全", "登录", "注册",
        "wallet", "payment", "login", "signin", "sign_in", "verify", "oauth"
    )

    /** 控件标签命中这些词直接丢弃（哪怕它只是个按钮） */
    private val LABEL_DENY = listOf(
        "密码", "验证码", "校验码", "pin", "password", "卡号", "身份证",
        "支付", "付款", "转账", "收款", "指纹", "面容", "解锁"
    )

    /**
     * 这个应用能不能采。
     *
     * @param pkg   包名（小写比较）
     * @param appLabel 应用显示名，可为空（查不到就只按包名判）
     */
    fun isPackageAllowed(pkg: String?, appLabel: String? = null): Boolean {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return false
        val lower = p.lowercase()
        if (PACKAGE_DENY.any { lower.contains(it.lowercase()) }) return false
        val label = appLabel?.trim().orEmpty()
        if (label.isNotEmpty() && PACKAGE_DENY.any { label.contains(it) }) return false
        return true
    }

    /**
     * 这个界面能不能采。
     *
     * @param title     界面标题（Activity label / 窗口标题）
     * @param className 事件来源的类名（`android.widget.EditText`、`...LoginActivity`）
     */
    fun isScreenSensitive(title: String?, className: String? = null): Boolean {
        val t = title?.lowercase().orEmpty()
        val c = className?.lowercase().orEmpty()
        // 输入框本身不算敏感界面 —— 它的内容由 [sanitizeLabel] 那一层挡，
        // 但整屏拒掉会让「他点开搜索框」这个正常行为也丢了。
        val haystack = t + " " + c
        return SCREEN_DENY.any { haystack.contains(it) }
    }

    /**
     * 这个控件是不是密码输入框。
     *
     * 三道判据任一命中即算：无障碍自带 `isPassword`、类名是 EditText、
     * 输入类型落在密码族。**这条命中就直接丢弃整个事件，不留标签。**
     */
    fun isPasswordField(
        isPassword: Boolean,
        inputType: Int,
        className: String? = null
    ): Boolean {
        if (isPassword) return true
        if (inputType.isPasswordInputType()) return true
        // 少数自绘控件的 inputType 是 0，只能靠类名兜
        val c = className?.lowercase().orEmpty()
        return c.contains("password") || c.contains("pinbox")
    }

    /** Int 是否是密码族输入类型 */
    fun Int.isPasswordInputType(): Boolean {
        if (this == 0) return false
        return when (this and MASK_CLASS) {
            CLASS_TEXT -> (this and MASK_VARIATION) in setOf(
                VAR_TEXT_PASSWORD, VAR_TEXT_VISIBLE_PASSWORD, VAR_TEXT_WEB_PASSWORD
            )
            CLASS_NUMBER -> (this and MASK_VARIATION) == VAR_NUMBER_PASSWORD
            else -> false
        }
    }

    /**
     * 控件标签脱敏。
     *
     * 只留「像个按钮的字」：短的、没有数字串的、不包含 @ 的。
     * 返回 null 表示这条标签不能用（调用方可以记成「[控件]」或直接丢事件）。
     */
    fun sanitizeLabel(raw: String?): String? {
        val t = raw?.trim()?.replace("\n", " ")?.replace("\r", " ") ?: return null
        if (t.isEmpty()) return null
        if (t.length > LABEL_MAX_LEN) return null
        // 连续 4 位以上数字：验证码、手机号、金额、卡号尾号，全都是内容
        if (DIGIT_RUN.containsMatchIn(t)) return null
        if (t.contains("@")) return null
        if (t.none { it.isLetter() }) return null
        if (LABEL_DENY.any { t.lowercase().contains(it) }) return null
        return t
    }

    /**
     * 界面标题脱敏。标题比标签宽松一点（可以长一些），但同样不许带数字串和 @。
     */
    fun sanitizeScreen(raw: String?): String {
        val t = raw?.trim()?.replace("\n", " ")?.replace("\r", " ") ?: return ""
        if (t.isEmpty()) return ""
        if (DIGIT_RUN.containsMatchIn(t)) return ""
        if (t.contains("@")) return ""
        if (isScreenSensitive(t)) return ""
        return t.take(SCREEN_MAX_LEN)
    }

    /** 一条事件的最终裁决：通过则返回净化后的事件，不通过返回 null */
    fun screen(
        at: Long,
        pkg: String?,
        appLabel: String?,
        kind: String,
        screenTitle: String?,
        className: String?,
        label: String?,
        isPassword: Boolean,
        inputType: Int
    ): HabitEvent? {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return null
        if (!isPackageAllowed(p, appLabel)) return null
        if (isPasswordField(isPassword, inputType, className)) return null
        if (isScreenSensitive(screenTitle, className)) return null
        return HabitEvent(
            at = at,
            pkg = p,
            app = appLabel?.trim().orEmpty().ifEmpty { shortPkg(p) },
            kind = kind,
            screen = sanitizeScreen(screenTitle),
            label = sanitizeLabel(label).orEmpty()
        )
    }

    /** 包名兜底成可读名：`com.tencent.mm` → `tencent.mm` */
    fun shortPkg(pkg: String): String {
        val p = pkg.trim()
        return if (p.startsWith("com.")) p.removePrefix("com.").take(24) else p.take(24)
    }

    /** 连续 4 位数字 */
    private val DIGIT_RUN = Regex("\\d{4,}")

    /** 标签最长字数：超过这个长度基本是正文而不是按钮 */
    const val LABEL_MAX_LEN = 14

    /** 界面标题最长字数 */
    const val SCREEN_MAX_LEN = 24
}
