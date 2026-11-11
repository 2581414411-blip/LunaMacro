package com.nexus.agent.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * 人格档案读写。
 *
 * 全部 suspend：Room 不允许主线程查询，调用方统一用协程，
 * 免得像 SharedPreferences 那样出现「主线程 apply 完还不确定写没写下去」的模糊地带。
 */
@Dao
interface PersonaProfileDao {

    @Query("SELECT * FROM persona_profile WHERE id = :id")
    suspend fun get(id: Long): PersonaProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: PersonaProfile)

    @Query("UPDATE persona_profile SET affinity = :affinity, updatedAt = :at WHERE id = :id")
    suspend fun updateAffinity(id: Long, affinity: Int, at: Long)

    @Query("UPDATE persona_profile SET mood = :mood, updatedAt = :at WHERE id = :id")
    suspend fun updateMood(id: Long, mood: String, at: Long)

    @Query("DELETE FROM persona_profile")
    suspend fun clear()
}

/**
 * 对话历史读写。
 *
 * [recent] 与 [recentDialogue] 都按 (createdAt, id) 倒序取，返回给调用方时再反转，
 * 这样即使两条消息时间戳相同（同一毫秒写入），顺序也是稳的。
 */
@Dao
interface ChatHistoryDao {

    @Insert
    suspend fun insert(row: ChatHistory): Long

