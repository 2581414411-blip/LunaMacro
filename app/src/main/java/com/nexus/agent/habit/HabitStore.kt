package com.nexus.agent.habit

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 习惯数据的本地仓库（0.9.11）。
 *
 * 存 JSONL 而不是 Room，理由有三个，都是实打实的：
 *  1. **写入太频繁**：一次点击一行，走 Room 得开协程、排队事务，还可能和主库抢锁；
 *  2. **必须能整体删干净**：「清除学习数据」要一次 rm 掉，删表还得 VACUUM 才真回收；
 *  3. **绝不碰数据库迁移**：这东西是可选功能，一旦引入表结构变更，所有没开这个功能的
 *     用户也要跟着迁移 —— 用独立目录就能把影响面关成 0。
 *
 * 位置：`filesDir/habit/YYYY-MM-DD.jsonl`，原始流水最多留 [KEEP_DAYS] 天。
 * 统计结论单独存 `profile.txt` / `profile.json`，比流水活得久（流水删了结论还能用）。
 */
object HabitStore {

    private const val TAG = "HabitStore"
    private const val DIR_NAME = "habit"
    private const val PROFILE_TXT = "profile.txt"

    /** 原始流水保留天数 —— 免责声明里明确写了 7 天 */
    const val KEEP_DAYS = 7

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR_NAME)

    private fun dayKey(at: Long): String = synchronized(dayFormat) { dayFormat.format(at) }

    private fun fileOf(context: Context, day: String): File = File(dir(context), "$day.jsonl")

    /** 追加一批事件到当天文件（必须在后台线程调用） */
    fun append(context: Context, events: List<HabitEvent>): Int {
        if (events.isEmpty()) return 0
        val grouped = events.groupBy { dayKey(it.at) }
        var written = 0
        synchronized(lock) {
            val base = dir(context)
            if (!base.exists() && !base.mkdirs()) {
                Log.w(TAG, "建目录失败：${base.absolutePath}")
                return 0
            }
            for ((day, list) in grouped) {
                val f = fileOf(context, day)
                try {
                    f.appendText(
                        list.joinToString("") { it.toJson() + "\n" },
                        Charsets.UTF_8
                    )
                    written += list.size
                } catch (e: Throwable) {
                    Log.w(TAG, "写入失败 $day：${e.javaClass.simpleName} ${e.message}")
                }
            }
        }
        return written
    }

    /** 读最近 [days] 天的全部事件（后台线程调用；单行损坏不影响其它行） */
    fun readRecent(context: Context, days: Int = KEEP_DAYS): List<HabitEvent> {
        val base = dir(context)
        if (!base.isDirectory) return emptyList()
        val cutoff = System.currentTimeMillis() - days.toLong() * 86_400_000L
        val out = ArrayList<HabitEvent>(512)
        val files = base.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
        synchronized(lock) {
            for (f in files) {
                if (f.lastModified() < cutoff) continue
                try {
                    f.forEachLine(Charsets.UTF_8) { line ->
                        HabitEvent.fromJson(line)?.let { if (it.at >= cutoff) out.add(it) }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "读取失败 ${f.name}：${e.message}")
                }
            }
        }
        out.sortBy { it.at }
        return out
    }

    /** 删掉超过保留期的流水文件（顺带把空文件清掉） */
    fun prune(context: Context) {
        val base = dir(context)
        if (!base.isDirectory) return
        val cutoff = System.currentTimeMillis() - KEEP_DAYS.toLong() * 86_400_000L
        synchronized(lock) {
            base.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".jsonl")) return@forEach
                if (f.lastModified() < cutoff || f.length() == 0L) {
                    if (f.delete()) Log.i(TAG, "清理过期流水 ${f.name}")
                }
            }
        }
    }

    /** 今日已记多少条（设置页展示用，只读一个文件，够快） */
    fun todayCount(context: Context): Int {
        val f = fileOf(context, dayKey(System.currentTimeMillis()))
        if (!f.isFile) return 0
        return try {
            f.useLines { it.count { line -> line.isNotBlank() } }
        } catch (e: Throwable) {
            0
        }
    }

    /** 一共存了多少天 */
    fun dayCount(context: Context): Int {
        val base = dir(context)
        if (!base.isDirectory) return 0
        return base.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.size ?: 0
    }

    // ---- 统计结论 ----
    // 只落一份纯文本。用纯文本不就是为了「用户自己能看见自己被她记了什么」吗 ——
    // 存成二进制/数据库反而让人没法核实「她到底记住了啥」，那才是最难解释的事。

    fun saveBrief(context: Context, profile: HabitProfile) {
        if (profile.brief.isBlank()) return
        synchronized(lock) {
            val base = dir(context)
            if (!base.exists() && !base.mkdirs()) return
            try {
                File(base, PROFILE_TXT).writeText(profile.brief, Charsets.UTF_8)
            } catch (e: Throwable) {
                Log.w(TAG, "保存画像失败：${e.message}")
            }
        }
    }

    fun loadBrief(context: Context): String? {
        synchronized(lock) {
            val f = File(dir(context), PROFILE_TXT)
            if (!f.isFile) return null
            return try {
                f.readText(Charsets.UTF_8).trim().ifEmpty { null }
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** 清空全部学习数据（流水 + 结论 + 目录本身） */
    fun clear(context: Context) {
        val base = dir(context)
        synchronized(lock) {
            if (!base.exists()) return
            val files = base.listFiles() ?: return
            var n = 0
            for (f in files) {
                if (f.delete()) n++
            }
            base.delete()
            Log.i(TAG, "学习数据已清空（$n 个文件）")
        }
    }

    /** 最后一天有记录是哪天（空则 null）—— UI 上显示「最后一次采集」 */
    fun lastEventDay(context: Context): String? {
        val base = dir(context)
        if (!base.isDirectory) return null
        return base.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.filter { it.length() > 0 }
            ?.maxByOrNull { it.lastModified() }
            ?.nameWithoutExtension
    }

    /** 目录当前占用字节数（给用户看「占了多少空间」） */
    fun sizeBytes(context: Context): Long {
        val base = dir(context)
        if (!base.isDirectory) return 0
        return base.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** 从时间戳取小时（0-23），按本地时区 */
    fun hourOf(at: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = at
        return cal.get(Calendar.HOUR_OF_DAY)
    }
}
