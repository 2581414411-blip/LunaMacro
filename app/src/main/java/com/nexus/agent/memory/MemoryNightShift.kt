package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 夜间巩固调度 —— 「白天经历，夜里整理」（0.9.14）。
 *
 * ## 为什么是 WorkManager
 *
 * 这件事的三个特征正好对上 WorkManager 的语义：
 *  - **不急**：今晚跑和明晚跑对用户没有区别，只要最后跑到；
 *  - **要能跨重启**：手机重启、App 被清理之后还得记得这件事；
 *  - **系统比我更懂什么时候合适**：锁屏、空闲、电量够的时候再跑。
 *
 * 用 `AlarmManager` 精确闹钟反而更差：国产 ROM 上精确闹钟的存活率并不比 WorkManager 高，
 * 而且会把用户从待机里拽起来只为统计一下点击记录。
 *
 * ## 三道闸，防止「夜里偷跑」
 *
 * 1. `setRequiresBatteryNotLow(true)` —— 电量低时不干这种非紧急的事；
 * 2. 周期 24 小时 + `initialDelay` 到次日凌晨 [NIGHT_HOUR] 点；
 * 3. 真到点了还要再看一眼：待巩固样本少于 [MIN_PENDING] 就不跑，
 *    不为了两条记录调一次模型。
 *
 * ## 为什么还有 `maybeRunOnLaunch`
 *
 * 本机是 vivo/iQOO，这类 ROM 会主动杀后台任务 —— WorkManager 里的周期任务
 * 可能连续几天不响（0.9.4 的心跳就被这个坑过）。所以启动时补一道兜底：
 * 只要「攒了 12 小时以上还没巩固」或者「积压超过 [BACKLOG_PENDING]」，
 * 就在后台补跑一次。它不会让巩固变频繁：跑完会刷新 [CopilotConfig.memoryLastConsolidateAt]。
 */
object MemoryNightShift {

    private const val TAG = "MemDebug"

    /** 周期任务唯一名 */
    const val WORK_NAME = "luna_memory_consolidate"

    /** 巩固周期：一天一次 */
    const val INTERVAL_HOURS = 24L

    /** 凌晨几点跑（用户睡着了，手机通常也在充电） */
    const val NIGHT_HOUR = 3

    /**
     * 排程指纹。
     *
     * 作用同 `HeartbeatScheduler.SCHEDULE_SIGNATURE`：`ExistingPeriodicWorkPolicy.UPDATE`
     * 会在每次 enqueue 时重置 initialDelay，而 `NexusApplication.onCreate` 每次冷启动都调
     * [schedule]。没有这道闸，用户频繁开 App 就会把「今晚 3 点」无限往后推。
     * **以后改周期或改约束，记得改这个指纹**，否则老用户不会被重排。
     */
    const val SCHEDULE_SIGNATURE = "night-24h-0.9.14"

    /** 少于这么多条待巩固样本，这一夜就不跑了（不为了两条记录烧 token） */
    const val MIN_PENDING = 3

    /** 积压到这个量，启动时就补跑，不等夜里 */
    const val BACKLOG_PENDING = 40

    /** 启动兜底：最老的样本躺了这么久还没巩固，就补跑 */
    const val IDLE_HOURS = 12L

