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
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 生命脉搏（0.9.2 批次二）—— 让她在你沉默太久之后，主动说一句话。
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
 * 周期是 [CHECK_INTERVAL_HOURS]（6 小时），但真正决定「发不发」的是三道闸：
 *
 *  1. 只在 [WAKE_HOUR]:00 ~ [SLEEP_HOUR]:00 之间发（谁也不想被凌晨三点的关心吵醒）；
 *  2. 距上次对话至少 [IDLE_HOURS] 小时（你刚聊完，她不该追问「在吗」）；
 *  3. 距上次主动问候至少 [MIN_GAP_HOURS] 小时（冷却）。
 *
 * 所以一天最多一条。周期取 6 而不是 12/24，是因为 Android 的周期任务本身不精确
 * （系统省电策略下可能延后几小时），12 小时一次很容易两次都落在夜里、一整天都没问候。
 */
object HeartbeatScheduler {

    private const val TAG = "Heartbeat"

    /** 周期任务唯一名。重复 enqueue 用 KEEP，不会叠成一堆 */
    const val WORK_NAME = "luna_heartbeat"
    private const val TAG_NAME = "luna_heartbeat"

    /** 检查频率（不是打扰频率）；见类注释 */
    const val CHECK_INTERVAL_HOURS = 6L

    /** 白天窗口：[WAKE_HOUR]:00 开始，[SLEEP_HOUR]:00 之前结束 */
    const val WAKE_HOUR = 9
    const val SLEEP_HOUR = 22

    /** 多久没说话才值得主动找一次 */
    const val IDLE_HOURS = 24L

    /** 两次主动问候之间的最小间隔（略小于 24h，防 6h 检查错位导致漏一天） */
    const val MIN_GAP_HOURS = 20L

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
        COOLDOWN
    }

    /**
     * 判定要不要发（纯函数，单测覆盖）。
     *
     * @param hourOfDay 0~23
     * @param lastChatAt 最后一次对话时间戳，0 = 从没聊过
     * @param lastHeartbeatAt 最后一次主动问候时间戳，0 = 从没发过
     * @param force 手动触发（设置页「现在就试」），跳过 NIGHT / NOT_IDLE / COOLDOWN 三道闸
     */
    fun decide(
        nowMs: Long,
        hourOfDay: Int,
        lastChatAt: Long,
        lastHeartbeatAt: Long,
        enabled: Boolean,
        force: Boolean = false
    ): Verdict {
        if (!enabled) return Verdict.DISABLED
        if (force) return Verdict.SEND
        if (hourOfDay < WAKE_HOUR || hourOfDay >= SLEEP_HOUR) return Verdict.NIGHT
        if (lastChatAt <= 0L) return Verdict.NEVER_CHATTED

        val idleMs = nowMs - lastChatAt
        if (idleMs < IDLE_HOURS * 3_600_000L) return Verdict.NOT_IDLE
        if (lastHeartbeatAt > 0L && nowMs - lastHeartbeatAt < MIN_GAP_HOURS * 3_600_000L) {
            return Verdict.COOLDOWN
        }
        return Verdict.SEND
    }

    /**
     * 第一次检查该等多久。
     *
     * 夜里注册任务时直接等 6 小时会正好落在凌晨（白跑一次），
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
            CHECK_INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.MINUTES)
            .addTag(TAG_NAME)
            .build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            Log.i(TAG, "心跳已排：每 ${CHECK_INTERVAL_HOURS}h 检查一次，首次 ${delay} 分钟后")
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
        val verdict = decide(
            nowMs = now,
            hourOfDay = hour,
            lastChatAt = CopilotConfig.lastChatAt(context),
            lastHeartbeatAt = CopilotConfig.lastHeartbeatAt(context),
            enabled = enabled,
            force = force
        )
        Log.d(
            TAG,
            "心跳判定=$verdict force=$force hour=$hour " +
                "上次对话=${if (CopilotConfig.lastChatAt(context) == 0L) "无" else "${(now - CopilotConfig.lastChatAt(context)) / 3_600_000}小时前"} " +
                "上次问候=${if (CopilotConfig.lastHeartbeatAt(context) == 0L) "无" else "${(now - CopilotConfig.lastHeartbeatAt(context)) / 3_600_000}小时前"}"
        )
        if (verdict != Verdict.SEND) return verdict.name

        val text = runCatching { PersonaEngine.proactiveGreeting(context) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (text == null) {
            Log.w(TAG, "问候生成失败（模型没接上/Key 无效/超时），本轮不发")
            return "NO_TEXT"
        }
        notifyGreeting(context, text)
        CopilotConfig.markHeartbeatNow(context)
        Log.i(TAG, "已主动问候：${text.take(40)}")
        return "SENT"
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
