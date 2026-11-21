package com.nexus.agent.copilot

import android.content.Context
import android.util.Log
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.persona.LocalDigitalBeing
import com.nexus.agent.persona.MemoryFunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import java.util.Locale

/**
 * 认知调度器 —— 所有请求的**唯一决策点**（0.9.29）。
 *
 * ## 它接替了什么
 *
 * 以前「谁来答、带多少上下文、等多久」这三件事散在三个地方：
 *  - 谁来答：`CopilotEngine.runDualBrain` 判完 Chat 就交给 `PersonaEngine`，没有「本地先答」这一档；
 *  - 带多少：`PersonaEngine.askRoleLlm` 里一句 `takeLast(12)` 硬裁；
 *  - 等多久：每个 Provider 各自 45 秒，后台任务分段超时，整体没有上限。
 *
 * 现在收敛成三步接力，任何一步能答就不往上走：
 *
 * ```
 * ① 规则/FLM 本地直达   时间、静默、已知事实（0 次模型调用）
 *         │ 没命中
 *         ▼
 * ② NP 本地记忆命中     命中一条强相关事实 → 直接复述（0 次模型调用）
 *         │ 没命中
 *         ▼
 * ③ 云端 LLM            用 MemoryFunnel 组装好的上下文
 * ```
 *
 * ## 三条边界（别越）
 *
 * 1. **本地只答确定性内容**（时间 / 静默 / 已知事实）。寒暄一律上云 ——
 *    本地模板回一句「嗯嗯在的」和云端那句「你怎么这么晚还没睡」是两个人，
 *    省下的钱不够补人格断层。
 * 2. **本地应答不记账**。它不走 `LocalDigitalBeing.recordTurn`，所以不涨好感度、
 *    不进滑窗、不抽事实。理由同兜底台词：本地拼的话拿去喂情绪引擎是假数据。
 * 3. **超时只掐后台**。聊天等 45 秒用户就炸了；60 分钟是给定时任务/多步 Agent
 *    这种「排队 + 多次调用」的总闸，不是给对话的。
 */
object CognitiveScheduler {

    private const val TAG = "Cognitive"

    /** 请求来源。`background=true` 的才吃 60 分钟总闸 */
    enum class Source(val background: Boolean) {
        CHAT(false),
        VOICE(false),
        ISLAND(false),
        ROOM(false),
        SCHEDULE(true),
        PLUGIN(true),
        HTTP(true),
        TV(true)
    }

    /** 后台排队/多步任务的总超时：60 分钟。到点抛 [DeadlineExceeded]，绝不无限挂起。 */
    const val BACKGROUND_LIMIT_MS = 60L * 60L * 1000L

    /** 单轮对话的上限，与 `PersonaEngine.REPLY_TIMEOUT_MS` 对齐 */
    const val CHAT_LIMIT_MS = 45L * 1000L

    /** 本地记忆最多看多少条（只看高权重的，别为了答一句话扫全表） */
    private const val FACT_SCAN = 30

    /** 本地复述的记忆文本上限：太长的不适合直接说出口 */
    private const val FACT_MAX_CHARS = 60

    sealed class Decision {
        /** 本地就能答，一次模型都不用调 */
        data class Local(val reply: String, val pattern: String) : Decision()

        /** 要上云。`visionFirst` = 这一轮优先走 LMM，失败降级纯文本 */
        data class Cloud(val visionFirst: Boolean) : Decision()

        /** 直接拒（空输入等），不产生任何调用 */
        data class Blocked(val reason: String) : Decision()
    }

    /** 超时异常：带上阶段名，便于日志和数据恢复时定位停在哪一步 */
    class DeadlineExceeded(val stage: String, val limitMs: Long) :
        Exception("阶段 $stage 超过 ${limitMs / 60_000} 分钟上限")

    // ------------------------------------------------------------------ 决策

    /**
     * 判这一轮由谁答。
     *
     * @param hasImage 用户这一轮带图 —— 带图不走本地（图必须先被看见），
     *                 只标记「优先 LMM」，降级纯文本由 `VisionRouter` 负责。
     */
    suspend fun decide(
        ctx: Context?,
        text: String,
        source: Source,
        hasImage: Boolean = false
    ): Decision {
        val t = text.trim()
        if (t.isEmpty() && !hasImage) return Decision.Blocked("空输入")
        if (hasImage) {
            Log.i(TAG, "[调度] 带图 → 云端（优先 LMM）")
            return Decision.Cloud(visionFirst = true)
        }

        // ① 规则层
        localReplyOf(t, System.currentTimeMillis())?.let { (reply, pattern) ->
            Log.i(TAG, "[调度] 本地直达（$pattern）：${t.take(12)} → ${reply.take(16)}")
            return Decision.Local(reply, pattern)
        }

        // ② NP 层：后台来源不查（群里/定时任务里复述用户事实，容易说错人）
        if (!source.background) {
            npReply(ctx, t)?.let { fact ->
                Log.i(TAG, "[调度] NP 记忆命中：${t.take(12)} → ${fact.take(16)}")
                return Decision.Local(fact, "np")
            }
        }

        Log.i(TAG, "[调度] 上云（source=$source, len=${t.length}）")
        return Decision.Cloud(visionFirst = false)
    }

