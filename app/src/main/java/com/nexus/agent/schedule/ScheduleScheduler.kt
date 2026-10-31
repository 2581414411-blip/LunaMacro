package com.nexus.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 用 AlarmManager 排程定时任务。
 *
 * 精度策略（按能力逐级降级，绝不因为拿不到权限就静默不跑）：
 * 1. Android 12+ 且已授予「精确闹钟」→ `setExactAndAllowWhileIdle`，准点触发
 * 2. 其他情况 → `setAndAllowWhileIdle`，系统允许偏差内触发（可能晚几分钟）
 *
 * Doze 模式下普通 `set()` 会被推迟到下一个维护窗口（可能几小时），
 * 所以这里一律走 allow-while-idle 分支。
 */
object ScheduleScheduler {

    private const val TAG = "ScheduleScheduler"

    /** 广播里携带任务 id 的键 */
    const val EXTRA_TASK_ID = "task_id"

    /** 触发任务的动作 */
    const val ACTION_RUN_TASK = "com.nexus.agent.schedule.RUN_TASK"

    // ------------------------------------------------------------------ 对外

    /** 重新排全部任务（开机、时区改变、App 启动时调用） */
    fun rescheduleAll(context: Context) {
        val ctx = context.applicationContext
        val tasks = ScheduleStore.list(ctx)
        tasks.forEach { task ->
            if (task.enabled) {
                schedule(ctx, task)
            } else {
                cancel(ctx, task.id)
            }
        }
        Log.i(TAG, "rescheduleAll: ${tasks.count { it.enabled }} 个启用中")
    }

    /** 排一条任务（会先取消旧的同 id 闹钟，避免重复触发） */
    fun schedule(context: Context, task: ScheduledTask) {
        val ctx = context.applicationContext
        val at = task.nextTriggerAt()
        val pi = pendingIntent(ctx, task.id)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: run {
            Log.e(TAG, "AlarmManager 不可用")
            return
        }
        // 先撤旧的：不然改了时间会两条闹钟一起响
        am.cancel(pi)
        if (at <= 0L) {
            Log.i(TAG, "任务 ${task.id} 无后续触发时间，仅撤不排")
            return
        }

        val exact = canUseExactAlarms(ctx)
        try {
            when {
                exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)

                else -> am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
            Log.i(TAG, "已排程 ${task.id} @ $at (exact=$exact)")
        } catch (e: SecurityException) {
            // 某些 ROM 即使 canScheduleExactAlarms() 也拦，退到不精确模式再来一次
            Log.w(TAG, "精确闹钟被拒，降级为不精确", e)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }.onFailure { Log.e(TAG, "排程彻底失败", it) }
        }
    }

    fun cancel(context: Context, id: String) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(pendingIntent(ctx, id)) }
    }

    /**
     * 是否拥有「精确闹钟」能力。
     *
     * Android 12 起 SCHEDULE_EXACT_ALARM 可被系统收回（省电策略），
     * 13+ 默认授予但用户可关，14+ 新装应用默认不授予 —— 必须运行时判断。
     */
    fun canUseExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    // ------------------------------------------------------------------ 内部

    private fun pendingIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_RUN_TASK
            putExtra(EXTRA_TASK_ID, id)
        }
        // requestCode 用 id 的哈希：同一任务重复 schedule 会复用同一个 PendingIntent，
        // 配合 FLAG_UPDATE_CURRENT 才能正确覆盖 extra
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
