package com.nexus.agent.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 定时任务广播接收器。
 *
 * 两类入口：
 * 1. 自己排的闹钟（[ScheduleScheduler.ACTION_RUN_TASK]）→ 交给 [ScheduleRunner] 执行
 * 2. 系统时间/时区变化 → 重新排全部任务（否则 `RTC_WAKEUP` 的绝对时间会整体错位）
 *
 * 注意：onReceive 里不做耗时操作，执行一律扔进协程。
 */
class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        when (intent.action) {
            ScheduleScheduler.ACTION_RUN_TASK -> {
                val id = intent.getStringExtra(ScheduleScheduler.EXTRA_TASK_ID)
                if (id.isNullOrBlank()) {
                    Log.w(TAG, "闹钟触发但没带任务 id")
                    return
                }
                Log.i(TAG, "闹钟触发任务 $id")
                ScheduleRunner.trigger(ctx, id)
            }

            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i(TAG, "系统时间变化（${intent.action}），重排全部定时任务")
                ScheduleScheduler.rescheduleAll(ctx)
            }
        }
    }

    private companion object {
        const val TAG = "ScheduleReceiver"
    }
}
