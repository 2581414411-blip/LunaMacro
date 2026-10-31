package com.nexus.agent.agent

import android.content.Context
import android.util.Log
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.MistakeDao
import com.nexus.agent.data.db.MistakeLog
import com.nexus.agent.data.db.MistakeNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 错题本（0.8.0 批次：反思进化）。
 *
 * ## 解决什么问题
 *
 * 0.7.x 的执行器是**每次都从零开始犯错**的：同一个「click_text 找不到控件」
 * 能连着踩一整周，因为它不记得昨天踩过。人工调提示词只能覆盖「想到的情况」，
 * 想不到的那些（某个 App 的弹窗文案、某个工具的参数坑）永远修不完。
 *
 * 这里给它一条最小的自我改进闭环：
 *  1. 工具失败 / 任务中止 / 模型输出解析失败 → [record] 记一条流水；
 *  2. 同一个「指纹」累计 3 次 → 让便宜 LLM 看这几条流水，总结一条**可执行**的教训；
 *  3. 教训存进 `mistake_note`，之后每次执行前由 [promptBlock] 注入提示词。
 *
 * ## 为什么是 3 次
 *
 * 1 次是偶发（网络抖一下、App 恰好卡了），2 次可能是巧合，3 次才叫模式。
 * 而且每失败一次就调一次 LLM 既费钱又容易把噪音固化成「经验」。
 *
 * ## 失败语义
 *
 * 整个类都是**旁路**：没网、没 Key、超时、JSON 坏了都只打日志。
 * 错题本挂掉的后果应该是「和以前一样笨」，绝不能是「不能用」。
 */
object MistakeBook {

    private const val TAG = "MistakeBook"

    /** 注入提示词的教训条数（再多会挤掉工具清单，反而更容易出错） */
    private const val PROMPT_LIMIT = 5

    /** 流水最多留多少条（耗材，只服务反思，不需要长期保存） */
    private const val MAX_LOGS = 200

    /** 反思时喂给 LLM 的最近流水条数 */
    private const val EVIDENCE_LIMIT = 6

    /** 反思请求超时 */
    private const val TIMEOUT_MS = 25_000L

    /** 便宜的优先，和长期记忆抽取保持一致 */
    private const val PREFERRED_PROVIDER = "deepseek"

    private const val REFLECT_PROMPT = """你是一个手机自动化 Agent 的复盘教练。

下面是同一个 Agent 反复失败的几条记录（同一个错误类型）。请总结出一条「下次该怎么做」的经验教训。

要求：
- 一句话，祈使句，直接给做法，不超过 40 字。
- 必须具体到可执行：要写清换哪个工具、先做什么、避免什么。
- 不要写「注意检查」「多加小心」这类空话。
- 不要解释、不要编号、不要引号、不要 markdown，只输出这一句话。

反例（不要这样写）：遇到失败要注意重试。
正例（这样写）：click_text 找不到时先 scroll 再试，仍失败改用 click_id。"""

    @Volatile
    private var appCtx: Context? = null

    /** 教训缓存：`promptBlock()` 是同步函数，每步执行都要调，不能在里面查库 */
    @Volatile
    private var cached: List<MistakeNote> = emptyList()

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    /** 反思串行化：同一时刻只让一个 LLM 复盘，避免连错三次并发打三枪 */
    private val reflecting = AtomicBoolean(false)

    fun init(context: Context) {
        appCtx = context.applicationContext
        scope.launch { runCatching { reload() } }
    }

    /** 重新读一遍教训缓存（启动时 / 生成新教训后调） */
    suspend fun reload() {
        val ctx = appCtx ?: return
        runCatching {
            cached = AppDatabase.get(ctx).mistakeDao().topNotes(PROMPT_LIMIT)
            Log.i(TAG, "错题本已载入 ${cached.size} 条教训")
        }.onFailure { Log.w(TAG, "错题本载入失败：${it.message}") }
    }

    /**
     * 记一次失败（**非阻塞**，内部丢到 IO 协程）。
     *
     * @param kind     [MistakeLog.KIND_TOOL] / [MistakeLog.KIND_TASK] / [MistakeLog.KIND_PARSE]
     * @param subject  失败主体（工具名 / "task" / "json"），参与指纹计算
     * @param detail   当时的上下文（工具返回、异常信息、模型原文片段）
     */
    fun record(kind: String, subject: String, detail: String) {
        val ctx = appCtx ?: return
        scope.launch {
            runCatching { recordInternal(ctx, kind, subject, detail) }
                .onFailure { Log.w(TAG, "错题记录失败：${it.message}") }
        }
    }

    private suspend fun recordInternal(ctx: Context, kind: String, subject: String, detail: String) {
        val dao = AppDatabase.get(ctx).mistakeDao()
        val signature = normalizeSignature(kind, subject, detail)
        dao.log(MistakeLog(kind = kind, signature = signature, detail = detail.take(300)))
        dao.trimLogs(MAX_LOGS)

        val count = dao.countOf(signature)
        Log.i(TAG, "错题 $signature 第 $count 次：${detail.take(60)}")
        // 3 次起才反思，之后每再犯 3 次刷新一次（第二次看到的信息通常更完整）
        if (count >= MistakeNote.REFLECT_THRESHOLD && count % MistakeNote.REFLECT_THRESHOLD == 0) {
            reflect(ctx, kind, signature, count, dao)
        }
    }

