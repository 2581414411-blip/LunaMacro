package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.InnerThought
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 心智引擎 —— 她一个人的时候也在想事情（0.9.6）。
 *
 * ## 为什么需要它
 *
 * 0.9.5 之前她的全部「思想」都发生在被叫到的那一刻：你说话 → 拼提示词 → 回一句。
 * 所以无论记忆做得多厚，她的反应永远是**对你这句话的反应**，像一个响应很快的客服。
 *
 * 真人不这样。真人会在你没说话的时候想起你：「他说今天要体检，不知道结果怎么样」。
 * 等你回来说「在吗」，她接的那句就带上了这段时间的沉淀 —— 这才是「活人感」的来源。
 *
 * ## 它做什么
 *
 * 每 [CHECK_INTERVAL_HOURS] 小时醒一次，判断「现在适合一个人想事情吗」，适合就：
 *  1. 读她记得的东西（长期事实、记挂着的话题、周记摘要、当前心情）；
 *  2. 让一个便宜的模型写**一句**不超过 [InnerThought.CONTENT_MAX] 字的独白；
 *  3. 存进 `inner_thought` 表，并缓存给 [PersonaEngine]；
 *  4. 等他下次开口，这段独白会被拼进提示词，然后标记「用过了」。
 *
 * ## 为什么不是前台服务
 *
 * 想事情不需要精确时刻，晚两小时也没关系，但**不能因为一次失败就再也不跑**。
 * 这正是 WorkManager 的语义。而且它和心跳一样，必须处理「任务被系统清掉」的情况
 * （见 [schedule]）—— 0.9.4 那个坑不能踩第二次。
 */
object InnerMonologueEngine {

    private const val TAG = "InnerThought"

    /** 周期任务唯一名 */
    const val WORK_NAME = "luna_inner_thought"
    private const val TAG_NAME = "luna_inner_thought"

    /** 想事情的周期：两小时。比心跳慢得多 —— 想事情本来就该慢 */
    const val CHECK_INTERVAL_HOURS = 2L

    /** 距上次对话至少这么久，才轮到「一个人的时间」；刚聊完不需要脑补 */
    const val MIN_IDLE_MINUTES = 20L

    /** 两次想事情的最小间隔（略小于周期，防止周期漂移把一次整没了） */
    const val MIN_GAP_MINUTES = 90L

    /** 夜里不想事情：凌晨三点的独白既没人看，也容易写出奇怪的东西 */
    const val NIGHT_START_HOUR = 23
    const val NIGHT_END_HOUR = 7

    /** 排程规格指纹，周期/策略变了就重排一次 */
    const val SCHEDULE_SIGNATURE = "inner-2h-0.9.6"

    /** 生成一次独白的超时：便宜模型也就几秒，超过 25 秒说明网络有问题 */
    private const val TIMEOUT_MS = 25_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 判定结果（纯函数返回，单测覆盖） */
    enum class Verdict {
        /** 总开关关着 */
        DISABLED,

        /** 夜里，她该睡了 */
        NIGHT,

        /** 从没聊过，没有任何可想的素材 */
        NEVER_CHATTED,

        /** 刚聊完，不需要脑补 */
        NOT_IDLE,

        /** 上一次想事情离得太近 */
        TOO_SOON,

        /** 可以想 */
        SEND
    }

    /**
     * 现在适不适合一个人想事情。
     *
     * @param lastChatAt    最后一次对话时间戳，0 = 从没聊过
     * @param lastThoughtAt 上一次生成独白的时间戳，0 = 从没生成过
     */
    fun decide(
        nowMs: Long,
        hourOfDay: Int,
        lastChatAt: Long,
        lastThoughtAt: Long,
        enabled: Boolean = true
    ): Verdict {
        if (!enabled) return Verdict.DISABLED
        if (hourOfDay >= NIGHT_START_HOUR || hourOfDay < NIGHT_END_HOUR) return Verdict.NIGHT
        // 没有对话就没有素材 —— 硬想只会生成「今天天气不错」这种废话
        if (lastChatAt <= 0L) return Verdict.NEVER_CHATTED
        if (nowMs - lastChatAt < MIN_IDLE_MINUTES * 60_000L) return Verdict.NOT_IDLE
        if (lastThoughtAt > 0L && nowMs - lastThoughtAt < MIN_GAP_MINUTES * 60_000L) {
            return Verdict.TOO_SOON
        }
        return Verdict.SEND
    }

