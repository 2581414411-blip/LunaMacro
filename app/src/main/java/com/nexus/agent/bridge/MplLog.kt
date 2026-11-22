package com.nexus.agent.bridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 桥接层调用记录（设置页「最近调用」与「最近一次连接」）。
 *
 * 两个东西刻意分开，别混：
 *
 * | | 存什么 | 用来回答 |
 * |---|---|---|
 * | [Entry] / `recent_calls` | 只有 `/v1/action`，一行一个 `id` 前 8 位 | 「这个动作执行了吗、结果是什么」 |
 * | [Conn] / `last_conn` | **一切请求**，ping / health / 401 / 404 都算 | 「到底有没有人来敲过门」 |
 *
 * 0.9.31 只有前者，结果联调时"配对码错了"和"根本没连上"看起来一模一样 ——
 * 两种情况该给的建议正好相反。所以 0.9.32 补后者，只留最后一条，不刷屏。
 *
 * 不写数据库：这不是审计（审计在 `AuditLogger`），是一次临时对照表，
 * 清缓存消失也无所谓。
 */
object MplLog {

    const val MAX = 20

    private const val PREFS = "mpl_bridge"
    private const val KEY_RECENT = "recent_calls"
    private const val KEY_LAST_CONN = "last_conn"

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

    /**
     * 一次 HTTP 连接的结果。
     *
     * @param status HTTP 状态码；`0` 表示「连上了但一个字节都没发」（端口探活）
     */
    data class Conn(
        val at: Long,
        val method: String,
        val path: String,
        val status: Int,
        val ms: Long,
        val note: String? = null
    ) {
        /** `14:03:22 GET /v1/health → 401 3ms` */
        fun line(): String {
            val t = runCatching {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(at))
            }.getOrDefault("--:--:--")
            val tail = when {
                status == 0 -> "空连接"
                status == 401 -> "401 配对码不对"
                status == 200 -> "200 ok"
                else -> "$status"
            }
            return "$t $method $path → $tail ${ms}ms"
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("at", at)
            put("method", method)
            put("path", path)
            put("status", status)
            put("ms", ms)
            put("note", note ?: "")
        }

        companion object {
            fun fromJson(o: JSONObject) = Conn(
                at = o.optLong("at"),
                method = o.optString("method"),
                path = o.optString("path"),
                status = o.optInt("status"),
                ms = o.optLong("ms"),
                note = o.optString("note").takeIf { it.isNotEmpty() }
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

    /**
     * 记「最近一次连接」。**只留最后一条**，不做列表 ——
     * 它要回答的是"刚才那次到底有没有到"，不是历史趋势。
     */
    @Synchronized
    fun note(context: Context, conn: Conn) {
        prefs(context).edit().putString(KEY_LAST_CONN, conn.toJson().toString()).apply()
    }

    fun lastConn(context: Context): Conn? {
        val raw = prefs(context).getString(KEY_LAST_CONN, null) ?: return null
        return runCatching { Conn.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECENT).remove(KEY_LAST_CONN).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
