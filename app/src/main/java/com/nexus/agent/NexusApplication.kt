package com.nexus.agent

import android.app.Application
import android.util.Log
import androidx.work.Configuration
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
 *
 * 0.9.2 起同时实现 [Configuration.Provider]：WorkManager（主动问候心跳）需要它。
 * **为什么必须自己提供配置**：本 App 有 `:vision` 子进程，WorkManager 默认会在所有进程里
 * 各自初始化一套，子进程也会跟着跑心跳任务（浪费、且两边都发通知）。
 * `setDefaultProcessName(packageName)` 把它钉死在主进程，子进程里 WorkManager 退化成空操作。
 */
class NexusApplication : Application(), Configuration.Provider {

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

    /**
     * WorkManager 配置（0.9.2 批次二）。
     *
     * 只做一件事：把 WorkManager 限制在主进程。没有这一行，`:vision` 子进程
     * 也会建一套 WorkManager 并跑同一个周期任务 —— 结果是「主动问候发两条」。
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName(packageName)
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    /** 当前进程是不是视觉子进程（manifest 里 `android:process=":vision"`） */
    private fun isVisionProcess(): Boolean = try {
        applicationInfo.processName?.endsWith(":vision") == true
    } catch (e: Throwable) {
        false
    }

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

        // 视觉子进程（:vision）只需要「崩溃捕获 + 取证」这两样，其余一律跳过：
        // 数据库、定时任务重排、人格引擎、Shizuku 绑定都只在主进程跑一次，
        // 在子进程里再跑一遍会导致重复闹钟、重复 bind、Room 多连接。
        if (isVisionProcess()) {
            Log.i(TAG, "运行在视觉子进程（:vision），跳过主进程初始化")
            return
        }

        // 初始化 Shizuku Manager（尽早，供 binder 监听回调使用）
        // 数据库单例（懒 open，这里只是建对象，不产生磁盘 IO）
        runCatching { AppDatabase.get(applicationContext) }
            .onFailure { Log.w(TAG, "Room 数据库初始化失败", it) }

        // 人格记忆/聊天记录的存储上下文（PersonaTab 之外也可能被写入）
        runCatching { PersonaEngine.init(applicationContext) }
            .onFailure { android.util.Log.w("NexusApp", "人格引擎初始化失败", it) }

        // 自我学习（0.9.11）：冷启动时顺手检查「今天聚合过没有」。
        // 开关关着时它内部会立刻返回，不做「先悄悄采集着、等用户打开」这种事。
        runCatching { com.nexus.agent.habit.HabitCollector.maybeLearnDaily(applicationContext) }
            .onFailure { Log.w(TAG, "习惯学习触发失败", it) }

        // 0.9.0 批次四：跨端接入（默认关）。开关关着时这里什么都不做。
        runCatching { com.nexus.agent.server.LocalAgentServer.startIfEnabled(applicationContext) }
            .onFailure { Log.w(TAG, "本地 HTTP 服务启动失败", it) }

        // 错题本：读回历史教训（注入 Executor 提示词用）
        runCatching { MistakeBook.init(applicationContext) }
            .onFailure { Log.w(TAG, "错题本初始化失败", it) }

        // 外挂记忆（0.9.13）：载入避坑经验缓存 + 跑一次达尔文衰减（一天一次）。
        // 只是读缓存和标记过期，不调模型，可以在冷启动时顺手做。
        runCatching { com.nexus.agent.memory.MemoryRouter.init(applicationContext) }
            .onFailure { Log.w(TAG, "外挂记忆初始化失败", it) }

        ShizukuManager.init(applicationContext)

        // 定时任务重排：闹钟会因重启/系统清理而丢失，
        // 每次进程起来都按当前时间重算一遍「下一次」，成本极低且能自愈
        runCatching { ScheduleScheduler.rescheduleAll(this) }
            .onFailure { Log.w(TAG, "reschedule scheduled tasks failed", it) }

        // 0.9.2 批次二：生命脉搏。幂等（KEEP），开关关着时这里会顺手取消已有任务。
        runCatching { com.nexus.agent.persona.HeartbeatScheduler.schedule(this) }
            .onFailure { Log.w(TAG, "心跳排程失败", it) }

        // 0.9.6：心智引擎。每两小时让她一个人想一件事（闲着的时候才有素材）
        runCatching { com.nexus.agent.persona.InnerMonologueEngine.schedule(this) }
            .onFailure { Log.w(TAG, "心智引擎排程失败", it) }

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

        // 0.9.0 批次三：MIMU / 和顺 这类保活地狱机型，如果 Shizuku 真的怎么都唤不醒，
        // 就把「自动化工具」自动关掉，让它安安心心当个纯数字生命（只聊天不操作手机）。
        // 判定与唤醒都要等结果，必须放后台线程。
        appScope.launch {
            delay(8000) // 等上面那轮唤醒先跑完，别两条路同时抢 Stellar
            runCatching {
                val degraded = com.nexus.agent.system.AutomationPolicy
                    .degradeIfTrappedOnCustomRom(applicationContext)
                if (degraded) {
                    Log.w(TAG, "已自动切换为纯数字生命模式（只能聊天）")
                }
            }.onFailure { Log.w(TAG, "自定义 ROM 降级判定失败", it) }
        }

        // 注意：本地识别模型（1.3GB）改为懒加载，不在启动时解压/加载。
        // 否则启动极慢，且内存小的手机会因加载 1.3GB 模型而 OOM 闪退。
        // 模型在首次游戏托管识别时才由 LocalScreenRecognizer.recognize 懒加载。

        Log.d(TAG, "Nexus Application initialized")
    }
}
