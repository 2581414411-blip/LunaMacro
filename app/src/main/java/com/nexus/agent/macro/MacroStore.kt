package com.nexus.agent.macro

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
 * Macro 的持久化存储。
 *
 * 使用 JSON 文件（不用 Room），存储路径：`filesDir/macros/{id}.json`。
 * 所有 IO 操作在 Dispatchers.IO 中执行。
 */
object MacroStore {

    private const val TAG = "MacroStore"
    private const val DIR_NAME = "macros"

    private val moshi: Moshi = Moshi.Builder()
        .add(AnyJsonAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()

    private val macroAdapter: JsonAdapter<Macro> = moshi.adapter(Macro::class.java)

    private fun ctx(): Context =
        ShizukuManager.appContext() ?: throw IllegalStateException("MacroStore: appContext 未初始化")

    private fun macrosDir(): File =
        File(ctx().filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun fileFor(id: String): File = File(macrosDir(), "$id.json")

    /** 同步读全部（内部用，供 listAll / findByName 复用） */
    private fun listAllSync(): List<Macro> {
        val files = macrosDir().listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                macroAdapter.fromJson(f.readText())
            } catch (e: Throwable) {
                Log.e(TAG, "解析 ${f.name} 失败", e)
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    /** 保存（覆盖写） */
    suspend fun save(macro: Macro) = withContext(Dispatchers.IO) {
        val json = macroAdapter.toJson(macro)
        fileFor(macro.id).writeText(json)
        Log.i(TAG, "saved macro ${macro.id} (${macro.name}, ${macro.steps.size} 步)")
    }

    /** 按 id 读取 */
    suspend fun load(id: String): Macro? = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (!f.exists()) return@withContext null
        try {
            macroAdapter.fromJson(f.readText())
        } catch (e: Throwable) {
            Log.e(TAG, "load $id 失败", e)
            null
        }
    }

    /** 列出全部宏（按更新时间倒序） */
    suspend fun listAll(): List<Macro> = withContext(Dispatchers.IO) { listAllSync() }

    /** 按名称查找（忽略大小写），供 play_macro 工具使用 */
    suspend fun findByName(name: String): Macro? = withContext(Dispatchers.IO) {
        listAllSync().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }

    /** 删除 */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val f = fileFor(id)
        if (f.exists()) {
            f.delete()
            Log.i(TAG, "deleted macro $id")
        }
    }

    /** 更新（等价 save，但刷新 updatedAt） */
    suspend fun update(macro: Macro) = save(macro.copy(updatedAt = System.currentTimeMillis()))
}

