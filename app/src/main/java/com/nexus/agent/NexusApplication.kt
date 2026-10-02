package com.nexus.agent

import android.app.Application
import android.util.Log
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
    }

    /** Shizuku Binder 断开时的监听器 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead (service disconnected)")
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化 Shizuku：注册 Binder 生命周期监听
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        Log.d(TAG, "Nexus Application initialized")
    }
}
