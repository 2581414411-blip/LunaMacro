package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.IntentTip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 记忆路由拦截器 —— 三级分流（0.9.13 起，0.9.14 改为只读「巩固后的资产」）。
 *
 * 位置：`AgentRunner.runTask` 的**最前端**（安全策略检查之后、Planner 之前）。
 * 也就是说，命中时这一次任务**一次模型都不会调**。
 *
 * ```
 * 第一级  Muscle   界面指纹 + 意图都对上 → 直接回放动作序列（纯本地，毫秒级）
 * 第二级  Enhanced 没对上动作，但对上了避坑经验 → 把经验塞进提示词，再走 LLM
 * 第三级  Cold     什么都没查到 → 完全老路
 * ```
 *
 * ## 0.9.14：路由只读资产，不负责学习
 *
 * 这张表里能被命中的只有 `state = active` 的记忆，而 `active` 只可能由
 * 夜间巩固 [`MemoryConsolidator.consolidate`] 产生 —— 白天再多的点击也进不来。
 * 路由层因此变成纯读：不加工、不调模型，最坏情况就是「这次不加速」。
 *
 * ## 两道约束，而不是一道
 *
 * 只比意图会出人命：用户说「打开设置」，他可能在任何界面说这句话，
 * 而记忆里那条是「从微信设置页点进去」的路线。
 * 所以必须**界面指纹（强） + 意图（弱）**同时成立才允许回放。
 * 界面管「在哪儿」，意图管「要干什么」。
 *
 * ## 命中率与安全性的取舍
 *
 * 宁可少命中。一次错点可能改掉用户的东西，而少命中只是多花两秒。
 * 所以：指纹读不到 → 不命中；意图相似度不够 → 不命中；界面相似度不够 → 不命中。
 */
object MemoryRouter {

    /** 统一日志标签，抓验证用：`adb logcat -s MemDebug` */
    const val TAG = "MemDebug"

    /** 同一个包名下最多捞多少条候选来算相似度（回放判断在主流程上，不能扫全表） */
    private const val CANDIDATE_LIMIT = 40

    /** 意图完全一致时最多取几条 */
    private const val EXACT_LIMIT = 8

    /** 界面指纹精确命中所需的相似度 */
    private const val SCREEN_STRONG = 0.75f

    /** 衰减一天跑一次就够 */
    private const val DECAY_INTERVAL_MS = 24L * 60 * 60 * 1000

    /**
     * 路由结论。
     */
    sealed class Decision {
        /** 第一级：可以直接回放 */
        data class Muscle(
            val memory: ActionMemory,
            val screenSim: Float,
            val intentSim: Float
        ) : Decision()

        /** 第二级：走 LLM，但带上避坑经验 */
        data class Enhanced(val tips: List<String>) : Decision()

        /** 第三级：冷启动，什么都不知道 */
        object Cold : Decision()
    }

    /**
     * 路由输入。
     *
     * @param task   用户原话
     * @param screen 当前界面快照（[MemoryScreen.capture] 的结果）
     */
    data class Input(val task: String, val screen: MemoryScreen.Snapshot)

