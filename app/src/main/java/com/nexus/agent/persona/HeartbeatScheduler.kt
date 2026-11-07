package com.nexus.agent.persona

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nexus.agent.R
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.TopicSeed
import com.nexus.agent.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 生命脉搏（0.9.2 批次二，0.9.3 放开频率）—— 让她在你沉默之后，主动说一句话。
 *
 * ## 为什么用 WorkManager
 *
 * App 里已经有一套基于 `AlarmManager` 的定时任务（[com.nexus.agent.schedule.ScheduleScheduler]），
 * 但它解决的是「用户指定几点干一件事」，需要精确时刻、需要在 App 被清理后还能重排。
 * 这里要的是**尽力而为的周期性检查** —— 早两小时晚两小时都无所谓，但不能因为一次失败就死掉。
 * 这正是 WorkManager 的语义：系统会保证它最终跑到，且不用自己处理重启补排。
 *
 * ## 检查频率 ≠ 打扰频率
 *
 * 0.9.3 起周期从 6 小时压到 **15 分钟**（WorkManager 的周期下限），也就是「每小时 4 次机会」。
 * 0.9.4 又在上面加了三道「防破产」闸门，免得「她一直在」变成「她一直在烦你」：
 *
 *  1. 只在 [WAKE_HOUR]:00 ~ [SLEEP_HOUR]:00 之间发（谁也不想被凌晨三点的关心吵醒）；
 *  2. 距上次对话至少 [IDLE_MINUTES] 分钟（你刚说完话，她不该马上插嘴）；
 *  3. 距上次主动问候至少 [MIN_GAP_MINUTES] 分钟（冷却，也是「每小时最多 4 条」的来源）；
 *  4. 生成失败过就静默 [com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_FAIL_BACKOFF_MINUTES] 分钟；
 *  5. **一天最多 [com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_DAILY_LIMIT] 条**，发满闭嘴；
 *  6. **连续 2 次开口后用户 4 小时没回**，最小间隔从 15 分钟拉到
 *     [com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_SLOW_GAP_MINUTES]（2 小时）—— 识趣一点。
 *
 * 15 分钟周期 + 15 分钟冷却 = 理论上限每小时 4 条，但真实触发取决于你什么时候停手不聊，
 * 不是定时推送；每日上限和降频负责兜住「一天到底能来多少条」。
 */
object HeartbeatScheduler {

    private const val TAG = "Heartbeat"

    /** 周期任务唯一名。0.9.4 起用 UPDATE 策略，见 [schedule] */
    const val WORK_NAME = "luna_heartbeat"
    private const val TAG_NAME = "luna_heartbeat"

    /**
     * 调度指纹（0.9.4）。
     *
     * 解决一个副作用：策略从 KEEP 换成 UPDATE 之后，**每次冷启动的 enqueue 都会重置计时**
     * （UPDATE 会用新 request 覆盖旧的，包括 initialDelay）。NexusApplication 每次进程启动
     * 都会调 [schedule]，用户反复开 App 就会把「3 分钟后第一次检查」无限往后推。
     *
     * 所以：指纹没变就不重复 enqueue —— 升级上来的老用户第一次进来指纹不同，
     * 会被 UPDATE 覆盖成 15 分钟；之后每次启动都是空操作。
     * **以后改周期/改策略，记得把这个指纹也改一下**，否则老用户不会被重排。
     */
    const val SCHEDULE_SIGNATURE = "15min-update-0.9.4"

    /**
     * 检查间隔：15 分钟。
     *
     * **这是 WorkManager 周期任务的下限**（MIN_PERIODIC_INTERVAL_MILLIS），写更小也会被系统按 15 分钟跑，
     * 所以「每小时 4 次机会」就是这个框架能给到的天花板。
     */
    const val CHECK_INTERVAL_MINUTES = 15L

