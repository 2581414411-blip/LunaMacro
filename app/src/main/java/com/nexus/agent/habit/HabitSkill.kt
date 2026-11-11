package com.nexus.agent.habit

import android.content.Context
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * 她学会的一个动作（0.9.12）。
 *
 * 和 [HabitProfile] 的区别，一句话说清：
 *  - 画像是「她对你这个人的印象」，只进提示词，不影响任何操作；
 *  - 技能是「她记住了你会这么做」，可以变成一串真实点击。
 *
 * 所以技能必须有一个**用户可见的状态机**，且默认停在最保守的那一档：
 *
 * ```
 *  candidate（她刚学到的，什么都不会做）
 *      │ 用户点「收下」
 *      ▼
 *  active（可以在聊天里被调用，每次执行前还要再确认一次）
 *      │ 用户点「忘记」
 *      ▼
 *  rejected（不再学这个动作，签名留着防止重复长出来）
 * ```
 *
 * **没有任何一条路径能从 candidate 直接变 active 而不经过用户的点击** ——
 * 这条约束不放在 UI 里，而是放在这里（[HabitSkillStore.setState] 只接受显式调用），
 * 因为 UI 会改，数据的默认值不该跟着改。
 */
data class HabitSkill(
    val id: String = UUID.randomUUID().toString(),
    /** [STATE_CANDIDATE] / [STATE_ACTIVE] / [STATE_REJECTED] */
    val state: String = STATE_CANDIDATE,
    val pkg: String,
    val app: String,
    /** 给人看的名字，例如「微信 · 搜索 → 订阅号」 */
    val name: String,
    val steps: List<HabitTrace.TraceStep>,
    /** 观察到重复了多少次 */
    val occurrences: Int,
    /** 分布在多少天 */
    val days: Int,
    val lastAt: Long,
    /** [HabitTrace.RISK_SAFE] / [HabitTrace.RISK_CAUTION] */
    val risk: String = HabitTrace.RISK_SAFE,
    val createdAt: Long = System.currentTimeMillis()
) {
    val isActive: Boolean get() = state == STATE_ACTIVE
    val isCandidate: Boolean get() = state == STATE_CANDIDATE
    val isRejected: Boolean get() = state == STATE_REJECTED

    /** 去重签名（与 [HabitTrace.SkillCandidate.signature] 同一套算法） */
    val signature: String
        get() = pkg + "\u0000" + steps.joinToString(">") { it.screen + "\u0001" + it.label }

    /** 执行前的自然语言描述：「微信里点 搜索 → 订阅号，共 2 步」 */
    fun describe(): String {
        val path = steps.joinToString(" → ") { it.label.ifBlank { it.screen }.ifBlank { "?" } }
        return "在「$app」里点 $path，共 ${steps.size} 步"
    }

    companion object {
        const val STATE_CANDIDATE = "candidate"
        const val STATE_ACTIVE = "active"
        const val STATE_REJECTED = "rejected"
    }
}

/**
 * 技能的本地仓库（0.9.12）。
 *
 * 独立于 [HabitStore] 的 JSONL，理由和当初一样：开关关掉时
 * `HabitStore.clear()` 把整个 `filesDir/habit/` 目录删掉，技能跟着一起没了 ——
 * 「清除学习数据」必须是干净的，不能留下一串「她还会做但你已经关掉学习」的动作。
 *
 * 格式刻意用 TSV 而不是 JSON：技能是**数组套数组**，手写 JSON 解析器容易在
 * 转义上出错，而一行一条、字段里禁掉分隔符的 TSV，坏行跳过就好。
 * 纯 JVM 单测里也能直接构造字符串来测。
 */
object HabitSkillStore {

    private const val TAG = "HabitSkillStore"
    private const val FILE_NAME = "skills.dat"

    /** 步骤内部的分隔符（不可能出现在无障碍文本里） */
    private const val SEP_WITHIN = '\u0001'
    private const val SEP_STEP = '\u0002'

    private val lock = Any()

    private fun fileOf(context: Context): File =
        File(HabitStore.dir(context), FILE_NAME)

