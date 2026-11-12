package com.nexus.agent.ui.room

import android.content.Context
import android.util.Log
import com.nexus.agent.data.db.AppDatabase
import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 房间数据的入口（0.9.17 批次 B）。
 *
 * 界面只跟这个对象打交道，不直接摸 Dao —— 这样「第一次进屋要铺家具」这条规则
 * 只有一处实现，不会在 RoomScreen 和以后的装修页各写一遍（各写一遍的结果是
 * 两边都以为对方铺过了，屋子空着）。
 *
 * ## 幂等
 *
 * `ensureSeeded()` 每次进屋都会调，用 `Mutex` 串行化 + 主题行做标记：
 * 并发进来两次也只铺一套。
 */
object RoomStore {

    private const val TAG = "RoomDebug"

    private val seedLock = Mutex()

    /** 家具列表的实时流：装修 / 增删都会推一次 */
    fun items(context: Context): Flow<List<RoomItem>> =
        AppDatabase.get(context.applicationContext).roomItemDao().observeAll()

    /** 主题的实时流；null = 从没装修过，界面用 [RoomTheme] 默认值 */
    fun theme(context: Context): Flow<RoomTheme?> =
        AppDatabase.get(context.applicationContext).roomThemeDao().observe()

    /**
     * 首次进屋铺一套默认家具。
     *
     * **失败不抛**：铺不出来最多是间空屋子，不该让「点门」这个动作崩掉。
     * 下次进来会再试一次（因为主题行仍然为空）。
     */
    suspend fun ensureSeeded(context: Context) {
        val app = context.applicationContext
        seedLock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val db = AppDatabase.get(app)
                    val themeDao = db.roomThemeDao()
                    val itemDao = db.roomItemDao()
                    if (themeDao.get() != null) return@runCatching
                    val now = System.currentTimeMillis()
                    themeDao.upsert(RoomDefaults.theme(now))
                    if (itemDao.count() == 0) {
                        RoomDefaults.furniture(now).forEach { itemDao.insert(it) }
                    }
                    Log.i(TAG, "首次进屋：已铺 ${RoomDefaults.furniture().size} 件家具")
                }.onFailure {
                    Log.w(TAG, "铺默认家具失败：${it.javaClass.simpleName} ${it.message}")
                }
            }
        }
    }

    /** 供排障/测试：清空整间屋子（家具 + 主题）。用户点「恢复默认布置」时会用到 */
    suspend fun reset(context: Context) {
        val app = context.applicationContext
        withContext(Dispatchers.IO) {
            runCatching {
                val db = AppDatabase.get(app)
                db.roomItemDao().clear()
                db.roomThemeDao().clear()
            }
        }
    }
}
