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
 * 真正的闸门是：
 *
 *  1. 只在 [WAKE_HOUR]:00 ~ [SLEEP_HOUR]:00 之间发（谁也不想被凌晨三点的关心吵醒）；
 *  2. 距上次对话至少 [IDLE_MINUTES] 分钟（你刚说完话，她不该马上插嘴）；
 *  3. 距上次主动问候至少 [MIN_GAP_MINUTES] 分钟（冷却，也是「每小时最多 4 条」的来源）；
 *  4. 生成失败过就静默 [com.nexus.agent.copilot.CopilotConfig.HEARTBEAT_FAIL_BACKOFF_MINUTES] 分钟。
 *
 * 15 分钟周期 + 15 分钟冷却 = 理论上限每小时 4 条，但真实触发取决于你什么时候停手不聊，
 * 不是定时推送。白天窗口内一天最多 52 条，这是用户明确要的「她一直在」。
 */
object HeartbeatScheduler {

    private const val TAG = "Heartbeat"

    /** 周期任务唯一名。重复 enqueue 用 KEEP，不会叠成一堆 */
    const val WORK_NAME = "luna_heartbeat"
    private const val TAG_NAME = "luna_heartbeat"

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

    /** 通知频道：和定时任务分开，用户可以单独静音「主动关心」 */
    private const val CHANNEL_ID = "luna_heartbeat"
    private const val NOTIFY_ID = 0x4C55

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
        BACKOFF
    }

    /**
     * 判定要不要发（纯函数，单测覆盖）。
     *
     * @param hourOfDay 0~23
     * @param lastChatAt 最后一次对话时间戳，0 = 从没聊过
     * @param lastHeartbeatAt 最后一次主动问候时间戳，0 = 从没发过
     * @param force 手动触发（设置页「现在就试」），跳过 NIGHT / NOT_IDLE / COOLDOWN / BACKOFF
     * @param backoffUntil 失败静默截止时间戳，0 = 没在退避
     */
    fun decide(
        nowMs: Long,
        hourOfDay: Int,
        lastChatAt: Long,
        lastHeartbeatAt: Long,
        enabled: Boolean,
        force: Boolean = false,
        backoffUntil: Long = 0L
    ): Verdict {
        if (!enabled) return Verdict.DISABLED
        if (force) return Verdict.SEND
        if (hourOfDay < WAKE_HOUR || hourOfDay >= SLEEP_HOUR) return Verdict.NIGHT
        if (lastChatAt <= 0L) return Verdict.NEVER_CHATTED
        if (backoffUntil > nowMs) return Verdict.BACKOFF

        val idleMs = nowMs - lastChatAt
        if (idleMs < IDLE_MINUTES * MINUTE_MS) return Verdict.NOT_IDLE
        if (lastHeartbeatAt > 0L && nowMs - lastHeartbeatAt < MIN_GAP_MINUTES * MINUTE_MS) {
            return Verdict.COOLDOWN
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

    /** 幂等：已经排了就不动（KEEP），用户开关反复点也不会重置计时 */
    fun schedule(context: Context) {
        if (!CopilotConfig.isHeartbeatEnabled(context)) {
            Log.i(TAG, "主动问候已关闭，不排任务")
            cancel(context)
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
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.i(TAG, "心跳已排：每 ${CHECK_INTERVAL_MINUTES} 分钟检查一次，首次 ${delay} 分钟后")
        }.onFailure { Log.w(TAG, "心跳排程失败（WorkManager 不可用？）", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
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
        val verdict = decide(
            nowMs = now,
            hourOfDay = hour,
            lastChatAt = lastChat,
            lastHeartbeatAt = lastBeat,
            enabled = enabled,
            force = force,
            backoffUntil = CopilotConfig.heartbeatBackoffUntil(context)
        )
        Log.d(
            TAG,
            "心跳判定=$verdict force=$force hour=$hour " +
                "上次对话=${if (lastChat <= 0L) "无" else "${(now - lastChat) / MINUTE_MS}分钟前"} " +
                "上次问候=${if (lastBeat <= 0L) "无" else "${(now - lastBeat) / MINUTE_MS}分钟前"}"
        )
        if (verdict != Verdict.SEND) return verdict.name

        val text = runCatching { PersonaEngine.proactiveGreeting(context) }
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
        Log.i(TAG, "已主动开口：${text.take(40)}")
        return "SENT"
    }

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
