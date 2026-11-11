package com.nexus.agent.copilot

import android.content.Context
import android.util.Log

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
     * 自我学习总开关（0.9.11）。
     *
     * 默认 **false**：这东西会采集操作行为，不可能是「默认帮你开」的选项。
     * 用户必须在设置页读完免责声明、点「我已阅读并同意」之后才会变 true。
     */
    private const val KEY_HABIT_ENABLED = "habit_learning_enabled"

    /** 免责声明是否已被用户明确同意（和开关分开存：关掉开关不该丢掉「他已经同意过」这件事） */
    private const val KEY_HABIT_TERMS_ACCEPTED = "habit_terms_accepted"

    /** 上次学习（生成画像）的时间戳，用于「每天最多学一次」 */
    private const val KEY_HABIT_LAST_LEARNED_AT = "habit_last_learned_at"

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

    /**
     * 生成失败后的静默截止时间戳（毫秒）。
     *
     * 0.9.3 把检查频率提到 15 分钟一次之后，Key 失效会变成「每 15 分钟白打一次 API」，
     * 失败一次就罚站半小时，别拿用户的钱包和电量堆重试。
     */
    private const val KEY_HEARTBEAT_BACKOFF_UNTIL = "heartbeat_backoff_until"

    /**
     * 她主动发来、但用户还没看的条数。
     *
     * 存 [android.content.SharedPreferences] 而不是内存：通知点进来 App 可能是冷启动，
     * 内存计数那一刻已经归零，红点就白挂了。
     */
    private const val KEY_PERSONA_UNREAD = "persona_unread"

    /**
     * 今天已经主动发了几条（0.9.4 防破产）。
     *
     * 配合 [KEY_HEARTBEAT_DAY] 一起存：跨天读的时候自然归零，不用定时器。
     * 0.9.3 放开了频率但没设上限，理论上白天能发 52 条 —— 那不是陪伴，那是骚扰。
     */
    private const val KEY_HEARTBEAT_TODAY_COUNT = "heartbeat_today_count"

    /** 上面那个计数属于哪一天（`yyyyMMdd`），日期一变就当 0 */
    private const val KEY_HEARTBEAT_DAY = "heartbeat_day"

    /** 她开口了、但用户还没搭理的「待确认」时间戳（0.9.4 智能降频） */
    private const val KEY_HEARTBEAT_PENDING_AT = "heartbeat_pending_at"

    /** 连续几次主动开口都被晾着（用户 4 小时内没回） */
    private const val KEY_HEARTBEAT_IGNORED_STREAK = "heartbeat_ignored_streak"

    /**
     * 主动问候生成失败后的静默时长（分钟）。
     *
     * 放在这里而不是 [com.nexus.agent.persona.HeartbeatScheduler]：写退避的是这个类，
     * 两边各写一份常量迟早分叉。
     */
    const val HEARTBEAT_FAIL_BACKOFF_MINUTES = 30L

    /** 一天最多主动发几条（0.9.4）。发满就闭嘴，等明天 */
    const val HEARTBEAT_DAILY_LIMIT = 10

    /** 连续几次被无视之后开始「识趣」（0.9.4） */
    const val HEARTBEAT_IGNORED_STREAK_TO_SLOW = 2

    /** 多久没回算「被无视」（小时）。短于这个不算，可能只是没看手机 */
    const val HEARTBEAT_IGNORED_WINDOW_HOURS = 4L

    /** 被无视过之后的最小间隔（分钟），从 15 分钟拉到 2 小时 */
    const val HEARTBEAT_SLOW_GAP_MINUTES = 120L

    /** 上一次成功排程用的指纹（见 HeartbeatScheduler.SCHEDULE_SIGNATURE） */
    private const val KEY_HEARTBEAT_SCHEDULE_SIG = "heartbeat_schedule_sig"

    /** 已排程指纹；空串 = 没排过 / 被取消过 */
    fun heartbeatScheduleSignature(context: Context): String =
        prefs(context).getString(KEY_HEARTBEAT_SCHEDULE_SIG, "") ?: ""

    fun setHeartbeatScheduleSignature(context: Context, sig: String) {
        prefs(context).edit().putString(KEY_HEARTBEAT_SCHEDULE_SIG, sig).apply()
    }

    /** 心智引擎的排程指纹（0.9.6，见 InnerMonologueEngine.SCHEDULE_SIGNATURE） */
    private const val KEY_INNER_THOUGHT_SIG = "inner_thought_schedule_sig"

    fun innerThoughtSignature(context: Context): String =
        prefs(context).getString(KEY_INNER_THOUGHT_SIG, "") ?: ""

    fun setInnerThoughtSignature(context: Context, sig: String) {
        prefs(context).edit().putString(KEY_INNER_THOUGHT_SIG, sig).apply()
    }

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
        val now = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_LAST_HEARTBEAT_AT, now)
            // 同一时刻记下「等她被看见」：用户 4 小时内没回，下次就降频
            .putLong(KEY_HEARTBEAT_PENDING_AT, now)
            .apply()
    }

    fun lastHeartbeatAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_HEARTBEAT_AT, 0L)

    // ------------------------------------------------------------ 防破产（0.9.4）

    /** 今天是哪天（`yyyyMMdd`）。跨天计数的唯一依据 */
    private fun today(context: Context): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(java.util.Date())

    /** 今天已经发了几条；日期变了就地归零（不靠定时任务） */
    fun heartbeatSentToday(context: Context): Int {
        val p = prefs(context)
        if (p.getString(KEY_HEARTBEAT_DAY, "") != today(context)) return 0
        return p.getInt(KEY_HEARTBEAT_TODAY_COUNT, 0)
    }

    /** 成功发出一条之后 +1（跨天自动从头开始数） */
    fun bumpHeartbeatToday(context: Context) {
        val p = prefs(context)
        val day = today(context)
        val next = if (p.getString(KEY_HEARTBEAT_DAY, "") == day) {
            p.getInt(KEY_HEARTBEAT_TODAY_COUNT, 0) + 1
        } else {
            1
        }
        p.edit().putString(KEY_HEARTBEAT_DAY, day).putInt(KEY_HEARTBEAT_TODAY_COUNT, next).apply()
    }

    /** 连续被无视的次数 */
    fun ignoredStreak(context: Context): Int =
        prefs(context).getInt(KEY_HEARTBEAT_IGNORED_STREAK, 0)

    /**
     * 刷新「被无视」计数，返回最新值。
     *
     * 判定只用两条时间戳，不需要读聊天记录：
     *  - 她上次开口之后用户说过话 → 不是无视，连击清零；
     *  - 她上次开口之后 4 小时用户一句话没说 → 这一次算被无视，连击 +1。
     *
     * 每轮心跳开头调一次：这样「连续两次被晾着」最早在她第二次开口 4 小时后成立，
     * 而不是等到用户主动打开 App 才发现。
     */
    fun refreshIgnoredStreak(context: Context, nowMs: Long = System.currentTimeMillis()): Int {
        val p = prefs(context)
        val pending = p.getLong(KEY_HEARTBEAT_PENDING_AT, 0L)
        var streak = p.getInt(KEY_HEARTBEAT_IGNORED_STREAK, 0)
        if (pending <= 0L) return streak

        return if (lastChatAt(context) > pending) {
            // 她开口之后用户回过话 → 她没被冷落
            if (streak != 0) p.edit().putInt(KEY_HEARTBEAT_IGNORED_STREAK, 0).apply()
            p.edit().remove(KEY_HEARTBEAT_PENDING_AT).apply()
            Log.d("Heartbeat", "上次开口后用户有回应，无视连击清零（原 $streak）")
            0
        } else if (nowMs - pending >= HEARTBEAT_IGNORED_WINDOW_HOURS * 3_600_000L) {
            streak += 1
            p.edit()
                .putInt(KEY_HEARTBEAT_IGNORED_STREAK, streak)
                .remove(KEY_HEARTBEAT_PENDING_AT)
                .apply()
            Log.i("Heartbeat", "上次开口 ${HEARTBEAT_IGNORED_WINDOW_HOURS} 小时无人应答，无视连击=$streak")
            streak
        } else {
            streak
        }
    }

    /** 清掉无视连击（用户回话、用户手动点「现在试」时） */
    fun clearIgnoredStreak(context: Context) {
        prefs(context).edit()
            .putInt(KEY_HEARTBEAT_IGNORED_STREAK, 0)
            .remove(KEY_HEARTBEAT_PENDING_AT)
            .apply()
    }

    /** 记一次生成失败，让心跳静默一段时间（见 [KEY_HEARTBEAT_BACKOFF_UNTIL]） */
    fun markHeartbeatFail(context: Context) {
        val until = System.currentTimeMillis() + HEARTBEAT_FAIL_BACKOFF_MINUTES * 60_000L
        prefs(context).edit().putLong(KEY_HEARTBEAT_BACKOFF_UNTIL, until).apply()
    }

    /** 退避截止时间戳；0 = 没在退避 */
    fun heartbeatBackoffUntil(context: Context): Long =
        prefs(context).getLong(KEY_HEARTBEAT_BACKOFF_UNTIL, 0L)

    /** 生成成功一次就把退避清掉，否则一次网络抖动会连累后面半小时 */
    fun clearHeartbeatFail(context: Context) {
        prefs(context).edit().remove(KEY_HEARTBEAT_BACKOFF_UNTIL).apply()
    }

    // ------------------------------------------------------------ 未读（0.9.3）

    /** 她主动发来一条，未读 +1 */
    fun bumpUnread(context: Context) {
        val next = (unreadCount(context) + 1).coerceAtMost(99)
        prefs(context).edit().putInt(KEY_PERSONA_UNREAD, next).apply()
    }

    fun unreadCount(context: Context): Int =
        prefs(context).getInt(KEY_PERSONA_UNREAD, 0)

    /** 用户进到聊天页了，红点清零 */
    fun clearUnread(context: Context) {
        val p = prefs(context)
        if (p.getInt(KEY_PERSONA_UNREAD, 0) == 0) return
        p.edit().putInt(KEY_PERSONA_UNREAD, 0).apply()
    }

    // ------------------------------------------------------------ 通知堆叠（0.9.4 批次三）

    /**
     * 通知里要堆叠的最近几条主动消息。
     *
     * 为什么要自己存一份：`MessagingStyle` 每次 `notify` 都是**整条通知重画**，
     * 只 add 最新一条的话，通知里永远只有一句话 —— 看着像"她刚说了这句"，
     * 而不是微信那样"她今天跟你说过几句"。系统不会替我们记历史，只能自己存。
     *
     * 编码用不可见分隔符而不是 JSON：这里只需要「时间戳 + 文本」，
     * 引入 org.json 在纯 JVM 单测里是空壳（返回 null），而这段代码路径不值得为此失去可测性。
     */
    private const val KEY_HEARTBEAT_NOTICES = "heartbeat_notices"

    /** 通知里最多堆几条：再多就顶出屏幕高度，也没人往上翻 */
    const val HEARTBEAT_NOTICE_MAX = 8

    private const val NOTICE_SEP = "\u0002"
    private const val FIELD_SEP = "\u0001"

    /** 记一条通知并返回「当前应该展示的全部」；只留最近 [HEARTBEAT_NOTICE_MAX] 条 */
    fun appendHeartbeatNotice(context: Context, text: String, atMs: Long): List<Pair<Long, String>> {
        val list = ArrayList(heartbeatNotices(context))
        // 同一条话重复推（生成内容撞车）就并到最新一条，别在通知栏里刷两遍同样的字
        if (list.lastOrNull()?.second == text) list.removeAt(list.size - 1)
        list.add(atMs to text)
        while (list.size > HEARTBEAT_NOTICE_MAX) list.removeAt(0)
        prefs(context).edit()
            .putString(KEY_HEARTBEAT_NOTICES, encodeNotices(list))
            .apply()
        return list
    }

    fun heartbeatNotices(context: Context): List<Pair<Long, String>> {
        val raw = prefs(context).getString(KEY_HEARTBEAT_NOTICES, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Long, String>>()
        for (seg in raw.split(NOTICE_SEP)) {
            if (seg.isEmpty()) continue
            val cut = seg.indexOf(FIELD_SEP)
            if (cut <= 0) continue
            val ts = seg.substring(0, cut).toLongOrNull() ?: continue
            val body = seg.substring(cut + 1)
            if (body.isNotEmpty()) out.add(ts to body)
        }
        return out
    }

    /** 用户点开通知/清空后可以把堆叠清掉，避免下次只弹一条却带着上个月的旧话 */
    fun clearHeartbeatNotices(context: Context) {
        prefs(context).edit().remove(KEY_HEARTBEAT_NOTICES).apply()
    }

    private fun encodeNotices(list: List<Pair<Long, String>>): String =
        list.joinToString(NOTICE_SEP) { (ts, body) ->
            ts.toString() + FIELD_SEP + body.replace(NOTICE_SEP, " ").replace(FIELD_SEP, " ")
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

    // ---- 自我学习（0.9.11）----

    /**
     * 自我学习是否开启。默认 false。
     *
     * 双条件：开关打开 **且** 免责声明已同意。缺任一条都不采 ——
     * 开关和同意分开存，是为了防止「迁移时误把开关读成 true」这类事故，
     * 单看一个布尔就开采集，风险太高。
     */
    fun isHabitLearningEnabled(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_HABIT_ENABLED, false) &&
            p.getBoolean(KEY_HABIT_TERMS_ACCEPTED, false)
    }

    /** 用户是否已经读过并同意免责声明 */
    fun hasAcceptedHabitTerms(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HABIT_TERMS_ACCEPTED, false)

    /**
     * 记录「用户已同意免责声明」。
     *
     * 只在免责声明弹窗里点「我已阅读并同意」时调用，
     * **不要在开关切换时顺手调** —— 那等于绕过声明。
     */
    fun setHabitTermsAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit().putBoolean(KEY_HABIT_TERMS_ACCEPTED, accepted).apply()
        if (!accepted) {
            // 撤回同意就等于关闭：不同意还继续采，那是耍流氓
            prefs(context).edit().putBoolean(KEY_HABIT_ENABLED, false).apply()
        }
    }

    fun setHabitLearningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HABIT_ENABLED, enabled).apply()
        Log.i("CopilotConfig", "自我学习开关：$enabled")
    }

    fun habitLastLearnedAt(context: Context): Long =
        prefs(context).getLong(KEY_HABIT_LAST_LEARNED_AT, 0L)

    fun setHabitLastLearnedAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_HABIT_LAST_LEARNED_AT, at).apply()
    }
}
