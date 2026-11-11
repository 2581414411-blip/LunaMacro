package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.IntentTip
import com.nexus.agent.data.db.TaskEpisode
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 经验加工引擎：把流水账变成资产（0.9.13）。
 *
 * ## 成功轨迹 → 动作记忆
 *
 * 反复求 LCS 提骨架：只有**每次都会做**的步骤才留下来。
 * 偶尔才点一下的那步必须被剔掉 —— 留着它，回放十次里有一次会点错。
 * （同样的思路在 0.9.12 的 `HabitTrace` 里用过，两边是独立实现：
 *  那边处理的是「用户手动点击的 label」，这边处理的是「工具调用 + 参数」。）
 *
 * ## 失败轨迹 → 避坑经验
 *
 * 一个界面 + 一类任务只调**一次**模型。这事不能每失败一次就做：
 * 既费钱，也会把偶发抖动固化成「经验」。重复失败只累加 [IntentTip.hits]。
 *
 * 全部在 IO 协程里跑，任何异常只打日志 —— 记忆是加速层，
 * 它挂掉的后果必须是「和以前一样慢」，不能是「任务失败」。
 */
object MemoryConsolidator {

    private const val TAG = "MemDebug"

    /** 生成经验的超时 */
    private const val TIMEOUT_MS = 25_000L

    /** 便宜的优先，和错题本 / 长期记忆抽取保持一致 */
    private const val PREFERRED_PROVIDER = "deepseek"

    private const val TIP_PROMPT = """你是一个手机自动化 Agent 的复盘教练。

下面是一次失败的任务执行。请总结出一条「下次在这个界面上该怎么做」的经验。

要求：
- 一句话，祈使句，直接给做法，不超过 40 字。
- 必须具体到可执行：写清先做什么、避免什么、换哪个做法。
- 不要写「注意检查」「多加小心」这类空话。
- 不要解释、不要编号、不要引号、不要 markdown，只输出这一句话。

反例（不要这样写）：操作失败要注意重试。
正例（这样写）：在搜索页先点搜索框等键盘弹出，再点搜索按钮。"""

    /** 同一时刻只让一个模型复盘，避免连错三次并发打三枪 */
    private val reflecting = AtomicBoolean(false)

    // ==================== 成功：动作记忆 ====================

