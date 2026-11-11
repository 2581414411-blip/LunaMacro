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

    /**
     * 按「这一句话 + 此刻的心情」召回长期记忆（0.9.15 阶段 2）。
     *
     * 默认实现直接退化成 [loadMemories]（权重前 N 条）—— 这样所有老调用方、
     * 以及单测里的 FakeStore 都不用改一行，行为精确等于 0.9.14。
     * 真正的情感调制只在 Room 实现里生效。
     *
     * 回调在**后台线程**执行；没回调 = 读失败，调用方沿用上一次的缓存。
     */
    fun loadMemoriesFor(
        query: String,
        pad: com.nexus.agent.persona.PAD,
        limit: Int,
        onLoaded: (List<String>) -> Unit
    ) = loadMemories(limit, onLoaded)

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

    /**
     * 情感调制的召回（0.9.15 阶段 2）。
     *
     * 三步：取候选池 → 按「语义 + 情绪一致性 + 时间衰减」重排 → 没人过关就回退。
     * 最后那一步是关键：情绪一致性只许**重排**，不许**凭空引入**。她难过时翻出
     * 一件毫不相干的伤心事，比记不住更吓人。
     */
    override fun loadMemoriesFor(
        query: String,
        pad: com.nexus.agent.persona.PAD,
        limit: Int,
        onLoaded: (List<String>) -> Unit
    ) = io.execute {
        val app = appContext
        val facts = runCatching {
            runBlocking {
                val dao = db().memoryDao()
                if (!com.nexus.agent.copilot.CopilotConfig.isAffectRecallEnabled(app)) {
                    Log.i(TAG, "[召回] 情感调制已关闭，走权重前 $limit 条")
                    return@runBlocking dao.top(limit).map { it.fact }
                }
                val pool = dao.recent(com.nexus.agent.persona.MemoryRecall.POOL)
                val ranked = com.nexus.agent.persona.MemoryRecall.rank(
                    pool, query, pad, System.currentTimeMillis(), limit
                )
                if (ranked.isEmpty()) {
                    Log.i(
                        TAG,
                        "[召回] 没有语义命中的记忆（候选 ${pool.size} 条），回退权重前 $limit 条"
                    )
                    dao.top(limit).map { it.fact }
                } else {
                    Log.i(TAG, com.nexus.agent.persona.MemoryRecall.explain(ranked, pad, pool.size))
                    ranked.map { it.candidate.fact }
                }
            }
        }.onFailure { Log.w(TAG, "[召回] 失败，沿用上一次的缓存", it) }.getOrNull()
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
