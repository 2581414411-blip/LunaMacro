package com.nexus.agent.schedule

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * 定时任务仓库（SharedPreferences + JSON）。
 *
 * 为什么不用数据库：任务量级是个位数到几十条，一张 JSON 数组足够，
 * 也方便用户导出排障。写入一律「读全量 → 改 → 覆写」，量小无性能问题。
 */
object ScheduleStore {

    private const val TAG = "ScheduleStore"
    private const val PREFS = "luna_schedule"
    private const val KEY_TASKS = "tasks"

    /** 单次最多保存的定时任务数，防止误操作把内存/电量玩坏 */
    const val MAX_TASKS = 30

    fun list(context: Context): List<ScheduledTask> {
        val raw = prefs(context).getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    runCatching { ScheduledTask.fromJson(o) }
                        .onFailure { Log.w(TAG, "跳过坏任务 #$i", it) }
                        .getOrNull()
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "任务表解析失败，按空处理", e)
            emptyList()
        }
    }

    fun get(context: Context, id: String): ScheduledTask? = list(context).firstOrNull { it.id == id }

    /** 新增或整条替换（按 id 匹配） */
    fun upsert(context: Context, task: ScheduledTask): Boolean {
        val all = list(context).toMutableList()
        val idx = all.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            all[idx] = task
        } else {
            if (all.size >= MAX_TASKS) {
                Log.w(TAG, "任务数已达上限 $MAX_TASKS")
                return false
            }
            all.add(task)
        }
        save(context, all)
        return true
    }

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    /** 只改执行结果，不动调度字段 */
    fun recordResult(context: Context, id: String, status: String, summary: String, runAt: Long) {
        val all = list(context)
        val t = all.firstOrNull { it.id == id } ?: return
        val updated = t.copy(
            lastRunAt = runAt,
            lastStatus = status,
            lastSummary = summary.take(200)
        )
        save(context, all.map { if (it.id == id) updated else it })
    }

    /** 一次性任务跑完就关掉，避免重启后又被重排 */
    fun disable(context: Context, id: String) {
        val all = list(context)
        if (all.none { it.id == id }) return
        save(context, all.map { if (it.id == id) it.copy(enabled = false) else it })
    }

    private fun save(context: Context, tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
