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
        val skipped: String = "",
        /** 手动路径新学到的候选（等用户命名，0.9.16） */
        val candidates: Int = 0,
        /** suspect 因为又跑通而恢复成 active 的条数（0.9.16） */
        val restored: Int = 0
    ) {
        val isEmpty: Boolean get() = skipped.isNotEmpty() || traces == 0

        fun summary(): String = if (skipped.isNotEmpty()) {
            "跳过巩固：$skipped"
        } else if (traces == 0) {
            "没有待巩固的轨迹"
        } else {
            buildString {
                append("$traces 条轨迹 → ${groups} 组：动作记忆 $learned 条")
                append("（其中转正 $promoted）")
                if (candidates > 0) append("、新候选 $candidates 条")
                if (restored > 0) append("、恢复 $restored 条")
                append("、避坑经验 $tips 条、作废 $revoked 条、清理 $purged 条")
                append("、模型调用 $llmCalls 次")
            }
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

        // 0.9.16：两条数据源分开处理。
        // agent 轨迹有意图，按「指纹|意图」归组、够 3 次自动转正；
        // user 轨迹没有意图，只能按骨架相似度聚类、且永远只能产候选。
        // 混在一起分组的话，user 轨迹会因为 intentKey 为空被 group() 整批丢掉。
        val userTraces = pending.filter { it.source == RawTrace.SOURCE_USER }
        val agentTraces = pending.filter { it.source != RawTrace.SOURCE_USER }
        val groups = group(agentTraces)
        Log.i(
            TAG,
            "[巩固] 开始 batch=$batchId 轨迹=${pending.size} 条（她自己 $agentTraces 条 / " +
                "手动 $userTraces 条）→ ${groups.size} 组（同界面同意图归一组）"
        )

        val amDao = db.actionMemoryDao()
        val tipDao = db.intentTipDao()
        val now = System.currentTimeMillis()

        var learned = 0
        var promoted = 0
        var revoked = 0
        var candidates = 0
        var restored = 0
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
                            origin = ActionMemory.ORIGIN_AGENT,
                            coverage = cov,
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
                            amDao.updateSteps(existing.id, ActionStep.encodeAll(merged), cov, now)
                        }
                        val success = existing.successCount + variants.size
                        val weight = MemoryWeight.scoreOf(
                            existing.hits, success, existing.failCount, existing.lastHitAt, now
                        )
                        amDao.markLearnedBulk(existing.id, now, weight, variants.size)
                        val newState = gatedState(
                            existing.origin, existing.label, nextStateOnSuccess(existing, success)
                        )
                        if (newState != existing.state) {
                            amDao.setState(existing.id, newState, now)
                            if (newState == ActionMemory.STATE_ACTIVE) promoted++
                            if (existing.state == ActionMemory.STATE_SUSPECT &&
                                newState == ActionMemory.STATE_ACTIVE
                            ) {
                                restored++
                            }
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
                    if (existing != null &&
                        existing.state != ActionMemory.STATE_REVOKED &&
                        existing.state != ActionMemory.STATE_REJECTED &&
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

        // ---- ②' 手动路径：聚类 → **只产候选**，等用户给它起名字 ----
        //
        // 这一段和上面那条循环的差别不是「少学一点」，而是**学的东西不一样**：
        // agent 轨迹有意图，攒够次数就能自动回放；手动轨迹只有动作，没有目的，
        // 所以永远停在 candidate，直到用户在设置页点「收下」并命名。
        val userArchivedIds = ArrayList<Long>()
        runCatching {
            val r = learnUserPaths(amDao, userTraces, now)
            candidates += r.candidates
            restored += r.restored
            userArchivedIds.addAll(r.archivedIds)
        }.onFailure { Log.w(TAG, "[巩固] 处理手动路径失败：${it.message}") }

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
        //
        // 手动路径的两类单独处理：
        //  - 已学成的（写进 action_memory 了）当场归档；
        //  - 样本不够、但已经躺过保留期的，也归档 —— 否则 pending 永远清不空，
        //    「待巩固 ≥ 3 条才跑夜间任务」这道闸会被几条陈年旧点击一直骗开。
        //    没到保留期的**留着**，让下一夜和新的样本一起凑够次数。
        val groupedIds = groups.flatMap { it.learnedIds() + it.failureIds() }.toHashSet()
        val orphanIds = agentTraces.map { it.id }.filter { it !in groupedIds }
        val purged = runCatching {
            val all = failureIds + orphanIds + userArchivedIds
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
            profile = profileOk,
            candidates = candidates,
            restored = restored
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
     *
     * 两个 0.9.16 补的分支：
     *  - `suspect` 直接回 `active`：它本来就攒够过次数，只是被一次失败打下来，
     *    当晚又跑通说明只是界面抽了一下，不该让它从零开始攒；
     *  - `rejected` 永远不动：那是用户主动说「不要这个」，跟「自己跑挂了」不是一回事。
     */
    internal fun nextStateOnSuccess(old: ActionMemory, successCount: Int): String = when {
        old.state == ActionMemory.STATE_REJECTED -> ActionMemory.STATE_REJECTED
        old.state == ActionMemory.STATE_ACTIVE -> ActionMemory.STATE_ACTIVE
        old.state == ActionMemory.STATE_SUSPECT -> ActionMemory.STATE_ACTIVE
        old.state == ActionMemory.STATE_EXPIRED || old.state == ActionMemory.STATE_REVOKED ->
            if (successCount >= ActionMemory.ACTIVE_THRESHOLD) ActionMemory.STATE_ACTIVE
            else ActionMemory.STATE_CANDIDATE
        successCount >= ActionMemory.ACTIVE_THRESHOLD -> ActionMemory.STATE_ACTIVE
        else -> ActionMemory.STATE_CANDIDATE
    }

    /**
     * 转正前的最后一道闸：**手动路径没有名字就不许 active**。
     *
     * 名字是手动轨迹唯一的意图来源。没有名字的 active 记忆在路由里也过不了
     * `isReplayable`，但状态先停在 candidate 更诚实 —— 设置页显示的「已学会」
     * 不该包含一条她其实调不动的记忆。
     */
    internal fun gatedState(origin: String, label: String, next: String): String =
        if (origin == ActionMemory.ORIGIN_USER &&
            label.isBlank() &&
            next == ActionMemory.STATE_ACTIVE
        ) {
            ActionMemory.STATE_CANDIDATE
        } else {
            next
        }

    /**
     * 回放失败之后的状态（0.9.16 的 `suspect` 中间态）。
     *
     * ```
     * 第 1 次失败：active → suspect（当场失去拦截资格，但不作废）
     * 第 2 次失败：→ revoked
     * ```
     *
     * 为什么不是「一次就作废」：界面卡一下、弹个广告、加载慢半拍，
     * 都能让一次回放失败。一次就废，用户体感是「她昨天还会，今天突然不会了」，
     * 而这条记忆可能明天还是好用的。suspect 既满足「错了立刻不拦」，
     * 又给它留了一次被当晚成功轨迹救回来的机会。
     */
    internal fun stateOnFail(current: String, consecutiveFail: Int): String = when {
        consecutiveFail >= ActionMemory.MAX_CONSECUTIVE_FAIL -> ActionMemory.STATE_REVOKED
        current == ActionMemory.STATE_ACTIVE || current == ActionMemory.STATE_SUSPECT ->
            ActionMemory.STATE_SUSPECT
        else -> current
    }

    // ==================== 手动路径：聚类（纯函数，单测直接钉） ====================

    /** 一段「用户自己反复走的同一条路」 */
    data class UserCluster(
        val pkg: String,
        val app: String,
        /** `指纹|建议名` —— 建议名由骨架决定，所以跨夜稳定，能累加 */
        val key: String,
        /** 系统给的建议名（用户收下时可以改） */
        val name: String,
        val steps: List<ActionStep>,
        val variants: List<List<ActionStep>>,
        val coverage: Float,
        val occurrences: Int,
        val days: Int,
        val lastAt: Long,
        val ids: List<Long>
    )

    internal data class UserOutcome(
        val candidates: Int = 0,
        val restored: Int = 0,
        /** 这一批可以归档的轨迹（学成的 + 丢弃的 + 躺过保留期的） */
        val archivedIds: List<Long> = emptyList()
    )

    /** 手动路径也要跨天：同一天连点三次不是习惯，是重复操作 */
    const val MIN_PATH_DAYS = 2

    /** 两条手动轨迹算「同一条路」的 Dice 阈值（与 habit 的 0.7 对齐） */
    const val PATH_SIMILARITY = 0.7f

    /** 一夜最多产出几个手动候选（和 habit 一样限流，避免一口气堆满设置页） */
    const val MAX_USER_CLUSTERS = 6

    /**
     * 手动轨迹 → 聚类。
     *
     * 和 [group] 的根本区别：**没有意图键可用**，只能按骨架相似度聚类。
     * 聚类键（`指纹|建议名`）里的建议名由骨架的头三步拼出来，所以
     * 同一条路在不同夜里会算出同一个键，成功次数才能跨夜累加。
     */
    fun clusterUserPaths(traces: List<RawTrace>): List<UserCluster> {
        val units = traces.filter { it.source == RawTrace.SOURCE_USER }.mapNotNull { t ->
            val s = ActionStep.decodeAll(t.steps)
            if (s.isEmpty() || s.size > ActionMemory.MAX_STEPS) null else Unit(t, s)
        }.filter { it.t.pkg.isNotBlank() }
        if (units.isEmpty()) return emptyList()

        val out = ArrayList<UserCluster>(8)
        for ((pkg, list) in units.groupBy { it.t.pkg }) {
            val buckets = clusterUnits(list)
            for (bucket in buckets) {
                val variants = bucket.units.map { it.steps }
                val merged = bucket.acc
                if (merged.size < MIN_REPLAY_STEPS) continue
                val app = bucket.units.first().t.detail.ifBlank { pkg }
                val name = suggestedNameOf(app, merged)
                val intentKey = IntentKey.normalize(name).ifBlank { pkg }
                out.add(
                    UserCluster(
                        pkg = pkg,
                        app = app,
                        key = memoryKeyOf(AccessibilityTraceAdapter.fingerprintOf(pkg), intentKey),
                        name = name,
                        steps = merged,
                        variants = variants,
                        coverage = coverage(merged, variants),
                        occurrences = bucket.units.size,
                        days = distinctDaysOf(bucket.units.map { it.t.createdAt }),
                        lastAt = bucket.units.maxOf { it.t.createdAt },
                        ids = bucket.units.map { it.t.id }
                    )
                )
            }
        }
        return out
            .sortedWith(compareByDescending<UserCluster> { it.occurrences }.thenByDescending { it.lastAt })
            .take(MAX_USER_CLUSTERS)
    }

    /** 一个簇：成员轨迹 + 随成员变化的公共骨架 */
    private class PathBucket(val units: MutableList<Unit>) {
        var acc: List<ActionStep> = units.first().steps
    }

    private data class Unit(val t: RawTrace, val steps: List<ActionStep>)

    /**
     * 两轮聚类。
     *
     * **为什么不能只跑一轮**：轨迹的末尾经常是「内容」而不是「路线」——
     * 「搜索 → 订阅号 → 某篇文章」和「搜索 → 订阅号 → 今天这篇」是同一个动作，
     * 但按 LCS 算相似度只有 2×2/(3+3)=0.67，卡在 [PATH_SIMILARITY] 下面。
     * 只跑一轮的话，同一条路每换一篇文章就被切成新的一簇，各攒各的次数，
     * 结果「每天看公众号」永远攒不到 3 次，一辈子学不会。
     *
     * 所以：
     *  1. 第一轮**按骨架迭代**（每加入一条就重算骨架），而不是跟「种子那条」比 ——
     *     骨架会随着样本增加越来越短、越来越稳，后来的变体才容易被接纳；
     *  2. 第二轮做**簇间合并**：两个簇的骨架之间再判一次相似度。
     *     上面那个例子在第二轮的骨架是 [搜索,订阅号] 和 [搜索,订阅号,今天这篇]，
     *     相似度 2×2/(2+3)=0.8，能合上。
     *
     * 合并过头的风险由 `MIN_COVERAGE`（0.6）兜着：真的两种走法被硬合到一起时，
     * 公共骨架会短于最长样本的 60%，那一簇在学的时候会被整段丢掉，
     * 不会变成一条「只点前两步」的半吊子记忆。
     */
    private fun clusterUnits(units: List<Unit>): List<PathBucket> {
        // 步数最多的先当种子：骨架从更全的那条里削，比从短的往上长稳
        val ordered = units.sortedWith(
            compareByDescending<Unit> { it.steps.size }.thenBy { it.t.createdAt }
        )
        val buckets = ArrayList<PathBucket>(4)
        for (u in ordered) {
            var best: PathBucket? = null
            var bestScore = PATH_SIMILARITY
            for (b in buckets) {
                val s = pathSimilarity(b.acc, u.steps)
                if (s >= bestScore) {
                    best = b
                    bestScore = s
                }
            }
            if (best != null) {
                best.units.add(u)
                // 骨架随成员变化：这一步让后面的变体按「已经收敛的路」来判定，
                // 而不是按种子那条的原始长度
                best.acc = mergeMany(best.units.map { it.steps })
            } else {
                buckets.add(PathBucket(mutableListOf(u)))
            }
        }

        // 第二轮：骨架之间再合一次（拆开的下标会变，所以用「重新扫一遍」的写法）
        var changed = true
        while (changed && buckets.size > 1) {
            changed = false
            loop@ for (i in buckets.indices) {
                for (j in i + 1 until buckets.size) {
                    if (pathSimilarity(buckets[i].acc, buckets[j].acc) >= PATH_SIMILARITY) {
                        buckets[i].units.addAll(buckets[j].units)
                        buckets[i].acc = mergeMany(buckets[i].units.map { it.steps })
                        buckets.removeAt(j)
                        changed = true
                        break@loop
                    }
                }
            }
        }
        return buckets
    }

    /**
     * 手动候选行（纯函数，单测直接钉）。
     *
     * **state 恒为 candidate**：不管攒了多少次、覆盖多高，都不许自动变 active。
     * 没有意图数据，转正必须由人点头 —— 这条约束写在这里，而不是写在 UI 里，
     * 因为 UI 会改，数据的默认值不该跟着改。
     */
    internal fun userRowOf(c: UserCluster, now: Long): ActionMemory = ActionMemory(
        memoryKey = c.key,
        intent = c.name.take(RawTrace.INTENT_MAX),
        intentKey = IntentKey.normalize(c.name).ifBlank { c.pkg },
        fingerprint = AccessibilityTraceAdapter.fingerprintOf(c.pkg),
        screenKeys = AccessibilityTraceAdapter.textKeys("", c.steps.mapNotNull { it.args["text"] }),
        pkg = c.pkg,
        origin = ActionMemory.ORIGIN_USER,
        // 名字等用户起；这里留空 —— 空的 label 在路由里过不了 isReplayable
        label = "",
        coverage = c.coverage,
        steps = ActionStep.encodeAll(c.steps),
        hits = 0,
        successCount = c.occurrences,
        state = ActionMemory.STATE_CANDIDATE,
        weight = MemoryWeight.scoreOf(0, c.occurrences, 0, c.lastAt, now),
        createdAt = c.lastAt,
        updatedAt = now
    )

    /** 「微信 · 搜索 → 订阅号」这种给人看的名（取骨架头三步，太长设置页放不下） */
    internal fun suggestedNameOf(app: String, steps: List<ActionStep>): String {
        val tail = steps.take(3).joinToString(" → ") { s ->
            s.args["text"] ?: s.args.values.firstOrNull() ?: s.tool
        }
        val head = app.trim().ifBlank { "这个应用" }
        // 名字同时进「设置页一行」和「memoryKey（截 40 字）」，
        // 太长会把两条不同的路截成同一个键，所以在这里就收口。
        return (if (tail.isBlank()) head else "$head · $tail").take(60)
    }

    /** Dice 系数：2|LCS| / (|a| + |b|)，参数化比较（数字归一在 [ActionStep.same] 里） */
    internal fun pathSimilarity(a: List<ActionStep>, b: List<ActionStep>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val l = lcsIndices(a, b).size
        return 2f * l / (a.size + b.size)
    }

    /** 分布在几天（按本地时区零点切） */
    private fun distinctDaysOf(times: List<Long>): Int {
        val set = HashSet<Long>(8)
        val cal = java.util.Calendar.getInstance()
        for (t in times) {
            cal.timeInMillis = t
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            set.add(cal.timeInMillis)
        }
        return set.size
    }

    /**
     * 把手动聚类落成候选（IO）。
     *
     * 三条硬约束：
     *  1. **新行只可能是 candidate**，state 不由样本数量决定 —— 数量只决定「够不够格出现在列表里」；
     *  2. 用户拒绝过的（rejected）不再长回来，也不更新样本计数；
     *  3. 不够格的样本**留在池子里**等下一夜凑数，只有躺过 [KEEP_TRACES_DAYS] 的才归档。
     */
    internal suspend fun learnUserPaths(
        dao: com.nexus.agent.data.db.ActionMemoryDao,
        traces: List<RawTrace>,
        now: Long
    ): UserOutcome {
        if (traces.isEmpty()) return UserOutcome()

        val learned = ArrayList<Long>()
        val dropped = ArrayList<Long>()
        var candidates = 0
        var restored = 0
        val clusters = clusterUserPaths(traces)
        Log.i(TAG, "[巩固·手动] ${traces.size} 条轨迹 → ${clusters.size} 段（无意图，只能按骨架聚类）")

        for (c in clusters) {
            if (c.occurrences < MIN_SUCCESS_TO_LEARN || c.days < MIN_PATH_DAYS) {
                Log.i(
                    TAG,
                    "[巩固·手动] 暂不学「${c.name}」：${c.occurrences} 次 / ${c.days} 天" +
                        "（要 ${MIN_SUCCESS_TO_LEARN} 次、$MIN_PATH_DAYS 天）→ 样本留着，下一夜再凑"
                )
                continue
            }
            if (c.coverage < MIN_COVERAGE) {
                dropped.addAll(c.ids)
                Log.i(
                    TAG,
                    "[巩固·手动] 丢弃「${c.name}」：${c.variants.size} 次的公共骨架只占 " +
                        "${(c.coverage * 100).toInt()}%，走的不是同一条路"
                )
                continue
            }
            if (MemoryGuard.rejectReason(c.steps).isNotEmpty()) {
                dropped.addAll(c.ids)
                Log.i(TAG, "[巩固·手动] 丢弃「${c.name}」：${MemoryGuard.rejectReason(c.steps)}")
                continue
            }

            val existing = dao.byKey(c.key)
            if (existing != null && existing.state == ActionMemory.STATE_REJECTED) {
                dropped.addAll(c.ids)
                Log.i(TAG, "[巩固·手动] 「${existing.intent}」用户说过不要，不再长候选")
                continue
            }

            runCatching {
                if (existing == null) {
                    val row = userRowOf(c, now)
                    val id = dao.upsert(row)
                    candidates++
                    Log.i(
                        TAG,
                        "[巩固·手动] 新候选 id=$id「${c.name}」${c.steps.size} 步" +
                            "（${c.occurrences} 次 / ${c.days} 天，覆盖 ${(c.coverage * 100).toInt()}%）" +
                            "→ 等他在设置页给这个名字"
                    )
                } else {
                    if (ActionStep.encodeAll(c.steps) != existing.steps) {
                        dao.updateSteps(existing.id, ActionStep.encodeAll(c.steps), c.coverage, now)
                    }
                    val success = existing.successCount + c.occurrences
                    val weight = MemoryWeight.scoreOf(
                        existing.hits, success, existing.failCount, existing.lastHitAt, now
                    )
                    dao.markLearnedBulk(existing.id, now, weight, c.occurrences)
                    val next = gatedState(
                        existing.origin, existing.label, nextStateOnSuccess(existing, success)
                    )
                    if (next != existing.state) {
                        dao.setState(existing.id, next, now)
                        if (existing.state == ActionMemory.STATE_SUSPECT &&
                            next == ActionMemory.STATE_ACTIVE
                        ) {
                            restored++
                            Log.i(TAG, "[巩固·手动] id=${existing.id} 又走通了 → suspect 恢复 active")
                        }
                    }
                    Log.i(
                        TAG,
                        "[巩固·手动] 累加 id=${existing.id}「${existing.intent}」" +
                            "+${c.occurrences} 次，累计 ${success} 次，state=${next}"
                    )
                }
                learned.addAll(c.ids)
            }.onFailure {
                Log.w(TAG, "[巩固·手动] 写「${c.name}」失败：${it.message}")
            }
        }

        // 不够格的样本留 7 天；过了保留期的归档，免得 pending 永远清不空
        val stale = traces
            .filter { it.id !in learned && it.id !in dropped }
            .filter { now - it.createdAt > KEEP_TRACES_DAYS * 86_400_000L }
            .map { it.id }

        return UserOutcome(
            candidates = candidates,
            restored = restored,
            archivedIds = learned + dropped + stale
        )
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
