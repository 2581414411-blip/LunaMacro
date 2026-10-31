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

    @Query("DELETE FROM long_term_memory")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM long_term_memory")
    suspend fun count(): Int
}
