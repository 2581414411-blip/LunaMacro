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
 *  - [TaskEpisode]      我替他办过什么（任务轨迹流水，成功与失败都记，0.9.13）
 *  - [ActionMemory]     同一件事我怎么做（可回放的肌肉记忆，0.9.13）
 *  - [IntentTip]        这个界面上有什么坑（界面级避坑经验，0.9.13）
 *
 * 版本策略：
 *  - v1（0.7.12）前三张表的初版，字段很少；
 *  - v2（0.8.0）`persona_profile` 增加口癖 + 三个性格维度，写 [MIGRATION_1_2] 保住老数据；
 *  - v3（0.8.0）新增错题本两张表，写 [MIGRATION_2_3]（纯建表，不动老表）；
 *  - v4（0.9.0）FlexiMem 新增周记表 `weekly_highlight`，写 [MIGRATION_3_4]（纯建表）；
 *  - v5（0.9.4）新增话题种子表 `topic_seed`，写 [MIGRATION_4_5]（纯建表）；
 *  - v6（0.9.6）新增内心独白表 `inner_thought`，写 [MIGRATION_5_6]（纯建表）；
 *  - v7（0.9.13）新增外挂记忆三张表 `task_episode` / `action_memory` / `intent_tip`，
 *    写 [MIGRATION_6_7]（纯建表）。
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
        TopicSeed::class,
        InnerThought::class,
        TaskEpisode::class,
        ActionMemory::class,
        IntentTip::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personaProfileDao(): PersonaProfileDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun memoryDao(): MemoryDao
    abstract fun mistakeDao(): MistakeDao
    abstract fun weeklyHighlightDao(): WeeklyHighlightDao
    abstract fun topicSeedDao(): TopicSeedDao
    abstract fun innerThoughtDao(): InnerThoughtDao
    abstract fun taskEpisodeDao(): TaskEpisodeDao
    abstract fun actionMemoryDao(): ActionMemoryDao
    abstract fun intentTipDao(): IntentTipDao

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
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7
                )
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
         * 只要这里写对，破坏性清理就永远不会发生。
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

        /**
         * v5 → v6：内心独白表（0.9.6 心智引擎）。
         *
         * 纯建表，不动任何老表 —— 用户七八个月的聊天记录、好感度、话题种子原样保留。
         * SQL 与 `app/schemas/.../6.json` 逐字一致（Room 会按 schema 校验，
         * 差一个空格或缺一个索引都会在启动时报 `Migration didn't properly handle`）。
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inner_thought` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`content` TEXT NOT NULL, " +
                        "`mood` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`consumed` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inner_thought_createdAt` " +
                        "ON `inner_thought` (`createdAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inner_thought_consumed` " +
                        "ON `inner_thought` (`consumed`)"
                )
            }
        }

        /**
         * v6 → v7：外挂记忆三张表（0.9.13）。
         *
         * 纯 CREATE，不动任何老表 —— 聊天记录、好感度、错题本、习惯画像一条都不丢。
         * SQL 与实体生成的 schema 逐字对齐（列顺序 / NOT NULL / AUTOINCREMENT / 索引名）。
         *
         * 三张表的分层和 `chat_history`→`long_term_memory`、
         * `mistake_log`→`mistake_note` 是同一套：
         *  - `task_episode` 是**流水**（耗材，会裁剪到 200 条）；
         *  - `action_memory` / `intent_tip` 是**资产**（提炼后的可回放动作与避坑经验）。
         *
         * 为什么不把动作步骤拆成子表：一条记忆永远是整条读、整条写，
         * 拆表只多一次 JOIN，还挡不住「半条记忆」被当成完整路径拿去点屏幕。
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_episode` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`intent` TEXT NOT NULL, " +
                        "`intentKey` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`screenKeys` TEXT NOT NULL, " +
                        "`pkg` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL, " +
                        "`outcome` TEXT NOT NULL, " +
                        "`failStep` INTEGER NOT NULL, " +
                        "`detail` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_episode_intentKey` ON `task_episode` (`intentKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_episode_fingerprint` ON `task_episode` (`fingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_episode_createdAt` ON `task_episode` (`createdAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `action_memory` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`memoryKey` TEXT NOT NULL, " +
                        "`intent` TEXT NOT NULL, " +
                        "`intentKey` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`screenKeys` TEXT NOT NULL, " +
                        "`pkg` TEXT NOT NULL, " +
                        "`steps` TEXT NOT NULL, " +
                        "`hits` INTEGER NOT NULL, " +
                        "`successCount` INTEGER NOT NULL, " +
                        "`failCount` INTEGER NOT NULL, " +
                        "`consecutiveFail` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`weight` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastHitAt` INTEGER NOT NULL, " +
                        "`lastFailAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_action_memory_memoryKey` " +
                        "ON `action_memory` (`memoryKey`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_memory_state` ON `action_memory` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_memory_lastHitAt` ON `action_memory` (`lastHitAt`)")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `intent_tip` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`tipKey` TEXT NOT NULL, " +
                        "`fingerprint` TEXT NOT NULL, " +
                        "`intentKey` TEXT NOT NULL, " +
                        "`pkg` TEXT NOT NULL, " +
                        "`tip` TEXT NOT NULL, " +
                        "`hits` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_intent_tip_tipKey` " +
                        "ON `intent_tip` (`tipKey`)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_intent_tip_updatedAt` ON `intent_tip` (`updatedAt`)")
            }
        }
    }
}
