package com.nexus.agent

import android.app.Application
import android.util.Log
import com.nexus.agent.agent.MistakeBook
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.game.VisionStage
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

        // 视觉 native 调用的「黑匣子」（0.8.4）：
        // native 崩溃不走 Java uncaughtException，CrashHandler 抓不到；
        // 这里读回上一次残留的阶段记号，就知道上次死在哪一步。
        runCatching {
            VisionStage.init(applicationContext)
            VisionStage.loadRecordedCrash()
            VisionStage.consume()
        }.onFailure { Log.w(TAG, "视觉崩溃取证初始化失败", it) }

        // 初始化 Shizuku Manager（尽早，供 binder 监听回调使用）
        // 数据库单例（懒 open，这里只是建对象，不产生磁盘 IO）
        runCatching { AppDatabase.get(applicationContext) }
            .onFailure { Log.w(TAG, "Room 数据库初始化失败", it) }

        // 人格记忆/聊天记录的存储上下文（PersonaTab 之外也可能被写入）
        runCatching { PersonaEngine.init(applicationContext) }
            .onFailure { android.util.Log.w("NexusApp", "人格引擎初始化失败", it) }

        // 错题本：读回历史教训（注入 Executor 提示词用）
        runCatching { MistakeBook.init(applicationContext) }
            .onFailure { Log.w(TAG, "错题本初始化失败", it) }

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
