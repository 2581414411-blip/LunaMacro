package com.nexus.agent.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桥接层调用记录（设置页「最近调用」）。
 *
 * 只留最近 20 条，一行一条：`id 前 8 位 | 动作 | 耗时 | 结果`。
 * 存在的理由是**联调**：两端 UI 都显示 id 前 8 位，出问题时能直接把两边对起来，
 * 而不是靠"大概三点多那次"猜。
 *
 * 不写数据库 —— 这不是审计（审计在 `AuditLogger`），是一次临时对照表，
 * 清缓存消失也无所谓。
 */
object MplLog {

    const val MAX = 20

    private const val PREFS = "mpl_bridge"
    private const val KEY_RECENT = "recent_calls"

    data class Entry(
        val at: Long,
        val id: String,
        val action: String,
        val ms: Long,
        val ok: Boolean,
        val note: String
    ) {
        /** `a7f3c1c8 click_text 2400ms ok` */
        fun line(): String =
            "${id.take(8)} $action ${ms}ms ${if (ok) "ok" else note}"

        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at)
            put("id", id)
            put("action", action)
            put("ms", ms)
            put("ok", ok)
            put("note", note)
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                at = o.optLong("at"),
                id = o.optString("id"),
                action = o.optString("action"),
                ms = o.optLong("ms"),
                ok = o.optBoolean("ok"),
                note = o.optString("note")
            )
        }
    }

    @Synchronized
    fun add(context: Context, entry: Entry) {
        val list = recent(context).toMutableList()
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RECENT, arr.toString()).apply()
    }

    @Synchronized
    fun recent(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Entry.fromJson(it) }
            }
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECENT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
