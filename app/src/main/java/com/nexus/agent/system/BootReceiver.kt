package com.nexus.agent.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexus.agent.userservice.NexusForegroundService

/**
 * 开机自启接收器 —— 设备重启后自动拉起前台服务。
 *
 * vivo/iQOO 重启手机后，无障碍服务通常不会自动恢复绑定（需要用户重新开），
 * 但前台服务会因 START_STICKY 尽量重建。这里在 BOOT_COMPLETED 时主动拉起
 * 前台服务，提升进程存活优先级，间接为无障碍服务重新绑定创造机会。
 *
 * 注意：BOOT_COMPLETED 在部分 vivo 机型上需要用户手动在「自启动管理」里
 * 允许本应用自启动，否则收不到广播（这正是 StabilityHelper 引导自启动的原因）。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "received $action, restarting foreground service")
                try {
                    NexusForegroundService.start(context)
                } catch (e: Throwable) {
                    Log.e(TAG, "restart foreground service failed", e)
                }
            }
        }
    }
}