    /**
     * 处理一次成功轨迹。
     *
     * @return 落地后的记忆；被安全闸拒绝时返回 null（会打日志说明原因）
     */
    suspend fun onSuccess(
        ctx: Context,
        intent: String,
        intentKey: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>
    ): ActionMemory? {
        if (!screen.usable) {
            Log.i(TAG, "[加工] 跳过：起始界面读不到指纹，记忆无法定位")
            return null
        }
        val reject = MemoryGuard.rejectReason(steps)
        if (reject.isNotEmpty()) {
            Log.i(TAG, "[加工] 拒绝入库：$reject")
            return null
        }
        val dao = AppDatabase.get(ctx).actionMemoryDao()
        val key = memoryKeyOf(screen.fingerprint, intentKey)
        val now = System.currentTimeMillis()
        val encoded = ActionStep.encodeAll(steps)
        val existing = dao.byKey(key)

        if (existing == null) {
            val row = ActionMemory(
                memoryKey = key,
                intent = intent.take(TaskEpisode.INTENT_MAX),
                intentKey = intentKey,
                fingerprint = screen.fingerprint,
                screenKeys = screen.keysCsv,
                pkg = screen.pkg,
                steps = encoded,
                hits = 0,
                successCount = 1,
                state = ActionMemory.STATE_CANDIDATE,
                weight = MemoryWeight.scoreOf(0, 1, 0, 0L, now),
                createdAt = now,
                updatedAt = now
            )
            val id = dao.upsert(row)
            Log.i(TAG, "[加工] 新建候选 id=$id 意图=\"$intent\" 步骤=${steps.size}")
            return row.copy(id = id)
        }

        // 已有：先合并骨架，再累加次数
        val oldSteps = ActionStep.decodeAll(existing.steps)
        val merged = mergeSteps(oldSteps, steps)
        val success = existing.successCount + 1
        val newState = nextStateOnSuccess(existing, success)
        val weight = MemoryWeight.scoreOf(existing.hits, success, existing.failCount, existing.lastHitAt, now)
        dao.updateSteps(existing.id, ActionStep.encodeAll(merged), now)
        dao.markLearned(existing.id, now, weight)
        if (newState != existing.state) {
            dao.setState(existing.id, newState, now)
            Log.i(TAG, "[进化] 意图=\"$intent\" ${existing.state} → $newState（成功 $success 次）")
        } else {
            Log.i(TAG, "[加工] 累加：意图=\"$intent\" 成功 $success 次，骨架 ${oldSteps.size}→${merged.size} 步")
        }
        return existing.copy(
            steps = ActionStep.encodeAll(merged),
            successCount = success,
            state = newState,
            weight = weight,
            updatedAt = now
        )
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

    // ==================== 失败：避坑经验 ====================

    /**
     * 处理一次失败轨迹。
     *
     * 只在「这个界面 + 这类任务还没有经验」时才调模型，其余情况只累加次数。
     */
    suspend fun onFailure(
        ctx: Context,
        intent: String,
        intentKey: String,
        screen: MemoryScreen.Snapshot,
        steps: List<ActionStep>,
        failStep: Int,
        detail: String
    ) {
        if (!screen.usable) {
            Log.i(TAG, "[经验] 跳过：界面指纹为空，经验无处挂靠")
            return
        }
        val dao = AppDatabase.get(ctx).intentTipDao()
        val key = memoryKeyOf(screen.fingerprint, intentKey)
        val existing = dao.byKey(key)
        if (existing != null) {
            // 已经有经验了：只累加，不重复烧 token
            dao.upsert(existing.copy(hits = existing.hits + 1, updatedAt = System.currentTimeMillis()))
            Log.i(TAG, "[经验] 已知坑（第 ${existing.hits + 1} 次）：${existing.tip}")
            return
        }
        if (!reflecting.compareAndSet(false, true)) {
            Log.i(TAG, "[经验] 上一个复盘还没跑完，本次跳过")
            return
        }
        try {
            val provider = pickProvider(ctx)
            val (k, _, model) = LlmClientFactory.getConfig(ctx, provider)
            if (k.isBlank() && provider != "ollama") {
                Log.i(TAG, "[经验] 未配置 $provider 的 Key，跳过复盘")
                return
            }
            val client = LlmClientFactory.create(provider, ctx)
            val userBlock = buildString {
                append("任务：").append(intent.take(80)).append('\n')
                append("界面包名：").append(screen.pkg).append('\n')
                append("界面上能点的东西：").append(screen.keysCsv.replace('\u0001', '、').take(200)).append('\n')
                append("执行到第 ").append(failStep.coerceAtLeast(1)).append(" 步失败\n")
                append("已经做了：\n")
                steps.take(8).forEachIndexed { i, s ->
                    append("  ${i + 1}. ").append(s.tool).append(' ').append(s.args.values.joinToString("/")).append('\n')
                }
                if (detail.isNotBlank()) append("失败原因：").append(detail.take(120)).append('\n')
            }
            val raw = withTimeoutOrNull(TIMEOUT_MS) {
                client.chat(
                    listOf(
                        LlmClient.Message("system", TIP_PROMPT),
                        LlmClient.Message("user", userBlock)
                    )
                )
            }
            val tip = sanitizeTip(raw)
            if (tip.isEmpty()) {
                Log.i(TAG, "[经验] 模型没产出有效经验（provider=$provider model=$model）")
                return
            }
            val now = System.currentTimeMillis()
            dao.upsert(
                IntentTip(
                    tipKey = key,
                    fingerprint = screen.fingerprint,
                    intentKey = intentKey,
                    pkg = screen.pkg,
                    tip = tip,
                    hits = 1,
                    createdAt = now,
                    updatedAt = now
                )
            )
            dao.trim(IntentTip.MAX_ROWS)
            Log.i(TAG, "[经验] 新避坑：$tip")
            MemoryRouter.reloadTips(ctx)
        } catch (e: Throwable) {
            Log.w(TAG, "[经验] 复盘失败：${e.javaClass.simpleName} ${e.message}")
        } finally {
            reflecting.set(false)
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

    /** 按参数化签名求 LCS，返回映射到 `new` 上的步骤序列 */
    private fun lcsIndices(old: List<ActionStep>, new: List<ActionStep>): List<ActionStep> {
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
 * 达尔文评分（0.9.13）。
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
