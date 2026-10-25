package com.nexus.agent.agent.state

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 状态机配置仓库。
 *
 * 加载顺序（后者覆盖前者，同 id 以 filesDir 为准）：
 * 1. assets 的 state_profiles 目录 —— 随包内置的预设（原神 / 星穹铁道）
 * 2. filesDir 的 state_profiles 目录 —— 用户自定义 / 热更新
 *
 * 新增支持一个游戏 = 往目录里丢一个 json，不改代码。
 */
object StateMachineStore {

    private const val TAG = "StateMachineStore"
    private const val ASSET_DIR = "state_profiles"
    private const val DIR_NAME = "state_profiles"

    /** 进程内缓存（配置很小，读一次就够） */
    @Volatile
    private var cache: List<StateProfile>? = null

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) runCatching { mkdirs() } }

    /** 全部可用配置（内置 + 用户覆盖） */
    fun profiles(context: Context): List<StateProfile> {
        cache?.let { return it }
        val merged = LinkedHashMap<String, StateProfile>()

        // 1) assets 预设
        runCatching {
            context.assets.list(ASSET_DIR)?.forEach { name ->
                if (!name.endsWith(".json")) return@forEach
                runCatching {
                    context.assets.open("$ASSET_DIR/$name").bufferedReader().use { it.readText() }
                }.getOrNull()?.let { text ->
                    StateProfile.parse(text)?.let { merged[it.id] = it }
                }
            }
        }.onFailure { Log.w(TAG, "读取 assets 状态机配置失败", it) }

        // 2) filesDir 覆盖
        runCatching {
            dir(context).listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
                runCatching { f.readText() }.getOrNull()?.let { text ->
                    StateProfile.parse(text)?.let { merged[it.id] = it }
                }
            }
        }.onFailure { Log.w(TAG, "读取本地状态机配置失败", it) }

        val list = merged.values.toList()
        cache = list
        Log.i(TAG, "已加载 ${list.size} 个状态机配置：${list.joinToString { it.id }}")
        return list
    }

    /** 按前台包名找配置；找不到返回 null（状态机旁路，行为与旧版一致） */
    fun forPackage(context: Context, packageName: String?): StateProfile? {
        if (packageName.isNullOrBlank()) return null
        return profiles(context).firstOrNull { p ->
            p.packageNames.any { pn ->
                packageName == pn || packageName.startsWith(pn)
            }
        }
    }

    /** 清缓存（改配置后调用） */
    fun invalidate() {
        cache = null
    }

    /** 保存一份自定义配置（供设置页/调试用） */
    suspend fun save(context: Context, profile: StateProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("initial", profile.initialState)
                put("packageNames", JSONArray(profile.packageNames))
                put("successKeywords", JSONArray(profile.successKeywords))
                put("states", JSONArray().apply {
                    profile.states.forEach { s ->
                        put(JSONObject().apply {
                            put("id", s.id)
                            put("name", s.name)
                            put("keywords", JSONArray(s.keywords))
                            put("antiKeywords", JSONArray(s.antiKeywords))
                            put("allowedTools", JSONArray(s.allowedTools))
                            put("stable", s.stable)
                            put("abnormal", s.abnormal)
                        })
                    }
                })
                put("transitions", JSONArray().apply {
                    profile.transitions.forEach { t ->
                        put(JSONObject().apply {
                            put("from", t.from)
                            put("action", t.action)
                            put("to", t.to)
                        })
                    }
                })
            }
            File(dir(context), "${profile.id}.json").writeText(root.toString(2))
            invalidate()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "保存状态机配置失败", e)
            false
        }
    }
}
