package com.nexus.agent

import android.app.Application
import android.util.Log
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /** 应用级协程作用域（IO，用于启动时延迟唤醒 Shizuku） */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 初始化 Shizuku Manager（尽早，供 binder 监听回调使用）
        ShizukuManager.init(applicationContext)

        // 初始化 Shizuku：注册 Binder 生命周期监听
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        // 启动时若 binder 未连接，延迟 3 秒后尝试自动唤醒
        // （延迟等待系统完成启动、Wi-Fi 可用），失败则延迟 10 秒重试一次
        if (!Shizuku.pingBinder()) {
            appScope.launch {
                delay(3000)
                val firstOk = ShizukuManager.tryWakeUpShizuku(this@NexusApplication)
                if (!firstOk) {
                    delay(10000)
                    val secondOk = ShizukuManager.tryWakeUpShizuku(this@NexusApplication)
                    if (!secondOk) {
                        Log.i("ShizukuHealth", "startup_wakeup_failed")
                    }
                }
            }
        }

        // 后台初始化本地识别模型（解压 1.3GB + 加载，耗时，不阻塞启动）
        appScope.launch {
            LocalScreenRecognizer.init(this@NexusApplication)
        }

        Log.d(TAG, "Nexus Application initialized")
    }
}
