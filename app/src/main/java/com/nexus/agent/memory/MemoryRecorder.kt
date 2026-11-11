package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.TaskEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 双向静默采集（0.9.13）。
 *
 * 「静默」是指用户不需要做任何额外动作 ——
 * 他正常让助手办事，这里在旁边把「界面 + 意图 + 动作序列 + 成败」记下来。
 * 成功和失败都记：
 *  - 成功 → [MemoryConsolidator.onSuccess] → 可能变成可回放的动作记忆；
 *  - 失败 → [MemoryConsolidator.onFailure] → 提炼一条避坑经验。
 *
 * ## 为什么不在主流程上同步做
 *
 * 加工可能要调一次模型（提炼经验，最长 25 秒）。把它挂在任务返回路径上，
 * 用户会看到「任务已经完成了，但界面卡着不动」。所以全部丢到一个串行 IO 协程：
 * 任务结束即返回，学习在后台慢慢做。
 *
 * 串行（单线程）而不是并发：两条轨迹同时加工会互相覆盖同一条记忆的计数，
 * 而且模型复盘本来也不该并发。
 */
object MemoryRecorder {

    private const val TAG = "MemDebug"

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /**
     * 加工串行锁。
     *
     * 两条轨迹同时加工会互相覆盖同一条记忆的计数（读-改-写不是原子的），
     * 而且模型复盘本来也不该并发 —— 一次只复盘一件，结论才不打架。
     */
    private val lock = Mutex()

    /**
     * 记一次成功。
     *
     * @param steps 结构化动作序列（`Executor` 的 `actionSink` 收集的）
     */
    fun recordSuccess(
        ctx: Context?,
        task: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>,
        source: String = TaskEpisode.SOURCE_AGENT
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return
        if (task.isBlank()) return
        val intentKey = IntentKey.normalize(task)
        if (intentKey.isEmpty()) return
        val encoded = ActionStep.encodeAll(steps)

        scope.launch {
            lock.withLock {
                runCatching {
                    val dao = AppDatabase.get(app).taskEpisodeDao()
                    dao.insert(
                        TaskEpisode(
                            intent = task.take(TaskEpisode.INTENT_MAX),
                            intentKey = intentKey,
                            fingerprint = screen.fingerprint,
                            screenKeys = screen.keysCsv,
                            pkg = screen.pkg,
                            steps = encoded,
                            outcome = TaskEpisode.OUTCOME_SUCCESS,
                            source = source
                        )
                    )
                    dao.trim(TaskEpisode.MAX_ROWS)
                    Log.i(
                        TAG,
                        "[采集] 成功轨迹已入库：意图=\"${task.take(24)}\" " +
                            "界面=${screen.fingerprint.ifBlank { "（读不到）" }} ${steps.size} 步"
                    )
                }.onFailure { Log.w(TAG, "采集成功轨迹失败：${it.message}") }

                runCatching {
                    MemoryConsolidator.onSuccess(app, task, intentKey, screen, steps)
                }.onFailure { Log.w(TAG, "加工成功轨迹失败：${it.message}") }
            }
        }
    }

    /**
     * 记一次失败。
     *
     * @param failStep 第几步失败（1 基）；0 = 不知道
     */
    fun recordFailure(
        ctx: Context?,
        task: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>,
        failStep: Int,
        detail: String,
        source: String = TaskEpisode.SOURCE_AGENT
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return
        if (task.isBlank()) return
        val intentKey = IntentKey.normalize(task)
        if (intentKey.isEmpty()) return
        val encoded = ActionStep.encodeAll(steps)

        scope.launch {
            lock.withLock {
                runCatching {
                    val dao = AppDatabase.get(app).taskEpisodeDao()
                    dao.insert(
                        TaskEpisode(
                            intent = task.take(TaskEpisode.INTENT_MAX),
                            intentKey = intentKey,
                            fingerprint = screen.fingerprint,
                            screenKeys = screen.keysCsv,
                            pkg = screen.pkg,
                            steps = encoded,
                            outcome = TaskEpisode.OUTCOME_FAILED,
                            failStep = failStep,
                            detail = detail.take(200),
                            source = source
                        )
                    )
                    dao.trim(TaskEpisode.MAX_ROWS)
                    Log.i(
                        TAG,
                        "[采集] 失败轨迹已入库：意图=\"${task.take(24)}\" 第 $failStep 步（${detail.take(40)}）"
                    )
                }.onFailure { Log.w(TAG, "采集失败轨迹失败：${it.message}") }

                runCatching {
                    MemoryConsolidator.onFailure(app, task, intentKey, screen, steps, failStep, detail)
                }.onFailure { Log.w(TAG, "提炼经验失败：${it.message}") }
            }
        }
    }

    /** 任务被用户中断：只留流水，不提炼（中断不是「做错了」，没有可学的坑） */
    fun recordAborted(
        ctx: Context?,
        task: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return
        val intentKey = IntentKey.normalize(task)
        if (intentKey.isEmpty()) return
        scope.launch {
            lock.withLock {
                runCatching {
                    AppDatabase.get(app).taskEpisodeDao().insert(
                        TaskEpisode(
                            intent = task.take(TaskEpisode.INTENT_MAX),
                            intentKey = intentKey,
                            fingerprint = screen.fingerprint,
                            screenKeys = screen.keysCsv,
                            pkg = screen.pkg,
                            steps = ActionStep.encodeAll(steps),
                            outcome = TaskEpisode.OUTCOME_ABORTED,
                            detail = "用户中断",
                            source = TaskEpisode.SOURCE_AGENT
                        )
                    )
                    Log.i(TAG, "[采集] 中断轨迹已入库：意图=\"${task.take(24)}\"")
                }.onFailure { Log.w(TAG, "采集中断轨迹失败：${it.message}") }
            }
        }
    }
}
