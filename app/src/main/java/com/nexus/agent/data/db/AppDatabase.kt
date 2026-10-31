package com.nexus.agent.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用唯一的 Room 数据库（`filesDir/databases/luna.db`）。
 *
 * 五张表各管一层，不要混着用：
 *  - [PersonaProfile] 我是谁（单行档案）
 *  - [ChatHistory]    我说过什么（原始流水，会被裁剪）
 *  - [MemoryEntity]   我记住了什么（提炼后的事实，长期保留）
 *  - [MistakeLog]     我怎么搞砸的（错题流水，会被裁剪）
 *  - [MistakeNote]    我怎么才能不搞砸（提炼后的经验教训，长期保留）
 *
 * 版本策略：
 *  - v1（0.7.12）前三张表的初版，字段很少；
 *  - v2（0.8.0）`persona_profile` 增加口癖 + 三个性格维度，写 [MIGRATION_1_2] 保住老数据；
 *  - v3（0.8.0）新增错题本两张表，写 [MIGRATION_2_3]（纯建表，不动老表）。
 *
 * 已经装过 0.7.12 的用户库在 v1，如果直接改实体不写迁移，Room 会抛
 * `IllegalStateException: Room cannot verify the data integrity`。
 * 所以这里必须显式迁移；`fallbackToDestructiveMigration` 只作为最后的兜底
 * （遇到没有迁移路径的版本才会清库，正常情况下不触发）。
 * schema 已导出到 `app/schemas/`，随时能和实体比对。
 */
@Database(
    entities = [
        PersonaProfile::class,
        ChatHistory::class,
        MemoryEntity::class,
        MistakeLog::class,
        MistakeNote::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personaProfileDao(): PersonaProfileDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun mistakeDao(): MistakeDao

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
                Log.i(TAG, "数据库实例已创建（$DB_NAME v3）")
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // 兜底：遇到没有写迁移的版本差，宁可清空也不要一升级就崩在启动页
                .fallbackToDestructiveMigration()
                .build()

        /** 仅供测试/清空数据用：关掉并丢弃单例 */
        @Synchronized
        fun close() {
            runCatching { instance?.close() }
            instance = null
        }

        /**
         * v1 → v2：人格档案补上口癖与性格三维。
         *
         * 全部是「加列 + 给默认值」，不涉及重建表，用 ALTER TABLE 就够，
         * 老用户的聊天记录、好感度、心情都原样保留。
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE persona_profile ADD COLUMN catchphrase TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE persona_profile ADD COLUMN extroversion INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE persona_profile ADD COLUMN sensibility INTEGER NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE persona_profile ADD COLUMN rebelliousness INTEGER NOT NULL DEFAULT 50")
            }
        }

        /**
         * v2 → v3：新增错题本两张表（0.8.0 批次四）。
         *
         * 纯 CREATE，不改任何老表 —— 升级只多两张空表，老用户的聊天记录、
         * 长期记忆、人格档案一条都不会动。
         *
         * SQL 必须和 Room 由实体生成的 schema **逐字对齐**（列顺序、NOT NULL、
         * AUTOINCREMENT、索引名），否则 Room 打开库时会抛
         * `Room cannot verify the data integrity`，比不写迁移还惨。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mistake_log` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`signature` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mistake_log_signature` ON `mistake_log` (`signature`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mistake_log_createdAt` ON `mistake_log` (`createdAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mistake_note` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`signature` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`lesson` TEXT NOT NULL, " +
                        "`hits` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_mistake_note_signature` " +
                        "ON `mistake_note` (`signature`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_mistake_note_updatedAt` " +
                        "ON `mistake_note` (`updatedAt`)"
                )
            }
        }
    }
}