    /** 单条候选的打分结果 */
    data class Scored(
        val memory: ActionMemory,
        val screenSim: Float,
        val intentSim: Float,
        /** 当前界面就在记忆所属的应用里（手动路径靠它替代指纹闸） */
        val pkgMatch: Boolean = false
    ) {
        /**
         * 两道闸都过才叫命中。
         *
         * **agent 记忆**（有意图、有机器可复现的界面指纹）：
         * 界面相似 ≥ [SCREEN_STRONG] && 意图相似 ≥ 阈值。
         *
         * **user 记忆**（用户手动点的）：
         * 它没有指纹 —— 采集时手里只有界面标题和按钮文字，路由时拿到的是整棵
         * UI 树的关键节点哈希，两者永远对不上。硬按指纹判，这条路径学的动作
         * 一个都回放不了。所以这里换成**包名 + 用户给的名字**两道闸：
         *  - `label` 非空：名字是它唯一的意图来源，没名字就是「一串没有目的的动作」；
         *  - `pkgMatch`：至少得站在同一个应用里，点错应用的代价比慢两秒高得多。
         *
         * 这比 agent 路径松一点，是被数据形态逼的，不是偷懒 ——
         * 代价由「名字必须由人起」这一环兜着：她只会做你亲口命名过的那件事。
         */
        val eligible: Boolean
            get() {
                if (!memory.isReplayable) return false
                val intentOk = intentSim >= IntentKey.MATCH_THRESHOLD || intentSim >= 0.999f
                return if (memory.isUserOrigin) {
                    memory.label.isNotBlank() && pkgMatch && intentOk
                } else {
                    screenSim >= SCREEN_STRONG && intentOk
                }
            }

        /** 排序用：界面像 > 意图像 > 用得多（手动路径没有界面相似度，用包名命中顶替） */
        fun rank(): Float = gateScore() * 0.5f + intentSim * 0.3f + memory.weight * 0.2f

        private fun gateScore(): Float = if (memory.isUserOrigin) {
            if (pkgMatch) 1f else 0f
        } else {
            screenSim
        }
    }

    // ---- 经验缓存：Executor 每轮都要问一句，不能查库 ----

