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
 * 各表各管一层，不要混着用：
 *  - [PersonaProfile]   我是谁（单行档案）
 *  - [ChatHistory]      我说过什么（原始流水，会被裁剪）
 *  - [MemoryEntity]     我记住了什么（提炼后的事实，长期保留）
 *  - [TopicSeed]        我该主动问他什么（计划/身体/情绪，0.9.4）
 *  - [WeeklyHighlight]  这一周印象最深的事（规则抽取的周记，0.9.0）
 *  - [MistakeLog]       我怎么搞砸的（错题流水，会被裁剪）
 *  - [MistakeNote]      我怎么才能不搞砸（提炼后的经验教训，长期保留）
 *
 * 版本策略：
 *  - v1（0.7.12）前三张表的初版，字段很少；
 *  - v2（0.8.0）`persona_profile` 增加口癖 + 三个性格维度，写 [MIGRATION_1_2] 保住老数据；
 *  - v3（0.8.0）新增错题本两张表，写 [MIGRATION_2_3]（纯建表，不动老表）；
 *  - v4（0.9.0）FlexiMem 新增周记表 `weekly_highlight`，写 [MIGRATION_3_4]（纯建表）；
 *  - v5（0.9.4）新增话题种子表 `topic_seed`，写 [MIGRATION_4_5]（纯建表）。
 *
 * 已经装过 0.7.12 的用户库在 v1，如果直接改实体不写迁移，Room 会抛
 * `IllegalStateException: Room cannot verify the data integrity`。
 * 所以这里必须显式迁移。
 *
 * **0.9.4 起不再用 `fallbackToDestructiveMigration`**：那条兜底看着安全，实际是
 * 「迁移漏写就把用户七八个月的聊天记录、好感度、记忆全部静默清空」——用户只会发现
 * 「她怎么不认识我了」，日志里什么都没有。现在宁可启动时抛异常（有栈、能定位），
 * 也不要悄悄抹掉数据。每加一版实体就必须补一条对应迁移，缺了会在本机测试时立刻炸出来。
 */
@Database(
    entities = [
        PersonaProfile::class,
        ChatHistory::class,
        MemoryEntity::class,
        MistakeLog::class,
        MistakeNote::class,
        WeeklyHighlight::class,
        TopicSeed::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personaProfileDao(): PersonaProfileDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun mistakeDao(): MistakeDao
    abstract fun weeklyHighlightDao(): WeeklyHighlightDao
    abstract fun topicSeedDao(): TopicSeedDao

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
                Log.i(TAG, "数据库实例已创建（$DB_NAME v4）")
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // 0.9.4 起不设 fallbackToDestructiveMigration：宁可崩，也不要静默清库
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

        /**
         * v3 → v4：FlexiMem 第三层，新增周记表（0.9.0 批次二）。
         *
         * 纯 CREATE，不动任何老表 —— 老用户的人格档案、聊天记录、长期记忆、
         * 错题本一条都不会丢。这一步就是「升级不做破坏性迁移」的保证：
         * 只要这里写对，[fallbackToDestructiveMigration] 永远不会被触发。
         *
         * SQL 与实体生成的 schema 逐字对齐（列顺序 / NOT NULL / AUTOINCREMENT / 索引名）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `weekly_highlight` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`weekStart` INTEGER NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_weekly_highlight_weekStart_content` " +
                        "ON `weekly_highlight` (`weekStart`, `content`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_weekly_highlight_weekStart` " +
                        "ON `weekly_highlight` (`weekStart`)"
                )
            }
        }

        /**
         * v4 → v5：话题种子表（0.9.4 批次二）。
         *
         * 纯 CREATE，不动任何老表。SQL 与实体生成 schema 逐字对齐 ——
         * 注意 `content` 是**唯一索引**（同一条话题重复抽取时靠 INSERT OR IGNORE 幂等），
         * 索引名必须是 `index_topic_seed_content`，写错名字 Room 校验时会直接抛。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `topic_seed` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`askCount` INTEGER NOT NULL, " +
                        "`lastAskedAt` INTEGER NOT NULL, " +
                        "`expireAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_topic_seed_content` " +
                        "ON `topic_seed` (`content`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_topic_seed_expireAt` " +
                        "ON `topic_seed` (`expireAt`)"
                )
            }
        }
    }
}
