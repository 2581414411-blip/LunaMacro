package com.nexus.agent.data.db

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors

/** 人格档案的存档快照（脱离 Room 实体，方便测试与跨层传递） */
data class ProfileSnapshot(
    val name: String,
    val soul: String,
    val identity: String,
    val affinity: Int,
    val mood: String,
    /** 专属口癖（「喵」「哼」），空 = 没有 */
    val catchphrase: String = "",
    /** 0 极度内向 ~ 100 极度外向 */
    val extroversion: Int = PersonaProfile.DEFAULT_TRAIT,
    /** 0 极度理性 ~ 100 极度感性 */
    val sensibility: Int = PersonaProfile.DEFAULT_TRAIT,
    /** 0 极其顺从 ~ 100 非常叛逆 */
    val rebelliousness: Int = PersonaProfile.DEFAULT_TRAIT
)

/** 一条聊天记录的存档快照 */
data class ChatRecord(
    val role: String,
    val content: String,
    val detail: String = "",
    val ok: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 人格数据的持久化接口。
 *
 * 抽成接口而不是让 [com.nexus.agent.copilot.PersonaEngine] 直接吃 DAO，有两个原因：
 *  1. PersonaEngine 是纯 Kotlin object，单测不跑 Android —— 直接依赖 Room 会让
 *     `PersonaEngineTest` 一碰就 `Method not mocked`；
 *  2. 以后换存储（比如加密库、远端同步）不用动引擎。
 */
interface PersonaStore {

    /**
     * 读存档。
     *
     * @param recentItems 聊天流最多取多少条（含动作气泡）
     * @param recentTurns 对话最多取多少轮（1 轮 = user + assistant 两条）
     * @param onLoaded    回调在**后台线程**执行，调用方自己切主线程
     */
    fun load(
        recentItems: Int,
        recentTurns: Int,
        onLoaded: (profile: ProfileSnapshot, items: List<ChatRecord>, dialogue: List<ChatRecord>) -> Unit
    )

    fun appendChat(record: ChatRecord)

    /**
     * 读长期记忆（权重最高的 [limit] 条事实原文），回调在**后台线程**执行。
     *
     * 组装 System Prompt 时要用，但那是同步路径，所以只读缓存、这里负责刷新缓存。
     */
    fun loadMemories(limit: Int, onLoaded: (List<String>) -> Unit)

    fun saveProfile(profile: ProfileSnapshot)

    fun clearChat()

    fun clearAll()
}

/**
 * Room 实现。
 *
 * 线程模型：所有读写都排进**单线程** executor，靠队列保证「先写 user 再写 assistant」
 * 的顺序；DAO 是 suspend 的，所以在该线程里用 runBlocking 跑。
 * 不用 Dispatchers.IO 全局池的原因：并发写会让聊天记录顺序错乱，而顺序就是上下文。
 */
class RoomPersonaStore(context: Context) : PersonaStore {

    private val appContext = context.applicationContext

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "persona-db").apply { isDaemon = true }
    }

    private fun db(): AppDatabase = AppDatabase.get(appContext)

    override fun load(
        recentItems: Int,
        recentTurns: Int,
        onLoaded: (ProfileSnapshot, List<ChatRecord>, List<ChatRecord>) -> Unit
    ) = io.execute {
        runCatching {
            runBlocking {
                val dao = db()
                val profileDao = dao.personaProfileDao()

                // 第一次跑：库是空的，先塞一份默认档案，省得后面到处判 null
                val profile = profileDao.get(PersonaProfile.DEFAULT_ID)
                    ?: PersonaProfile().also { profileDao.upsert(it) }

                // DAO 返回的是倒序（最近的在前），翻正后才能按时间顺序拼上下文
                val items = dao.chatHistoryDao().recent(recentItems).reversed()
                val dialogue = dao.chatHistoryDao().recentDialogue(recentTurns * 2).reversed()

                onLoaded(
                    ProfileSnapshot(
                        name = profile.name,
                        soul = profile.soul,
                        identity = profile.identity,
                        affinity = profile.affinity,
                        mood = profile.mood,
                        catchphrase = profile.catchphrase,
                        extroversion = profile.extroversion,
                        sensibility = profile.sensibility,
                        rebelliousness = profile.rebelliousness
                    ),
                    items.map { it.toRecord() },
                    dialogue.map { it.toRecord() }
                )
            }
        }.onFailure { Log.w(TAG, "读取人格存档失败", it) }
    }

    override fun appendChat(record: ChatRecord) = io.execute {
        runCatching {
            runBlocking {
                db().chatHistoryDao().insert(
                    ChatHistory(
                        role = record.role,
                        content = record.content,
                        detail = record.detail,
                        ok = record.ok,
                        createdAt = record.createdAt
                    )
                )
                // 表不能无限长：只留最近 MAX_ROWS 条，多的删掉
                db().chatHistoryDao().trimTo(MAX_ROWS)
            }
        }.onFailure { Log.w(TAG, "写入聊天记录失败", it) }
    }

    override fun loadMemories(limit: Int, onLoaded: (List<String>) -> Unit) = io.execute {
        val facts = runCatching {
            runBlocking { db().memoryDao().top(limit).map { it.fact } }
        }.onFailure { Log.w(TAG, "读取长期记忆失败", it) }.getOrNull()
        // 读失败就不要回调：宁可沿用上一次的缓存，也别把记忆清空
        if (facts != null) onLoaded(facts)
    }

    override fun saveProfile(profile: ProfileSnapshot) = io.execute {
        runCatching {
            runBlocking {
                val dao = db().personaProfileDao()
                val current = dao.get(PersonaProfile.DEFAULT_ID) ?: PersonaProfile()
                dao.upsert(
                    current.copy(
                        name = profile.name,
                        soul = profile.soul,
                        identity = profile.identity,
                        affinity = profile.affinity.coerceIn(
                            PersonaProfile.AFFINITY_MIN, PersonaProfile.AFFINITY_MAX
                        ),
                        mood = profile.mood,
                        // 口癖与性格三维可能来自 UI 输入，落库前统一夹取
                        catchphrase = profile.catchphrase.take(PersonaProfile.CATCHPHRASE_MAX),
                        extroversion = profile.extroversion.coerceIn(
                            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
                        ),
                        sensibility = profile.sensibility.coerceIn(
                            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
                        ),
                        rebelliousness = profile.rebelliousness.coerceIn(
                            PersonaProfile.TRAIT_MIN, PersonaProfile.TRAIT_MAX
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }.onFailure { Log.w(TAG, "保存人格档案失败", it) }
    }

    override fun clearChat() = io.execute {
        runCatching { runBlocking { db().chatHistoryDao().clear() } }
            .onFailure { Log.w(TAG, "清空聊天记录失败", it) }
    }

    override fun clearAll() = io.execute {
        runCatching {
            runBlocking {
                db().chatHistoryDao().clear()
                db().memoryDao().clear()
            }
        }.onFailure { Log.w(TAG, "清空人格数据失败", it) }
    }

    private fun ChatHistory.toRecord() = ChatRecord(
        role = role,
        content = content,
        detail = detail,
        ok = ok,
        createdAt = createdAt
    )

    companion object {
        private const val TAG = "RoomPersonaStore"

        /** chat_history 最多保留的行数（120 条 UI 记录 + 一些余量） */
        private const val MAX_ROWS = 200
    }
}
