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