    /** 白天窗口：[WAKE_HOUR]:00 开始，[SLEEP_HOUR]:00 之前结束 */
    const val WAKE_HOUR = 9
    const val SLEEP_HOUR = 22

    /** 多久没说话就算「没理我」，给她一次开口的机会 */
    const val IDLE_MINUTES = 15L

    /** 两条主动消息之间的最小间隔（= 每小时最多 4 条） */
    const val MIN_GAP_MINUTES = 15L

    /** 一分钟的毫秒数，下面几处时间运算共用 */
    private const val MINUTE_MS = 60_000L

    /** 同一个话题种子至少隔多久才能再问（0.9.4） */
    const val TOPIC_REASK_HOURS = 48L

    /** 通知频道：和定时任务分开，用户可以单独静音「主动关心」 */
    private const val CHANNEL_ID = "luna_heartbeat"

    /** 通知 id（0.9.4 批次三会按会话派生，先留着保证单条通知可用） */
    private const val NOTIFY_ID = 0x4C55

    /**
     * 会话通知 id 的种子（0.9.4 弃用固定 NOTIFY_ID）。
     *
     * MessagingStyle 要「堆叠成一条会话」，就必须始终用同一个 id 更新（否则每条消息
     * 都是一个独立通知，反而更乱）；这里不再是硬编码常数，而是按助手名派生 ——
     * 改名字相当于开一个新会话，旧会话通知不会被串改。
     */
    private const val CONVERSATION_KEY = "luna_beat_"

    /** 一次心跳的判定结果（也是日志里的取值） */
    enum class Verdict {
        /** 该发了 */
        SEND,

        /** 用户关掉了主动问候 */
        DISABLED,

        /** 现在是夜里 / 太早 */
        NIGHT,

        /** 从没聊过，没有可依据的记忆 —— 不发 */
        NEVER_CHATTED,

        /** 最近还在聊，不打扰 */
        NOT_IDLE,

        /** 刚主动问候过，冷却中 */
        COOLDOWN,

        /** 上次生成失败，正在静默期（别拿失效的 Key 每 15 分钟打一次接口） */
        BACKOFF,

        /** 今天已经发满 10 条（0.9.4 防破产），等明天 */
        DAILY_LIMIT,

        /** 连续两次被晾着，主动降到 2 小时一条（0.9.4 识趣） */
        SLOWED
    }

    /**
     * 判定要不要发（纯函数，单测覆盖）。
     *
     * @param hourOfDay 0~23
     * @param lastChatAt 最后一次对话时间戳，0 = 从没聊过
     * @param lastHeartbeatAt 最后一次主动问候时间戳，0 = 从没发过
     * @param force 手动触发（设置页「现在就试」），跳过 NIGHT / NOT_IDLE / COOLDOWN / BACKOFF /
     *              DAILY_LIMIT / SLOWED 全部闸门，只保留总开关
     * @param backoffUntil 失败静默截止时间戳，0 = 没在退避
     * @param sentToday 今天已经主动发了几条（见 [CopilotConfig.heartbeatSentToday]）
     * @param ignoredStreak 连续几次开口后 4 小时无人应答（见 [CopilotConfig.refreshIgnoredStreak]）
     */
    fun decide(
        nowMs: Long,
        hourOfDay: Int,
        lastChatAt: Long,
        lastHeartbeatAt: Long,
        enabled: Boolean,
        force: Boolean = false,
        backoffUntil: Long = 0L,
        sentToday: Int = 0,
        ignoredStreak: Int = 0
    ): Verdict {
        if (!enabled) return Verdict.DISABLED
        if (force) return Verdict.SEND
        if (hourOfDay < WAKE_HOUR || hourOfDay >= SLEEP_HOUR) return Verdict.NIGHT
        if (lastChatAt <= 0L) return Verdict.NEVER_CHATTED
        if (backoffUntil > nowMs) return Verdict.BACKOFF
        // 一天 10 条是硬上限：她说得再多也不该变成信息轰炸
        if (sentToday >= CopilotConfig.HEARTBEAT_DAILY_LIMIT) return Verdict.DAILY_LIMIT

        val idleMs = nowMs - lastChatAt
        if (idleMs < IDLE_MINUTES * MINUTE_MS) return Verdict.NOT_IDLE
        if (lastHeartbeatAt > 0L && nowMs - lastHeartbeatAt < MIN_GAP_MINUTES * MINUTE_MS) {
            return Verdict.COOLDOWN
        }
        // 连着两次都被晾着 → 识趣，隔 2 小时再开口（不是赌气，是别把电和钱烧在没人看的地方）
        if (ignoredStreak >= CopilotConfig.HEARTBEAT_IGNORED_STREAK_TO_SLOW &&
            lastHeartbeatAt > 0L &&
            nowMs - lastHeartbeatAt < CopilotConfig.HEARTBEAT_SLOW_GAP_MINUTES * MINUTE_MS
        ) {
            return Verdict.SLOWED
        }
        return Verdict.SEND
    }

