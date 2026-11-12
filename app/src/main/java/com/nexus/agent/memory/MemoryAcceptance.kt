package com.nexus.agent.memory

import android.content.Context
import android.util.Log
import com.nexus.agent.copilot.CopilotConfig
import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.AppDatabase

/**
 * 「收下并命名」—— 手动学到的动作唯一的转正入口（0.9.16）。
 *
 * ## 为什么必须由人命名
 *
 * 无障碍事件里**没有意图**：用户点「发送」可能是要发消息，也可能是翻记录时手滑。
 * 程序无从判断这串点击「想干什么」，所以不能替用户决定它该在什么时候回放。
 * 名字是唯一的意图来源，也是日后路由能命中的唯一依据 ——
 * 用户给它起名「每天早上签到」，之后说这句话时她才知道该走这条路。
 *
 * ## 为什么保留 memoryKey 不变
 *
 * `memoryKey = 指纹|建议名`，是**记忆的身份证**，用来判断「这条我是不是已经有了」。
 * 用户改名之后如果连 key 一起改，下一夜巩固按建议名算出的 key 就找不到这一行，
 * 会再长出一个一模一样的候选 —— 用户会看到自己刚收下的东西又冒出来一遍。
 * 所以改名只动 `label` / `intent` / `intentKey`（这三个决定「怎么被叫到」），
 * 不动 `memoryKey`（它只决定「是不是同一条」）。
 *
 * ## 为什么拒绝要用 `rejected` 而不是 `revoked`
 *
 * `revoked` 的语义是「自己跑挂了」，会在一晚成功轨迹之后重新攒回 candidate；
 * 用户主动说「不要这个」的东西不该自己长回来。这是两个不同的意思，
 * 用两个状态，而不是在同一条 SQL 里靠注释区分。
 */
object MemoryAcceptance {

    private const val TAG = "MemDebug"

    /** 设置页一次最多列几个候选 */
    const val CANDIDATE_LIMIT = 20

    /** 候选列表（按最近活跃排序） */
    suspend fun candidates(context: Context?, limit: Int = CANDIDATE_LIMIT): List<ActionMemory> {
        val app = context?.applicationContext ?: return emptyList()
        if (!CopilotConfig.isMemoryEnabled(app)) return emptyList()
        return runCatching {
            AppDatabase.get(app).actionMemoryDao()
                .byOriginState(ActionMemory.ORIGIN_USER, ActionMemory.STATE_CANDIDATE, limit)
        }.onFailure { Log.w(TAG, "[收下] 读候选失败：${it.message}") }.getOrDefault(emptyList())
    }

    /** 已收下的动作（设置页「她学会的动作」用） */
    suspend fun accepted(context: Context?, limit: Int = CANDIDATE_LIMIT): List<ActionMemory> {
        val app = context?.applicationContext ?: return emptyList()
        if (!CopilotConfig.isMemoryEnabled(app)) return emptyList()
        return runCatching {
            AppDatabase.get(app).actionMemoryDao()
                .byOriginState(ActionMemory.ORIGIN_USER, ActionMemory.STATE_ACTIVE, limit)
        }.onFailure { Log.w(TAG, "[收下] 读已收下失败：${it.message}") }.getOrDefault(emptyList())
    }

    /**
     * 收下并命名。
     *
     * @return true = 真的生效了；false = 名字为空 / 不是手动学的 / 已被拒绝过
     */
    suspend fun accept(context: Context?, id: Long, label: String?): Boolean {
        val app = context?.applicationContext ?: return false
        if (!CopilotConfig.isMemoryEnabled(app)) return false
        val dao = AppDatabase.get(app).actionMemoryDao()
        val old = runCatching { dao.byId(id) }.getOrNull() ?: return false
        val next = acceptRow(old, label, System.currentTimeMillis()) ?: run {
            Log.i(TAG, "[收下] 拒绝：id=$id 名字为空或不是手动学到的动作")
            return false
        }
        return runCatching {
            dao.upsert(next)
            Log.i(
                TAG,
                "[收下] id=$id 命名为「${next.label}」→ active，" +
                    "${ActionStep.decodeAll(next.steps).size} 步，之后说这句话就能直接走"
            )
            true
        }.getOrElse {
            Log.w(TAG, "[收下] 写库失败：${it.message}")
            false
        }
    }

    /**
     * 「不要这个」。
     *
     * 只标状态不删行：删了的话，下一夜巩固会从轨迹池里重新聚出一个一模一样的候选，
     * 用户会觉得「我说了不要它怎么又回来了」。留着 `rejected` 就是记号。
     */
    suspend fun reject(context: Context?, id: Long): Boolean {
        val app = context?.applicationContext ?: return false
        val dao = AppDatabase.get(app).actionMemoryDao()
        val old = runCatching { dao.byId(id) }.getOrNull() ?: return false
        if (!old.isUserOrigin) return false
        return runCatching {
            dao.upsert(old.copy(state = ActionMemory.STATE_REJECTED, updatedAt = System.currentTimeMillis()))
            Log.i(TAG, "[收下] id=$id「${old.intent}」→ rejected（不会再长候选）")
            true
        }.getOrElse {
            Log.w(TAG, "[收下] 标记不要失败：${it.message}")
            false
        }
    }

    /**
     * 收下这条记忆之后应该长什么样（纯函数，单测直接钉）。
     *
     * 拒绝的情形：名字空、不是手动路径、用户已经拒绝过、没有可回放的步骤。
     */
    internal fun acceptRow(old: ActionMemory, rawLabel: String?, now: Long): ActionMemory? {
        if (!old.isUserOrigin) return null
        if (old.state == ActionMemory.STATE_REJECTED) return null
        if (old.steps.isBlank()) return null
        val label = sanitizeLabel(rawLabel)
        if (label.isEmpty()) return null
        val key = IntentKey.normalize(label)
        val weight = MemoryWeight.scoreOf(old.hits, old.successCount, old.failCount, old.lastHitAt, now)
        return old.copy(
            label = label,
            intent = label,
            intentKey = key.ifBlank { old.intentKey },
            state = ActionMemory.STATE_ACTIVE,
            consecutiveFail = 0,
            weight = weight,
            updatedAt = now
        )
    }

    /**
     * 名字清洗：压空白、去换行、截断。
     *
     * 不剥离任何字符 —— 用户想起「早上签到（微信）」就让他这么叫，
     * 反正意图归一化的时候标点会被抹掉，不影响命中。
     */
    internal fun sanitizeLabel(raw: String?): String =
        raw.orEmpty().trim().replace(Regex("\\s+"), " ").take(ActionMemory.LABEL_MAX).trim()
}
