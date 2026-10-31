package com.nexus.agent.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nexus.agent.R
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.copilot.CopilotEngine
import com.nexus.agent.ui.MainActivity
import com.nexus.agent.userservice.NexusForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 定时任务执行器：闹钟响了之后真正去「干活」的地方。
 *
 * 为什么不做成前台服务：
 * App 本来就有常驻前台服务（[NexusForegroundService]，带 microphone 类型），
 * 闹钟触发时进程通常还活着；这里只负责「尽力把进程/服务提起来 → 跑任务 → 通知结果」。
 * 多开一个 FGS 反而会让 Android 14 的类型校验更啰嗦，收益不大。
 *
 * 前置检查（不满足就明确跳过并告知，绝不在锁屏状态下乱点屏幕）：
 * - 无障碍服务是否绑定
 * - 屏幕是否点亮（锁屏状态直接跳过）
 */
object ScheduleRunner {

    private const val TAG = "ScheduleRunner"

    private const val CHANNEL_ID = "luna_schedule"
    private const val NOTIFY_ID = 7301

    /** 单个任务最长执行时间：超过就中断，避免把电量耗在卡死任务上 */
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

    /** Agent 正忙时最多等多久再放弃（90 秒） */
    private const val BUSY_WAIT_MS = 90 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 串行闸：同一时刻只跑一个定时任务（Agent 本身是全局单例） */
    private val runLock = Mutex()

    private val running = AtomicBoolean(false)

    /** 是否真的在跑（供界面显示状态） */
    val isRunning: Boolean get() = running.get()

    /**
     * 触发一条任务。
     *
     * @param manual true = 用户在界面上点「立即运行」，此时不做锁屏/无障碍的硬拦截以外的额外放宽
     */
    fun trigger(context: Context, taskId: String, manual: Boolean = false) {
        val ctx = context.applicationContext
        val task = ScheduleStore.get(ctx, taskId) ?: run {
            Log.w(TAG, "任务 $taskId 不存在，忽略触发")
            return
        }
        val fireAt = System.currentTimeMillis()

        // 先占坑：无论后面成功失败都要把「下次」排上，否则这条任务就彻底不跑了。
        // 手动「试跑」不消耗排程（尤其一次性任务，试跑不该把它关掉）。
        if (!manual) scheduleNext(ctx, task, fireAt)

        scope.launch {
            runLock.withLock {
                running.set(true)
                try {
                    val skipReason = preflight(ctx)
                    if (skipReason != null) {
                        Log.i(TAG, "跳过任务 ${task.id}：$skipReason")
                        finish(ctx, task, "skip", skipReason, fireAt)
                        return@withLock
                    }

                    // 尽力把常驻服务拉起来，提升进程存活优先级（失败不影响执行）
                    runCatching { NexusForegroundService.start(ctx) }
                        .onFailure { Log.w(TAG, "拉起前台服务失败", it) }

                    // 副驾驶是全局单例，正忙时 ask() 会直接回一句就返回、不回调 onDone，
                    // 那种情况下等下去只会空转到超时，所以先等一小会儿再判死
                    if (!waitUntilIdle()) {
                        finish(ctx, task, "skip", "Agent 正在忙（有人正在用），本次未执行", fireAt)
                        return@withLock
                    }

                    notifyRunning(ctx, task)
                    val reply = runCopilot(ctx, task.prompt)
                    val summary = reply.ifBlank { "任务已下发，未收到明确结果" }
                    finish(ctx, task, "ok", summary, fireAt)
                } catch (e: Throwable) {
                    Log.e(TAG, "任务 ${task.id} 执行异常", e)
                    finish(ctx, task, "fail", "执行异常：${e.message ?: e.javaClass.simpleName}", fireAt)
                } finally {
                    running.set(false)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 执行

    private suspend fun preflight(ctx: Context): String? {
        if (!NexusAccessibilityService.isBound()) return "无障碍服务未连接"
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            // 试着点亮屏幕（ACQUIRE_CAUSES_WAKEUP），大多数任务需要看到界面才能点
            runCatching {
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "luna:schedule_wake"
                ).acquire(8000L)
            }.onFailure { Log.w(TAG, "唤醒屏幕失败", it) }
            delay(1200L)
        }
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) return "手机处于锁屏状态（需要解锁后才能自动操作）"
        return null
    }

    /**
     * 等副驾驶空下来（最多 [BUSY_WAIT_MS]）。
     *
     * 用户正在跟它聊的时候，让定时任务等一会儿比直接丢弃更友好；
     * 等太久则放弃，避免任务排着队把后续时间点全堵住。
     */
    private suspend fun waitUntilIdle(): Boolean {
        if (!CopilotEngine.isBusy) return true
        val deadline = System.currentTimeMillis() + BUSY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(3000L)
            if (!CopilotEngine.isBusy) return true
        }
        return false
    }

