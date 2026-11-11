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

    /** 同意的是哪一版条款（0.9.12）。条款改实质内容就升版本，让老用户重新读 */
    private const val KEY_HABIT_TERMS_VERSION = "habit_terms_version"

    /** 上次学习（生成画像）的时间戳，用于「每天最多学一次」 */
    private const val KEY_HABIT_LAST_LEARNED_AT = "habit_last_learned_at"

    /** 外挂记忆总开关（0.9.13，默认 true） */
    private const val KEY_MEMORY_ENABLED = "memory_enabled"

    /** 上次跑达尔文衰减的时间 */
    private const val KEY_MEMORY_LAST_DECAY_AT = "memory_last_decay_at"

    /** 夜间自动巩固开关（0.9.14，默认 true） */
    private const val KEY_MEMORY_AUTO_CONSOLIDATE = "memory_auto_consolidate"

    /** 上次成功巩固的时间 */
    private const val KEY_MEMORY_LAST_CONSOLIDATE_AT = "memory_last_consolidate_at"

    /** 上次巩固的一句话结果（设置页直接显示，不用重新算） */
    private const val KEY_MEMORY_LAST_REPORT = "memory_last_report"

    /** 夜间任务的排程指纹（改了周期/约束要换值，否则老用户不会重排） */
    private const val KEY_MEMORY_SCHEDULE_SIG = "memory_schedule_sig"

    /**
     * 连续情感（PAD）开关（0.9.15）。
     *
     * 默认 **true**：这一版的核心就是它，装了不生效等于没装。
     * 关掉 = 精确回到 0.9.14 的三档标签行为（心情仍按「被夸/被凶/普通」写），
     * 万一她觉得「说话变文艺了、出戏」，用户能一键退回去，不用等下一版。
     */
    private const val KEY_PAD_ENABLED = "pad_enabled"

    /**
     * 情感调制记忆检索开关（0.9.15 阶段 2）。
     *
     * 默认 **true**：老逻辑是 `top(10)`（权重前 10 条，谁的心情都看同一份），
     * 这一版改成「语义 + 情绪一致性 + 时间衰减」综合打分。关掉 = 精确回退到
     * 权重前 10 条，一旦她开始「翻出一件不相干的伤心事」，用户可以立刻退回。
     */
    private const val KEY_AFFECT_RECALL_ENABLED = "affect_recall_enabled"

    /**
     * 会话日记开关（0.9.15 阶段 3）。
     *
     * 默认 **true**：夜里让她以第一人称写一句当天的心情，第二天开机当作「刚醒来
     * 的记忆碎片」注入一次。关掉 = 不写也不读，开机照常。
     */
    private const val KEY_DIARY_ENABLED = "diary_enabled"

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
        // 开关与「同意的是当前版本条款」双条件。条款升版后老用户自动停采，
        // 直到重新读完新版 —— 加了新能力却沿用旧同意，不算同意。
        return p.getBoolean(KEY_HABIT_ENABLED, false) && hasAcceptedHabitTerms(context)
    }

    /**
     * 用户是否已经读过并同意**当前这版**免责声明。
     *
     * 0.9.12 起要版本对得上：条款里新增了「她会代你执行动作」这一节，
     * 只看布尔的写法会让老用户带着 v1 的同意继续用 v2 的功能 —— 那不算同意。
     */
    fun hasAcceptedHabitTerms(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean(KEY_HABIT_TERMS_ACCEPTED, false) &&
            p.getInt(KEY_HABIT_TERMS_VERSION, 0) >= com.nexus.agent.habit.HabitTerms.VERSION
    }

    /**
     * 记录「用户已同意免责声明」。
     *
     * 只在免责声明弹窗里点「我已阅读并同意」时调用，
     * **不要在开关切换时顺手调** —— 那等于绕过声明。
     */
    fun setHabitTermsAccepted(context: Context, accepted: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HABIT_TERMS_ACCEPTED, accepted)
            .putInt(
                KEY_HABIT_TERMS_VERSION,
                if (accepted) com.nexus.agent.habit.HabitTerms.VERSION else 0
            )
            .apply()
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

    // ---- 外挂记忆 / 肌肉记忆回放（0.9.13）----

    /**
     * 外挂记忆是否开启。**默认 true**。
     *
     * 与自我学习不同，这里默认开着，理由是它不额外采集用户信息：
     * 记录的是「她自己刚才怎么把这件事办成的」，
     * 而她看到的东西本来就是用户让她看的那块屏幕。
     *
     * 但依然给一个硬开关 —— 有人就是不想让软件留痕，那就得能关掉。
     * 关掉后：不再采集、不再拦截、已有记忆仍留着（不偷偷删用户数据）。
     */
    fun isMemoryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEMORY_ENABLED, true)

    fun setMemoryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORY_ENABLED, enabled).apply()
        Log.i("CopilotConfig", "外挂记忆开关：$enabled")
    }

    /**
     * 连续情感（PAD）开关（0.9.15）。默认开。
     *
     * 关掉之后：不再推进 PAD、不再往提示词里塞情感段落、心情退回「被夸/被凶/普通」
     * 三档标签 —— 也就是 0.9.14 的行为，一行不差。
     */
    fun isPadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAD_ENABLED, true)

    fun setPadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAD_ENABLED, enabled).apply()
        Log.i("CopilotConfig", "连续情感（PAD）开关：$enabled")
    }

    /** 情感调制检索（0.9.15 阶段 2）。关掉就走回 `top(10)` */
    fun isAffectRecallEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AFFECT_RECALL_ENABLED, true)

    fun setAffectRecallEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AFFECT_RECALL_ENABLED, enabled).apply()
        Log.i("CopilotConfig", "情感调制检索开关：$enabled")
    }

    /** 会话日记（0.9.15 阶段 3）。关掉 = 不写不读 */
    fun isDiaryEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DIARY_ENABLED, true)

    fun setDiaryEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DIARY_ENABLED, enabled).apply()
        Log.i("CopilotConfig", "会话日记开关：$enabled")
    }

    /** 上次跑衰减的时间（一天一次就够，别每次任务都扫全表） */
    fun memoryLastDecayAt(context: Context): Long =
        prefs(context).getLong(KEY_MEMORY_LAST_DECAY_AT, 0L)

    fun setMemoryLastDecayAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_MEMORY_LAST_DECAY_AT, at).apply()
    }

    /**
     * 夜间自动巩固是否开启。**默认 true**，但受 [isMemoryEnabled] 总闸约束。
     *
     * 单独给一个开关，是因为「愿意让她记」和「愿意让她半夜烧 token」是两件事：
     * 有人可以接受记忆留在本地，但不想让 App 在夜里调云端模型。
     */
    fun isAutoConsolidateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEMORY_AUTO_CONSOLIDATE, true)

    fun setAutoConsolidateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEMORY_AUTO_CONSOLIDATE, enabled).apply()
        Log.i("CopilotConfig", "夜间自动巩固开关：$enabled")
    }

    fun memoryLastConsolidateAt(context: Context): Long =
        prefs(context).getLong(KEY_MEMORY_LAST_CONSOLIDATE_AT, 0L)

    fun setMemoryLastConsolidateAt(context: Context, at: Long) {
        prefs(context).edit().putLong(KEY_MEMORY_LAST_CONSOLIDATE_AT, at).apply()
    }

    /** 上次巩固的一句话结果（「12 条轨迹 → 3 组…」），只用于设置页展示 */
    fun memoryLastReport(context: Context): String =
        prefs(context).getString(KEY_MEMORY_LAST_REPORT, "").orEmpty()

    fun setMemoryLastReport(context: Context, text: String) {
        prefs(context).edit().putString(KEY_MEMORY_LAST_REPORT, text.take(200)).apply()
    }

    fun memoryScheduleSignature(context: Context): String =
        prefs(context).getString(KEY_MEMORY_SCHEDULE_SIG, "").orEmpty()

    fun setMemoryScheduleSignature(context: Context, sig: String) {
        prefs(context).edit().putString(KEY_MEMORY_SCHEDULE_SIG, sig).apply()
    }
}
