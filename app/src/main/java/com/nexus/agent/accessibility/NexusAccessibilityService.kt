package com.nexus.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.nexus.agent.userservice.NexusForegroundService

/**
 * Nexus Agent 无障碍服务。
 *
 * 职责：
 * 1. 监听窗口状态 / 内容变化，记录当前前台应用包名
 * 2. 对外提供当前活动窗口根节点（供 UiTreeParser 解析）
 * 3. 通过 dispatchGesture 提供备用点击能力（与 Shizuku 注入互为补充）
 *
 * 通过 companion object 持有静态实例，其它组件（如 Agent 主循环、
 * UiTreeParser 调用方）可直接获取服务实例。
 */
class NexusAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "NexusAccessibilityService"

        /** 当前服务实例（服务连接时赋值，断开时清空） */
        @Volatile
        private var instance: NexusAccessibilityService? = null

        /**
         * 获取当前无障碍服务实例。
         *
         * @return 服务实例；未启动 / 已断开时返回 null
         */
        fun getInstance(): NexusAccessibilityService? = instance

        /**
         * 服务实例是否真的已绑定。
         *
         * onServiceConnected 后为 true；onUnbind / onDestroy 后为 false。
         * 这是「服务真的活着、真的能读屏」的核心判据。
         */
        fun isBound(): Boolean = instance != null

        /**
         * 是否在系统启用列表中（仅反映「开关被打开过」，不反映服务是否真活着）。
         *
         * 单独暴露出来，供首页区分「未开开关」与「开了但还在连接中/被系统禁用」。
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/com.nexus.agent.accessibility.NexusAccessibilityService"
            val enabled = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Throwable) {
                null
            }
            return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
        }

        /**
         * 完整检测无障碍服务是否真的开启且可用。
         *
         * 只读 ENABLED_ACCESSIBILITY_SERVICES 并不可靠：vivo/iQOO 上
         * 服务被系统后台杀掉后，设置里仍显示「已开启」，但 instance 已为 null、
         * getRootInActiveWindow() 拿不到内容。因此这里做双重校验：
         *  1. 设置列表里是否登记了本服务（用户开过开关）
         *  2. 服务实例是否真正绑定（onServiceConnected 已发生）
         * 两者同时满足，才判定为「真的开启」。
         */
        fun isServiceRunning(context: Context): Boolean {
            return isEnabledInSettings(context) && instance != null
        }
    }

    /** 当前前台应用包名 */
    @Volatile
    private var currentPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "AccessibilityService connected")

        // 拉起前台常驻服务，防止进程被 vivo/iQOO 省电机制回收
        NexusForegroundService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    currentPackageName = pkg
                    Log.d(TAG, "foreground package: $pkg")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt —— 系统请求中断，尝试自动恢复")
        // 若 instance 丢失则重新挂上，并确保前台服务在跑
        if (instance == null) {
            instance = this
        }
        try {
            NexusForegroundService.start(this)
        } catch (e: Throwable) {
            Log.e(TAG, "onInterrupt 恢复前台服务失败", e)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        currentPackageName = null
        Log.i(TAG, "onUnbind")
        // 返回 true 表示下次（用户重新开启无障碍服务时）会回调 onRebind，
        // 用于自愈恢复 instance；即使系统短暂解绑，也能在重连时自动恢复。
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        Log.i(TAG, "onRebind —— 服务自愈恢复 instance")
        // 重连后再次确保前台服务在跑
        NexusForegroundService.start(this)
    }

    override fun onDestroy() {
        instance = null
        Log.w(TAG, "onDestroy —— 无障碍服务被销毁（可能被系统回收）")
        super.onDestroy()
    }

    /**
     * 获取当前前台应用包名。
     *
     * @return 前台应用包名；未知时返回 null
     */
    fun getCurrentPackageName(): String? = currentPackageName

    /**
     * 获取当前活动窗口的根节点。
     *
     * 处理 getRootInActiveWindow() 返回 null 的情况（如受保护的系统弹窗、
     * 锁屏界面等），此时返回 null 并记录日志，调用方需自行判空。
     *
     * 容错：首次拿到 null 时短暂等待后重试一次，规避窗口切换瞬间的抖动。
     *
     * @return 根节点；获取失败返回 null。调用方使用后应 recycle()。
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow ?: run {
                // 窗口切换瞬间可能拿不到，等 50ms 重试一次
                Log.w(TAG, "getRootInActiveWindow() returned null, retrying once")
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                rootInActiveWindow ?: run {
                    Log.w(TAG, "getRootInActiveWindow() returned null (protected window?)")
                    null
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "getRootNode failed", e)
            null
        }
    }

    /**
     * 通过 dispatchGesture 实现备用点击。
     *
     * 与 Shizuku 的 IInputManager 注入不同，此路径依赖无障碍的
     * canPerformGestures 能力，作为后备方案。
     *
     * @param x 屏幕 X 坐标（像素）
     * @param y 屏幕 Y 坐标（像素）
     * @return 手势是否已成功派发
     */
    fun performClick(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x, y)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Throwable) {
            Log.e(TAG, "performClick($x, $y) failed", e)
            false
        }
    }
}