    /**
     * 第一次检查该等多久。
     *
     * 夜里注册任务时直接等 15 分钟会正好落在凌晨（白跑一次），
     * 所以夜里统一推到当天/次日 [WAKE_HOUR]:05 附近；白天则 3 分钟后就来一次，
     * 让「刚打开 App 就装上心跳」的用户当天就能看到效果。
     */
    fun initialDelayMinutes(hourOfDay: Int, minute: Int): Long {
        if (hourOfDay in WAKE_HOUR until SLEEP_HOUR) return 3L
        val untilWake = if (hourOfDay >= SLEEP_HOUR) {
            (24 - hourOfDay + WAKE_HOUR) * 60L - minute
        } else {
            (WAKE_HOUR - hourOfDay) * 60L - minute
        }
        return untilWake.coerceAtLeast(1L) + 5L
    }

    // ------------------------------------------------------------ 调度

    /**
     * 幂等排程。
     *
     * **0.9.4 的关键修复**：原来用 [ExistingPeriodicWorkPolicy.KEEP]，而任务名沿用了 0.9.2 的
     * `luna_heartbeat`。KEEP 的语义是「已有同名任务就什么都不做」，而 WorkManager 的任务存在
     * App 的数据库里、覆盖安装不清数据 —— 于是从 0.9.2 升上来的用户**实际还是 6 小时一次**，
     * 日志却照样打印「每 15 分钟检查一次」。日志骗人，体感就是「她还是半天才理我一次」。
     *
     * 现在改成 [ExistingPeriodicWorkPolicy.UPDATE]：升级后第一次启动会用新周期覆盖旧任务。
     * 为了不让每次冷启动都把计时重置，用 [SCHEDULE_SIGNATURE] 做闸门（见常量注释）。
     */
    fun schedule(context: Context) {
        if (!CopilotConfig.isHeartbeatEnabled(context)) {
            Log.i(TAG, "主动问候已关闭，不排任务")
            cancel(context)
            return
        }
        if (CopilotConfig.heartbeatScheduleSignature(context) == SCHEDULE_SIGNATURE) {
            Log.d(TAG, "心跳已按 [$SCHEDULE_SIGNATURE] 排过，跳过（避免冷启动重置计时）")
            return
        }
        val now = Calendar.getInstance()
        val delay = initialDelayMinutes(
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE)
        )
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(
            CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .addTag(TAG_NAME)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            CopilotConfig.setHeartbeatScheduleSignature(context, SCHEDULE_SIGNATURE)
            Log.i(TAG, "心跳已排（$SCHEDULE_SIGNATURE）：每 $CHECK_INTERVAL_MINUTES 分钟检查一次，首次 $delay 分钟后")
        }.onFailure { Log.w(TAG, "心跳排程失败（WorkManager 不可用？）", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            // 取消后指纹也要清掉，否则重新打开开关会「以为已经排了」而什么都不做
            CopilotConfig.setHeartbeatScheduleSignature(context, "")
            Log.i(TAG, "心跳已取消")
        }.onFailure { Log.w(TAG, "取消失败", it) }
    }

