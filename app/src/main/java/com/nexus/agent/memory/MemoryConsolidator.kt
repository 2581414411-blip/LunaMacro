package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.IntentTip
import com.nexus.agent.data.db.RawTrace
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 经验加工引擎 —— **只在夜里跑，一次看一整天的样本**（0.9.14 重构）。
 *
 * 0.9.13 是「边跑边学」：成功一次就建候选、失败一次就调模型。
 * 这一版把学习从执行路径上彻底摘下来，改成批处理（海马体巩固）。
 *
 * ## 为什么批处理比实时学更准
 *
 * 单条轨迹证明不了任何事。手滑点错、界面弹广告、网络刚好断一下，
 * 单看每一条都像「他就是这样做的 / 这里就是有坑」。
 * 只有把一天的全部样本摊开，才分得清：
 *
 *  - 同一界面同一意图**成功 ≥ 3 次** → 可能是习惯 → 提骨架做成可回放动作；
 *  - 同一处**失败 ≥ 2 次** → 可能真有坑 → 才值得调一次模型写经验；
 *  - 只出现一次的失败 → 大概率是偶发，**什么都不做**，样本照常归档。
 *
 * ## 一批只调一次模型
 *
 * 整批的失败点会合并成**一次**请求（最多 [MAX_TIP_ASKS] 个），
 * 而不是每个坑调一次。模型不可用时退化成规则生成的提示，
 * 保证「有证据但没预算」时也有产物，不至于白攒一夜。
 *
 * ## 三条副作用约束
 *
 * 1. 全程在 IO 线程，任何异常只打日志 —— 巩固失败只该「这晚没学到东西」，
 *    不该影响任何用户可见功能；
 * 2. **先写资产、后标归档**：一次崩溃只会让这一批样本下次重跑，
 *    绝不会出现「样本被标成已学、东西却没学会」；
 * 3. 归档用一条 `UPDATE ... WHERE id IN (...)`，不存在「标了一半」的中间态。
 */
object MemoryConsolidator {

    private const val TAG = "MemDebug"

    /** 一批最多读多少条轨迹（读太多会占内存，剩下的留到下一次） */
    const val MAX_BATCH = 400

    /** 一个界面 + 一个意图，成功几次才值得做成可回放动作 */
    const val MIN_SUCCESS_TO_LEARN = ActionMemory.ACTIVE_THRESHOLD

    /** 一条记忆至少要几步才值得建。单步记忆就是「点一下」，回放它没有意义还容易点错 */
    const val MIN_REPLAY_STEPS = 2

    /** 普通任务失败几次才认定「这里真的有坑」 */
    const val MIN_FAILS_FOR_TIP = 2

    /**
     * 骨架覆盖率下限。
     *
     * 同一个意图在同一个界面上完全可能有两种做法（先搜再点 / 直接点历史记录）。
     * 这时公共骨架会很短，硬压成一条「肌肉记忆」——两次里有一次必错。
     * 低于这个比例就不学，宁可少一条，也不要一个会点错的。
     */
    const val MIN_COVERAGE = 0.6f

    /** 回放失败一次就够 —— 那是我们自己造的路走不通，不是用户手滑 */
    const val MIN_REPLAY_FAILS_FOR_TIP = 1

    /** 一批最多问模型几个坑 */
    const val MAX_TIP_ASKS = 3

    /** 巩固过的轨迹保留几天再删（留几天是为了排查「那一夜到底学了什么」） */
    const val KEEP_TRACES_DAYS = 7

    private const val TIMEOUT_MS = 30_000L

    /** 便宜的优先，和错题本 / 长期记忆抽取保持一致 */
    private const val PREFERRED_PROVIDER = "deepseek"

    // ==================== 对外：一次巩固 ====================

