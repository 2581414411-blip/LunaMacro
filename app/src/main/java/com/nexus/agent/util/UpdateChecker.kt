package com.nexus.agent.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 版本更新检查（更新源走 GitHub Releases）。
 *
 * 约定：版本 release 的 tag 以 "v" 开头（如 v0.6.29），并附带 APK 作为 asset；
 * 模型 release 用 tag "models"（不含 APK），检查更新时会自动跳过。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "2581414411-blip/LunaMacro"

    /** 更新信息 */
    data class UpdateInfo(
        val version: String,   // 版本号，如 "0.6.29"
        val apkUrl: String?,   // APK 下载链接（可能为空，为空则无法应用内更新）
        val notes: String,     // 更新说明
        val apkSize: Long = 0L // APK 字节数（用于下载完整性校验，0 = 未知）
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 最新版本（跳过 tag 不以 v 开头的 release，如 "models"）。
     *
     * @return 最新版本的 [UpdateInfo]；检查失败或无版本 release 时返回 null
     */
    suspend fun checkLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$REPO/releases?per_page=30"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LunaMacro-Android")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "检查更新 HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val releases = JSONArray(body)
                for (i in 0 until releases.length()) {
                    val obj = releases.getJSONObject(i)
                    val tag = obj.optString("tag_name")
                    if (!tag.startsWith("v")) continue  // 跳过 models 等非版本 tag

                    var apkUrl: String? = null
                    var apkSize = 0L
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val a = assets.getJSONObject(j)
                            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                                apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                                apkSize = a.optLong("size", 0L)
                                break
                            }
                        }
                    }
                    return@withContext UpdateInfo(
                        version = tag.removePrefix("v"),
                        apkUrl = apkUrl,
                        notes = obj.optString("body"),
                        apkSize = apkSize
                    )
                }
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "检查更新失败", e)
            null
        }
    }

    /** Releases 页面地址（无 APK 直接跳转这里下载） */
    fun releasesPageUrl(): String =
        "https://github.com/$REPO/releases"
}
