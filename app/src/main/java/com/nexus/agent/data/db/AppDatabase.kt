package com.nexus.agent.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 应用唯一的 Room 数据库（`filesDir/databases/luna.db`）。
 *
 * 三张表各管一层，不要混着用：
 *  - [PersonaProfile] 我是谁（单行档案）
 *  - [ChatHistory]    我说过什么（原始流水，会被裁剪）
 *  - [MemoryEntity]   我记住了什么（提炼后的事实，长期保留）
 *
 * 版本策略：现在还是 v1，结构两个月内肯定要动，
 * 先用 [fallbackToDestructiveMigration] 保证升级不崩（代价是清空本地聊天记录）；
 * 等 0.8.x 结构稳定了，必须改成写 [androidx.room.migration.Migration]，
 * schema 已经通过 `ksp { arg("room.schemaLocation", ...) }` 导出到 `app/schemas/`，随时能比对。
 */
@Database(
    entities = [PersonaProfile::class, ChatHistory::class, MemoryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personaProfileDao(): PersonaProfileDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun memoryDao(): MemoryDao

    companion object {

        private const val TAG = "AppDatabase"
        private const val DB_NAME = "luna.db"

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * 取单例。
         *
         * build 本身是懒的（不 open、不建表），所以可以在 [com.nexus.agent.NexusApplication]
         * 的 onCreate 里直接调；真正的磁盘 IO 发生在第一次查询时。
         */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also {
                instance = it
                Log.i(TAG, "数据库实例已创建（$DB_NAME v1）")
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                // v1 阶段结构还在动：宁可清空也不要一升级就崩在启动页
                .fallbackToDestructiveMigration()
                .build()

        /** 仅供测试/清空数据用：关掉并丢弃单例 */
        @Synchronized
        fun close() {
            runCatching { instance?.close() }
            instance = null
        }
    }
}
