package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.RawTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 原始轨迹采集 —— **白天只记账，什么都不学**（0.9.14 重构）。
 *
 * ## 和 0.9.13 的区别
 *
 * 0.9.13 在这四个入口里顺手把「加工」也做了：成功就建候选记忆、失败就调模型出经验。
 * 那个做法有两个硬伤，都是这一版要修的：
 *
 * 1. **记忆污染**：一天里手滑点错一次、界面刚好弹了个广告，都会被当成「他就是这么做事的」。
 *    单次样本没有任何统计意义，可它一旦写进 `action_memory` 就得靠衰减慢慢清。
 * 2. **成本失控**：一天办二十件事，后台可能调二十次模型，用户完全不知情。
 *
 * 现在这里只做一件事：往 [RawTrace] 写一行。零模型调用、零判断、零合并。
 * 真正的提炼在夜里一次性做（[MemoryConsolidator.consolidate]），那时能看到一整天的全部样本，
 * 才有资格说「这是习惯」还是「这是偶发」。
 *
 * ## 为什么仍然异步
 *
 * 写库是磁盘 IO，任务收尾路径上不该有它。丢到串行 IO 协程：
 * 任务一结束就返回，记账在后面慢慢写。串行是为了保证写入顺序 = 发生顺序，
 * 夜里做骨架合并时「先做了什么」这个顺序是有语义的。
 */
object MemoryRecorder {

    private const val TAG = "MemDebug"

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 串行写：保证入库顺序等于发生顺序，避免同一毫秒的两条轨迹在库里前后颠倒 */
    private val lock = Mutex()

    /**
     * 记一次成功。
     *
     * @param steps `Executor` 的 `actionSink` 收集到的结构化动作
     */
    fun recordSuccess(
        ctx: Context?,
        task: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>,
        source: String = RawTrace.SOURCE_AGENT
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return
        val intentKey = IntentKey.normalize(task)
        if (task.isBlank() || intentKey.isEmpty()) return
        // 一步都没记下来的「成功」没有回放价值，进池子只会污染分组统计
        if (steps.isEmpty()) return

        write(
            app,
            RawTrace(
                kind = RawTrace.KIND_AGENT_SUCCESS,
                intent = task.take(RawTrace.INTENT_MAX),
                intentKey = intentKey,
                fingerprint = screen.fingerprint,
                screenKeys = screen.keysCsv,
                pkg = screen.pkg,
                steps = ActionStep.encodeAll(steps),
                outcome = RawTrace.OUTCOME_SUCCESS,
                source = source
            ),
            "成功轨迹"
        )
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
        source: String = RawTrace.SOURCE_AGENT
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return
        val intentKey = IntentKey.normalize(task)
        if (task.isBlank() || intentKey.isEmpty()) return

        write(
            app,
            RawTrace(
                kind = RawTrace.KIND_AGENT_FAIL,
                intent = task.take(RawTrace.INTENT_MAX),
                intentKey = intentKey,
                fingerprint = screen.fingerprint,
                screenKeys = screen.keysCsv,
                pkg = screen.pkg,
                steps = ActionStep.encodeAll(steps),
                outcome = RawTrace.OUTCOME_FAILED,
                failStep = failStep,
                detail = detail.take(RawTrace.DETAIL_MAX),
                source = source
            ),
            "失败轨迹"
        )
    }

    /** 任务被用户中断：只留流水。中断不是「做错了」，巩固时不该从它里面提炼经验 */
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

        write(
            app,
            RawTrace(
                kind = RawTrace.KIND_AGENT_ABORT,
                intent = task.take(RawTrace.INTENT_MAX),
                intentKey = intentKey,
                fingerprint = screen.fingerprint,
                screenKeys = screen.keysCsv,
                pkg = screen.pkg,
                steps = ActionStep.encodeAll(steps),
                outcome = RawTrace.OUTCOME_ABORTED,
                detail = "用户中断",
                source = RawTrace.SOURCE_AGENT
            ),
            "中断轨迹"
        )
    }

    /**
     * 回放失败写回（0.9.14 阶段四）。
     *
     * 这是「让下一次巩固能纠正这个错误」的唯一通道。
     * 为什么不当场改记忆：当场只知道「这次没点中」，不知道**为什么**没点中；
     * 攒几次再看，才能分清是「界面改版了」还是「这条路本来就只对某台机器有效」。
     *
     * 当场该做的（降级、连败计数）已经在 [MemoryRouter.onReplayFail] 里做了，
     * 这里只负责留证据。
     *
     * @param completed 已经成功走完的那几步，失败点之后的动作对复盘毫无用处
     */
    fun recordReplayFail(
        ctx: Context?,
        memory: ActionMemory,
        reason: String,
        completed: List<ActionStep> = emptyList()
    ) {
        val app = ctx ?: return
        if (!CopilotConfig.isMemoryEnabled(app)) return

        write(
            app,
            RawTrace(
                kind = RawTrace.KIND_REPLAY_FAIL,
                intent = memory.intent.take(RawTrace.INTENT_MAX),
                intentKey = memory.intentKey,
                fingerprint = memory.fingerprint,
                screenKeys = memory.screenKeys,
                pkg = memory.pkg,
                steps = ActionStep.encodeAll(completed),
                outcome = RawTrace.OUTCOME_FAILED,
                failStep = completed.size + 1,
                detail = reason.take(RawTrace.DETAIL_MAX),
                source = RawTrace.SOURCE_AGENT,
                memoryId = memory.id
            ),
            "回放失败"
        )
    }

    /** 统一的落库路径：一条 insert + 一次防膨胀裁剪，都在 IO 线程 */
    private fun write(app: Context, row: RawTrace, label: String) {
        scope.launch {
            lock.withLock {
                runCatching {
                    val dao = AppDatabase.get(app).rawTraceDao()
                    val id = dao.insert(row)
                    dao.trim(RawTrace.MAX_ROWS)
                    Log.i(
                        TAG,
                        "[采集] $label 入池 id=$id（未加工）意图=\"${row.intent.take(24)}\" " +
                            "界面=${row.fingerprint.ifBlank { "（读不到）" }} " +
                            "步数=${ActionStep.decodeAll(row.steps).size} 待巩固=${dao.pendingCount()} 条"
                    )
                }.onFailure { Log.w(TAG, "采集$label 失败：${it.javaClass.simpleName} ${it.message}") }
            }
        }
    }

    /**
     * 写一条已经拼好的轨迹（0.9.16：给手动路径适配器用）。
     *
     * 走同一条串行队列，而不是让适配器自己开协程写库 ——
     * 手动路径的「先点哪个后点哪个」同样是有语义的顺序，
     * 两条写入路径各排各的队，库里就会前后颠倒。
     */
    internal fun writeTrace(ctx: Context?, row: RawTrace, label: String) {
        val app = ctx ?: return
        write(app, row, label)
    }
}