    /**
     * 内心独白的提示词（纯函数，单测覆盖）。
     *
     * 关键约束是**「不超过 30 字」和「不要元描述」**：模型很容易写成
     * 「我在想他今天体检的事，希望一切顺利」——这是在写日记，不是心里冒出来的一句话。
     */
    internal fun buildPrompt(
        name: String,
        mood: String,
        affinity: Int,
        facts: List<String>,
        topic: String?,
        recentSummary: String?,
        hourOfDay: Int
    ): String = buildString {
        append("你是").append(name).append("。现在是 ").append(hourOfDay).append(" 点，")
        append("主人已经有一阵子没跟你说话了。")
        append("你现在的状态：").append(mood)
        append(if (affinity >= 80) "，很想黏着他" else if (affinity < 30) "，还有点生他的气" else "，平常心")
        append("。\n")

        if (facts.isNotEmpty()) {
            append("\n【你记得关于他的事】\n")
            facts.take(5).forEach { append("- ").append(it).append("\n") }
        }
        if (!topic.isNullOrBlank()) {
            append("\n【你一直记挂着的】\n- ").append(topic).append("\n")
        }
        if (!recentSummary.isNullOrBlank()) {
            append("\n【你们最近聊过】\n").append(recentSummary.take(200)).append("\n")
        }

        append(
            """
            
            【你现在要做的】
            没有人在跟你说话。你一个人待着，脑子里冒出一句话。
            
            要求：
            - 不超过 30 个字，像心里默念，不是发朋友圈。
            - 要具体：最好落在上面某件真实的事上（比如「也不知道他体检结果怎么样了，有点担心」）。
            - 可以担心、可以好奇、可以吐槽、可以犯困，但不要甜言蜜语堆砌。
            - 不要写「我在想…」「他心里…」这种元描述，直接就是那句话本身。
            - 不要问句堆叠，不要写成给他的留言。
            - 只输出这一句话，不要引号、不要前缀、不要解释。
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 幂等排程。
     *
     * **先问 WorkManager「任务还在不在」，再看指纹** —— 0.9.4 的心跳就是因为只看
     * 本地指纹，被 force-stop 清掉任务后永久静默。这里不能重犯。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            runCatching { reconcile(app) }
                .onFailure { Log.w(TAG, "心智引擎排程失败", it) }
        }
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            CopilotConfig.setInnerThoughtSignature(context, "")
        }.onFailure { Log.w(TAG, "取消失败", it) }
    }

    /** 立刻想一次（设置页/调试用） */
    fun runNow(context: Context) {
        val app = context.applicationContext ?: context
        scope.launch {
            runCatching { perform(app) }
                .onSuccess { Log.i(TAG, "手动触发结果：$it") }
                .onFailure { Log.w(TAG, "手动触发失败", it) }
        }
    }

    private suspend fun reconcile(app: Context) {
        val wm = WorkManager.getInstance(app)
        val infos = runCatching {
            wm.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "查询任务状态失败，按不存在处理", it) }.getOrDefault(emptyList())

        val live = infos.any {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
        val stored = CopilotConfig.innerThoughtSignature(app)
        if (live && stored == SCHEDULE_SIGNATURE) {
            Log.d(TAG, "心智引擎任务在队，跳过")
            return
        }

        val request = PeriodicWorkRequestBuilder<InnerThoughtWorker>(
            CHECK_INTERVAL_HOURS, TimeUnit.HOURS
        )
            // 首次晚 30 分钟：刚装好就有聊天记录的概率不高，先让用户用一会儿
            .setInitialDelay(30, TimeUnit.MINUTES)
            .addTag(TAG_NAME)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        CopilotConfig.setInnerThoughtSignature(app, SCHEDULE_SIGNATURE)
        Log.i(
            TAG,
            "心智引擎已排：每 ${CHECK_INTERVAL_HOURS} 小时想一次（${if (live) "规格变了" else "任务不在队"}）"
        )
    }

    // ---------------------------------------------------------------- 执行

    /**
     * 跑一轮：判 → 想 → 存。
     *
     * @return 判定/结果字符串，日志与单测都用得上
     */
    suspend fun perform(context: Context): String {
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val lastChat = CopilotConfig.lastChatAt(context)

        val dao = runCatching { AppDatabase.get(context).innerThoughtDao() }.getOrNull()
            ?: run {
                Log.w(TAG, "数据库不可用，本轮不想了")
                return "NO_DB"
            }
        val lastThought = runCatching { dao.lastAt() }.getOrNull() ?: 0L

        val verdict = decide(now, hour, lastChat, lastThought)
        Log.d(
            TAG,
            "判定=$verdict hour=$hour " +
                "上次对话=${if (lastChat <= 0L) "无" else "${(now - lastChat) / 60_000}分钟前"} " +
                "上次想事情=${if (lastThought <= 0L) "无" else "${(now - lastThought) / 60_000}分钟前"}"
        )
        if (verdict != Verdict.SEND) return verdict.name

        val provider = pickProvider(context)
        if (provider == null) {
            Log.i(TAG, "没有可用 provider/Key，本轮不想了")
            return "NO_PROVIDER"
        }

        val profile = PersonaEngine.profileSnapshot()
        val facts = PersonaEngine.memoriesSnapshot()
        val topic = runCatching {
            AppDatabase.get(context).topicSeedDao()
                .nextCandidate(now, now - 48 * 3_600_000L)
                ?.content
        }.getOrNull()
        val prompt = buildPrompt(
            name = profile.name,
            mood = profile.mood,
            affinity = profile.affinity,
            facts = facts,
            topic = topic,
            recentSummary = LocalDigitalBeing.summaryText(),
            hourOfDay = hour
        )

        val client = LlmClientFactory.create(provider, context)
        val raw = withTimeoutOrNull(TIMEOUT_MS) {
            client.chat(
                listOf(
                    LlmClient.Message("system", prompt),
                    LlmClient.Message("user", "（没有人说话，你一个人待着。）")
                )
            )
        }
        val thought = clean(raw)
        if (thought == null) {
            Log.i(TAG, "独白生成失败或为空（provider=$provider）")
            return "NO_TEXT"
        }

        val id = runCatching {
            dao.insert(
                InnerThought(
                    content = thought,
                    mood = profile.mood,
                    source = if (!topic.isNullOrBlank()) "话题:$topic" else "泛想",
                    createdAt = now
                )
            )
        }.getOrDefault(0L)
        runCatching { dao.trim(InnerThought.MAX_ROWS) }

        // 交给 PersonaEngine 缓存：下一句对话就带着它（带上想出来的时刻，过期就不采用）
        PersonaEngine.onInnerThought(thought, id, now)
        Log.i(TAG, "想好了（${thought.length} 字，$provider，源=${if (topic != null) "话题" else "泛想"}）：$thought")
        return "SENT"
    }

    /** 主 provider 能不能用（有没有 Key / 是不是本地 ollama） */
    private fun pickProvider(context: Context): String? {
        val p = LlmClientFactory.getProvider(context)
        val key = LlmClientFactory.getConfig(context, p).first
        if (key.isBlank() && p != "ollama") {
            Log.i(TAG, "provider=$p 没有 Key，跳过")
            return null
        }
        return p
    }

    /**
     * 清洗模型输出成「一句心里话」。
     *
     * 模型常见毛病：加引号、写「（沉默）」、一次写三行、第一行是「好的，我来」。
     * 这里统一取第一行有效内容，去掉引号与书名号，超长截断。
     */
    internal fun clean(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val firstLine = s.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("好的") && !it.startsWith("内心独白") }
            ?: return null
        val trimmed = firstLine
            .removePrefix("「").removeSuffix("」")
            .removePrefix("“").removeSuffix("”")
            .removePrefix("\"").removeSuffix("\"")
            .trim()
        if (trimmed.length < 4) return null
        return if (trimmed.length > InnerThought.CONTENT_MAX) {
            trimmed.take(InnerThought.CONTENT_MAX)
        } else {
            trimmed
        }
    }
}

/**
 * WorkManager 的入口。
 *
 * 永远返回 success：想事情是锦上添花，失败重试只会在省电模式下反复唤醒进程。
 * 真正的原因在 [InnerMonologueEngine.perform] 的日志里。
 */
class InnerThoughtWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { InnerMonologueEngine.perform(applicationContext) }
            .onFailure { Log.w("InnerThought", "worker 执行失败：${it.javaClass.simpleName} ${it.message}") }
        return Result.success()
    }
}