    /**
     * 巩固结果。既是日志，也是设置页「上次巩固学了什么」的数据源。
     */
    data class Report(
        val batchId: Long = 0L,
        val traces: Int = 0,
        val groups: Int = 0,
        val learned: Int = 0,
        val promoted: Int = 0,
        val tips: Int = 0,
        val revoked: Int = 0,
        val purged: Int = 0,
        val llmCalls: Int = 0,
        val profile: Boolean = false,
        val skipped: String = ""
    ) {
        val isEmpty: Boolean get() = skipped.isNotEmpty() || traces == 0

        fun summary(): String = if (skipped.isNotEmpty()) {
            "跳过巩固：$skipped"
        } else if (traces == 0) {
            "没有待巩固的轨迹"
        } else {
            "$traces 条轨迹 → ${groups} 组：动作记忆 $learned 条（其中转正 $promoted）、" +
                "避坑经验 $tips 条、作废 $revoked 条、清理 $purged 条、模型调用 $llmCalls 次"
        }

        companion object {
            fun skipped(why: String) = Report(skipped = why)
        }
    }

    /**
     * 跑一次巩固。
     *
     * 调用方：夜间 Worker、设置页「现在巩固一次」、以及启动时的兜底（见 `MemoryNightShift`）。
     * 幂等：没有待处理轨迹时直接返回，不调模型、不写库。
     */
    suspend fun consolidate(ctx: Context): Report {
        val app = ctx.applicationContext
        if (!CopilotConfig.isMemoryEnabled(app)) {
            return Report.skipped("外挂记忆已关闭").also { Log.i(TAG, "[巩固] ${it.summary()}") }
        }
        val batchId = System.currentTimeMillis()
        val db = AppDatabase.get(app)
        val traceDao = db.rawTraceDao()

        val pending = runCatching { traceDao.pending(MAX_BATCH) }.getOrElse {
            Log.w(TAG, "[巩固] 读轨迹池失败：${it.message}")
            return Report.skipped("读轨迹池失败")
        }
        if (pending.isEmpty()) {
            Log.i(TAG, "[巩固] 轨迹池是空的，跳过（不调模型）")
            return Report(batchId = batchId)
        }

        val groups = group(pending)
        Log.i(
            TAG,
            "[巩固] 开始 batch=$batchId 轨迹=${pending.size} 条 → ${groups.size} 组" +
                "（同界面同意图归一组）"
        )

        val amDao = db.actionMemoryDao()
        val tipDao = db.intentTipDao()
        val now = System.currentTimeMillis()

        var learned = 0
        var promoted = 0
        var revoked = 0
        val tipAsks = ArrayList<Ask>(MAX_TIP_ASKS)
        var tipsExisting = 0
        val failureIds = ArrayList<Long>()

        for (g in groups) {
            // ---- ① 成功样本 → 动作记忆 ----
            var learnedOk = true
            runCatching {
                val variants = g.successVariants()
                if (variants.isNotEmpty()) {
                    val merged = mergeMany(variants)
                    val cov = coverage(merged, variants)
                    val existing = amDao.byKey(g.key)
                    if (cov < MIN_COVERAGE) {
                        Log.i(
                            TAG,
                            "[巩固] 跳过「${g.intent.take(20)}」：${variants.size} 次执行的公共骨架只占 " +
                                "${(cov * 100).toInt()}%（低于 ${(MIN_COVERAGE * 100).toInt()}%），" +
                                "说明走的不是同一条路，压成一条会两边都点不准"
                        )
                    } else if (merged.size < MIN_REPLAY_STEPS && existing == null) {
                        Log.i(TAG, "[巩固] 跳过「${g.intent.take(20)}」：骨架只有 ${merged.size} 步，太短不值得回放")
                    } else if (MemoryGuard.rejectReason(merged).isNotEmpty()) {
                        Log.i(TAG, "[巩固] 跳过「${g.intent.take(20)}」：${MemoryGuard.rejectReason(merged)}")
                    } else if (existing == null) {
                        val state = if (variants.size >= MIN_SUCCESS_TO_LEARN) {
                            ActionMemory.STATE_ACTIVE
                        } else {
                            ActionMemory.STATE_CANDIDATE
                        }
                        val row = ActionMemory(
                            memoryKey = g.key,
                            intent = g.intent.take(RawTrace.INTENT_MAX),
                            intentKey = g.intentKey,
                            fingerprint = g.fingerprint,
                            screenKeys = g.screenKeys,
                            pkg = g.pkg,
                            steps = ActionStep.encodeAll(merged),
                            hits = 0,
                            successCount = variants.size,
                            state = state,
                            weight = MemoryWeight.scoreOf(0, variants.size, 0, 0L, now),
                            createdAt = now,
                            updatedAt = now
                        )
                        val id = amDao.upsert(row)
                        learned++
                        if (state == ActionMemory.STATE_ACTIVE) promoted++
                        Log.i(
                            TAG,
                            "[巩固] 新建动作记忆 id=$id 「${g.intent.take(20)}」 " +
                                "${merged.size} 步（${variants.size} 个样本）state=$state"
                        )
                    } else {
                        val oldSteps = ActionStep.decodeAll(existing.steps)
                        if (merged.isNotEmpty() && ActionStep.encodeAll(merged) != existing.steps) {
                            amDao.updateSteps(existing.id, ActionStep.encodeAll(merged), now)
                        }
                        val success = existing.successCount + variants.size
                        val weight = MemoryWeight.scoreOf(
                            existing.hits, success, existing.failCount, existing.lastHitAt, now
                        )
                        amDao.markLearnedBulk(existing.id, now, weight, variants.size)
                        val newState = nextStateOnSuccess(existing, success)
                        if (newState != existing.state) {
                            amDao.setState(existing.id, newState, now)
                            if (newState == ActionMemory.STATE_ACTIVE) promoted++
                            Log.i(TAG, "[巩固] 进化 id=${existing.id} ${existing.state} → $newState（累计成功 $success 次）")
                        }
                        learned++
                        Log.i(
                            TAG,
                            "[巩固] 累加 id=${existing.id}「${g.intent.take(20)}」 " +
                                "+${variants.size} 个样本，骨架 ${oldSteps.size}→${merged.size} 步，" +
                                "累计成功 $success 次"
                        )
                    }
                }
            }.onFailure {
                learnedOk = false
                Log.w(TAG, "[巩固] 处理成功样本失败：${it.message}")
            }
            // 学一个归档一个：后面若崩掉、或用户中途退出设置页，
            // 这批成功样本不会在下一夜被再数一遍（成功次数翻倍 = 两条就转正）
            if (learnedOk) {
                val ids = g.learnedIds()
                if (ids.isNotEmpty()) {
                    runCatching { traceDao.markProcessed(ids, now, batchId) }
                        .onFailure { Log.w(TAG, "[巩固] 归档成功样本失败：${it.message}") }
                }
            }

            // ---- ② 回放失败 → 兜底作废 + 收集经验素材 ----
            var failOk = true
            runCatching {
                if (g.replayFails.isNotEmpty()) {
                    val existing = amDao.byKey(g.key)
                    if (existing != null && existing.state != ActionMemory.STATE_REVOKED &&
                        existing.consecutiveFail >= ActionMemory.MAX_CONSECUTIVE_FAIL
                    ) {
                        // 正常情况下运行时已经作废过了；这里兜住「进程被杀没来得及写」的情况
                        amDao.setState(existing.id, ActionMemory.STATE_REVOKED, now)
                        revoked++
                        Log.i(TAG, "[巩固] 作废 id=${existing.id}：连续 ${existing.consecutiveFail} 次回放失败")
                    }
                }
                val ask = askForTip(g, tipDao)
                when (ask) {
                    is TipDecision.Needed -> {
                        if (tipAsks.size < MAX_TIP_ASKS) tipAsks.add(ask.ask) else Log.i(
                            TAG, "[巩固] 坑太多，本批只问前 $MAX_TIP_ASKS 个：「${ask.ask.intent.take(16)}」留到下次"
                        )
                    }

                    is TipDecision.Existing -> tipsExisting++
                    TipDecision.NotEnough -> Unit
                }
            }.onFailure {
                failOk = false
                Log.w(TAG, "[巩固] 处理失败样本失败：${it.message}")
            }
            // 失败样本要等经验落地才归档；这里只记下 id，第 ⑤ 步统一标
            if (failOk) failureIds.addAll(g.failureIds())
        }

        // ---- ③ 一次请求，把本批的坑写成经验 ----
        var llmCalls = 0
        var tips = tipsExisting
        if (tipAsks.isNotEmpty()) {
            val (map, calls) = reflectTips(app, tipAsks)
            llmCalls += calls
            for (ask in tipAsks) {
                val tip = map[ask.key].orEmpty().ifBlank { fallbackTip(ask) }
                if (tip.isBlank()) continue
                runCatching {
                    tipDao.upsert(
                        IntentTip(
                            tipKey = ask.key,
                            fingerprint = ask.fingerprint,
                            intentKey = ask.intentKey,
                            pkg = ask.pkg,
                            tip = tip.take(IntentTip.TIP_MAX_CHARS),
                            hits = ask.fails,
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                    tips++
                    Log.i(TAG, "[巩固] 新经验（${ask.fails} 次证据）：$tip")
                }.onFailure { Log.w(TAG, "[巩固] 写经验失败：${it.message}") }
            }
            runCatching { tipDao.trim(IntentTip.MAX_ROWS) }
        }

        // ---- ④ 偏好：从轨迹池的最近窗口里统计，不额外采集 ----
        // 用 recent 而不是 pending：pending 只有「这一夜新增的」，
        // 逢周末三天没巩固，画像就会只剩最后一晚的样本，看起来像换了个人。
        val profileOk = runCatching {
            MemoryProfile.rebuild(app, traceDao.recent(MemoryProfile.WINDOW))
        }.onFailure { Log.w(TAG, "[巩固] 更新偏好失败：${it.message}") }.isSuccess

        // ---- ⑤ 归档：失败样本在这时才能归档，加上从没进过分组的（空指纹） ----
        //
        // 分组逻辑会丢掉「指纹为空 / 意图为空」的轨迹（它们学不出东西）。
        // 如果不把它们一起标掉，它们会永远躺在 pending 里，每晚都占一个批次名额。
        val groupedIds = groups.flatMap { it.learnedIds() + it.failureIds() }.toHashSet()
        val orphanIds = pending.map { it.id }.filter { it !in groupedIds }
        val purged = runCatching {
            val all = failureIds + orphanIds
            if (all.isNotEmpty()) traceDao.markProcessed(all, now, batchId)
            traceDao.purgeProcessed(now - KEEP_TRACES_DAYS * 86_400_000L) + traceDao.trim(RawTrace.MAX_ROWS)
        }.getOrElse {
            Log.w(TAG, "[巩固] 归档失败：${it.message}")
            0
        }

        // 经验变了，路由的内存缓存要跟着换，否则新学的一句都还没生效
        if (tips > tipsExisting) runCatching { MemoryRouter.reloadTips(app) }

        val report = Report(
            batchId = batchId,
            traces = pending.size,
            groups = groups.size,
            learned = learned,
            promoted = promoted,
            tips = tips,
            revoked = revoked,
            purged = purged,
            llmCalls = llmCalls,
            profile = profileOk
        )
        CopilotConfig.setMemoryLastConsolidateAt(app, now)
        CopilotConfig.setMemoryLastReport(app, report.summary())
        Log.i(TAG, "[巩固] 完成 ${report.summary()}")
        return report
    }

    // ==================== 分组（纯函数，单测直接钉） ====================

    /**
     * 一组「同一个界面 + 同一个意图」的样本。
     *
     * 为什么按 `指纹|意图` 分组而不是按意图分组：同一个意图在不同界面下要走的
     * 完全是两条路（「打开设置」在任何界面说都可能成立）。混在一起学，学出来的是拼接怪。
     */
    data class Group(
        val key: String,
        val fingerprint: String,
        val intentKey: String,
        val intent: String,
        val pkg: String,
        val screenKeys: String,
        val successes: List<RawTrace>,
        val failures: List<RawTrace>,
        val replayFails: List<RawTrace>,
        val aborts: List<RawTrace> = emptyList()
    ) {
        /** 可用的成功变体（解码后、过滤掉空的和超长的） */
        fun successVariants(): List<List<ActionStep>> = successes.mapNotNull { t ->
            val steps = ActionStep.decodeAll(t.steps)
            if (steps.isEmpty() || steps.size > ActionMemory.MAX_STEPS) null else steps
        }

        /**
         * 「已经学完、可以立刻归档」的那部分轨迹。
         *
         * 成功和中断都算：成功样本一旦写成动作记忆就再也不需要了，
         * 中断样本本来就没东西可学。失败样本不在这里 ——
         * 它要等经验写出来才能归档，否则崩在中间就把唯一的证据弄丢了。
         */
        fun learnedIds(): List<Long> = successes.map { it.id } + aborts.map { it.id }

        /** 失败样本（等经验落地后才归档） */
        fun failureIds(): List<Long> = failures.map { it.id } + replayFails.map { it.id }
    }

    /**
     * 把一批轨迹按 `指纹|意图` 归组。
     *
     * **指纹为空的直接丢**：没有界面就定位不了「在哪儿做」，拿它去学只会得到
     * 一条「在任何界面都能跑」的假记忆 —— 那是最危险的一种。
     */
    fun group(traces: List<RawTrace>): List<Group> {
        data class B(
            val key: String,
            val fingerprint: String,
            val intentKey: String,
            var intent: String,
            val pkg: String,
            var screenKeys: String,
            val successes: MutableList<RawTrace> = ArrayList(),
            val failures: MutableList<RawTrace> = ArrayList(),
            val replayFails: MutableList<RawTrace> = ArrayList(),
            val aborts: MutableList<RawTrace> = ArrayList()
        )

        val map = LinkedHashMap<String, B>()
        for (t in traces) {
            if (t.fingerprint.isBlank() || t.intentKey.isBlank()) continue
            val key = memoryKeyOf(t.fingerprint, t.intentKey)
            val b = map.getOrPut(key) {
                B(key, t.fingerprint, t.intentKey, t.intent, t.pkg, t.screenKeys)
            }
            if (b.intent.isBlank()) b.intent = t.intent
            if (b.screenKeys.isBlank()) b.screenKeys = t.screenKeys
            when {
                t.kind == RawTrace.KIND_REPLAY_FAIL -> b.replayFails.add(t)
                t.outcome == RawTrace.OUTCOME_SUCCESS && t.steps.isNotBlank() -> b.successes.add(t)
                t.kind == RawTrace.KIND_AGENT_ABORT -> b.aborts.add(t) // 中断不学，但要归档
                else -> b.failures.add(t)
            }
        }
        return map.values.map {
            Group(
                key = it.key,
                fingerprint = it.fingerprint,
                intentKey = it.intentKey,
                intent = it.intent,
                pkg = it.pkg,
                screenKeys = it.screenKeys,
                successes = it.successes,
                failures = it.failures,
                replayFails = it.replayFails,
                aborts = it.aborts
            )
        }
    }

    // ==================== 骨架合并 ====================

    /**
     * 求两条轨迹的 LCS 骨架，取**新**那条的参数值。
     *
     * 取新的而不是旧的：参数里如果带着「今天」这种会变的字段，
     * 留着上个月的值只会让下一次失败。
     *
     * 保护：合并结果少于 2 步时不做收敛 —— 两条轨迹差太多说明不是同一个动作，
     * 这时候硬求交集会得到一条几乎空的记忆，回放必然失败。
     */
    internal fun mergeSteps(old: List<ActionStep>, new: List<ActionStep>): List<ActionStep> {
        if (old.isEmpty()) return new
        if (new.isEmpty()) return old
        val table = lcsIndices(old, new)
        return if (table.size < 2) new else table
    }

    /**
     * 多条轨迹一起求骨架（夜间批处理用）。
     *
     * 和 [mergeSteps] 的区别：**遇到差异过大的样本时保留累积结果，而不是换成那条样本**。
     * 批处理里一条离群样本（用户那天走了一条更短的路）不该把已经收敛的骨架整个替掉；
     * 两条样本的实时合并则可以信新的那条（那时还没有「累积」可言）。
     *
     * 起点取最长的一条：它最可能包含完整路径，从它往下削比从短的往上长更稳。
     */
    fun mergeMany(variants: List<List<ActionStep>>): List<ActionStep> {
        val list = variants.filter { it.isNotEmpty() }
        if (list.isEmpty()) return emptyList()
        if (list.size == 1) return list[0]
        var acc = list.maxByOrNull { it.size }!!
        for (v in list) {
            if (v === acc) continue
            val lcs = lcsIndices(acc, v)
            // 少于两步说明这一条跟积累的完全不是一条路 → 当成离群样本丢掉
            if (lcs.size >= 2) acc = lcs
        }
        return acc
    }

    /**
     * 骨架覆盖率 = 骨架步数 / 最长样本步数。
     *
     * 它回答的是「这几次执行到底是不是同一条路」：
     *  - 1.0 → 每次都一模一样，可以放心回放；
     *  - 0.6 → 主干一致，细节有出入（偶尔多一步广告），仍可学；
     *  - 0.3 → 完全两种做法，学出来必然一半失败 → [MIN_COVERAGE] 直接拦掉。
     */
    fun coverage(merged: List<ActionStep>, variants: List<List<ActionStep>>): Float {
        val longest = variants.maxOfOrNull { it.size } ?: return 0f
        if (longest <= 0) return 0f
        return merged.size.toFloat() / longest
    }

    /** 按参数化签名求 LCS，返回映射到 `new` 上的步骤序列 */    private fun lcsIndices(old: List<ActionStep>, new: List<ActionStep>): List<ActionStep> {
        val n = old.size
        val m = new.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (ActionStep.same(old[i], new[j])) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val out = ArrayList<ActionStep>(minOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                ActionStep.same(old[i], new[j]) -> {
                    out.add(new[j])
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /**
     * 成功累计到阈值才转 ACTIVE。
     *
     * 已过期/已作废的重新跑通一次 → 退回 CANDIDATE 重新攒，**不直接复活**。
     * 理由：它被作废过，说明这条路径在真实界面上不稳；
     * 一次成功不等于改好了，得连续成功够数再说。
     */
    internal fun nextStateOnSuccess(old: ActionMemory, successCount: Int): String = when {
        old.state == ActionMemory.STATE_ACTIVE -> ActionMemory.STATE_ACTIVE
        old.state == ActionMemory.STATE_EXPIRED || old.state == ActionMemory.STATE_REVOKED ->
            if (successCount >= ActionMemory.ACTIVE_THRESHOLD) ActionMemory.STATE_ACTIVE
            else ActionMemory.STATE_CANDIDATE
        successCount >= ActionMemory.ACTIVE_THRESHOLD -> ActionMemory.STATE_ACTIVE
        else -> ActionMemory.STATE_CANDIDATE
    }

    // ==================== 经验：素材收集 → 一次提炼 ====================

    /** 一个待提炼的坑 */
    data class Ask(
        val key: String,
        val fingerprint: String,
        val intentKey: String,
        val pkg: String,
        val intent: String,
        val fails: Int,
        val evidence: String,
        val fromReplay: Boolean
    )

    sealed class TipDecision {
        data class Needed(val ask: Ask) : TipDecision()
        data class Existing(val hitCount: Int) : TipDecision()
        object NotEnough : TipDecision()
    }

    /**
     * 这个组值不值得出一条经验。
     *
     * 三道闸：
     *  1. **回放失败**一次就算（那是我们自己的路走不通）；
     *  2. **普通失败**要 ≥ [MIN_FAILS_FOR_TIP] 次（单次失败大概率是偶发）；
     *  3. 已经有经验的，只累加次数，**不再调模型**。
     */
    suspend fun askForTip(g: Group, tipDao: com.nexus.agent.data.db.IntentTipDao): TipDecision {
        val replay = g.replayFails.size
        val normal = g.failures.size
        val replayQualified = replay >= MIN_REPLAY_FAILS_FOR_TIP
        val normalQualified = normal >= MIN_FAILS_FOR_TIP
        if (!replayQualified && !normalQualified) return TipDecision.NotEnough

        val existing = tipDao.byKey(g.key)
        if (existing != null) {
            val bumped = existing.copy(hits = existing.hits + replay + normal, updatedAt = System.currentTimeMillis())
            runCatching { tipDao.upsert(bumped) }
            Log.i(TAG, "[巩固] 已知坑（第 ${bumped.hits} 次）：${existing.tip}")
            return TipDecision.Existing(bumped.hits)
        }

        val evidence = buildString {
            append("界面包名：").append(g.pkg.ifBlank { "未知" }).append('\n')
            append("界面上能点的东西：")
                .append(g.screenKeys.replace('\u0001', '、').take(180)).append('\n')
            val sample = (g.replayFails + g.failures).take(4)
            sample.forEachIndexed { i, t ->
                append("失败 ").append(i + 1).append("：")
                if (t.kind == RawTrace.KIND_REPLAY_FAIL) append("（回放记忆时）")
                append("第 ").append(t.failStep.coerceAtLeast(1)).append(" 步失败")
                if (t.detail.isNotBlank()) append("，原因：").append(t.detail.take(80))
                append('\n')
                val done = ActionStep.decodeAll(t.steps)
                if (done.isNotEmpty()) {
                    append("  已做：")
                        .append(done.take(6).joinToString(" → ") { it.tool + it.args.values.joinToString("/") })
                        .append('\n')
                }
            }
        }
        return TipDecision.Needed(
            Ask(
                key = g.key,
                fingerprint = g.fingerprint,
                intentKey = g.intentKey,
                pkg = g.pkg,
                intent = g.intent,
                fails = replay + normal,
                evidence = evidence,
                fromReplay = replayQualified
            )
        )
    }

    private const val TIP_PROMPT = """你是一个手机自动化 Agent 的复盘教练。

下面是今天收集到的几个「在这个界面上反复失败」的场景。
请为**每一个**场景总结一条「下次该怎么做」的经验。

要求：
- 每个场景只写一句话，祈使句，直接给做法，不超过 40 字。
- 必须具体到可执行：写清先做什么、避免什么、换哪个做法。
- 不要写「注意检查」「多加小心」这类空话。
- 严格按 `序号|这句话` 的格式输出，一个场景一行，不要编号别的、不要解释、不要 markdown。
- 序号就是下面给出的序号，不要改。

示例输出：
1|在搜索页先点搜索框等键盘弹出，再点搜索按钮。
2|点发布前先往下翻确认内容已加载完。"""

    private fun buildTipUserBlock(asks: List<Ask>): String = buildString {
        asks.forEachIndexed { i, a ->
            append("【场景 ").append(i + 1).append("】")
            append("任务：").append(a.intent.take(60))
            if (a.fromReplay) append("（我们自己照着记忆回放，失败了）")
            append('\n')
            append(a.evidence.lines().joinToString("\n") { "  $it" }.trimStart())
            append("\n\n")
        }
    }

    /**
     * 清洗模型输出并按序号对应回场景。
     *
     * 解析失败一律返回空表 —— 让调用方退化到 [fallbackTip]，
     * **不要靠猜**把一句话挂到错误的界面上：挂错的坑比没有坑更害人。
     */
    internal fun parseTipLines(raw: String?, count: Int): Map<Int, String> {
        if (raw.isNullOrBlank() || count <= 0) return emptyMap()
        val out = LinkedHashMap<Int, String>()
        for (line in raw.lineSequence()) {
            val s = line.trim()
            if (s.isEmpty()) continue
            val bar = s.indexOf('|')
            if (bar <= 0) continue
            val idx = s.substring(0, bar).trim().toIntOrNull() ?: continue
            if (idx !in 1..count) continue
            val tip = sanitizeTip(s.substring(bar + 1))
            if (tip.isNotEmpty()) out.putIfAbsent(idx, tip)
        }
        return out
    }

    /**
     * 一次请求搞定整批。
     *
     * @return 场景 key → 经验；以及实际调用次数（0 = 没配模型）
     */
    private suspend fun reflectTips(ctx: Context, asks: List<Ask>): Pair<Map<String, String>, Int> {
        val provider = pickProvider(ctx)
        val (k, _, model) = LlmClientFactory.getConfig(ctx, provider)
        if (k.isBlank() && provider != "ollama") {
            Log.i(TAG, "[巩固] 未配置 $provider 的 Key → 经验退化为规则生成（不调模型）")
            return emptyMap<String, String>() to 0
        }
        val client = runCatching { LlmClientFactory.create(provider, ctx) }.getOrElse {
            Log.w(TAG, "[巩固] 建模型客户端失败：${it.message}")
            return emptyMap<String, String>() to 0
        }
        val raw = runCatching {
            withTimeoutOrNull(TIMEOUT_MS) {
                client.chat(
                    listOf(
                        LlmClient.Message("system", TIP_PROMPT),
                        LlmClient.Message("user", buildTipUserBlock(asks))
                    )
                )
            }
        }.onFailure {
            Log.w(TAG, "[巩固] 经验提炼失败：${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()

        val parsed = parseTipLines(raw, asks.size)
        if (parsed.isEmpty()) {
            Log.i(TAG, "[巩固] 模型没给出可解析的经验（provider=$provider model=$model）→ 退化为规则生成")
            return emptyMap<String, String>() to 1
        }
        val map = LinkedHashMap<String, String>()
        parsed.forEach { (idx, tip) -> map[asks[idx - 1].key] = tip }
        Log.i(TAG, "[巩固] 模型一次产出 ${map.size}/${asks.size} 条经验")
        return map to 1
    }

    /**
     * 模型不可用时的兜底经验。
     *
     * 它不如模型写得好，但**比什么都不写强**：至少说清了「在第几步、
     * 什么动作上挂过几次」，下次规划时能看到「这里容易出事，先确认界面」。
     * 内容全部来自事实，没有猜测。
     */
    internal fun fallbackTip(a: Ask): String {
        val where = a.intent.take(12).ifBlank { "这个界面" }
        return if (a.fromReplay) {
            "「$where」这条记忆回放过 ${a.fails} 次都没走通，别再用记忆代跑，先手动确认界面。"
        } else {
            "「$where」在第 ${a.fails} 次操作里反复失败，动手前先确认界面已加载完再点。"
        }
    }

    // ==================== 工具方法 ====================

    /** 记忆桶键：`指纹|意图键` */
    fun memoryKeyOf(fingerprint: String, intentKey: String): String =
        "$fingerprint|${intentKey.take(IntentKey.MAX_CHARS)}"

    /**
     * 清洗模型输出。
     *
     * 和错题本同一套处理：模型爱加「经验：」「1.」和引号，
     * 还经常写完一句再补一段解释。这里只留第一句有效内容。
     */
    internal fun sanitizeTip(raw: String?): String {
        val first = raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return ""
        var s = first
        s = s.replace(Regex("^[-*·]\\s*"), "")
        s = s.replace(Regex("^\\d+[.、)]\\s*"), "")
        s = s.trim()
        listOf("经验：", "经验:", "教训：", "教训:", "结论：", "结论:").forEach { s = s.removePrefix(it) }
        s = s.trim().trim('"', '\'', '“', '”', '「', '」', '`', ' ')
        if (s.length > IntentTip.TIP_MAX_CHARS) s = s.take(IntentTip.TIP_MAX_CHARS)
        return s
    }

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) PREFERRED_PROVIDER else LlmClientFactory.getProvider(ctx)
    }
}

/**
 * 达尔文评分（0.9.13 起）。
 *
 * `weight = 0.45 × 成功率 + 0.35 × 新近度 + 0.20 × 频率`
 *
 * - **成功率**是最重的：一条回放两次挂两次的记忆，就算用得多也不该排在前面；
 * - **新近度**用指数衰减，30 天掉到 0.37 —— 界面的改版周期大概就是这个量级；
 * - **频率**封顶在 10 次，避免一条早高峰的地铁闸机式操作把别的全压下去。
 *
 * 全部是纯函数，单测直接钉死。
 */
object MemoryWeight {

    private const val RECENCY_HALF_LIFE_DAYS = 30.0

    fun scoreOf(hits: Int, successCount: Int, failCount: Int, lastHitAt: Long, now: Long): Float {
        val total = successCount + failCount
        val successRate = if (total <= 0) 0.5f else successCount.toFloat() / total
        val recency = if (lastHitAt <= 0L) 0.5f else {
            val days = ((now - lastHitAt).coerceAtLeast(0L)) / 86_400_000.0
            Math.pow(0.5, days / RECENCY_HALF_LIFE_DAYS).toFloat()
        }
        val freq = (hits.coerceAtMost(10)) / 10f
        return (0.45f * successRate + 0.35f * recency + 0.20f * freq).coerceIn(0f, 1f)
    }
}