    /** 读全部（同步；调用方在 IO 线程） */
    fun load(context: Context): List<HabitSkill> = synchronized(lock) {
        val f = fileOf(context)
        if (!f.isFile) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8).mapNotNull { parseLine(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "读技能失败：${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** 覆盖写全部（同步；调用方在 IO 线程） */
    fun save(context: Context, skills: List<HabitSkill>) {
        synchronized(lock) {
            val dir = HabitStore.dir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "建目录失败")
                return
            }
            try {
                fileOf(context).writeText(
                    skills.joinToString("") { encodeLine(it) + "\n" },
                    Charsets.UTF_8
                )
            } catch (e: Throwable) {
                Log.w(TAG, "写技能失败：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /** 追加一批候选（同签名已存在则跳过，rejected 的也跳过 —— 用户说别再学） */
    fun addCandidates(context: Context, cands: List<HabitTrace.SkillCandidate>): Int {
        if (cands.isEmpty()) return 0
        val existing = load(context)
        val seen = existing.map { it.signature }.toHashSet()
        val add = ArrayList<HabitSkill>(cands.size)
        for (c in cands) {
            val sk = HabitSkill(
                pkg = c.pkg,
                app = c.app,
                name = c.displayName.take(48),
                steps = c.steps,
                occurrences = c.occurrences,
                days = c.days,
                lastAt = c.lastAt,
                risk = c.risk
            )
            if (sk.signature in seen) continue
            seen.add(sk.signature)
            add.add(sk)
        }
        if (add.isEmpty()) return 0
        save(context, existing + add)
        Log.i(TAG, "新增候选技能 ${add.size} 条")
        return add.size
    }

    /** 改状态：这是唯一让技能生效的入口，只接受用户触发的调用 */
    fun setState(context: Context, id: String, state: String) {
        val list = load(context)
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val next = ArrayList(list)
        next[idx] = next[idx].copy(state = state)
        save(context, next)
        Log.i(TAG, "技能 ${next[idx].name} → $state")
    }

    /** 删除一条（「永远别再学」用 rejected；这里是彻底忘掉） */
    fun remove(context: Context, id: String) {
        val list = load(context)
        if (list.none { it.id == id }) return
        save(context, list.filterNot { it.id == id })
    }

    /** 已存在的签名（mine 时用来避免重复产出） */
    fun signatures(context: Context): Set<String> = load(context).map { it.signature }.toHashSet()

    // ---- 编码 ----

    private fun clean(v: String): String =
        v.replace('\t', ' ').replace('\n', ' ').replace(SEP_WITHIN, ' ').replace(SEP_STEP, ' ')

    private fun encodeLine(s: HabitSkill): String {
        val steps = s.steps.joinToString(SEP_STEP.toString()) {
            clean(it.screen) + SEP_WITHIN + clean(it.label)
        }
        return listOf(
            clean(s.id), clean(s.state), clean(s.pkg), clean(s.app), clean(s.name),
            s.occurrences.toString(), s.days.toString(), s.lastAt.toString(),
            clean(s.risk), steps, s.createdAt.toString()
        ).joinToString("\t")
    }

    private fun parseLine(line: String): HabitSkill? {
        if (line.isBlank()) return null
        val f = line.split('\t')
        if (f.size < 11) return null
        val steps = f[9].split(SEP_STEP).mapNotNull { raw ->
            if (raw.isEmpty()) return@mapNotNull null
            val p = raw.split(SEP_WITHIN)
            HabitTrace.TraceStep(p.getOrElse(0) { "" }, p.getOrElse(1) { "" })
        }
        if (steps.size < 2) return null
        val pkg = f[2]
        if (pkg.isBlank()) return null
        return HabitSkill(
            id = f[0],
            state = f[1].ifBlank { HabitSkill.STATE_CANDIDATE },
            pkg = pkg,
            app = f[3],
            name = f[4],
            steps = steps,
            occurrences = f[5].toIntOrNull() ?: 0,
            days = f[6].toIntOrNull() ?: 0,
            lastAt = f[7].toLongOrNull() ?: 0L,
            risk = f[8].ifBlank { HabitTrace.RISK_SAFE },
            createdAt = f[10].toLongOrNull() ?: 0L
        )
    }
}