    /**
     * 纯规则层：不需要 IO、不需要 Context，所以能在纯 JVM 单测里逐条钉死。
     *
     * @return (回复, 命中的模式名)；null = 交给下一层
     */
    internal fun localReplyOf(
        text: String,
        now: Long,
        facts: List<String> = emptyList()
    ): Pair<String, String>? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 40) return null

        if (HUSH_RE.matches(t)) return "好。" to "hush"
        if (TIME_RE.containsMatchIn(t)) return nowText(now) to "time"

        if (NAME_RE.containsMatchIn(t)) {
            facts.firstOrNull { it.contains("叫") && it.length <= 20 }?.let {
                return it to "np-name"
            }
        }
        if (RECALL_RE.containsMatchIn(t)) {
            matchFact(t, facts)?.let { return it to "np-recall" }
        }
        return null
    }

    /** 查库拿「可能跟这句话相关」的事实，交给 [localReplyOf] 做判断 */
    private suspend fun npReply(ctx: Context?, query: String): String? {
        val c = ctx ?: return null
        if (!NAME_RE.containsMatchIn(query) && !RECALL_RE.containsMatchIn(query)) return null
        val facts = runCatching {
            AppDatabase.get(c).memoryDao().top(FACT_SCAN).map { it.fact }
        }.getOrElse {
            Log.w(TAG, "读长期记忆失败：${it.javaClass.simpleName}")
            return null
        }
        if (facts.isEmpty()) return null
        return localReplyOf(query, System.currentTimeMillis(), facts)?.first
    }

    /**
     * 字面 2-gram 匹配（不用向量模型：那又是一次调用）。
     *
     * 只用于「他还记得我上周说要体检吗」这种**带具体词**的回忆，
     * 要求至少命中 2 个 2-gram，宁可漏答也不能张冠李戴。
     */
    internal fun matchFact(query: String, facts: List<String>): String? {
        val qGrams = grams(query)
        if (qGrams.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        facts.forEach { fact ->
            if (fact.isBlank() || fact.length > FACT_MAX_CHARS) return@forEach
            if (fact.any { it in "叫" } && fact.length <= 20) return@forEach
            val score = grams(fact).count { it in qGrams }
            if (score > bestScore) {
                bestScore = score
                best = fact
            }
        }
        return if (bestScore >= 2) best else null
    }

    internal fun grams(text: String): Set<String> {
        val clean = text.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return emptySet()
        val out = HashSet<String>(clean.length)
        for (i in 0 until clean.length - 1) out.add(clean.substring(i, i + 2))
        return out
    }

    internal fun nowText(now: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val week = WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return String.format(Locale.CHINA, "现在 %d:%02d，%s", hh, mm, week)
    }

    private val WEEK = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

    private val TIME_RE = Regex("(几点了|现在几点|什么时间|现在时间|今天几号|今天星期几|星期几了)")
    private val HUSH_RE = Regex("^\\s*(别说了|闭嘴|安静(点|一下)?|停一下|不用回了?|先别说话)\\s*[。！!]?\\s*$")
    private val NAME_RE = Regex("(我叫什么|我叫啥|我的名字|知道我(叫|是)谁吗)")
    private val RECALL_RE = Regex("(还记得|记不记得|我之前(说|提)过?|我上次(说|提)过?)")

    // ------------------------------------------------------------------ 上下文

    /** 把滑窗轮次喂给漏斗，拿到「这一轮该带多少历史」 */
    fun contextFor(
        turns: List<LocalDigitalBeing.Turn>,
        now: Long = System.currentTimeMillis(),
        cfg: MemoryFunnel.Config = MemoryFunnel.Config()
    ): MemoryFunnel.Assembled {
        val items = turns.map { MemoryFunnel.Item(it.user, it.assistant, it.at) }
        val assembled = MemoryFunnel.assemble(items, now, cfg)
        Log.i(TAG, "[漏斗] ${MemoryFunnel.debugLine(assembled)}")
        return assembled
    }

    // ------------------------------------------------------------------ 超时

    /**
     * 给一段可能很长的工作套上总闸。
     *
     * 超时**不回滚**：已经写进库的记忆、已经执行的工具调用都留着（回滚更危险）。
     * 调用方 catch 到 [DeadlineExceeded] 后应该用 [timeoutLine] 说一句人话，
     * 而不是把异常抛给用户。
     */
    suspend fun <T> runLimited(
        limitMs: Long,
        stage: String,
        block: suspend CoroutineScope.() -> T
    ): T = try {
        withTimeout(limitMs, block)
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "[熔断] 阶段 $stage 超过 ${limitMs / 60_000} 分钟")
        throw DeadlineExceeded(stage, limitMs)
    }

    /** 后台任务的来源才吃 60 分钟总闸；对话维持秒级 */
    fun limitOf(source: Source): Long =
        if (source.background) BACKGROUND_LIMIT_MS else CHAT_LIMIT_MS

    fun timeoutLine(limitMs: Long): String {
        val minutes = (limitMs / 60_000).coerceAtLeast(1)
        return "这个任务排队超过 $minutes 分钟了，我先停在这儿。已经做的都留着，回头可以接着来。"
    }
}
