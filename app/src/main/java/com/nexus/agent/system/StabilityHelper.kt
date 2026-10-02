package com.nexus.agent.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 稳定性辅助 —— 引导用户把 App 加入省电/自启动白名单。
 *
 * vivo/iQOO 上，即便开了前台服务，`com.vivo.pem` 等进程管理仍可能在
 * 深度省电 / 待机时回收无障碍服务。要「最稳定」，需让用户手动完成三件事：
 * 1. 忽略电池优化（允许后台运行）
 * 2. 允许自启动 + 关联启动（vivo 特有）
 * 3. 后台高耗电/智能耗电保护白名单
 *
 * 本类封装状态检测 + 跳转入口。vivo 的跳转 Activity/action 非公开 API，
 * 不同机型/系统版本可能变动，故所有跳转都做了 try-catch，失败时降级到
 * 通用设置页或 Toast 提示手动路径。
 */
object StabilityHelper {

    private const val TAG = "StabilityHelper"

    /**
     * 是否已忽略电池优化（允许后台运行）。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳转到「忽略电池优化」设置页。
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            // 某些机型不支持直跳，降级到应用详情页
            openAppDetails(context)
        }
    }

    /**
     * 跳转到 vivo/iQOO 的「自启动管理」。
     *
     * vivo 的 i管家 自启动管理没有稳定的公开 intent，这里尝试多个已知入口，
     * 全部失败则降级到应用详情页让用户手动找。
     */
    fun openAutoStartSettings(context: Context) {
        val pkg = context.packageName
        val attempts = listOf(
            // 通用：自启动管理（部分 ROM 支持）
            Intent("com.vivo.permissionmanager.action.AUTOSTART_SETTING"),
            Intent("com.vivo.smartmultiwindow.action.AUTOSTART"),
            // i管家 权限管理页
            Intent().apply {
                component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            },
            Intent().apply {
                component = ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AutoStartActivity")
            }
        )

        for (intent in attempts) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: Throwable) {
                Log.d(TAG, "autoStart intent failed: ${intent.component ?: intent.action}")
            }
        }
        // 全部失败 → 应用详情页
        openAppDetails(context)
    }

    /**
     * 跳转到「电池/后台耗电管理」（vivo 智能耗电保护）。
     */
    fun openBackgroundPowerSettings(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.vivo.pem",
                    "com.vivo.pem.ui.AppSettingsActivity"
                )
                putExtra("packageName", context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.d(TAG, "pem intent failed, fallback to app details")
            openAppDetails(context)
        }
    }

    /**
     * 跳转到应用详情页（通用兜底）。
     */
    fun openAppDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(TAG, "openAppDetails failed", e)
        }
    }

    /**
     * 返回稳定性状态摘要（供 UI 展示）。
     */
    fun statusText(context: Context): String {
        val sb = StringBuilder()
        sb.append(if (isIgnoringBatteryOptimizations(context)) "✅" else "❌").append(" 忽略电池优化\n")
        sb.append("⚠️ 自启动/后台白名单（需手动到 i管家 设置）")
        return sb.toString()
    }
}