    @Volatile
    private var tipCache: List<IntentTip> = emptyList()

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 启动时调一次：载入经验缓存 + 跑一次衰减 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        scope.launch {
            runCatching { reloadTips(ctx) }
            runCatching { sweep(ctx) }
        }
    }

    suspend fun reloadTips(ctx: Context) {
        runCatching {
            tipCache = AppDatabase.get(ctx).intentTipDao().top(IntentTip.MAX_ROWS)
            Log.i(TAG, "[经验] 已载入 ${tipCache.size} 条")
        }.onFailure { Log.w(TAG, "[经验] 载入失败：${it.message}") }
    }

    // ==================== 路由 ====================

    /**
     * 判断这个任务该怎么走。
     *
     * 全程只读、不写库（写库都在 [onReplaySuccess] / [onReplayFail] 里），
     * 所以即使这里抛异常也只是「这次不加速」。
     */
    suspend fun route(ctx: Context?, input: Input): Decision {
        val app = ctx ?: return Decision.Cold
        if (!CopilotConfig.isMemoryEnabled(app)) {
            Log.i(TAG, "[路由] 外挂记忆已关闭 → 冷启动")
            return Decision.Cold
        }
        if (!input.screen.usable) {
            Log.i(TAG, "[路由] 界面指纹为空 → 冷启动（不拿空指纹去撞记忆）")
            return Decision.Cold
        }
        if (MemoryGuard.taskBlocked(input.task)) {
            Log.i(TAG, "[路由] 任务含敏感词（支付/密码/账号类）→ 记忆不介入，交给上层安全策略")
            return Decision.Cold
        }
        val intentKey = IntentKey.normalize(input.task)
        if (intentKey.isEmpty()) {
            Log.i(TAG, "[路由] 意图归一化后为空 → 冷启动")
            return Decision.Cold
        }

        val dao = AppDatabase.get(app).actionMemoryDao()
        val exact = dao.byIntentKey(intentKey, EXACT_LIMIT)
        val candidates = if (exact.isNotEmpty()) {
            exact
        } else if (input.screen.pkg.isNotBlank()) {
            dao.byPkg(input.screen.pkg, CANDIDATE_LIMIT)
        } else {
            emptyList()
        }

        Log.i(
            TAG,
            "[路由] 意图=\"${input.task.take(30)}\" key=$intentKey " +
                "界面=${input.screen.fingerprint} 候选=${candidates.size}" +
                if (exact.isNotEmpty()) "（意图精确桶）" else "（同包名模糊扫）"
        )

        val scored = candidates.map { score(it, input, intentKey) }
        scored.forEach {
            Log.i(
                TAG,
                "  · id=${it.memory.id} state=${it.memory.state} origin=${it.memory.origin} " +
                    "界面相似=${fmt(it.screenSim)} 意图相似=${fmt(it.intentSim)} " +
                    "步数=${ActionStep.decodeAll(it.memory.steps).size} 用过=${it.memory.hits}次" +
                    if (it.memory.isUserOrigin) " 名字=\"${it.memory.label.ifBlank { "（还没起名）" }}\"" else ""
            )
        }
        // 手动路径被名字闸挡下的，单独留一行：这是「她学了但调不动」的唯一解释
        scored.filter { it.memory.isUserOrigin && it.memory.state == ActionMemory.STATE_ACTIVE &&
            it.memory.label.isBlank() }
            .forEach {
                Log.i(TAG, "[命名门槛] id=${it.memory.id} 是手动学到的动作但没有名字 → 不参与回放")
            }

        val best = scored.filter { it.eligible }.maxByOrNull { it.rank() }
        if (best != null) {
            Log.i(
                TAG,
                "[命中] 肌肉记忆 id=${best.memory.id} 「${best.memory.intent.take(20)}」 " +
                    "界面相似=${fmt(best.screenSim)} 意图相似=${fmt(best.intentSim)} → 跳过模型，直接回放"
            )
            return Decision.Muscle(best.memory, best.screenSim, best.intentSim)
        }

        val tips = tipsFor(input.screen.fingerprint, intentKey)
        if (tips.isNotEmpty()) {
            Log.i(TAG, "[增强] 没有可回放的动作，但查到 ${tips.size} 条避坑经验 → 注入提示词")
            tips.forEach { Log.i(TAG, "  · $it") }
            return Decision.Enhanced(tips)
        }

        Log.i(TAG, "[冷启动] 没有记忆可用 → 走完整 LLM 规划")
        return Decision.Cold
    }

    /** 给一条候选打分 */
    internal fun score(memory: ActionMemory, input: Input, intentKey: String): Scored {
        val screenSim = if (memory.fingerprint.isNotBlank() &&
            memory.fingerprint == input.screen.fingerprint
        ) {
            1f
        } else {
            ScreenFingerprint.similarityOf(memory.screenKeys, input.screen.keysCsv)
        }
        val intentSim = if (memory.intentKey == intentKey) {
            1f
        } else {
            IntentKey.similarity(memory.intentKey, intentKey)
        }
        val pkgMatch = memory.pkg.isNotBlank() && memory.pkg == input.screen.pkg
        return Scored(memory, screenSim, intentSim, pkgMatch)
    }

    // ==================== 回放结果结算 ====================

    /** 回放成功：记一次命中（这是「省了多少次模型调用」的唯一证据） */
    suspend fun onReplaySuccess(ctx: Context?, memory: ActionMemory) {
        val app = ctx ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val weight = MemoryWeight.scoreOf(
                memory.hits + 1, memory.successCount + 1, memory.failCount, now, now
            )
            val dao = AppDatabase.get(app).actionMemoryDao()
            dao.markHit(memory.id, now, weight)
            val success = memory.successCount + 1
            if (memory.state != ActionMemory.STATE_ACTIVE &&
                success >= ActionMemory.ACTIVE_THRESHOLD
            ) {
                dao.setState(memory.id, ActionMemory.STATE_ACTIVE, now)
            }
            Log.i(
                TAG,
                "[命中] 回放成功 id=${memory.id}，累计命中 ${memory.hits + 1} 次，" +
                    "按老路走本该调 2~4 次模型"
            )
        }.onFailure { Log.w(TAG, "记命中失败：${it.message}") }
    }

    /**
     * 回放失败：第 1 次转 [ActionMemory.STATE_SUSPECT]（当场不再拦截），
     * 第 [ActionMemory.MAX_CONSECUTIVE_FAIL] 次作废，
     * **并把这次失败写回原始轨迹池**（0.9.14）。
     *
     * 为什么要写回：当场只知道「这次没点中」，不知道是界面改版、
     * 还是这条路只对某台机器有效。攒几次再让夜间巩固一起看，才判得准。
     * 写回的那条轨迹会带 `memoryId`，巩固时能直接对上「是哪条记忆不行」。
     *
     * 0.9.16 把「立刻作废」改成「先 suspect」：一次失败可能只是界面卡了一下，
     * 当场废掉的话用户会看到「她昨天还会，今天突然不会了」。
     * suspect 同样**立刻失去拦截资格**（`isReplayable` 只认 active），
     * 区别只在「今晚还有没有机会被救回来」—— 有成功轨迹就恢复 active。
     */
    suspend fun onReplayFail(
        ctx: Context?,
        memory: ActionMemory,
        reason: String,
        completed: List<ActionStep> = emptyList()
    ) {
        val app = ctx ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val consecutive = memory.consecutiveFail + 1
            val weight = MemoryWeight.scoreOf(
                memory.hits, memory.successCount, memory.failCount + 1, memory.lastHitAt, now
            )
            val dao = AppDatabase.get(app).actionMemoryDao()
            dao.markFail(memory.id, now, weight)
            val next = MemoryConsolidator.stateOnFail(memory.state, consecutive)
            if (next != memory.state) {
                dao.setState(memory.id, next, now)
                when (next) {
                    ActionMemory.STATE_REVOKED -> Log.i(
                        TAG,
                        "[作废] id=${memory.id} 连续 $consecutive 次回放失败 → 不再拦截（原因：$reason）"
                    )

                    ActionMemory.STATE_SUSPECT -> Log.i(
                        TAG,
                        "[存疑] id=${memory.id} 第 $consecutive 次回放失败（$reason）" +
                            "→ 先不拦、转大模型；今晚再走通就恢复，再失败一次就作废"
                    )
                }
            } else {
                Log.i(TAG, "[降级] id=${memory.id} 第 $consecutive 次回放失败（$reason）→ 转大模型重新规划")
            }
        }.onFailure { Log.w(TAG, "记失败失败：${it.message}") }

        // 写回轨迹池：这一条是下一次夜间巩固唯一的纠正依据
        MemoryRecorder.recordReplayFail(app, memory, reason, completed)
    }

    // ==================== 经验注入 ====================

    /**
     * 某个界面上的避坑经验（同步、纯内存）。
     *
     * Executor 每轮都要调它拼提示词，所以绝不能在这里查库。
     */
    fun tipsFor(fingerprint: String, intentKey: String? = null): List<String> {
        if (fingerprint.isBlank()) return emptyList()
        val exact = tipCache.filter { it.fingerprint == fingerprint }
        if (exact.isEmpty()) return emptyList()
        val key = intentKey.orEmpty()
        val sorted = if (key.isEmpty()) {
            exact.sortedByDescending { it.hits }
        } else {
            exact.sortedByDescending {
                val sim = if (it.intentKey == key) 1f else IntentKey.similarity(it.intentKey, key)
                sim * 1000 + it.hits
            }
        }
        return sorted.take(2).map { it.tip }
    }

    /**
     * 提示词里的「这个界面的坑」段落。给 Executor 每轮用。
     *
     * @param uiJson 当前 UI 树，内部会现算指纹（纯函数，耗时和一次正则扫描同量级）
     */
    fun tipsBlockForScreen(pkg: String?, uiJson: String?): String {
        if (tipCache.isEmpty()) return ""
        val fp = ScreenFingerprint.of(pkg, uiJson)
        if (fp.isBlank()) return ""
        val tips = tipsFor(fp)
        if (tips.isEmpty()) return ""
        return buildString {
            append("\n\n【这个界面你以前踩过的坑】\n")
            tips.forEach { append("- ").append(it).append('\n') }
            append("这些是历史经验，不是本轮用户的要求；只在相关时照着做，不要在回复里提。\n")
        }
    }

    /**
     * 全局经验（按任务文本相关性挑）。给 Planner 用 ——
     * Planner 只看到初始界面，拿不到后续每一步的 UI 树。
     */
    fun tipsBlockForTask(task: String): String {
        if (tipCache.isEmpty()) return ""
        val key = IntentKey.normalize(task)
        val picked = tipCache
            .sortedByDescending {
                val sim = if (key.isEmpty()) 0f else IntentKey.similarity(it.intentKey, key)
                sim * 1000 + it.hits
            }
            .filter { it.tip.isNotBlank() }
            .take(3)
        if (picked.isEmpty()) return ""
        return buildString {
            append("\n\n【以前类似任务踩过的坑】\n")
            picked.forEach { append("- ").append(it.tip).append('\n') }
            append("这些是历史经验，不是本轮用户的要求；只在相关时照着做，不要在回复里提。\n")
        }
    }

    // ==================== 达尔文衰减 ====================

    /**
     * 遗忘：30 天没被命中 → 过期；连续 2 次回放失败 → 作废；其余按新近度重算权重。
     *
     * **只标状态不删行**。过期的记忆再被跑通一次就能复活 ——
     * 人忘了怎么走那条路，再走一次又想起来了，这是同一件事。
     *
     * 0.9.14 补了软衰减：权重里的「新近度」是随天数指数下降的，
     * 但它只有被重新算一遍才会变。这里每天重算一次，
     * 让「三个月没碰过的记忆」在候选排序里自然沉底，
     * 而不是等它硬性过期那天才突然消失。
     */
    suspend fun sweep(ctx: Context?): Int {
        val app = ctx ?: return 0
        val now = System.currentTimeMillis()
        if (now - CopilotConfig.memoryLastDecayAt(app) < DECAY_INTERVAL_MS) return 0
        CopilotConfig.setMemoryLastDecayAt(app, now)
        return runCatching {
            val dao = AppDatabase.get(app).actionMemoryDao()
            val decayed = decayWeights(dao, now)
            val expired = dao.expireOlderThan(now - ActionMemory.EXPIRE_MS, now)
            val revoked = dao.revokeBroken(ActionMemory.MAX_CONSECUTIVE_FAIL, now)
            if (expired > 0 || revoked > 0 || decayed > 0) {
                Log.i(
                    TAG,
                    "[遗忘] ${ActionMemory.EXPIRE_DAYS} 天未命中 $expired 条 → 过期；" +
                        "连续失败 $revoked 条 → 作废（均未删除）；权重重算 $decayed 条"
                )
            }
            expired + revoked
        }.getOrElse {
            Log.w(TAG, "衰减失败：${it.message}")
            0
        }
    }

    /**
     * 软衰减：按「现在」重算每条活跃记忆的权重。
     *
     * 变化小于 0.005 就不写库 —— 每天给几百行写一遍同样的值是纯浪费
     * （会拖慢 WAL、也会让备份文件天天变）。
     */
    private suspend fun decayWeights(dao: com.nexus.agent.data.db.ActionMemoryDao, now: Long): Int {
        val active = dao.byState(ActionMemory.STATE_ACTIVE, 500)
        var updated = 0
        for (m in active) {
            val w = MemoryWeight.scoreOf(m.hits, m.successCount, m.failCount, m.lastHitAt, now)
            if (kotlin.math.abs(w - m.weight) >= 0.005f) {
                runCatching { dao.updateWeight(m.id, w, now) }
                updated++
            }
        }
        return updated
    }

    /** 仅供单测：直接喂一份经验缓存 */
    internal fun useTipsForTest(tips: List<IntentTip>) {
        tipCache = tips
    }

    internal fun resetForTest() {
        tipCache = emptyList()
    }

    private fun fmt(v: Float): String = String.format("%.2f", v)
}