    /**
     * 立刻跑一次（设置页「现在就试」）。
     *
     * 走 force 分支：跳过时间窗、24 小时、冷却三道闸，只保留总开关。
     * 不然用户点完等半天没反应，会以为是坏的。
     */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
            .setInputData(workDataOf(HeartbeatWorker.KEY_FORCE to true))
            .addTag(TAG_NAME)
            .build()
        runCatching { WorkManager.getInstance(context).enqueue(request) }
            .onFailure { Log.w(TAG, "立即触发失败", it) }
    }

    /** 开关入口：改完偏好要同步调度状态 */
    fun setEnabled(context: Context, enabled: Boolean) {
        CopilotConfig.setHeartbeatEnabled(context, enabled)
        if (enabled) schedule(context) else cancel(context)
    }

    // ------------------------------------------------------------ 实际执行

    /**
     * 真正跑一轮：判 → 生成 → 通知。
     *
     * 返回判定结果字符串，Worker 用它写日志；单测也能直接调（除了生成那段会要模型）。
     */
    suspend fun perform(context: Context, force: Boolean = false): String {
        val enabled = CopilotConfig.isHeartbeatEnabled(context)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val lastChat = CopilotConfig.lastChatAt(context)
        val lastBeat = CopilotConfig.lastHeartbeatAt(context)
        // 先结算上一次「她开口了但没人理」——降频判定要用最新值
        val ignoredStreak = CopilotConfig.refreshIgnoredStreak(context, now)
        val sentToday = CopilotConfig.heartbeatSentToday(context)
        val verdict = decide(
            nowMs = now,
            hourOfDay = hour,
            lastChatAt = lastChat,
            lastHeartbeatAt = lastBeat,
            enabled = enabled,
            force = force,
            backoffUntil = CopilotConfig.heartbeatBackoffUntil(context),
            sentToday = sentToday,
            ignoredStreak = ignoredStreak
        )
        Log.d(
            TAG,
            "心跳判定=$verdict force=$force hour=$hour 今日已发=$sentToday/${
                CopilotConfig.HEARTBEAT_DAILY_LIMIT
            } 无视连击=$ignoredStreak " +
                "上次对话=${if (lastChat <= 0L) "无" else "${(now - lastChat) / MINUTE_MS}分钟前"} " +
                "上次问候=${if (lastBeat <= 0L) "无" else "${(now - lastBeat) / MINUTE_MS}分钟前"}"
        )
        if (verdict != Verdict.SEND) return verdict.name

        // 0.9.4：开口要有理由 —— 先看有没有「记挂着的事」，没有才泛泛问候
        val seed = pickTopicSeed(context, now)
        val text = runCatching { PersonaEngine.proactiveGreeting(context, seed?.content) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text == null) {
            // Key 失效 / 没网时不记账的话，接下来每次 15 分钟检查都会再打一次接口
            CopilotConfig.markHeartbeatFail(context)
            Log.w(TAG, "问候生成失败，静默 ${CopilotConfig.HEARTBEAT_FAIL_BACKOFF_MINUTES} 分钟再试")
            return "NO_TEXT"
        }
        CopilotConfig.clearHeartbeatFail(context)

        // 顺序有讲究：先落进聊天记录再弹通知 ——
        // 用户从通知点进来那一刻，那句话必须已经在对话里了，否则点开是空的，比不通知还怪。
        // 未读计数在入档前 +1：入档会广播一次界面刷新，红点必须在那一刻就已经是新值。
        CopilotConfig.bumpUnread(context)
        deliverToChat(context, text)
        notifyGreeting(context, text)
        CopilotConfig.markHeartbeatNow(context)
        CopilotConfig.bumpHeartbeatToday(context)
        // 种子记账放最后：只有这句话真的发出去了，才算「问过」（否则失败重试时话题就白丢了）
        if (seed != null) {
            runCatching { AppDatabase.get(context).topicSeedDao().markAsked(seed.id, now) }
                .onFailure { Log.w(TAG, "话题种子记账失败", it) }
        }
        Log.i(
            TAG,
            if (seed != null) {
                "已主动开口（话题「${seed.content}」第 ${seed.askCount + 1} 次）：${text.take(40)}"
            } else {
                "已主动开口（无话题种子，泛泛问候）：${text.take(40)}"
            }
        )
        return "SENT"
    }

    /**
     * 挑一条「该问了」的话题种子。
     *
     * 条件：未过期 + （从没问过，或距上次问已过 [TOPIC_REASK_HOURS] 小时）。
     * 优先级：问得少的排在前面，同档按加入时间 —— 「明天体检」比「下周出差」更急。
     *
     * 数据库不可用时返回 null：退回泛泛问候，绝不让心跳因为读库失败而彻底不发。
     */
    private suspend fun pickTopicSeed(context: Context, nowMs: Long): TopicSeed? = runCatching {
        val dao = AppDatabase.get(context).topicSeedDao()
        // 顺手清过期种子：不清就会攒出「你头还疼吗」——那是三周前的头疼
        runCatching { dao.purgeExpired(nowMs) }
        dao.nextCandidate(nowMs, nowMs - TOPIC_REASK_HOURS * 3_600_000L)
    }.onFailure {
        Log.w(TAG, "读取话题种子失败（本轮退化为泛泛问候）：${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    /**
     * 把这句话写成一条真实聊天消息（0.9.3）。
     *
     * 两件事：
     *  - [PersonaEngine.addAssistantItem]：进聊天流 + 落库，通知点进来就能看到；
     *  - [PersonaEngine.rememberProactiveLine]：进对话记忆窗口，
     *    否则用户回一句「嗯？」时她不知道自己刚说了什么。
     *
     * 必须切主线程：Compose 的 state 只能在主线程改，Worker 默认跑在后台线程。
     */
    private suspend fun deliverToChat(context: Context, text: String) {
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                PersonaEngine.init(context)
                PersonaEngine.addAssistantItem(text)
                PersonaEngine.rememberProactiveLine(text)
            }.onFailure { Log.w(TAG, "写进聊天记录失败：${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun notifyGreeting(ctx: Context, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "主动关心",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "她想起你了，主动说一句话"
                    setShowBadge(true)
                }
            )
        }
        val name = runCatching { CopilotConfig.assistantName(ctx) }.getOrDefault("宏月")
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(name)
            .setContentText(text.take(60))
            // 长文本要能展开——一句问候被截成半句最伤
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(ctx))
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
            .onFailure { Log.w(TAG, "通知发送失败（可能没给通知权限）", it) }
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // 0.9.3：点通知直接落在她的聊天页，而不是丢用户回首页自己找
            putExtra(MainActivity.EXTRA_OPEN_PERSONA, true)
        }
        return PendingIntent.getActivity(
            ctx,
            0x4C55,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * WorkManager 的壳子。
 *
 * 逻辑全在 [HeartbeatScheduler.perform] 里（那样才能在纯 JVM 单测里直接调），
 * 这里只负责「失败不要重试到把手机跑没电」：
 * 生成问候失败是常态（Key 过期、没网），重试也一样，所以统一返回 success，
 * 等下一个 6 小时周期自然会再试一次。
 */
class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_FORCE = "force"
    }

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        return try {
            HeartbeatScheduler.perform(applicationContext, force)
            Result.success()
        } catch (e: Throwable) {
            Log.w("Heartbeat", "心跳执行异常：${e.javaClass.simpleName} ${e.message}")
            Result.success()
        }
    }
}
