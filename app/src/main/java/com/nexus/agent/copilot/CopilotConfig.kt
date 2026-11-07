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

    /**
     * 自动化工具总开关（0.9.0）。
     *
     * 默认 **true**：老用户升级上来行为不变，不会突然发现它不会干活了。
     * 键名保持 `enable_automation_tools`，和设置页/文档里的叫法一致。
     */
    private const val KEY_ENABLE_AUTOMATION = "enable_automation_tools"

    /** 主动问候开关（0.9.2 批次二）。默认开：用户要的就是「她会主动找我」 */
    private const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"

    /** 最后一次成功对话的时间戳（毫秒）。判定「多久没说话」的唯一依据 */
    private const val KEY_LAST_CHAT_AT = "last_chat_at"

    /** 最后一次主动问候的时间戳（毫秒）。避免 12 小时一检查就发一条 */
    private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"

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

    /**
     * 自动化工具总开关（0.9.0）。默认开。
     *
     * 关掉之后：不注入工具 Schema、拦截所有 action/task，AI 只能用文字教用户操作。
     * 真正的判定与拦截在 [com.nexus.agent.system.AutomationPolicy]，
     * 这里只负责存取 —— 免得每个调用点都去猜键名。
     */
    fun isAutomationEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLE_AUTOMATION, true)

    fun setAutomationEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLE_AUTOMATION, enabled).apply()
    }

    // ------------------------------------------------------------ 生命脉搏（0.9.2 批次二）

    /**
     * 她会不会主动找你。
     *
     * 默认 **true** —— 这一版的主题就是「活起来」，默认关掉等于什么都没做。
     * 觉得烦的用户可以在人格页顶部开关里关掉，开关状态立即生效（会取消 WorkManager）。
     */
    fun isHeartbeatEnabled(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_HEARTBEAT_ENABLED, true)
    }

    fun setHeartbeatEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HEARTBEAT_ENABLED, enabled).apply()
    }

    /** 记一次「刚说过话」。每轮成功回复后调，供心跳判断多久没聊了 */
    fun markChatNow(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHAT_AT, System.currentTimeMillis()).apply()
    }

    /** 最后一次说话的时间戳；从没说过返回 0（当成「很久没聊」处理） */
    fun lastChatAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CHAT_AT, 0L)

    /** 记一次「刚主动问候过」，用来控制最小间隔 */
    fun markHeartbeatNow(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_HEARTBEAT_AT, System.currentTimeMillis()).apply()
    }

    fun lastHeartbeatAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_HEARTBEAT_AT, 0L)

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
