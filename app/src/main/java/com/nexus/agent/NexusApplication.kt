package com.nexus.agent

import android.app.Application
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.schedule.ScheduleScheduler
import com.nexus.agent.system.CrashHandler
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

        // 全局崩溃捕获：必须最先安装，否则启动阶段的崩溃会漏掉
        CrashHandler.install(this)

        // 初始化 Shizuku Manager（尽早，供 binder 监听回调使用）
        ShizukuManager.init(applicationContext)

        // 定时任务重排：闹钟会因重启/系统清理而丢失，
        // 每次进程起来都按当前时间重算一遍「下一次」，成本极低且能自愈
        runCatching { ScheduleScheduler.rescheduleAll(this) }
            .onFailure { Log.w(TAG, "reschedule scheduled tasks failed", it) }

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

        // 注意：本地识别模型（1.3GB）改为懒加载，不在启动时解压/加载。
        // 否则启动极慢，且内存小的手机会因加载 1.3GB 模型而 OOM 闪退。
        // 模型在首次游戏托管识别时才由 LocalScreenRecognizer.recognize 懒加载。

        Log.d(TAG, "Nexus Application initialized")
    }
}
