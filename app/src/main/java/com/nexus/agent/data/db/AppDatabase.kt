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
 *  - [RawTrace]         我白天都经历了什么（原始轨迹池，只记账不加工，0.9.14）
 *  - [ActionMemory]     同一件事我怎么做（可回放的肌肉记忆，0.9.13）
 *  - [IntentTip]        这个界面上有什么坑（界面级避坑经验，0.9.13）
 *  - [PadState]         我此刻是什么心情（单行情感状态，0.9.15）
 *  - [RoomItem]         我屋里摆了什么（赛博房间家具，0.9.17）
 *  - [RoomTheme]        我的屋子长什么样（单行主题，0.9.17）
 *
 * 版本策略：
 *  - v1（0.7.12）前三张表的初版，字段很少；
 *  - v2（0.8.0）`persona_profile` 增加口癖 + 三个性格维度，写 [MIGRATION_1_2] 保住老数据；
 *  - v3（0.8.0）新增错题本两张表，写 [MIGRATION_2_3]（纯建表，不动老表）；
 *  - v4（0.9.0）FlexiMem 新增周记表 `weekly_highlight`，写 [MIGRATION_3_4]（纯建表）；
 *  - v5（0.9.4）新增话题种子表 `topic_seed`，写 [MIGRATION_4_5]（纯建表）；
 *  - v6（0.9.6）新增内心独白表 `inner_thought`，写 [MIGRATION_5_6]（纯建表）；
 *  - v7（0.9.13）新增外挂记忆三张表 `task_episode` / `action_memory` / `intent_tip`，
 *    写 [MIGRATION_6_7]（纯建表）；
 *  - v8（0.9.14）`task_episode` 升级为 `raw_trace`（原始轨迹池，加 kind / memoryId /
 *    batchId / processedAt），写 [MIGRATION_7_8]：**先把老流水整批搬进新表再删旧表**，
 *    老数据标记为「已巩固」（`processedAt = createdAt`），否则新版第一次巩固会把
 *    0.9.13 已经学到的东西再学一遍，成功次数直接翻倍。
 *  - v9（0.9.15）情感系统重构的数据层：新建 `pad_state`（单行情感状态）、
 *    `long_term_memory` 加 5 列情感指纹（pad 三轴 / 强度 / 关键词），
 *    写 [MIGRATION_8_9]。老记忆全部落成中性值，打分时不产生任何偏置。
 *  - v10（0.9.16）认知闭环合并：`action_memory` 加 `origin`（数据来源）/
 *    `label`（用户给的名字）/ `coverage`（骨架覆盖率），写 [MIGRATION_9_10]。
 *    老行一律落成 `origin=agent` —— 0.9.15 之前这张表里只有她自己跑出来的动作，
 *    没有手动路径，所以默认值就是事实，不需要额外推断。
 *  - v11（0.9.17）赛博房间：新增 `room_item`（家具）与 `room_theme`（主题，单行），
 *    写 [MIGRATION_10_11]（纯建表）。这是第一版「她有自己的空间」的数据落点，
 *    表是空的 —— 首次进屋时由 `room/RoomStore` 铺一套默认家具。
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
        RawTrace::class,
        ActionMemory::class,
        IntentTip::class,
        PadState::class,
        RoomItem::class,
        RoomTheme::class
    ],
    version = 11,
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
    abstract fun rawTraceDao(): RawTraceDao
    abstract fun actionMemoryDao(): ActionMemoryDao
    abstract fun intentTipDao(): IntentTipDao
    abstract fun padStateDao(): PadStateDao
    abstract fun roomItemDao(): RoomItemDao
    abstract fun roomThemeDao(): RoomThemeDao

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
                Log.i(TAG, "数据库实例已创建（$DB_NAME v11）")
            }
        }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11
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

        /**
         * v7 → v8：`task_episode` 换成 `raw_trace`（0.9.14 白天只记账、夜里批量巩固）。
         *
         * ## 老流水怎么处理
         *
         * 0.9.13 是边跑边学：`task_episode` 里每一条其实**已经被加工过了**
         * （成功的那条已经写进 `action_memory.successCount`，失败的那条已经出过经验）。
         * 所以搬家时统一写成 `processedAt = createdAt`、`batchId = -1`（历史批），
         * 让它们一进新表就是「已巩固」状态。
         *
         * 如果偷懒写成 0，新版第一夜会把 0.9.13 学过的样本全部重学一遍：
         * 成功次数翻倍，两条就转正——**这恰好是这一版要杜绝的「记忆污染」**。
         *
         * ## 为什么要删旧表
         *
         * 留着它就有两份真相：一张表在写、一张表在长毛，过几个月没人记得哪张是活的。
         * 数据已经完整搬进 `raw_trace`，删掉不丢任何东西。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `raw_trace` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
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
                        "`memoryId` INTEGER NOT NULL, " +
                        "`batchId` INTEGER NOT NULL, " +
                        "`processedAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_trace_intentKey` ON `raw_trace` (`intentKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_trace_fingerprint` ON `raw_trace` (`fingerprint`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_trace_processedAt` ON `raw_trace` (`processedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_trace_createdAt` ON `raw_trace` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_raw_trace_memoryId` ON `raw_trace` (`memoryId`)")

                // 老流水搬进新池子，一律标记为「已巩固」——它们早就被 0.9.13 加工过了
                db.execSQL(
                    "INSERT INTO `raw_trace` (" +
                        "`kind`, `intent`, `intentKey`, `fingerprint`, `screenKeys`, `pkg`, " +
                        "`steps`, `outcome`, `failStep`, `detail`, `source`, " +
                        "`memoryId`, `batchId`, `processedAt`, `createdAt`) " +
                        "SELECT " +
                        "CASE `outcome` " +
                        "WHEN 'success' THEN 'agent_success' " +
                        "WHEN 'aborted' THEN 'agent_abort' " +
                        "ELSE 'agent_fail' END, " +
                        "`intent`, `intentKey`, `fingerprint`, `screenKeys`, `pkg`, " +
                        "`steps`, `outcome`, `failStep`, `detail`, `source`, " +
                        "0, -1, `createdAt`, `createdAt` " +
                        "FROM `task_episode`"
                )
                db.execSQL("DROP TABLE IF EXISTS `task_episode`")
            }
        }

        /**
         * v8 → v9：情感系统重构，数据层（0.9.15 阶段 0）。
         *
         * 两件事，都是纯增量：
         *
         * 1. **建 `pad_state`**（单行表）。为什么不塞进 `persona_profile`：
         *    `saveProfile()` 是低频全量覆盖，用户改个名字就会把情绪清零。
         *    高频写的状态和低频写的档案分开，是这一版最重要的一条防呆。
         *
         * 2. **`long_term_memory` 加 5 列情感指纹**。老行全部落成中性：
         *    pad 全 0、强度 0、关键词空 —— 在 `MemoryRanker` 里既不会被抬高
         *    也不会被压低（中性记忆的「情绪一致性」是同一个常数，只压缩分差不改顺序）。
         *
         * `DEFAULT` 子句**必须**和实体上的 `@ColumnInfo(defaultValue = ...)` 逐字对应，
         * 否则 Room 打开库时会抛 `Room cannot verify the data integrity`
         * （它会把 sqlite_master 里的默认值拿去和实体期望值比对）。
         *
         * 不加索引：这五列只在内存里参与打分（候选池本身只有 200 行），
         * 建索引只会让每次插入多写一个 B-tree。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 单行表。注意主键写法：Room 对「非自增整型主键」生成的是
                //     `id` INTEGER NOT NULL, ..., PRIMARY KEY(`id`)
                // 而不是列级的 `id` INTEGER PRIMARY KEY。两者在 SQLite 里语义等价
                // （都是 rowid 别名，PRAGMA table_info 输出也一致），但这里逐字对齐
                // 导出的 9.json，免得以后有人拿两份对照时以为漏写。
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pad_state` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`quickP` REAL NOT NULL, " +
                        "`quickA` REAL NOT NULL, " +
                        "`quickD` REAL NOT NULL, " +
                        "`slowP` REAL NOT NULL, " +
                        "`slowA` REAL NOT NULL, " +
                        "`slowD` REAL NOT NULL, " +
                        "`baseP` REAL NOT NULL, " +
                        "`baseA` REAL NOT NULL, " +
                        "`baseD` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastDecayAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )

                db.execSQL("ALTER TABLE `long_term_memory` ADD COLUMN `padP` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `long_term_memory` ADD COLUMN `padA` REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `long_term_memory` ADD COLUMN `padD` REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE `long_term_memory` ADD COLUMN `emotionalIntensity` " +
                        "REAL NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `long_term_memory` ADD COLUMN `keywords` TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v9 → v10：认知闭环合并（0.9.16）。
         *
         * 只加三列，全是「带默认值的 ALTER TABLE」，不重建表 ——
         * `action_memory` 里可能有用户攒了几个月的肌肉记忆，重建表一旦中途崩
         * 就是全丢，而这三列都有天然默认值：
         *
         *  - `origin='agent'`：0.9.15 之前这张表只有她自己跑出来的动作，
         *    手动路径（`habit/`）落在文件里、从没进过这张表。所以默认值就是事实。
         *  - `label=''`：老记忆的意图来自用户原话，本来就有 `intentKey`，不需要补名字。
         *  - `coverage=0`：当时没算过这个数，填 0 表示「不知道」，
         *    不要伪造成 1.0 —— 设置页要按「未知」显示，不能显示成「百分之百覆盖」。
         *
         * 状态机新增的 `suspect` / `rejected` 不需要迁移：`state` 本来就是 TEXT。
         *
         * `DEFAULT` 子句必须与实体上的 `@ColumnInfo(defaultValue = ...)` 逐字对应，
         * 否则 Room 打开库时会抛 `Room cannot verify the data integrity`。
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `action_memory` ADD COLUMN `origin` TEXT NOT NULL DEFAULT 'agent'")
                db.execSQL("ALTER TABLE `action_memory` ADD COLUMN `label` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `action_memory` ADD COLUMN `coverage` REAL NOT NULL DEFAULT 0")
            }
        }

        /**
         * v10 → v11：赛博房间（0.9.17 批次 B）。
         *
         * 纯建表，两张都是新表，不动任何老表 —— 聊天记录、记忆、情绪一条都不碰。
         *
         * 两个刻意的决定：
         *
         * 1. **家具位置存槽位不存 x/y**。房间是透视的，同一份坐标在手机和折叠屏上
         *    会跑到墙外；槽位（`back_left` 等 7 个）由渲染层换算成当前屏幕的坐标，
         *    一份数据在任何尺寸下都不穿帮。
         * 2. **`room_theme` 是单行表，且迁移时一行都不写**。写一行「默认主题」
         *    看起来更省事，但那样界面永远分不清「用户把墙改成这个颜色」和
         *    「他根本没进过房间」。空表 = 没装修过，用 [RoomTheme] 的默认值渲染。
         *
         * 不加索引：两张表最多几十行，全表扫描比走 B-tree 还快。
         * SQL 与实体生成的 schema 逐字对齐（列顺序 / NOT NULL / AUTOINCREMENT / 主键写法）。
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `room_item` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`slot` TEXT NOT NULL, " +
                        "`tint` INTEGER NOT NULL, " +
                        "`scale` REAL NOT NULL, " +
                        "`flip` INTEGER NOT NULL, " +
                        "`note` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                // 单行表：主键按 Room 生成的写法放在表尾（与 `pad_state` 一致）
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `room_theme` (" +
                        "`id` INTEGER NOT NULL, " +
                        "`wall` INTEGER NOT NULL, " +
                        "`floor` INTEGER NOT NULL, " +
                        "`window` INTEGER NOT NULL, " +
                        "`preset` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }
    }
}
