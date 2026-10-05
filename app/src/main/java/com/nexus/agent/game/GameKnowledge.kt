package com.nexus.agent.game

import android.content.Context
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import com.nexus.agent.util.AnyJsonAdapter
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 游戏知识库：开发者预置的游戏任务流程与界面定义，供零配置托管使用。
 */
data class GameKnowledge(
    val gameId: String,
    val gameName: String,
    val packageName: String,
    val flowDescription: String,
    val screenTypes: List<ScreenTypeDef> = emptyList(),
    val commonActions: List<String> = emptyList()
)

/**
 * 界面类型定义。
 */
data class ScreenTypeDef(
    val name: String,
    val description: String,
    val typicalButtons: List<String> = emptyList()
)

/**
 * 游戏知识库存储。
 *
 * - 开发者预设放在 `assets/knowledge/{gameId}.json`
 * - 首次使用时可导入到 `filesDir/game_knowledge/{gameId}.json`
 * - 提供 save/load/listAll/delete，以及从 assets 加载预设的能力
 */
object GameKnowledgeStore {

    private const val TAG = "GameKnowledgeStore"
    private const val DIR_NAME = "game_knowledge"
    private const val ASSETS_DIR = "knowledge"

    private val moshi = Moshi.Builder()
        .add(AnyJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
    private val adapter: JsonAdapter<GameKnowledge> = moshi.adapter(GameKnowledge::class.java)

    private fun ctx(): Context =
        ShizukuManager.appContext() ?: throw IllegalStateException("GameKnowledgeStore: appContext 未初始化")

    private fun dir(): File = File(ctx().filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun fileFor(gameId: String): File = File(dir(), "$gameId.json")

    /**
     * 保存知识库到 filesDir。
     */
    suspend fun save(knowledge: GameKnowledge) = withContext(Dispatchers.IO) {
        fileFor(knowledge.gameId).writeText(adapter.toJson(knowledge))
        Log.i(TAG, "saved knowledge ${knowledge.gameId} (${knowledge.gameName})")
    }

    /**
     * 从 filesDir 加载知识库。
     */
    suspend fun load(gameId: String): GameKnowledge? = withContext(Dispatchers.IO) {
        val f = fileFor(gameId)
        if (!f.exists()) return@withContext null
        try {
            adapter.fromJson(f.readText())
        } catch (e: Throwable) {
            Log.e(TAG, "load $gameId 失败", e)
            null
        }
    }

    /**
     * 列出 filesDir 中所有知识库。
     */
    suspend fun listAll(): List<GameKnowledge> = withContext(Dispatchers.IO) {
        val files = dir().listFiles { f -> f.name.endsWith(".json") } ?: return@withContext emptyList()
        files.mapNotNull { f ->
            try {
                adapter.fromJson(f.readText())
            } catch (e: Throwable) {
                Log.e(TAG, "解析 ${f.name} 失败", e)
                null
            }
        }.sortedBy { it.gameName }
    }

    /**
     * 删除知识库。
     */
    suspend fun delete(gameId: String) = withContext(Dispatchers.IO) {
        val f = fileFor(gameId)
        if (f.exists()) f.delete()
    }

    /**
     * 从 assets/knowledge/ 加载预设知识库。
     */
    fun loadPresetFromAssets(context: Context, gameId: String): GameKnowledge? {
        return try {
            val json = context.assets.open("$ASSETS_DIR/$gameId.json").bufferedReader().use { it.readText() }
            adapter.fromJson(json)
        } catch (e: Throwable) {
            Log.w(TAG, "加载预设 $gameId 失败（可能不存在）", e)
            null
        }
    }

    /**
     * 列出 assets/knowledge/ 中所有预设知识库 id。
     */
    fun listPresetIds(context: Context): List<String> {
        return try {
            context.assets.list(ASSETS_DIR)?.filter { it.endsWith(".json") }?.map { it.removeSuffix(".json") } ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * 按游戏名查找（先查 filesDir，再查 assets 预设）。
     */
    fun findByName(context: Context, gameName: String): GameKnowledge? {
        val byId = runCatching { kotlinx.coroutines.runBlocking { listAll() } }.getOrDefault(emptyList())
        val hit = byId.firstOrNull { it.gameName == gameName || it.gameId == gameName }
        if (hit != null) return hit
        return listPresetIds(context).mapNotNull { loadPresetFromAssets(context, it) }
            .firstOrNull { it.gameName == gameName || it.gameId == gameName }
    }
}
