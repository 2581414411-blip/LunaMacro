package com.nexus.agent.qq

import android.content.Context

/**
 * QQ 自动回复的配置（0.9.25 批次 E）。
 *
 * ## 为什么全放 SharedPreferences 而不是数据库
 *
 * 这些值会被 [QqNotificationService]（系统绑定的服务，随时可能被拉起）在**任意时刻**读，
 * 而且必须在 `onNotificationPosted` 的 5 秒回调窗口内读完 —— 走 Room 要起协程、
 * 等 IO、还要处理未初始化，太慢也太脆。SharedPreferences 是同步的，够用。
 *
 * ## 默认值一律保守
 *
 * - [isEnabled] 默认 **false**：这是「替主人回消息」，不可能默认开。
 * - [autoSend] 默认 **false**：第一次用只能「建议回复」，用户确认过行为之后
 *   再手动打开静默发送。误发一条消息给老板的代价，比少一个功能大得多。
 * - [selfNickname] 默认空：空着时走「发送者是不是我自己」的兜底判断，
 *   但填了会更准（QQ 的通知里 title 就是发言人的昵称）。
 */
object QqConfig {

    private const val PREFS = "luna_qq"

    private const val KEY_ENABLED = "qq_enabled"
    private const val KEY_AUTO_SEND = "qq_auto_send"
    private const val KEY_PACKAGE = "qq_package"
    private const val KEY_USER_ID = "qq_user_id"
    private const val KEY_SELF_NICK = "qq_self_nickname"

    /** QQ 主包名。分身（QQ2）用的是同一个包名 + 不同的 `user.identifier` */
    const val DEFAULT_PACKAGE = "com.tencent.mobileqq"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** 是否真的静默发送。关闭时只发一条本机通知「建议这样回」 */
    fun isAutoSend(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SEND, false)

    fun setAutoSend(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SEND, on).apply()
    }

    /**
     * 监听哪个包名。
     *
     * 留成可配置而不是写死：有的人用 TIM、有的人用 QQ 极速版，
     * 包名不一样但通知结构一样 —— 写死会让功能对这类用户直接失效。
     */
    fun packageName(context: Context): String =
        prefs(context).getString(KEY_PACKAGE, DEFAULT_PACKAGE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_PACKAGE

    fun setPackageName(context: Context, pkg: String) {
        prefs(context).edit().putString(KEY_PACKAGE, pkg.trim()).apply()
    }

    /**
     * 监听哪个用户（应用分身）。
     *
     * Android 的 `StatusBarNotification.user` 对主用户是 `Process.myUserHandle()`（identifier 0），
     * 分身通常落在 999（小米/vivo 常见）或其他多用户 id 上。默认 0 = 主号。
     */
    fun userId(context: Context): Int =
        prefs(context).getInt(KEY_USER_ID, 0)

    fun setUserId(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_USER_ID, id.coerceAtLeast(0)).apply()
    }

    /** 主人自己的 QQ 昵称：通知里 title 等于它的，直接判定为「自己发的」 */
    fun selfNickname(context: Context): String =
        prefs(context).getString(KEY_SELF_NICK, "")?.trim().orEmpty()

    fun setSelfNickname(context: Context, nick: String) {
        prefs(context).edit().putString(KEY_SELF_NICK, nick.trim()).apply()
    }
}
