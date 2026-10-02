package com.nexus.agent.userservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nexus.agent.R
import com.nexus.agent.ui.MainActivity

/**
 * 前台常驻服务 —— 防止无障碍服务进程被系统回收。
 *
 * ## 背景
 * vivo/iQOO 的省电机制（com.vivo.pem / com.iqoo.powersaving / com.vivo.safecenter）
 * 会在后台静默杀掉无障碍服务进程，导致服务"不稳定"：
 * instance 变 null → getRootNode() 返回 null → Agent 变"瞎"。
 *
 * 前台服务（foreground service）在 Android 里优先级最高，系统几乎不会主动杀，
 * 通过挂一个常驻通知，让无障碍服务与 app 进程常驻，从根上稳住读屏能力。
 *
 * ## 用法
 * 由 [com.nexus.agent.accessibility.NexusAccessibilityService] 在
 * onServiceConnected 时调用 [start]，把进程提升为前台；onDestroy 时无需主动
 * 停止（服务随进程存活，保持常驻即可）。
 */
class NexusForegroundService : Service() {

    companion object {
        private const val TAG = "NexusForegroundService"
        private const val CHANNEL_ID = "nexus_agent_persistent"
        private const val NOTIFICATION_ID = 1001

        /**
         * 启动前台服务，把进程提升为前台常驻。
         */
        fun start(context: Context) {
            val intent = Intent(context, NexusForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                // 后台启动前台服务可能被限制，记录即可，无障碍服务本身仍会运行
                Log.w(TAG, "start foreground service failed", e)
            }
        }

        /**
         * 停止前台服务（可选；通常不需要主动调，进程存活期间保持常驻）。
         */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, NexusForegroundService::class.java))
            } catch (e: Throwable) {
                Log.w(TAG, "stop foreground service failed", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 立即进入前台，避免 startForeground 超时 ANR
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // 系统杀进程后尝试重启；若重启失败返回 START_NOT_STICKY 也接受，
        // 因为无障碍服务本身会在用户重新开启时再次拉起本服务
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 创建通知渠道（Android 8.0+ 必需）。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fg_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.fg_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * 构造并显示前台通知（兼容不同 Android 版本）。
     */
    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 需要显式声明 serviceType
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 构造常驻通知。
     */
    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.fg_notification_title))
            .setContentText(getString(R.string.fg_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
