package com.nexus.agent.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 每日公告拉取。
 *
 * 约定：仓库根目录放一个 announcement.json（main 分支），格式：
 * ```json
 * {
 *   "id": "2026-10-25-01",
 *   "title": "公告",
 *   "content": "正文，支持 \n 换行",
 *   "date": "2026-10-25",
 *   "enabled": true
 * }
 * ```
 *
 * 触发时机：每天第一次启动 App 时拉一次，拉到非空公告就在首页弹窗（一个「确定」按钮）。
 * 拉取成功后当天不再重复请求；失败则保留当天重试机会（两次尝试间隔 ≥5 分钟，避免狂刷）。
 *
 * 多源策略与 ModelManager 一致：api.github.com（国内一般可达）→ raw 直连 → 加速镜像。
 */
object AnnouncementChecker {

    private const val TAG = "Announcement"

    private const val REPO = "2581414411-blip/LunaMacro"
    private const val FILE = "announcement.json"

    /** 仓库默认分支（注意是 master，不是 main —— 写错会全源 404，公告永远不弹） */
    private const val BRANCH = "master"

    private const val PREFS = "luna_announcement"
    private const val KEY_LAST_DATE = "last_check_date"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"

    /** 同一天内两次拉取的最小间隔（拉取失败时才会用到） */
    private const val RETRY_GAP_MS = 5 * 60 * 1000L

    data class Announcement(
        val id: String,
        val title: String,
        val content: String,
        val date: String
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 公告源（url → 是否需要 GitHub raw 的 Accept 头，contents API 需要）。
     *
     * 顺序按国内实测可达性排：api.github.com（最快最稳）→ ghfast 镜像 → 其他镜像 → raw 直连兜底
     * （raw.githubusercontent.com 在国内常被墙，实测直连超时，只能放最后）。
     */
    private fun sources(): List<Pair<String, Boolean>> = listOf(
        "https://api.github.com/repos/$REPO/contents/$FILE?ref=$BRANCH" to true,
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE" to false,
        "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE" to false,
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE" to false,
        "https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE" to false
    )

    /**
     * 今日是否需要拉公告，需要则拉取。
     *
     * @return 有公告返回内容；今天已查过 / 无公告 / 网络失败返回 null
     */
    suspend fun fetchIfNeeded(context: Context): Announcement? = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

            if (prefs.getString(KEY_LAST_DATE, null) == today) {
                Log.d(TAG, "今日已检查过公告，跳过")
                return@withContext null
            }
            val now = System.currentTimeMillis()
            val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
            if (lastAttempt > 0 && now - lastAttempt < RETRY_GAP_MS) {
                Log.d(TAG, "距上次拉取不足 5 分钟，跳过")
                return@withContext null
            }
            prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()

            val outcome = fetch()
            if (!outcome.reached) {
                Log.w(TAG, "公告拉取全部失败，保留今日重试机会")
                return@withContext null
            }
            // 连上了（无论有没有公告）→ 今天不再请求
            prefs.edit().putString(KEY_LAST_DATE, today).apply()
            outcome.announcement
        } catch (e: Throwable) {
            Log.e(TAG, "拉取公告异常", e)
            null
        }
    }

    private class Outcome(val announcement: Announcement?, val reached: Boolean)

    /** 单个源的拉取结果：ok=源可用（不管有没有公告）；notFound=文件不存在 */
    private class OneResult(
        val announcement: Announcement?,
        val ok: Boolean,
        val notFound: Boolean = false
    )

    private fun fetch(): Outcome {
        // 404 不立刻放弃：可能只是某个源路径/分支不同步，继续试下一个源；
        // 只有所有源都 404 才认定「确实没有公告文件」。
        var sawNotFound = false
        for ((url, needRawAccept) in sources()) {
            val r = fetchOne(url, needRawAccept)
            when {
                r.announcement != null -> {
                    Log.i(TAG, "公告拉取成功 ← $url")
                    return Outcome(r.announcement, true)
                }
                r.ok && !r.notFound -> {
                    Log.i(TAG, "源无有效公告（已关闭/正文为空/格式错）← $url")
                    return Outcome(null, true)
                }
                r.notFound -> {
                    Log.i(TAG, "源上无公告文件（404）← $url")
                    sawNotFound = true
                }
                // r.ok=false：本源网络失败，换下一个
            }
        }
        return Outcome(null, sawNotFound)
    }

    private fun fetchOne(url: String, needRawAccept: Boolean): OneResult {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", "LunaMacro-Android")
                .header("Cache-Control", "no-cache")
            if (needRawAccept) builder.header("Accept", "application/vnd.github.raw")
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 404 -> OneResult(null, true, notFound = true)
                    !resp.isSuccessful -> {
                        Log.w(TAG, "HTTP ${resp.code} ← $url")
                        OneResult(null, false)
                    }
                    else -> {
                        val body = resp.body?.string()
                        if (body.isNullOrBlank()) {
                            Log.w(TAG, "响应为空 ← $url")
                            OneResult(null, false)
                        } else {
                            OneResult(parse(body), true)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "拉取失败 ← $url：${e.javaClass.simpleName} ${e.message}")
            OneResult(null, false)
        }
    }

    private fun parse(raw: String): Announcement? {
        val text = raw.trim().removePrefix("\uFEFF")
        val json = try {
            JSONObject(text)
        } catch (e: Throwable) {
            Log.w(TAG, "公告 JSON 解析失败：${text.take(120).replace("\n", " ")}")
            return null
        }
        if (!json.optBoolean("enabled", true)) {
            Log.i(TAG, "公告已被置为关闭（enabled=false）")
            return null
        }
        val content = json.optString("content").trim()
        if (content.isEmpty()) {
            Log.i(TAG, "公告正文为空，不弹窗")
            return null
        }
        return Announcement(
            id = json.optString("id", ""),
            title = json.optString("title", "公告").ifBlank { "公告" },
            content = content,
            date = json.optString("date", "")
        )
    }
}
