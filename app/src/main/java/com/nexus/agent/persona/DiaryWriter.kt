package com.nexus.agent.persona

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.AppDatabase
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * 会话日记的生成端（0.9.15 阶段 3）。
 *
 * ## 它在一整条链路里的位置
 *
 * ```
 * 凌晨 3 点（MemoryNightShift） → 她写一句今天的心情 → filesDir/persona/diary/{day}.json
 * 第二天开机（PersonaEngine.init） → 读到它 → 只在第一轮对话注入一次
 * ```
 *
 * 和「巩固」的分工很干净：巩固学的是**怎么做事**（动作记忆），日记记的是
 * **她这一天过得怎么样**（情绪基调 + 还没放下的事）。两件事共用一个夜间窗口，
 * 因为都是「用户睡着时才有资格做」的活。
 *
 * ## 三条硬规则
 *
 * 1. **绝不空档**（合伙人 2026-11-11 拍板）：没 Key、超时、解析失败一律走
 *    [Diary.template] 的规则模板。日记缺席一次，第二天开场就少了一块「刚醒来」的感觉，
 *    而这种缺失用户是能感觉到的。
 * 2. **20 小时幂等**：夜间定时和启动兜底会各调一次，靠 [Diary.shouldWrite] 拦住。
 * 3. **一天一份**：文件名就是日期，重写覆盖 —— 同一天写两次是补写，不是新的一篇。
 *
 * 全部排障日志统一走 `adb logcat -s PadDebug`，搜 `[日记]`。
 */
object DiaryWriter {

    private const val TAG = "PadDebug"

    /** 首选写日记的模型（便宜、够用，和记忆抽取同一个） */
    private const val PREFERRED_PROVIDER = "deepseek"

    /** 一次最多带几件「还没放下的事」进去 —— 多了会写成待办清单 */
    private const val MAX_UNCLOSED = 3

    /** 超时：写不出来就用模板，绝不让夜间任务挂在这儿 */
    private const val TIMEOUT_MS = 25_000L

    /**
     * 写日记的提示词。
     *
     * 刻意把「不要文采」「不要总结」写进去：模型写「日记」的默认风格是作文比赛，
     * 而这里要的是一个人睡前随手记的一句半句。字数上限给 100（硬上限 200 在 [Diary]），
     * 是为了让它自己先收敛一次。
     */
    private const val PROMPT = """你在扮演一个叫宏月的女孩，睡前在自己手机里记一句当天的心情。

要求：
- 第一人称，说感受和还放不下的事，不写日程、不列清单、不总结今天做了几件事
- 一到三句，不超过 80 字，口语，像随手记的，不要文采、不要比喻堆砌
- 不要用「今天」开头，不要提「用户」，直接说「他」
- 只输出这一句话本身：不要引号、不要标题、不要任何解释"""

    /** 模型爱在开头加的这些前缀，一律削掉（留着就成了「好的，以下是日记：……」） */
    private val PREFIXES = listOf(
        "好的，", "好的。", "以下是", "日记：", "日记:", "心情：", "心情:", "宏月："
    )

    /**
     * 写一份日记。**任何情况下都不抛异常**（夜间任务里抛出去只会变成一条崩溃日志）。
     *
     * @return 写成功的那份；不需要写 / 写失败返回 null
     */
    suspend fun maybeWrite(
        ctx: Context,
        now: Long = System.currentTimeMillis()
    ): Diary? = runCatching {
        val app = ctx.applicationContext
        if (!CopilotConfig.isDiaryEnabled(app)) {
            Log.i(TAG, "[日记] 开关关着，跳过")
            return@runCatching null
        }
        val dir = Diary.dirOf(app.filesDir)
        if (!Diary.shouldWrite(dir, now)) {
            Log.i(TAG, "[日记] 20 小时内刚写过，跳过")
            return@runCatching null
        }

        // 心情取的是「此刻」（已按时间衰减）。凌晨三点她多半已经落回基线，
        // 那正是「今天整体什么基调」该有的样子 —— 用白天的峰值反而是错的。
        val snap = PadRuntime.ensureLoaded(app)
        val unclosed = unclosedTopics(app, now)
        val text = writeWithLlm(app, snap, unclosed) ?: run {
            val t = Diary.template(snap.slow, snap.quick, unclosed)
            Log.i(TAG, "[日记] 走规则兜底（没 Key / 超时 / 空回复）")
            t
        }
        val diary = Diary(
            day = Diary.dayKey(now),
            at = now,
            quick = snap.quick,
            slow = snap.slow,
            text = text
        )
        val ok = Diary.write(dir, diary)
        if (!ok) {
            Log.w(TAG, "[日记] 写盘失败：${dir.absolutePath}")
            return@runCatching null
        }
        Log.i(
            TAG,
            "[日记] 已写 ${diary.day}：此刻=${PadDynamics.describeOf(snap.quick)} " +
                "底色=${PadDynamics.describeOf(snap.slow)} " +
                "牵挂=${unclosed.size} 件 ${text.length} 字 → $text"
        )
        diary
    }.onFailure {
        Log.w(TAG, "[日记] 生成异常：${it.javaClass.simpleName} ${it.message}")
    }.getOrNull()

    // ---------------------------------------------------------------- 模型