    /** 最近 [limit] 条（含动作气泡），倒序 */
    @Query("SELECT * FROM chat_history ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ChatHistory>

    /** 最近 [limit] 条**对话**（只要 user/assistant，不含动作气泡），倒序 */
    @Query(
        "SELECT * FROM chat_history WHERE role IN ('user', 'assistant') " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentDialogue(limit: Int): List<ChatHistory>

    @Query("SELECT COUNT(*) FROM chat_history")
    suspend fun count(): Int

    @Query("DELETE FROM chat_history")
    suspend fun clear()

    @Query("DELETE FROM chat_history WHERE id NOT IN (SELECT id FROM chat_history ORDER BY createdAt DESC, id DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)
}

/**
 * 长期记忆读写。
 *
 * [top] 按权重取，是 0.8.0 组装 prompt 时召回记忆的主入口；
 * 现在先把表建好并预埋查询，等提取器（LLM 抽事实）接上就能直接用。
 */
@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM long_term_memory ORDER BY weight DESC, createdAt DESC LIMIT :limit")
    suspend fun top(limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM long_term_memory WHERE category = :category " +
            "ORDER BY weight DESC, createdAt DESC LIMIT :limit"
    )
    suspend fun byCategory(category: String, limit: Int): List<MemoryEntity>

    /** 命中后刷新召回时间（遗忘曲线的依据） */
    @Query("UPDATE long_term_memory SET lastHitAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM long_term_memory WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * 只保留权重最高的 [keep] 条。
     *
     * 事实是只增不减的，聊半年能攒出上千条；不裁剪的话 `top(10)` 每次都要排序整表，
     * 而真正有价值的永远是权重高、时间新的那批。
     */
    @Query(
        "DELETE FROM long_term_memory WHERE id NOT IN (" +
            "SELECT id FROM long_term_memory ORDER BY weight DESC, createdAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM long_term_memory")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM long_term_memory")
    suspend fun count(): Int
}

/**
 * 错题本读写（0.8.0）。
 *
 * 两条查询最重要：
 *  - [countOf] 判断「同类错误是不是已经够 3 次」，够才值得花一次 LLM 调用去总结；
 *  - [topNotes] 是注入提示词的入口，按「犯得多 + 最近更新」排序，
 *    犯 10 次的坑显然比只犯 1 次的更该被提醒。
 */
@Dao
interface MistakeDao {

    @Insert
    suspend fun log(row: MistakeLog): Long

    /** 某个指纹累计犯了几次 */
    @Query("SELECT COUNT(*) FROM mistake_log WHERE signature = :signature")
    suspend fun countOf(signature: String): Int

    /** 某个指纹最近的若干条流水（按时间倒序），给反思提示词当证据 */
    @Query(
        "SELECT * FROM mistake_log WHERE signature = :signature " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentOf(signature: String, limit: Int): List<MistakeLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: MistakeNote): Long

    @Query("SELECT * FROM mistake_note WHERE signature = :signature LIMIT 1")
    suspend fun noteOf(signature: String): MistakeNote?

    @Query("SELECT * FROM mistake_note ORDER BY hits DESC, updatedAt DESC LIMIT :limit")
    suspend fun topNotes(limit: Int): List<MistakeNote>

    /** 只保留最近的 [keep] 条流水：流水是耗材，教训才是资产 */
    @Query(
        "DELETE FROM mistake_log WHERE id NOT IN (" +
            "SELECT id FROM mistake_log ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimLogs(keep: Int)

    @Query("SELECT COUNT(*) FROM mistake_log")
    suspend fun logCount(): Int

    @Query("SELECT COUNT(*) FROM mistake_note")
    suspend fun noteCount(): Int

    @Query("DELETE FROM mistake_log")
    suspend fun clearLogs()

    @Query("DELETE FROM mistake_note")
    suspend fun clearNotes()
}

/**
 * 话题种子读写（0.9.4）。
 *
 * 只有 [nextCandidate] 是「业务核心」：心跳每轮都问它一次「有没有该关心的事」。
 * 排序刻意写成 `askCount ASC, createdAt ASC`：
 *  - 先问没问过的（问过的可能已经聊透了）；
 *  - 同档里先问先说的（「明天体检」比「下周出差」更急）。
 *
 * [purgeExpired] 每次心跳顺手调一次：过期种子不清，表会变成事后的笑话集
 * （「你头还疼吗」——那是三周前的头疼）。
 */
@Dao
interface TopicSeedDao {

    /** 内容唯一，重复抽取同一条话题靠 IGNORE 幂等 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(seed: TopicSeed): Long

    /**
     * 挑一条该问的种子。
     *
     * @param nowMs       现在（用来过滤过期）
     * @param reAskBefore 早于这个时间问过的才允许再问（= now - 48h）
     */
    @Query(
        "SELECT * FROM topic_seed " +
            "WHERE (expireAt = 0 OR expireAt > :nowMs) " +
            "AND (askCount = 0 OR lastAskedAt <= :reAskBefore) " +
            "ORDER BY askCount ASC, createdAt ASC LIMIT 1"
    )
    suspend fun nextCandidate(nowMs: Long, reAskBefore: Long): TopicSeed?

    /** 还没问过的种子（调试 / 设置页展示用） */
    @Query(
        "SELECT * FROM topic_seed WHERE askCount = 0 AND (expireAt = 0 OR expireAt > :nowMs) " +
            "ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun fresh(nowMs: Long, limit: Int = 10): List<TopicSeed>

    @Query("UPDATE topic_seed SET askCount = askCount + 1, lastAskedAt = :at WHERE id = :id")
    suspend fun markAsked(id: Long, at: Long)

    @Query("DELETE FROM topic_seed WHERE expireAt > 0 AND expireAt <= :nowMs")
    suspend fun purgeExpired(nowMs: Long)

    /** 只留最新的 [keep] 条（按加入时间），防止表无限长 */
    @Query(
        "DELETE FROM topic_seed WHERE id NOT IN (" +
            "SELECT id FROM topic_seed ORDER BY createdAt DESC LIMIT :keep)"
    )
    suspend fun trimTo(keep: Int)

    @Query("SELECT COUNT(*) FROM topic_seed")
    suspend fun count(): Int

    @Query("DELETE FROM topic_seed")
    suspend fun clear()
}

/**
 * 周记读写（0.9.0 FlexiMem 第三层）。
 *
 * 只有三条查询是有意义的：[insert]（同周同内容靠唯一索引幂等）、
 * [latest]（注入提示词用）、[lastWeekStart]（判断「这周的周记做没做」）。
 */
@Dao
interface WeeklyHighlightDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: WeeklyHighlight): Long

    /** 最近 [limit] 条（按周倒序、周内按生成顺序），注入 Prompt 用 */
    @Query("SELECT * FROM weekly_highlight ORDER BY weekStart DESC, id ASC LIMIT :limit")
    suspend fun latest(limit: Int): List<WeeklyHighlight>

    /** 最新一周的 items（周记注入只认最近做完的那一周） */
    @Query("SELECT * FROM weekly_highlight WHERE weekStart = :weekStart ORDER BY id ASC")
    suspend fun byWeek(weekStart: Long): List<WeeklyHighlight>

    /** 最近一次生成过周记的周起始时间；null = 从来没生成过 */
    @Query("SELECT MAX(weekStart) FROM weekly_highlight")
    suspend fun lastWeekStart(): Long?

    /** 只保留最近 [keep] 周：周记是长期资产，但也没必要留十年 */
    @Query(
        "DELETE FROM weekly_highlight WHERE weekStart NOT IN (" +
            "SELECT DISTINCT weekStart FROM weekly_highlight ORDER BY weekStart DESC LIMIT :keep)"
    )
    suspend fun trimWeeks(keep: Int)

    @Query("DELETE FROM weekly_highlight")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM weekly_highlight")
    suspend fun count(): Int
}

/**
 * 内心独白读写（0.9.6 心智引擎）。
 *
 * 所有查询都按 `createdAt DESC`：内心戏只有「最近的」有意义，
 * 三天前想的什么对现在的语气没有帮助。
 */
@Dao
interface InnerThoughtDao {

    @Insert
    suspend fun insert(row: InnerThought): Long

    /** 最新一条（不管用没用过）—— UI 上「她刚才在想」用它 */
    @Query("SELECT * FROM inner_thought ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(): InnerThought?

    /**
     * 最新一条**还没被用掉**的独白。
     *
     * 对话注入只认这条：用过的再注入，她就会把同一件事翻来覆去说，
     * 那是最容易被察觉的「假」。
     */
    @Query("SELECT * FROM inner_thought WHERE consumed = 0 ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestUnconsumed(): InnerThought?

    @Query("SELECT * FROM inner_thought ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<InnerThought>

    /** 标记「这句已经用过了」 */
    @Query("UPDATE inner_thought SET consumed = 1 WHERE id = :id")
    suspend fun markConsumed(id: Long)

    /** 只留最近 [keep] 条 */
    @Query(
        "DELETE FROM inner_thought WHERE id NOT IN (" +
            "SELECT id FROM inner_thought ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    /** 上一次想事情的时间戳；null = 从来没生成过（第一次跑不该被「冷却」拦住） */
    @Query("SELECT MAX(createdAt) FROM inner_thought")
    suspend fun lastAt(): Long?

    @Query("DELETE FROM inner_thought")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM inner_thought")
    suspend fun count(): Int
}

// ==================== 0.9.13 外挂记忆 ====================

/**
 * 任务流水读写。
 *
 * 只有 [insert]（每次任务一行）、[recent]（调试与再加工）和 [trim]（防膨胀）三个动作，
 * 没有「按条件查历史」的需求 —— 需要被检索的是提炼后的资产，不是流水本身。
 */
@Dao
interface TaskEpisodeDao {

    @Insert
    suspend fun insert(row: TaskEpisode): Long

    /** 最近 [limit] 条（倒序） */
    @Query("SELECT * FROM task_episode ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TaskEpisode>

    /** 同一意图的最近若干条（重新提炼时看历史上怎么失败的） */
    @Query(
        "SELECT * FROM task_episode WHERE intentKey = :intentKey " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recentOfIntent(intentKey: String, limit: Int): List<TaskEpisode>

    @Query("SELECT COUNT(*) FROM task_episode")
    suspend fun count(): Int

    /** 只留最近 [keep] 条 */
    @Query(
        "DELETE FROM task_episode WHERE id NOT IN (" +
            "SELECT id FROM task_episode ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM task_episode")
    suspend fun clear()
}

/**
 * 动作记忆读写。
 *
 * 关键查询是 [byPkg]：意图归一化做不到「同义句完全相等」，
 * 所以真实匹配流程是「先按包名捞一小撮候选，再在内存里算意图相似度」。
 * 捞的时候一定要限条数 —— 回放判断发生在任务最前端，不能让它在主流程上扫全表。
 */
@Dao
interface ActionMemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ActionMemory): Long

    @Query("SELECT * FROM action_memory WHERE memoryKey = :memoryKey LIMIT 1")
    suspend fun byKey(memoryKey: String): ActionMemory?

    @Query("SELECT * FROM action_memory WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ActionMemory?

    /** 同包名下的候选（含各状态），按权重与新近排序 */
    @Query(
        "SELECT * FROM action_memory WHERE pkg = :pkg " +
            "ORDER BY weight DESC, updatedAt DESC LIMIT :limit"
    )
    suspend fun byPkg(pkg: String, limit: Int): List<ActionMemory>

    /** 意图键完全一致的候选 */
    @Query("SELECT * FROM action_memory WHERE intentKey = :intentKey LIMIT :limit")
    suspend fun byIntentKey(intentKey: String, limit: Int): List<ActionMemory>

    /** 当前可拦截的全部记忆（设置页展示 / 衰减核对用） */
    @Query("SELECT * FROM action_memory WHERE state = :state ORDER BY weight DESC, updatedAt DESC LIMIT :limit")
    suspend fun byState(state: String, limit: Int): List<ActionMemory>

    /**
     * 命中：次数、权重、最近使用时间一起更新。
     *
     * `consecutiveFail` 清零放在这里 —— 「命中」指的是**回放成功**，
     * 失败那次不会把连败记录抹掉。
     */
    @Query(
        "UPDATE action_memory SET hits = hits + 1, successCount = successCount + 1, " +
            "consecutiveFail = 0, weight = :weight, lastHitAt = :at, updatedAt = :at " +
            "WHERE id = :id"
    )
    suspend fun markHit(id: Long, at: Long, weight: Float)

    @Query(
        "UPDATE action_memory SET failCount = failCount + 1, consecutiveFail = consecutiveFail + 1, " +
            "weight = :weight, lastFailAt = :at, updatedAt = :at WHERE id = :id"
    )
    suspend fun markFail(id: Long, at: Long, weight: Float)

    /**
     * 又采集到一次成功执行（不是回放命中）。
     *
     * 和 [markHit] 分开：`hits` 只统计「被记忆拦下来直接跑了多少次」，
     * 它才是这个功能省了多少次模型调用的证据。把采集也记进 hits，
     * 屏幕上那个「替你省了 N 次」就会虚高。
     */
    @Query(
        "UPDATE action_memory SET successCount = successCount + 1, consecutiveFail = 0, " +
            "weight = :weight, updatedAt = :at WHERE id = :id"
    )
    suspend fun markLearned(id: Long, at: Long, weight: Float)

    /** 合并后的骨架替换掉旧步骤 */
    @Query("UPDATE action_memory SET steps = :steps, updatedAt = :at WHERE id = :id")
    suspend fun updateSteps(id: Long, steps: String, at: Long)

    @Query("UPDATE action_memory SET state = :state, updatedAt = :at WHERE id = :id")
    suspend fun setState(id: Long, state: String, at: Long)

    /**
     * 达尔文衰减第一刀：超过 [ActionMemory.EXPIRE_DAYS] 天没被命中的，标记过期。
     *
     * 从没被命中过的（lastHitAt = 0）用 createdAt 起算 ——
     * 否则一条学会就再没跑过的记忆会永远赖在拦截名单里。
     *
     * **只标状态，不删行**：过期的记忆重新被跑通一次就能复活，
     * 删了就再也想不起来了。
     *
     * 注：SQL 里的字符串常量写死成字面量，不拼 `ActionMemory.XXX`
     * —— Room 在注解处理阶段就要拿到完整 SQL，拼常量容易在 KSP 那步炸。
     */
    @Query(
        "UPDATE action_memory SET state = 'expired', updatedAt = :now " +
            "WHERE state = 'active' " +
            "AND (CASE WHEN lastHitAt > 0 THEN lastHitAt ELSE createdAt END) < :cutoff"
    )
    suspend fun expireOlderThan(cutoff: Long, now: Long): Int

    /** 连续失败到阈值的一律作废 */
    @Query(
        "UPDATE action_memory SET state = 'revoked', updatedAt = :now " +
            "WHERE state != 'revoked' AND consecutiveFail >= :maxFail"
    )
    suspend fun revokeBroken(maxFail: Int, now: Long): Int

    @Query("SELECT COUNT(*) FROM action_memory WHERE state = :state")
    suspend fun countOf(state: String): Int

    @Query("SELECT COUNT(*) FROM action_memory")
    suspend fun count(): Int

    @Query("DELETE FROM action_memory")
    suspend fun clear()
}

/**
 * 经验记忆读写。
 *
 * [byKey] 是第二级路由的入口：同一个界面 + 同一类任务只留一条经验，
 * 攒多了会互相打架（「先等键盘」和「先滚动」同时注入，模型自己都矛盾）。
 */
@Dao
interface IntentTipDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: IntentTip): Long

    @Query("SELECT * FROM intent_tip WHERE tipKey = :tipKey LIMIT 1")
    suspend fun byKey(tipKey: String): IntentTip?

    /** 某个界面的经验（指纹精确匹配） */
    @Query("SELECT * FROM intent_tip WHERE fingerprint = :fingerprint ORDER BY hits DESC, updatedAt DESC LIMIT :limit")
    suspend fun byFingerprint(fingerprint: String, limit: Int): List<IntentTip>

    /** 注入提示词用的头部若干条（按命中次数） */
    @Query("SELECT * FROM intent_tip ORDER BY hits DESC, updatedAt DESC LIMIT :limit")
    suspend fun top(limit: Int): List<IntentTip>

    @Query("SELECT COUNT(*) FROM intent_tip")
    suspend fun count(): Int

    @Query(
        "DELETE FROM intent_tip WHERE id NOT IN (" +
            "SELECT id FROM intent_tip ORDER BY updatedAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun trim(keep: Int)

    @Query("DELETE FROM intent_tip")
    suspend fun clear()
}
