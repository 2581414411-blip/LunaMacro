package com.nexus.agent.copilot

import android.content.Context

/**
 * 副驾驶（Copilot）本地配置。
 *
 * 集中存放免责声明同意状态与两个功能开关，避免散落在各处写 SharedPreferences。
 */
object CopilotConfig {

    private const val PREFS = "luna_copilot"
    private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    private const val KEY_ISLAND_ENABLED = "island_enabled"
    private const val KEY_COMPANION_ENABLED = "companion_enabled"
    private const val KEY_LAST_BUBBLE = "last_bubble"
    private const val KEY_ASSISTANT_NAME = "assistant_name"

    /** 默认助手名，未自定义时用它（同时作为提示词里的自称） */
    const val DEFAULT_ASSISTANT_NAME = "宏月"

    /** 名字长度上限：太长会撑爆灵动岛和气泡 */
    private const val NAME_MAX_LEN = 12

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否已同意免责声明 */
    fun isDisclaimerAccepted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    fun setDisclaimerAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, accepted).apply()
    }

    /** 灵动岛是否开启（默认关，需用户主动开） */
    fun isIslandEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ISLAND_ENABLED, false)

    fun setIslandEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ISLAND_ENABLED, enabled).apply()
    }

    /** 情绪陪玩（画面突变吐槽）是否开启 */
    fun isCompanionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_COMPANION_ENABLED, false)

    fun setCompanionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COMPANION_ENABLED, enabled).apply()
    }

    /** 最近一条弹幕（重进页面时回显） */
    fun lastBubble(context: Context): String =
        prefs(context).getString(KEY_LAST_BUBBLE, "") ?: ""

    fun setLastBubble(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LAST_BUBBLE, text).apply()
    }

    /**
     * 助手名字（显示名 + 提示词里的自称）。
     *
     * 空值 / 全空白时回落到 [DEFAULT_ASSISTANT_NAME]，避免提示词里出现「你是 。」这种残句。
     */
    fun assistantName(context: Context): String {
        val raw = prefs(context).getString(KEY_ASSISTANT_NAME, "")?.trim().orEmpty()
        return raw.ifBlank { DEFAULT_ASSISTANT_NAME }
    }

    /**
     * 设置助手名字。返回是否被接受（空串 = 恢复默认，返回 true）。
     *
     * 超过 [NAME_MAX_LEN] 个字直接拒绝，不截断——截断会让用户以为改成功了。
     */
    fun setAssistantName(context: Context, name: String): Boolean {
        val clean = name.trim().replace("\n", "").replace("\r", "")
        if (clean.length > NAME_MAX_LEN) return false
        prefs(context).edit().putString(KEY_ASSISTANT_NAME, clean).apply()
        return true
    }
}
