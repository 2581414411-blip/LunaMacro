package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.RawTrace
import com.nexus.agent.habit.HabitSkill
import com.nexus.agent.habit.HabitSkillStore

/**
 * 老数据兼容 —— 把 0.9.12 的 `skills.dat` 搬进 `action_memory`（0.9.16）。
 *
 * ## 为什么要搬
 *
 * `habit/` 的技能是**文件**里的（`filesDir/habit/skills.dat`），`memory/` 的记忆是
 * Room 里的。合并之后只剩一条学习线，老用户已经「收下」的动作不能凭空消失 ——
 * 那等于升级一次把她学会的东西全忘了。
 *
 * ## 为什么不清掉老文件
 *
 * 蓝图里原来写的是「导入完把目录改名 `habit.migrated`」。实现时改了主意：
 *
 *  - 批次 A 不动 UI，设置页那个旧列表读的还是 `skills.dat`。目录一改名，
 *    用户升级后会看到「学会的动作」整块空了 —— 明明昨天还能点着执行。
 *  - 老文件的执行通道（`HabitSkillBridge`）是**独立于记忆**的，保留它零风险；
 *    真要下线，等批次 B 把设置页接到 `MemoryAcceptance` 上再一起删。
 *
 * 所以这里只做**只读导入**：`skills.dat` 一行不动，导入按 `memoryKey` 幂等。
 *
 * ## 幂等靠什么
 *
 * `memoryKey = 指纹|意图键`，两个都由技能内容决定（包名 + 名字），不掺时间戳。
 * 所以每次启动重跑一遍导入是安全的：已存在的行直接跳过，不会翻倍。
 * 这比「导一次写个标记位」稳 —— 标记位写失败就永远导不进来，而重跑只是几次读库。
 */
object LegacyHabitImporter {

    private const val TAG = "MemDebug"

    /** 老技能最多导多少条（正常用户个位数；上限只是防脏数据） */
    const val MAX_IMPORT = 60

    /**
     * 技能 → 记忆行（纯函数，单测直接钉）。
     *
     * 状态映射：
     *  - `active`（用户已收下）→ `active`，并把名字填进 [ActionMemory.label]
     *    —— 这一步很关键：`origin=user` 没有 label 就不许回放，老数据必须带上名字。
     *  - `candidate`（还没收下）→ `candidate`，label 留空，等用户命名。
     *  - `rejected`（用户说不要）→ `rejected` —— **不能**映射成 `revoked`：
     *    revoked 会被下一晚的成功轨迹「重新攒回 candidate」，
     *    而用户明确拒绝过的东西不该自己长回来。
     *
     * @param existingKeys 库里已有的 `memoryKey`，命中的跳过
     */
    fun plan(skills: List<HabitSkill>, existingKeys: Set<String>, now: Long): List<ActionMemory> {
        val out = ArrayList<ActionMemory>(skills.size)
        val seen = HashSet<String>(existingKeys)
        for (skill in skills.take(MAX_IMPORT)) {
            val row = rowOf(skill, now) ?: continue
            if (row.memoryKey in seen) continue
            seen.add(row.memoryKey)
            out.add(row)
        }
        return out
    }

    /** 单条转换；转不了（没包名 / 没可点的字 / 已经关掉学习）返回 null */
    fun rowOf(skill: HabitSkill, now: Long): ActionMemory? {
        if (skill.pkg.isBlank()) return null
        val name = skill.name.trim().ifBlank { skill.pkg }
        val labels = skill.steps.map { it.label.trim() }.filter { it.isNotEmpty() }
        // 一步都点不着的东西不是动作，是一条描述
        if (labels.size < 2) return null

        val state = when (skill.state) {
            HabitSkill.STATE_ACTIVE -> ActionMemory.STATE_ACTIVE
            HabitSkill.STATE_REJECTED -> ActionMemory.STATE_REJECTED
            else -> ActionMemory.STATE_CANDIDATE
        }
        val intentKey = IntentKey.normalize(name).ifBlank { skill.pkg }
        val fingerprint = AccessibilityTraceAdapter.fingerprintOf(skill.pkg)
        val occurrences = skill.occurrences.coerceAtLeast(1)
        val screenKeys = ScreenFingerprint.keyString(
            skill.steps.mapNotNull {
                val n = ScreenFingerprint.normalize(it.screen)
                if (n.isEmpty()) null else "t:$n"
            }.distinct()
        )

        return ActionMemory(
            memoryKey = MemoryConsolidator.memoryKeyOf(fingerprint, intentKey),
            intent = name.take(RawTrace.INTENT_MAX),
            intentKey = intentKey,
            fingerprint = fingerprint,
            screenKeys = screenKeys,
            pkg = skill.pkg,
            origin = ActionMemory.ORIGIN_USER,
            // 已经收下的才有名字；候选等用户起名
            label = if (state == ActionMemory.STATE_ACTIVE) name.take(ActionMemory.LABEL_MAX) else "",
            coverage = 0f, // 当年没算过覆盖率，填 0 表示「未知」，不伪造成 1.0
            steps = ActionStep.encodeAll(labels.map { AccessibilityTraceAdapter.clickOf(it) }),
            successCount = occurrences,
            state = state,
            weight = MemoryWeight.scoreOf(0, occurrences, 0, skill.lastAt, now),
            // **不沿用老技能的 createdAt**：那条路可能是半年前学会的，
            // 直接搬过来的话，导入当天就会被「30 天没命中 → 过期」的衰减扫掉，
            // 用户刚升级完就发现「她学会的动作全没了」。导入算重新入册，从今天起算。
            createdAt = now,
            updatedAt = now
        )
    }

    /**
     * 启动时跑一次（后台线程）。
     *
     * @return 本次实际导入条数（已存在的跳过，所以第二次起是 0）
     */
    suspend fun importIfNeeded(context: Context): Int {
        val app = context.applicationContext ?: context
        if (!CopilotConfig.isMemoryEnabled(app)) return 0
        val skills = runCatching { HabitSkillStore.load(app) }.getOrElse {
            Log.w(TAG, "[导入] 读老技能失败：${it.message}")
            return 0
        }
        if (skills.isEmpty()) return 0
        val dao = AppDatabase.get(app).actionMemoryDao()
        val existing = runCatching { dao.allKeys().toHashSet() }.getOrElse {
            Log.w(TAG, "[导入] 读现有记忆键失败：${it.message}")
            return 0
        }
        val rows = plan(skills, existing, System.currentTimeMillis())
        if (rows.isEmpty()) {
            Log.i(TAG, "[导入] 老技能 ${skills.size} 条全部已在库里，跳过")
            return 0
        }
        var imported = 0
        for (row in rows) {
            runCatching { dao.upsert(row) }
                .onSuccess { imported++ }
                .onFailure { Log.w(TAG, "[导入] 写失败「${row.intent}」：${it.message}") }
        }
        Log.i(
            TAG,
            "[导入] 老技能 ${skills.size} 条 → 导入 $imported 条（origin=user，" +
                "已收下 ${rows.count { it.state == ActionMemory.STATE_ACTIVE }} 条）"
        )
        return imported
    }
}