    private suspend fun writeWithLlm(
        ctx: Context,
        snap: PadRuntime.Snapshot,
        unclosed: List<String>
    ): String? = runCatching {
        val provider = pickProvider(ctx)
        val (key, _, model) = LlmClientFactory.getConfig(ctx, provider)
        // Ollama 本地不需要 Key；其他家没 Key 就别白跑一趟，直接交给模板
        if (key.isBlank() && provider != "ollama") {
            Log.i(TAG, "[日记] 未配置 $provider 的 Key，走模板")
            return@runCatching null
        }
        val client = LlmClientFactory.create(provider, ctx)
        val raw = withTimeoutOrNull(TIMEOUT_MS) {
            client.chat(
                listOf(
                    LlmClient.Message("system", PROMPT),
                    LlmClient.Message("user", contextOf(snap, unclosed))
                )
            )
        }
        val text = clean(raw)
        if (text == null) Log.i(TAG, "[日记] 模型无有效输出（provider=$provider model=$model）")
        text
    }.onFailure { Log.w(TAG, "[日记] 模型调用失败：${it.javaClass.simpleName} ${it.message}") }
        .getOrNull()

    /** 给模型看的上下文（纯函数，单测直接断言它带上了心情和牵挂） */
    internal fun contextOf(snap: PadRuntime.Snapshot, unclosed: List<String>): String = buildString {
        append("此刻的心情：").append(PadDynamics.describeOf(snap.quick)).append('\n')
        append("最近整体的底色：").append(PadDynamics.describeOf(snap.slow)).append('\n')
        if (unclosed.isEmpty()) {
            append("还没放下的事：没有")
        } else {
            append("还没放下的事：").append(unclosed.take(MAX_UNCLOSED).joinToString("、"))
        }
    }

    /**
     * 把模型输出洗干净。返回 null = 这一句不能用，走模板。
     *
     * 要处理四种常见跑偏：```json 围栏、整段被引号包住、开头一句「好的，以下是日记：」、
     * 以及一口气写了三百字。最后那种按句号截，别把半句话留在文件里。
     */
    internal fun clean(raw: String?): String? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        // 日记是一段话，不是分行诗：换行压成一段。
        // 中文句子之间**不能补空格**（「第一句。 第二句。」读起来像翻译腔），
        // 英文单词之间又必须补 —— 交给 [joinLines] 按两侧字符判断。
        s = joinLines(s).replace(Regex("\\s{2,}"), " ").trim()
        s = s.trim('"', '\'', '「', '」', '“', '”', '：', ':', ' ')
        for (p in PREFIXES) {
            if (s.startsWith(p)) s = s.removePrefix(p).trim()
        }
        s = s.trim('"', '\'', '「', '」', '“', '”', '：', ':', ' ')
        if (s.isEmpty()) return null
        if (s.length <= Diary.TEXT_MAX) return s
        return cutAtSentence(s, Diary.TEXT_MAX)
    }

    /**
     * 把多行拼成一段。
     *
     * 规则只有一条：**换行两侧都是东亚字符时不补空格**。中文句子中间多一个空格，
     * 读起来像机翻；而英文单词之间少一个空格就成了连写。用码位粗判（≥ 0x2E80
     * 覆盖 CJK、假名、全角标点），不引入任何依赖。
     */
    private fun joinLines(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\n' || c == '\r') {
                // 顺带吃掉换行前后那些无意义的缩进
                while (i < s.length && (s[i] == '\n' || s[i] == '\r' || s[i] == ' ' || s[i] == '\t')) i++
                val prev = sb.lastOrNull()
                val next = if (i < s.length) s[i] else null
                if (prev != null && next != null && !isEastAsian(prev) && !isEastAsian(next)) {
                    sb.append(' ')
                }
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isEastAsian(c: Char): Boolean = c.code >= 0x2E80

    /** 超长时尽量在句号处断，实在没有就硬截 —— 但绝不返回半句话加省略号 */
    private fun cutAtSentence(s: String, max: Int): String {        val head = s.take(max)
        val end = head.indexOfLast { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' }
        val out = if (end >= 8) head.substring(0, end + 1) else head
        return out.trim()
    }

    // ---------------------------------------------------------------- 材料

    /** 「还没放下的事」= 还没过期、也还没问过的话题种子 */
    private suspend fun unclosedTopics(ctx: Context, now: Long): List<String> = runCatching {
        AppDatabase.get(ctx).topicSeedDao().fresh(now, MAX_UNCLOSED).map { it.content }
    }.onFailure {
        Log.w(TAG, "[日记] 读话题种子失败：${it.javaClass.simpleName}")
    }.getOrDefault(emptyList())

    private fun pickProvider(ctx: Context): String {
        val preferredKey = LlmClientFactory.getConfig(ctx, PREFERRED_PROVIDER).first
        return if (preferredKey.isNotBlank()) PREFERRED_PROVIDER else LlmClientFactory.getProvider(ctx)
    }

    /** 排障用：一行说清「这次为什么没写成」 */
    internal fun describe(snap: PadRuntime.Snapshot): String =
        "此刻=${PadDynamics.describeOf(snap.quick)} 底色=${PadDynamics.describeOf(snap.slow)} " +
            "强度=${"%.2f".format(Locale.US, snap.quick.normalized())}"
}
