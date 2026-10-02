package com.nexus.agent

import android.app.Application
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import rikka.shizuku.Shizuku

/**
 * Nexus Agent 应用入口。
 *
 * 在进程启动时完成 Shizuku 连接状态的监听注册，
 * 后续各模块（input / userservice / agent）依赖这里的初始化时序。
 */
class NexusApplication : Application() {

    companion object {
        private const val TAG = "NexusAgent"
    }

    /** Shizuku Binder 到达时的监听器 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received (service connected)")
        // binder 恢复时自动重新绑定 UserService（binder 断开/重启后立即重连）
        ShizukuManager.bind { }
    }

    /** Shizuku Binder 断开时的监听器 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead (service disconnected)")
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化 Shizuku Manager（尽早，供 binder 监听回调使用）
        ShizukuManager.init(applicationContext)

        // 初始化 Shizuku：注册 Binder 生命周期监听
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        Log.d(TAG, "Nexus Application initialized")
    }
}