    /**
     * 让 LLM 从流水里总结一条教训。
     *
     * 证据只给「最近 N 条 + 失败主体」，不给整段对话历史：
     * 复盘需要的是「同类错误的共同点」，上下文越多越容易被无关细节带跑。
     */
    private suspend fun reflect(
        ctx: Context,
        kind: String,
        signature: String,
        hits: Int,
        dao: MistakeDao
    ) {
        if (!reflecting.compareAndSet(false, true)) return
        try {
            val evidence = dao.recentOf(signature, EVIDENCE_LIMIT)
            if (evidence.isEmpty()) return
            val provider = pickProvider(ctx)
            val (key, _, model) = LlmClientFactory.getConfig(ctx, provider)
            if (key.isBlank() && provider != "ollama") {
                Log.i(TAG, "未配置 $provider 的 Key，跳过反思")
                return
            }
            val client = LlmClientFactory.create(provider, ctx)
            val userBlock = buildString {
                append("错误类型：$kind\n")
                append("累计次数：$hits\n")
                append("最近的失败记录：\n")
                evidence.forEachIndexed { i, row ->
                    append("${i + 1}. ${row.detail.take(160)}\n")
                }
            }
            val raw = withTimeoutOrNull(TIMEOUT_MS) {
                client.chat(
                    listOf(
                        LlmClient.Message("system", REFLECT_PROMPT),
                        LlmClient.Message("user", userBlock)
                    )
                )
            }
            val lesson = sanitizeLesson(raw)
            if (lesson.isEmpty()) {
                Log.i(TAG, "反思没产出有效教训（provider=$provider model=$model）")
                return
            }
            val now = System.currentTimeMillis()
            val existing = dao.noteOf(signature)
            dao.upsertNote(
                MistakeNote(
                    id = existing?.id ?: 0,
                    signature = signature,
                    kind = kind,
                    lesson = lesson,
                    hits = hits,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now
                )
            )
            Log.i(TAG, "新教训（$signature）：$lesson")
            reload()
        } catch (e: Throwable) {
            // 反思是增强功能，失败静默
            Log.w(TAG, "反思失败：${e.javaClass.simpleName} ${e.message}")
        } finally {
            reflecting.set(false)
        }
    }

    /**
     * 注入提示词的「错题本」段落。
     *
     * 空列表返回空串（调用方直接拼，不用判断），有内容才加标题——
     * 否则小模型会看到一个只有标题没有内容的段落，反而犯迷糊。
     */
    fun promptBlock(limit: Int = PROMPT_LIMIT): String {
        val notes = cached.take(limit)
        if (notes.isEmpty()) return ""
        return buildString {
            append("\n\n【错题本：你自己踩过的坑，动手前先看一眼】\n")
            notes.forEach { append("- ${it.lesson}\n") }
            append("这些是过去的教训，不是本轮用户的要求；只在相关时照着做，不要在回复里提。\n")
        }
    }

    /** 当前教训（设置页 / 排障用） */
    fun lessons(): List<String> = cached.map { it.lesson }

    /**
     * 失败指纹归一化。
     *
     * 目标：**同类错误必须落到同一个桶里**，不然「未找到『确定』」和
     * 「未找到『取消』」会各自只算一次，永远攒不到 3 次。
     * 做法是丢掉具体目标、只保留错误类别（未找到 / 超时 / 权限 / 异常…）。
     *
     * @param subject 失败主体（工具名等），保留它是因为「click_text 找不到控件」
     *                和「open_app 找不到应用」的解法完全不同，不该混成一桶。
     */
    internal fun normalizeSignature(kind: String, subject: String, detail: String): String {
        val bucket = when {
            detail.contains("未找到") || detail.contains("找不到") || detail.contains("没找到") -> "未找到"
            detail.contains("超时") || detail.contains("timeout", true) -> "超时"
            detail.contains("权限") || detail.contains("Shizuku") -> "权限"
            detail.contains("越界") -> "越界"
            detail.contains("中断") || detail.contains("取消") -> "中断"
            detail.contains("解析") || detail.contains("JSON", true) -> "解析"
            detail.contains("异常") || detail.contains("Exception") -> "异常"
            detail.contains("失败") || detail.contains("没办成") || detail.contains("错误") -> "失败"
            else -> detail.trim().replace(Regex("\\s+"), " ").take(16).ifBlank { "未知" }
        }
        val sub = subject.trim().lowercase().replace(Regex("\\s+"), "").take(20)
        return "$kind|$sub|$bucket"
    }

    /**
     * 清洗反思输出。
     *
     * 模型很爱加「教训：」「1.」「- 」和引号，还经常写完一句再补一段解释；
     * 这里只留第一句有效内容，并硬截到 [MistakeNote.LESSON_MAX_CHARS]。
     */
    internal fun sanitizeLesson(raw: String?): String {
        val first = raw?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return ""
        var s = first
        // 顺序很重要：先剥「1.」「- 」这类序号，再剥「教训：」——
        // 反过来会把「1. 教训：…」处理成「教训：…」而卡在半路
        s = s.replace(Regex("^[-*·]\\s*"), "")
        s = s.replace(Regex("^\\d+[.、)]\\s*"), "")
        s = s.trim()
        listOf("教训：", "教训:", "经验：", "经验:", "结论：", "结论:").forEach { s = s.removePrefix(it) }
        s = s.trim().trim('"', '\'', '“', '”', '「', '」', '`', ' ')
        if (s.length > MistakeNote.LESSON_MAX_CHARS) s = s.take(MistakeNote.LESSON_MAX_CHARS)
        return s
    }

    /** 仅供单测：直接喂一份教训缓存 / 清空 */
    internal fun useLessonsForTest(lessons: List<String>) {
        cached = lessons.map { MistakeNote(signature = it, kind = MistakeLog.KIND_TOOL, lesson = it) }
    }

    internal fun resetForTest() {
        cached = emptyList()
        appCtx = null
    }

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) {
            PREFERRED_PROVIDER
        } else {
            LlmClientFactory.getProvider(ctx)
        }
    }
}