    /** 启动兜底的冷却：这么短时间内不重复补跑 */
    private const val LAUNCH_GUARD_MS = 12L * 60 * 60 * 1000

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * 排程（`NexusApplication.onCreate` 调，开关切换时也调）。
     *
     * 关闭外挂记忆或关闭自动巩固 → 取消任务。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext ?: context
        if (!CopilotConfig.isMemoryEnabled(app) || !CopilotConfig.isAutoConsolidateEnabled(app)) {
            Log.i(TAG, "[巩固] 自动巩固已关闭，取消夜间任务")
            cancel(app)
            return
        }
        scope.launch {
            runCatching { reconcile(app) }
                .onFailure { Log.w(TAG, "[巩固] 夜间任务排程失败", it) }
        }
    }

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        CopilotConfig.setMemoryScheduleSignature(context, "")
    }

    /**
     * 启动兜底。
     *
     * 只在两种情况下补跑，避免把「巩固」变成「每次开 App 都跑」：
     *  - 积压到 [BACKLOG_PENDING] 条；
     *  - 最老的一条已经躺了 [IDLE_HOURS] 小时，且距上次巩固超过 [LAUNCH_GUARD_MS]。
     */
    fun maybeRunOnLaunch(context: Context) {
        val app = context.applicationContext ?: context
        if (!CopilotConfig.isMemoryEnabled(app) || !CopilotConfig.isAutoConsolidateEnabled(app)) return
        scope.launch {
            runCatching {
                val dao = AppDatabase.get(app).rawTraceDao()
                val pending = dao.pendingCount()
                if (pending < MIN_PENDING) return@runCatching
                val now = System.currentTimeMillis()
                val oldest = dao.oldestPendingAt() ?: return@runCatching
                val backlog = pending >= BACKLOG_PENDING
                val idle = now - oldest >= IDLE_HOURS * 60 * 60 * 1000
                if (!backlog && !idle) return@runCatching
                if (!backlog && now - CopilotConfig.memoryLastConsolidateAt(app) < LAUNCH_GUARD_MS) {
                    Log.i(TAG, "[巩固] 启动兜底：距上次巩固不足 12 小时，先不动（待巩固 $pending 条）")
                    return@runCatching
                }
                Log.i(TAG, "[巩固] 启动兜底触发（待巩固 $pending 条，积压=$backlog）")
                MemoryConsolidator.consolidate(app)
            }.onFailure { Log.w(TAG, "[巩固] 启动兜底失败：${it.message}") }
        }
    }

    /** 排程对账：先问 WorkManager 任务还在不在，再决定要不要重排 */
    private suspend fun reconcile(app: Context) {
        val live = hasLiveWork(app)
        val sig = CopilotConfig.memoryScheduleSignature(app)
        if (live && sig == SCHEDULE_SIGNATURE) {
            Log.i(TAG, "[巩固] 夜间任务已在（$SCHEDULE_SIGNATURE），不重排")
            return
        }
        enqueue(app)
        CopilotConfig.setMemoryScheduleSignature(app, SCHEDULE_SIGNATURE)
        Log.i(TAG, "[巩固] 夜间任务已排：每 ${INTERVAL_HOURS}h 一次，首次在 ${NIGHT_HOUR}:00 之后（live=$live）")
    }

    private suspend fun hasLiveWork(app: Context): Boolean = runCatching {
        val wm = WorkManager.getInstance(app)
        val infos = wm.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS)
        infos.any {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }.onFailure { Log.w(TAG, "[巩固] 查任务状态失败，按「不存在」处理", it) }
        .getOrDefault(false)

    private fun enqueue(app: Context) {
        val delay = nextNightDelayMs(System.currentTimeMillis())
        val req = PeriodicWorkRequestBuilder<MemoryConsolidationWorker>(
            INTERVAL_HOURS, TimeUnit.HOURS
        )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(app)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /**
     * 距下一个「凌晨 [hour] 点」还有多少毫秒。
     *
     * 纯函数（用默认时区的 Calendar），单测直接钉：结果必须 > 0 且 ≤ 24h。
     */
    fun nextNightDelayMs(now: Long, hour: Int = NIGHT_HOUR): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return (cal.timeInMillis - now).coerceAtLeast(1L)
    }
}

/**
 * 巩固 Worker。
 *
 * **永远返回 success**：这是周期任务，失败重试的语义只会让它在后台反复烧模型
 * （WorkManager 的退避是「隔一会儿再来一次」，而失败原因多半是「没网/没配 Key」，
 * 重试并不能解决）。真出问题时下一次周期会自然再试。
 */
class MemoryConsolidationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val pending = AppDatabase.get(applicationContext).rawTraceDao().pendingCount()
            if (pending < MemoryNightShift.MIN_PENDING) {
                Log.i("MemDebug", "[巩固] 只有 $pending 条待巩固，不值得跑一次")
                return Result.success()
            }
            MemoryConsolidator.consolidate(applicationContext)
            Result.success()
        } catch (e: Throwable) {
            Log.w("MemDebug", "[巩固] 执行异常：${e.javaClass.simpleName} ${e.message}")
            Result.success()
        }
    }
}