    /**
     * 把任务交给副驾驶引擎，等它跑完。
     *
     * [CopilotEngine.ask] 是回调式的，而且「正在忙」时不会回调 onDone，
     * 所以这里必须用超时兜底，否则协程会一直挂着。
     */
    private suspend fun runCopilot(ctx: Context, prompt: String): String {
        return withTimeoutOrNull(RUN_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var lastReply = ""
                CopilotEngine.ask(
                    ctx,
                    prompt,
                    onReply = { r -> lastReply = r },
                    onDone = { if (cont.isActive) cont.resume(lastReply) }
                )
            }
        } ?: run {
            // 超时：把 Agent 停下来，别让它继续占着全局单例
            runCatching { com.nexus.agent.agent.AgentRunner.stop() }
            "执行超时（超过 ${RUN_TIMEOUT_MS / 60000} 分钟已中断）"
        }
    }

    private fun scheduleNext(ctx: Context, task: ScheduledTask, fireAt: Long) {
        if (task.repeat == ScheduledTask.Repeat.ONCE) {
            // 一次性任务：关掉，避免重启后又被重排
            ScheduleStore.disable(ctx, task.id)
            ScheduleScheduler.cancel(ctx, task.id)
        } else {
            // 重复任务：以「触发时刻 + 1 秒」为基准算下一次，
            // 用当前时间算的话，任务跑得久一点就会把今天这次算成「已过」而跳到明天
            val next = task.copy(enabled = true).nextTriggerAt(fireAt + 1000L)
            if (next > 0L) {
                ScheduleScheduler.schedule(ctx, task.copy(enabled = true))
            } else {
                Log.w(TAG, "任务 ${task.id} 算不出下次触发时间")
            }
        }
    }

    private fun finish(ctx: Context, task: ScheduledTask, status: String, summary: String, runAt: Long) {
        ScheduleStore.recordResult(ctx, task.id, status, summary, runAt)
        notifyResult(ctx, task, status, summary)
    }

    // ------------------------------------------------------------------ 通知

    private fun notifyRunning(ctx: Context, task: ScheduledTask) {
        val nm = ensureChannel(ctx) ?: return
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在执行：${task.title}")
            .setContentText(task.prompt.take(60))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(ctx))
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    private fun notifyResult(ctx: Context, task: ScheduledTask, status: String, summary: String) {
        val nm = ensureChannel(ctx) ?: return
        val title = when (status) {
            "ok" -> "✅ 定时任务完成：${task.title}"
            "skip" -> "⏭️ 定时任务已跳过：${task.title}"
            else -> "⚠️ 定时任务失败：${task.title}"
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summary.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(ctx))
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    private fun ensureChannel(ctx: Context): NotificationManager? {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "定时任务的执行结果（完成 / 跳过 / 失败）"
                    setShowBadge(true)
                }
            )
        }
        return nm
    }

    private fun openAppIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            ctx,
            NOTIFY_ID,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
