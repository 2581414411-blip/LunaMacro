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
 * GameProfile 持久化存储。
 *
 * 使用 JSON 文件，存储路径：`filesDir/game_profiles/{id}.json`。
 * 所有 IO 在 Dispatchers.IO 中执行。
 */
object GameProfileStore {

    private const val TAG = "GameProfileStore"
    private const val DIR_NAME = "game_profiles"

    private val moshi = Moshi.Builder()
        .add(AnyJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter: JsonAdapter<GameProfile> = moshi.adapter(GameProfile::class.java)

    private fun ctx(): Context =
        ShizukuManager.appContext() ?: throw IllegalStateException("GameProfileStore: appContext 未初始化")

    private fun dir(): File = File(ctx().filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun fileFor(id: String): File = File(dir(), "$id.json")

    private fun listAllSync(): List<GameProfile> {
        val files = dir().listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                adapter.fromJson(f.readText())
            } catch (e: Throwable) {
                Log.e(TAG, "解析 ${f.name} 失败", e)
                null
            }
        }.sortedBy { it.name }
    }

    suspend fun save(profile: GameProfile) = withContext(Dispatchers.IO) {
        fileFor(profile.id).writeText(adapter.toJson(profile))
        Log.i(TAG, "saved profile ${profile.id} (${profile.name}, ${profile.rules.size} 规则)")
    }

    suspend fun load(id: String): GameProfile? = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (!f.exists()) return@withContext null
        try {
            adapter.fromJson(f.readText())
        } catch (e: Throwable) {
            Log.e(TAG, "load $id 失败", e)
            null
        }
    }

    suspend fun listAll(): List<GameProfile> = withContext(Dispatchers.IO) { listAllSync() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (f.exists()) f.delete()
    }
}
