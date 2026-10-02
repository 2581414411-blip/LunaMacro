package com.nexus.agent.input

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.nexus.agent.userservice.IUserService
import com.nexus.agent.userservice.UserServiceImpl
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
        return Shizuku.UserServiceArgs(
            ComponentName(
                appContext!!,
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

    /** 是否已获得 Shizuku 权限 */
    fun hasShizukuPermission(): Boolean {
        return if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
            true
        } else {
            try {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (_: Throwable) {
                false
            }
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
}
