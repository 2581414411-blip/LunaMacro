package com.nexus.agent.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.nexus.agent.input.ShizukuManager

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
    private const val PREFS = "stability"

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
     * 请求忽略电池优化（允许后台运行）。
     *
     * 跳转到系统「忽略电池优化」授权页，data 指向本应用。
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "requestIgnoreBatteryOptimizations failed, fallback to app details", e)
            openAppDetails(context)
        }
    }

    /**
     * 厂商白名单跳转表：各主流 ROM 的「自启动/后台白名单」管理页精确入口。
     *
     * 借鉴「喵喵助手」的保活手段——它硬编码了 8 家厂商的自启动管理页，
     * 引导用户一键打开，把 App 加入自启动白名单，从 ROM 层面放行后台存活。
     *
     * 每个条目：Pair(检测关键字, 跳转 Intent)。检测关键字用于匹配
     * Build.MANUFACTURER / Build.BRAND，命中后按顺序尝试该厂商的 Intent。
     */
    private data class VendorAutoStart(
        val keyword: String,
        val intents: List<Intent>
    )

    /**
     * 所有支持的厂商自启动跳转入口（按优先级排序）。
     */
    private fun vendorAutoStartTable(): List<VendorAutoStart> = listOf(
        VendorAutoStart("vivo", listOf(
            Intent("com.vivo.permissionmanager.action.AUTOSTART_SETTING"),
            Intent("com.vivo.smartmultiwindow.action.AUTOSTART"),
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )),
            Intent().setComponent(ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            ))
        )),
        VendorAutoStart("iqoo", listOf(
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )),
            Intent().setComponent(ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AutoStartActivity"
            ))
        )),
        VendorAutoStart("xiaomi", listOf(
            Intent("miui.intent.action.OP_AUTO_START"),
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ))
        )),
        VendorAutoStart("redmi", listOf(
            Intent("miui.intent.action.OP_AUTO_START"),
            Intent().setComponent(ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ))
        )),
        VendorAutoStart("oppo", listOf(
            Intent("com.oplus.app.action.STARTUP_MANAGER"),
            Intent("com.coloros.safecenter.action.STARTUP_MANAGER"),
            Intent().setComponent(ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ))
        )),
        VendorAutoStart("oneplus", listOf(
            Intent().setComponent(ComponentName(
                "com.oneplus.security",
                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
            ))
        )),
        VendorAutoStart("huawei", listOf(
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )),
            Intent().setComponent(ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ))
        )),
        VendorAutoStart("honor", listOf(
            Intent().setComponent(ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.optimize.process.ProtectActivity"
            ))
        )),
        VendorAutoStart("samsung", listOf(
            Intent().setComponent(ComponentName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            ))
        )),
        VendorAutoStart("meizu", listOf(
            Intent().setComponent(ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.security.zzlh.AppAutoRunActivity"
            ))
        )),
        VendorAutoStart("letv", listOf(
            Intent().setComponent(ComponentName(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity"
            ))
        ))
    )

    /**
     * 自动探测当前厂商，跳转到对应的「自启动/后台白名单」管理页。
     *
     * 覆盖 vivo/iQOO/小米/Redmi/OPPO/一加/华为/荣耀/三星/魅族/乐视。
     * 命中厂商则依次尝试该厂商的跳转 Intent；全部失败或未识别厂商时，
     * 降级到应用详情页（Settings.ACTION_APPLICATION_DETAILS_SETTINGS）。
     *
     * @return true 表示成功跳转到某个厂商管理页，false 表示降级到应用详情页。
     */
    fun openAutoStartByVendor(context: Context): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val combined = "$manufacturer $brand"

        val table = vendorAutoStartTable()
        // 先精确匹配当前厂商
        val matched = table.filter { combined.contains(it.keyword) }
        val candidates = if (matched.isNotEmpty()) matched else table

        for (vendor in candidates) {
            for (intent in vendor.intents) {
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.i(TAG, "autoStart vendor=${vendor.keyword} intent=${intent.component ?: intent.action}")
                    return true
                } catch (e: Throwable) {
                    Log.d(TAG, "autoStart vendor=${vendor.keyword} failed: ${intent.component ?: intent.action}")
                }
            }
        }
        // 全失败 → 应用详情页兜底
        openAppDetails(context)
        return false
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
     * 跳转到 OriginOS 的自启动管理页（BgStartUpManagerActivity）。
     *
     * 失败时回退到应用详情页让用户手动设置。
     */
    fun openOriginOsAutoStart(context: Context) {
        try {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.d(TAG, "OriginOS autostart intent failed, fallback to app details")
            openAppDetails(context)
        }
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
     * 打开 thedjchi 版 Shizuku 的设置页。
     *
     * 优先尝试打开设置页（含 Start on Boot 开关），无法打开则回退到
     * 打开 Shizuku 应用主界面（用户手动进设置）。
     */
    fun openShizukuSettings(context: Context) {
        // 先检查 Stellar 是否安装，安装则打开 Stellar 设置
        if (ShizukuManager.isStellarInstalled(context)) {
            openStellarSettings(context)
            return
        }
        // 否则打开原版 Shizuku 设置
        val pkg = "moe.shizuku.privileged.api"
        // 优先尝试打开设置页（thedjchi 版可能的 settings activity）
        val settingsAttempts = listOf(
            ComponentName(pkg, "$pkg.ui.SettingsActivity"),
            ComponentName(pkg, "rikka.shizuku.ui.SettingsActivity")
        )
        for (component in settingsAttempts) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Throwable) {
                Log.d(TAG, "openShizukuSettings activity failed: $component")
            }
        }
        // 回退：打开 Shizuku 主界面
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                Log.w(TAG, "未安装 thedjchi 版 Shizuku")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "openShizukuSettings fallback failed", e)
        }
    }

    /**
     * 打开 Stellar 的设置页。
     *
     * 优先尝试打开设置页，失败则回退到打开 Stellar 主界面。
     */
    fun openStellarSettings(context: Context) {
        val pkg = "roro.stellar.manager"
        val settingsAttempts = listOf(
            ComponentName(pkg, "$pkg.ui.SettingsActivity"),
            ComponentName(pkg, "$pkg.MainActivity")
        )
        for (component in settingsAttempts) {
            try {
                val intent = Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            } catch (e: Throwable) {
                Log.d(TAG, "openStellarSettings activity failed: $component")
            }
        }
        // 回退：打开 Stellar 主界面
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                Log.w(TAG, "未安装 Stellar")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "openStellarSettings fallback failed", e)
        }
    }

    /**
     * 打开浏览器跳转到 Stellar 的 GitHub Releases 下载页。
     */
    fun openStellarDownloadPage(context: Context) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/roro2239/Stellar/releases")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "openStellarDownloadPage failed", e)
        }
    }

    /**
     * 记录一次 Stellar 熔断（跨会话累计），用于判断设备上 Stellar 是否持续不稳定。
     */
    fun recordCircuitTrip(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt("circuit_trips", 0) + 1
        prefs.edit().putInt("circuit_trips", count).apply()
        Log.i(TAG, "recordCircuitTrip, total=$count")
    }

    /**
     * 读取累计熔断次数。
     */
    fun getCircuitTripCount(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("circuit_trips", 0)
    }

    /**
     * Stellar 是否在此设备上持续不稳定（累计熔断 ≥3 次），建议降级到原版 Shizuku。
     */
    fun isStellarUnstable(context: Context): Boolean {
        return getCircuitTripCount(context) >= 3
    }

    /**
     * 打开原版 Shizuku 官网（降级方案：放弃开机自启换取稳定性）。
     */
    fun openShizukuWebsite(context: Context) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "openShizukuWebsite failed", e)
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
