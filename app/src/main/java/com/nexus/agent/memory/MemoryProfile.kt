package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.data.db.RawTrace
import java.io.File

/**
 * 偏好记忆 —— 从轨迹池里统计出来的「他大概什么时候、在哪、做什么」（0.9.14）。
 *
 * ## 为什么不另建一套采集
 *
 * 0.9.11 的 `habit/` 已经在做「作息 + 常用应用」，而且已经注入人格提示词。
 * 再写一套采集只会互相打架：两处都说「他常刷微信」，数字却不一样。
 *
 * 这里只做**批处理的副产品**：夜里巩固时顺手把这一批轨迹数一遍，
 * 得出的东西给规划器当背景（「他这会儿常做的事是这些」）。
 * 不新增任何采集点、不新增任何权限、不动 `habit/` 的任何一行。
 *
 * ## 和 habit 画像的区别
 *
 * | | habit（0.9.11） | 本文件（0.9.14） |
 * |---|---|---|
 * | 数据来源 | 用户手动点击（要免责声明 + 20 秒） | 她自己办过的任务轨迹 |
 * | 粒度 | 作息 / 应用 / 控件 | 应用 / 任务 / 时段 |
 * | 用途 | 人格提示词（「你最近好像很晚睡」） | 规划提示词（「这类事他常让我干」） |
 * | 更新 | 每天一次，读 JSONL | 每次夜间巩固，读轨迹池 |
 */
object MemoryProfile {

    private const val TAG = "MemDebug"
    private const val DIR = "memory"
    private const val FILE = "profile.txt"

    /** 每个维度最多写几条（提示词里多一行都是成本） */
    const val TOP_APPS = 3
    const val TOP_INTENTS = 3

    /**
     * 统计窗口：最近多少条轨迹。
     *
     * 巩固过的轨迹保留 [MemoryConsolidator.KEEP_TRACES_DAYS] 天，所以这个窗口
     * 实际覆盖「最近一周左右」——比只看「这一夜新增的」稳定得多。
     */
    const val WINDOW = 500

    /** 纯统计结果 */
    data class Summary(
        val topApps: List<Pair<String, Int>>,
        val topIntents: List<Pair<String, Int>>,
        val busiestSlot: Int
    ) {
        val isEmpty: Boolean get() = topApps.isEmpty() && topIntents.isEmpty()
    }

    @Volatile
    private var cached: String? = null

    /**
     * 数一遍这批轨迹（纯函数）。
     *
     * 只数**成功**的轨迹：失败的那条说明「这件事他没干成」，
     * 把它算进「常做的事」会得到一张错的能力清单。
     */
    fun summarize(traces: List<RawTrace>): Summary {
        val apps = HashMap<String, Int>()
        val intents = HashMap<String, Int>()
        val slots = IntArray(4)
        for (t in traces) {
            if (t.outcome != RawTrace.OUTCOME_SUCCESS) continue
            if (t.pkg.isNotBlank()) apps[t.pkg] = (apps[t.pkg] ?: 0) + 1
            val label = t.intent.trim().take(24)
            if (label.isNotEmpty()) intents[label] = (intents[label] ?: 0) + 1
            val hour = hourOf(t.createdAt)
            if (hour >= 0) slots[slotOf(hour)]++
        }
        val busiest = slots.indices.maxByOrNull { slots[it] } ?: -1
        return Summary(
            topApps = apps.entries.sortedByDescending { it.value }.take(TOP_APPS).map { it.key to it.value },
            topIntents = intents.entries.sortedByDescending { it.value }.take(TOP_INTENTS).map { it.key to it.value },
            busiestSlot = if (busiest >= 0 && slots[busiest] > 0) busiest else -1
        )
    }

    /** 一天分四段，比「几点几分」稳，也更有用（人对自己作息的认知就是这个粒度） */
    fun slotOf(hour: Int): Int = when (hour) {
        in 0..5 -> 0
        in 6..11 -> 1
        in 12..17 -> 2
        else -> 3
    }

    fun slotName(slot: Int): String = when (slot) {
        0 -> "凌晨"
        1 -> "上午"
        2 -> "下午"
        else -> "晚上"
    }

    private fun hourOf(at: Long): Int = runCatching {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = at
        cal.get(java.util.Calendar.HOUR_OF_DAY)
    }.getOrDefault(-1)

    /**
     * 渲染成提示词块。
     *
     * 结尾那句免责很重要：它是背景，不是本轮要求。
     * 少了这句，模型会开始主动汇报「我注意到你常刷微信」——那个观感很吓人。
     */
    fun render(summary: Summary, appLabel: (String) -> String = { it }): String {
        if (summary.isEmpty) return ""
        val sb = StringBuilder()
        sb.append("【他最近常做的事（你自己统计的，不是他这轮的要求）】\n")
        if (summary.topApps.isNotEmpty()) {
            sb.append("- 常用应用：")
            sb.append(summary.topApps.joinToString("、") { "${appLabel(it.first)}（${it.second} 次）" })
            sb.append('\n')
        }
        if (summary.topIntents.isNotEmpty()) {
            sb.append("- 常让我做的：")
            sb.append(summary.topIntents.joinToString("、") { "${it.first}（${it.second} 次）" })
            sb.append('\n')
        }
        if (summary.busiestSlot >= 0) {
            sb.append("- 最常在这个时段用手机：").append(slotName(summary.busiestSlot)).append('\n')
        }
        sb.append("以上只是背景，用来判断他大概想要什么；不要主动提，也不要当成命令。\n")
        return sb.toString()
    }

    /**
     * 巩固时重建（IO 线程）。
     *
     * @return 是否写出了新内容（全空时删掉旧文件，免得留下过期画像）
     */
    fun rebuild(ctx: Context, traces: List<RawTrace>): Boolean {
        val summary = summarize(traces)
        val text = if (summary.isEmpty) "" else render(summary) { pkg -> labelOf(ctx, pkg) }
        val f = fileOf(ctx)
        return runCatching {
            if (text.isBlank()) {
                if (f.exists()) f.delete()
                cached = ""
                false
            } else {
                f.parentFile?.mkdirs()
                f.writeText(text, Charsets.UTF_8)
                cached = text
                Log.i(TAG, "[偏好] 已更新：${summary.topApps.take(2).joinToString("、") { it.first }} 等")
                true
            }
        }.getOrElse {
            Log.w(TAG, "[偏好] 写入失败：${it.message}")
            false
        }
    }

    /**
     * 取提示词块（给 Planner / PersonaEngine 用）。
     *
     * 读内存缓存，不在这条路径上碰磁盘 —— 每次规划都读一次文件是不可接受的。
     */
    fun block(ctx: Context?): String {
        val app = ctx ?: return ""
        val hit = cached
        if (hit != null) return hit
        val text = runCatching {
            val f = fileOf(app)
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
        cached = text
        return text
    }

    /** 巩固完调一次，让下一次规划看到新画像 */
    fun invalidate() {
        cached = null
    }

    fun clear(ctx: Context) {
        cached = ""
        runCatching { fileOf(ctx).delete() }
    }

    private fun fileOf(ctx: Context): File = File(File(ctx.filesDir, DIR), FILE)

    /** 包名 → 应用名；拿不到就退回包名（不要因为解析失败整行消失） */
    private fun labelOf(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
