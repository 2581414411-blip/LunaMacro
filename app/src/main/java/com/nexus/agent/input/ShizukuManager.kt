package com.nexus.agent.input

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.nexus.agent.system.StabilityHelper
import com.nexus.agent.userservice.IUserService
import com.nexus.agent.userservice.UserServiceImpl
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Shizuku 绑定管理器。
 *
 * 负责：
 * - 通过 [Shizuku.bindUserService] 绑定 [UserServiceImpl]
 * - 在连接 / 断开回调中维护 [IUserService] 实例
 * - 提供 Shizuku 可用性、权限、延迟测量等便捷方法
 *
 * 使用方式（单例，需在 Application 或 Activity 中初始化）：
 * ```
 * ShizukuManager.init(context)
 * ShizukuManager.bind { service -> ... }
 * ```
 */
object ShizukuManager {

    private const val TAG = "ShizukuManager"

    /** 权限请求码（用于 onRequestPermissionsResult 回调） */
    const val REQUEST_CODE = 1001

    private var appContext: Context? = null

    /** 已绑定的 IUserService 实例 */
    @Volatile
    private var userService: IUserService? = null

    /** 绑定完成的回调 */
    private var onConnected: ((IUserService) -> Unit)? = null

    /** 服务连接回调 */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.e(TAG, "onServiceConnected: $name")
            val service = IUserService.Stub.asInterface(binder)
            userService = service
            onConnected?.invoke(service)
            onConnected = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.e(TAG, "onServiceDisconnected: $name")
            // 清理实例，避免持有已失效的 Binder
            userService = null
            onConnected = null
        }
    }

    /** 构造 UserServiceArgs：非 daemon，进程后缀 "injector" */
    private fun buildArgs(): Shizuku.UserServiceArgs {
        val ctx = appContext ?: throw IllegalStateException("ShizukuManager 未初始化")
        return Shizuku.UserServiceArgs(
            ComponentName(
                ctx,
                UserServiceImpl::class.java
            )
        )
            .daemon(false)
            .processNameSuffix("injector")
    }

    /** 初始化（保存 Context） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 获取已保存的 applicationContext（未初始化时为 null） */
    fun appContext(): Context? = appContext

    /**
     * 绑定 UserService。
     *
     * @param onBound 绑定成功后的回调，参数为可用的 [IUserService]
     */
    fun bind(onBound: (IUserService) -> Unit) {
        val ctx = appContext ?: run {
            Log.e(TAG, "ShizukuManager not initialized")
            return
        }
        if (!isShizukuAvailable()) {
            Log.e(TAG, "Shizuku not available, skip bind")
            return
        }

        onConnected = onBound
        try {
            Shizuku.bindUserService(buildArgs(), serviceConnection)
        } catch (e: Throwable) {
            Log.e(TAG, "bindUserService failed", e)
            onConnected = null
        }
    }

    /** 解绑 UserService */
    fun unbind() {
        try {
            Shizuku.unbindUserService(buildArgs(), serviceConnection, true)
        } catch (e: Throwable) {
            Log.e(TAG, "unbindUserService failed", e)
        }
        userService = null
    }

    /** 获取已绑定的服务实例（可能为 null） */
    fun getService(): IUserService? = userService

    /** Shizuku 服务是否可用（binder 可达） */
    fun isShizukuAvailable(): Boolean = Shizuku.pingBinder()

    /** 是否已获得 Shizuku 权限（binder 未建立时安全返回 false，不抛异常） */
    fun hasShizukuPermission(): Boolean {
        return try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                true
            } else {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** 请求 Shizuku 权限（结果通过 Shizuku 的权限回调监听返回） */
    fun requestShizukuPermission() {
        if (isShizukuAvailable() && !hasShizukuPermission()) {
            try {
                Shizuku.requestPermission(REQUEST_CODE)
            } catch (e: Throwable) {
                Log.e(TAG, "requestPermission failed", e)
            }
        }
    }

    /**
     * 测量注入延迟。
     *
     * 调用 [IUserService.injectTap]，用 [System.nanoTime] 测量
     * 从发起调用到返回的总耗时（含 IPC + 实际注入），返回毫秒数。
     *
     * @param x 测试点击的 X 坐标（像素）
     * @param y 测试点击的 Y 坐标（像素）
     * @return 注入耗时（毫秒）；服务不可用时返回 -1
     */
    fun measureInjectionLatency(x: Float = 100f, y: Float = 100f): Long {
        val service = userService ?: return -1

        return try {
            val start = System.nanoTime()
            service.injectTap(x, y)
            val end = System.nanoTime()
            (end - start) / 1_000_000  // 纳秒 -> 毫秒
        } catch (e: Throwable) {
            Log.e(TAG, "measureInjectionLatency failed", e)
            -1
        }
    }

    /**
     * 向指定坐标注入一次点击并返回耗时（毫秒）。
     *
     * 与 [measureInjectionLatency] 等价，语义更明确：用于「注入到真实控件坐标」。
     *
     * @param x 屏幕 X 坐标（像素）
     * @param y 屏幕 Y 坐标（像素）
     * @return 注入耗时（毫秒）；服务不可用时返回 -1
     */
    fun injectTapAt(x: Float, y: Float): Long {
        val service = userService ?: return -1

        return try {
            val start = System.nanoTime()
            service.injectTap(x, y)
            val end = System.nanoTime()
            (end - start) / 1_000_000  // 纳秒 -> 毫秒
        } catch (e: Throwable) {
            Log.e(TAG, "injectTapAt($x, $y) failed", e)
            -1
        }
    }

    // ------------------------------------------------------------------
    // 健康检查
    // ------------------------------------------------------------------

    /** 健康检查间隔（毫秒） */
    private const val HEALTH_CHECK_INTERVAL_MS = 30_000L

    /** Stellar 唤醒失败后的重试间隔（毫秒，Stellar 自启需要时间） */
    private const val STELLAR_RETRY_INTERVAL_MS = 5_000L

    /** 原版 Shizuku 唤醒失败后的重试间隔（毫秒，原版不支持自启，重试意义不大） */
    private const val SHIZUKU_RETRY_INTERVAL_MS = 30_000L

    /** Stellar（Shizuku 增强版）包名 */
    private const val STELLAR_PACKAGE = "roro.stellar.manager"

    /** 原版 Shizuku 包名 */
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Stellar 自动唤醒广播 action */
    private const val ACTION_STELLAR_AUTO_WAKEUP = "roro.stellar.manager.AUTO_WAKEUP"

    /** 原版 Shizuku 自动唤醒广播 action */
    private const val ACTION_SHIZUKU_AUTO_WAKEUP = "moe.shizuku.privileged.api.AUTO_WAKEUP"

    /** 指数退避最大间隔（5 分钟封顶） */
    private const val MAX_BACKOFF_MS = 300_000L

    /** 连续唤醒失败熔断阈值 */
    private const val MAX_FAILURES = 5

    /** 健康检查 Handler（主线程调度） */
    private val healthHandler = Handler(Looper.getMainLooper())

    /** 健康检查协程作用域（IO 线程执行 pingBinder，避免阻塞主线程） */
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前健康检查 Runnable */
    private var healthRunnable: Runnable? = null

    /** 连续重绑尝试次数 */
    @Volatile
    private var rebindAttempt = 0

    /** 自动唤醒尝试次数 */
    @Volatile
    private var wakeupAttempt = 0

    /** 连续唤醒失败计数（熔断依据） */
    @Volatile
    private var consecutiveFailures = 0

    /** 当前健康检查间隔（指数退避） */
    @Volatile
    private var currentInterval = HEALTH_CHECK_INTERVAL_MS

    /** 熔断状态（连续失败达阈值后置 true，UI 只读） */
    @Volatile
    var circuitOpen = false
        private set

    /** 熔断触发回调（UI 注册，用于显示配置引导入口） */
    @Volatile
    var onCircuitOpen: (() -> Unit)? = null

    /**
     * 检查 Stellar（Shizuku 增强版）是否已安装。
     */
    fun isStellarInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(STELLAR_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 检查原版 Shizuku 是否已安装。
     */
    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * 获取当前正在运行的 provider。
     *
     * @return "stellar" / "shizuku" / "none"
     */
    fun getRunningProvider(context: Context): String {
        val pingOk = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!pingOk) return "none"
        return when {
            isStellarInstalled(context) -> "stellar"
            isShizukuInstalled(context) -> "shizuku"
            else -> "none"
        }
    }

    /**
     * 尝试唤醒 Shizuku/Stellar：当 pingBinder() 返回 false 时调用。
     *
     * 优先尝试唤醒 Stellar（增强版，支持开机自启），失败则回退到原版 Shizuku。
     * 每次发广播后等待 2 秒再检查 pingBinder。
     *
     * @return 唤醒后 pingBinder 是否恢复
     */
    /**
     * 尝试把 Stellar / Shizuku 拉起来。
     *
     * @param allowUi 是否允许**拉起 Stellar 的界面**。
     *
     * 0.8.5 修复的关键参数。之前这里最后一招是
     * `getLaunchIntentForPackage(STELLAR).let { startActivity(it) }` ——
     * 而它会被**每 30 秒一次的后台健康检查**反复调用。
     * 后果：App 正在前台做事（比如点「测试视觉模型」），后台定时器把 Stellar 的
     * Activity 顶上来，自己的界面被挤到后台；Stellar 界面一退，用户就落在桌面。
     * 表现就是「不是崩溃，是自己回到桌面」，而且**只有 Stellar 装了/在跑时才出现**。
     *
     * 所以：只有用户**亲手点**「点击重连」时才允许拉界面（他本来就expect一个弹窗）；
     * 任何自动路径（启动、健康检查）一律不允许抢前台。
     */
    fun tryWakeUpShizuku(context: Context, allowUi: Boolean = false): Boolean {
        return try {
            wakeupAttempt++
            var alive = pingAlive()
            if (alive) return true

            // 三管齐下唤醒 Stellar：广播 → startService → 启动 Activity（仅 allowUi）
            if (isStellarInstalled(context)) {
                // a. 广播
                context.sendBroadcast(
                    Intent(ACTION_STELLAR_AUTO_WAKEUP).setPackage(STELLAR_PACKAGE)
                )
                Thread.sleep(1000)
                alive = pingAlive()
                if (alive) { logWakeupOk("broadcast"); return true }

                // b. startForegroundService（真正拉起持久服务）
                try {
                    val svcIntent = Intent().setComponent(
                        ComponentName(STELLAR_PACKAGE, "roro.stellar.manager.StellarService")
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svcIntent)
                    } else {
                        context.startService(svcIntent)
                    }
                    Thread.sleep(1000)
                    alive = pingAlive()
                    if (alive) { logWakeupOk("start_service"); return true }
                } catch (e: Throwable) {
                    Log.w("ShizukuHealth", "state=wakeup_failed, reason=start_service_error")
                }

                // c. 启动 Stellar 主 Activity —— **仅限用户主动点击**
                if (allowUi) {
                    try {
                        val launch = context.packageManager.getLaunchIntentForPackage(STELLAR_PACKAGE)
                        if (launch != null) {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(launch)
                            Thread.sleep(2000)
                            alive = pingAlive()
                            if (alive) { logWakeupOk("launch_activity"); return true }
                        }
                    } catch (e: Throwable) {
                        Log.w("ShizukuHealth", "state=wakeup_failed, reason=launch_activity_error")
                    }
                } else {
                    // 自动路径绝不抢前台：留一条日志，方便以后排查「为什么没醒」
                    Log.i(
                        "ShizukuHealth",
                        "state=ui_launch_skipped, reason=automatic_path, note=只广播/startService，不抢前台"
                    )
                }
            }

            // 回退原版 Shizuku（广播）
            if (!alive && isShizukuInstalled(context)) {
                context.sendBroadcast(
                    Intent(ACTION_SHIZUKU_AUTO_WAKEUP).setPackage(SHIZUKU_PACKAGE)
                )
                Thread.sleep(2000)
                alive = pingAlive()
                if (alive) { logWakeupOk("shizuku_broadcast"); return true }
            }

            val provider = getRunningProvider(context)
            Log.i("ShizukuHealth", "state=wakeup_failed, reason=still_dead, provider=$provider, attempt=$wakeupAttempt")
            alive
        } catch (e: Throwable) {
            Log.e(TAG, "tryWakeUpShizuku failed", e)
            false
        }
    }

    /** ping binder，吞异常返回布尔。 */
    private fun pingAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** 记录唤醒成功日志。 */
    private fun logWakeupOk(method: String) {
        Log.i("ShizukuHealth", "state=wakeup_ok, command_test=passed, method=$method, attempt=$wakeupAttempt")
    }

    /**
     * 检查 Shizuku 是否已启用「Start on Boot」。
     *
     * 尝试读取 thedjchi 版 Shizuku 的 SharedPreferences。
     * 注意：非 root 下无法读取其他应用的 /data/data 目录，此时返回 false，
     * 由 UI 层提示用户手动检查。
     */
    fun isStartOnBootEnabled(): Boolean {
        return try {
            val prefsDir = File("/data/data/$SHIZUKU_PACKAGE/shared_prefs/")
            if (!prefsDir.exists() || !prefsDir.isDirectory) return false
            val files = prefsDir.listFiles() ?: return false
            for (f in files) {
                if (f.name.endsWith(".xml")) {
                    val content = f.readText()
                    // 模糊匹配 start on boot 相关 key 且值为 true
                    val hasBootKey = content.contains("start_on_boot", ignoreCase = true) ||
                        content.contains("startOnBoot", ignoreCase = true) ||
                        content.contains("start_on_boot", ignoreCase = true)
                    if (hasBootKey && content.contains("true", ignoreCase = true)) {
                        return true
                    }
                }
            }
            false
        } catch (e: Throwable) {
            Log.d(TAG, "无法读取 Shizuku 配置（非 root），视为未启用 Start on Boot")
            false
        }
    }

    /**
     * 启动 Shizuku 健康检查：指数退避 + 熔断。
     *
     * - 间隔从 30 秒起，每次失败翻倍（30s→60s→120s→240s→300s 封顶），成功重置 30 秒。
     * - 连续 [MAX_FAILURES] 次唤醒失败后熔断，停止自动唤醒，触发 [onCircuitOpen] 通知 UI。
     * - 唤醒成功与否不仅看 pingBinder，还要真正执行一次 shell 命令（echo ok）验证。
     */
    fun startHealthCheck() {
        if (healthRunnable != null) return  // 已在运行，避免重复启动

        // 重置状态
        consecutiveFailures = 0
        currentInterval = HEALTH_CHECK_INTERVAL_MS
        circuitOpen = false

        lateinit var runnable: Runnable
        runnable = object : Runnable {
            override fun run() {
                healthScope.launch {
                    val ctx = appContext
                    val pingOk = try {
                        Shizuku.pingBinder()
                    } catch (e: Throwable) {
                        false
                    }
                    val bound = userService != null
                    val alive = pingOk && bound
                    val provider = ctx?.let { getRunningProvider(it) } ?: "none"

                    Log.i(
                        "ShizukuHealth",
                        "state=checking, interval_ms=$currentInterval, consecutive_failures=$consecutiveFailures, provider=$provider"
                    )

                    if (circuitOpen) {
                        // 熔断中：不再自动唤醒，等用户手动恢复
                        Log.i("ShizukuHealth", "state=circuit_open, total_failures=$consecutiveFailures, action=notify_user")
                        healthHandler.postDelayed(runnable, currentInterval)
                        return@launch
                    }

                    if (alive && commandTestPassed()) {
                        // 真正存活（binder 在 + 命令可执行）：重置退避
                        consecutiveFailures = 0
                        currentInterval = HEALTH_CHECK_INTERVAL_MS
                        Log.i("ShizukuHealth", "state=wakeup_ok, command_test=passed")
                    } else if (pingOk && !bound) {
                        // binder 活着，只是我们这边没绑上 UserService（权限/进程被杀）——
                        // 重新绑一次就行，**不要去唤醒**，更不能拉别人的界面
                        Log.i("ShizukuHealth", "state=rebind, note=binder_alive_but_service_unbound")
                        bind { }
                    } else {
                        // 断开或命令测试失败：尝试唤醒
                        val woken = ctx != null && tryWakeUpShizuku(ctx)
                        if (woken && commandTestPassed()) {
                            consecutiveFailures = 0
                            currentInterval = HEALTH_CHECK_INTERVAL_MS
                            Log.i("ShizukuHealth", "state=wakeup_ok, command_test=passed")
                        } else {
                            consecutiveFailures++
                            currentInterval = (currentInterval * 2).coerceAtMost(MAX_BACKOFF_MS)
                            if (consecutiveFailures >= MAX_FAILURES) {
                                circuitOpen = true
                                Log.i("ShizukuHealth", "state=circuit_open, total_failures=$consecutiveFailures, action=notify_user")
                                appContext?.let { StabilityHelper.recordCircuitTrip(it) }
                                onCircuitOpen?.invoke()
                            } else {
                                Log.i("ShizukuHealth", "state=wakeup_failed, reason=still_dead, will_retry_in=$currentInterval")
                            }
                        }
                    }
                    healthHandler.postDelayed(runnable, currentInterval)
                }
            }
        }
        healthRunnable = runnable
        healthHandler.post(runnable)
        Log.i("ShizukuHealth", "健康检查已启动（指数退避 + 熔断）")
    }

    /**
     * 验证当前 UserService 能真正执行 shell 命令（echo ok）。
     */
    private fun commandTestPassed(): Boolean {
        return try {
            val svc = userService ?: return false
            val out = svc.execCommand("echo ok")
            out.contains("ok", ignoreCase = true)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 停止 Shizuku 健康检查。
     */
    fun stopHealthCheck() {
        healthRunnable?.let { healthHandler.removeCallbacks(it) }
        healthRunnable = null
        rebindAttempt = 0
        consecutiveFailures = 0
        circuitOpen = false
        Log.i("ShizukuHealth", "健康检查已停止")
    }
}
