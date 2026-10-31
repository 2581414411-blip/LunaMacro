package com.nexus.agent.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 版本更新检查（更新源走 GitHub Releases + `update.json` 声明）。
 *
 * ## 三档更新策略
 * - **experimental（实验性）**：设置页只显示红点，不打扰
 * - **recommended（推荐）**：普通弹窗，可点「稍后」
 * - **mandatory（强制）**：全屏遮罩锁死 App，必须更新才能继续用
 *
 * 档位来源优先级（高 → 低）：
 * 1. `filesDir/update_override.json` —— **本地测试用**，不用推 GitHub 就能验证强制锁屏
 * 2. 仓库 `update.json`（master 分支，多镜像）——运营侧发版时写这里
 * 3. Release body 里的 `[mandatory]` / `[experimental]` 标记 —— 临时手段
 * 4. 默认 recommended
 *
 * `update.json` 示例：
 * ```json
 * {
 *   "version": "0.7.7",
 *   "update_level": "mandatory",
 *   "notes": "修复了严重崩溃问题，请务必更新",
 *   "apk_url": ""
 * }
 * ```
 *
 * 本地强制测试（不动远端）：
 * ```bash
 * adb shell "run-as com.nexus.agent sh -c 'cat > files/update_override.json <<EOF
 * {\"version\":\"99.0.0\",\"update_level\":\"mandatory\",\"force\":true}
 * EOF'"
 * ```
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "2581414411-blip/LunaMacro"

    /** 仓库默认分支（注意不是 main） */
    private const val BRANCH = "master"
    private const val UPDATE_JSON = "update.json"

    /** 强制更新状态持久化（重启后仍然锁着，直到版本号变了） */
    private const val PREFS = "luna_update"
    private const val KEY_MANDATORY = "mandatory_payload"

    /** 本地测试覆盖文件 */
    private const val OVERRIDE_FILE = "update_override.json"

    /** 更新档位 */
    enum class UpdateLevel {
        /** 实验性：只在设置页显示红点 */
        EXPERIMENTAL,

        /** 推荐：弹窗可稍后 */
        RECOMMENDED,

        /** 强制：全屏锁死，不更新不给用 */
        MANDATORY;

        companion object {
            fun of(raw: String?): UpdateLevel = when (raw?.trim()?.lowercase()) {
                "experimental", "beta", "alpha", "test" -> EXPERIMENTAL
                "mandatory", "force", "forced", "critical", "strong" -> MANDATORY
                else -> RECOMMENDED
            }

            fun label(level: UpdateLevel): String = when (level) {
                EXPERIMENTAL -> "实验性"
                RECOMMENDED -> "推荐"
                MANDATORY -> "强制"
            }
        }
    }

    /**
     * 更新信息。
     *
     * @param level 更新档位（决定 UI 形态）
     * @param force 本地测试用：true 时即使版本号没变也按「有更新」处理
     */
    data class UpdateInfo(
        val version: String,
        val apkUrl: String?,
        val notes: String,
        val apkSize: Long = 0L,
        val level: UpdateLevel = UpdateLevel.RECOMMENDED,
        val force: Boolean = false
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------------ 主入口

    /**
     * 检查最新版本（合并三档策略）。
     *
     * @param context 传了就顺带读本地测试覆盖文件；不传则只走网络
     * @return 最新版本信息；检查失败返回 null
     */
    suspend fun checkLatest(context: Context? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        val release = try {
            fetchLatestRelease()
        } catch (e: Throwable) {
            Log.e(TAG, "拉取 Release 失败", e)
            null
        }

        val declaration = try {
            fetchUpdateJson()
        } catch (e: Throwable) {
            Log.w(TAG, "拉取 update.json 失败：${e.message}")
            null
        }

        val base = release ?: declaration?.let {
            // 没有 Release 但 update.json 自己带了下载地址，也能用
            UpdateInfo(
                version = it.version ?: return@let null,
                apkUrl = it.apkUrl,
                notes = it.notes.orEmpty(),
                level = it.level
            )
        }

        if (base == null) {
            Log.w(TAG, "既没有版本 Release，也没有可用的 update.json")
            return@withContext null
        }

        // 仓库 update.json 声明优先于 Release body 标记（仅当版本对得上）
        var merged = base
        if (declaration != null && declaration.version != null &&
            declaration.version == base.version
        ) {
            merged = base.copy(
                level = declaration.level,
                notes = declaration.notes ?: base.notes,
                apkUrl = declaration.apkUrl ?: base.apkUrl
            )
            Log.i(TAG, "update.json 覆盖档位：${declaration.level}（v${base.version}）")
        }

        // 本地测试覆盖（最高优先级）
        val local = context?.let { readLocalOverride(it) }
        if (local != null) {
            merged = merged.copy(
                version = local.version?.takeIf { it.isNotBlank() } ?: merged.version,
                level = local.level,
                notes = local.notes ?: merged.notes,
                apkUrl = local.apkUrl ?: merged.apkUrl,
                force = local.force
            )
            Log.w(TAG, "使用本地 update_override.json：level=${local.level} force=${local.force}")
        }

        merged
    }

    // ------------------------------------------------------------------ 数据源

    /** 从 Release 列表取最新版本（跳过 models 等非 v* tag） */
    private fun fetchLatestRelease(): UpdateInfo? {
        val url = "https://api.github.com/repos/$REPO/releases?per_page=30"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LunaMacro-Android")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "检查更新 HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string() ?: return null
            val releases = JSONArray(body)
            for (i in 0 until releases.length()) {
                val obj = releases.getJSONObject(i)
                val tag = obj.optString("tag_name")
                if (!tag.startsWith("v")) continue  // 跳过 models

                var apkUrl: String? = null
                var apkSize = 0L
                obj.optJSONArray("assets")?.let { assets ->
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                            apkSize = a.optLong("size", 0L)
                            break
                        }
                    }
                }
                val notes = obj.optString("body")
                return UpdateInfo(
                    version = tag.removePrefix("v"),
                    apkUrl = apkUrl,
                    notes = notes,
                    apkSize = apkSize,
                    level = parseLevelFromBody(notes)
                )
            }
        }
        return null
    }

    /** Release body 里写 `[mandatory]` / `[experimental]` 也能定档 */
    private fun parseLevelFromBody(body: String?): UpdateLevel {
        val b = body.orEmpty().lowercase()
        return when {
            b.contains("[mandatory]") || b.contains("【强制】") -> UpdateLevel.MANDATORY
            b.contains("[experimental]") || b.contains("【实验】") -> UpdateLevel.EXPERIMENTAL
            else -> UpdateLevel.RECOMMENDED
        }
    }

    /** update.json 声明 */
    private data class Declaration(
        val version: String?,
        val level: UpdateLevel,
        val notes: String?,
        val apkUrl: String?,
        val force: Boolean
    )

    /** 多源拉 update.json：api 直取 → 三个镜像 → raw 兜底 */
    private fun fetchUpdateJson(): Declaration? {
        val sources = listOf(
            "https://api.github.com/repos/$REPO/contents/$UPDATE_JSON?ref=$BRANCH" to true,
            "https://ghfast.top/https://raw.githubusercontent.com/$REPO/$BRANCH/$UPDATE_JSON" to false,
            "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/$BRANCH/$UPDATE_JSON" to false,
            "https://raw.githubusercontent.com/$REPO/$BRANCH/$UPDATE_JSON" to false
        )
        for ((url, isApi) in sources) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LunaMacro-Android")
                if (isApi) builder.header("Accept", "application/vnd.github.raw")
                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "update.json ${resp.code} @ $url")
                        return@use
                    }
                    val text = resp.body?.string().orEmpty()
                    if (!text.trimStart().startsWith("{")) return@use
                    return parseDeclaration(text)
                }
            } catch (e: Throwable) {
                Log.d(TAG, "update.json 拉取失败 $url：${e.message}")
            }
        }
        return null
    }

    private fun parseDeclaration(json: String): Declaration? = try {
        val o = JSONObject(json)
        Declaration(
            version = o.optString("version").takeIf { it.isNotBlank() },
            level = UpdateLevel.of(o.optString("update_level")),
            notes = o.optString("notes").takeIf { it.isNotBlank() },
            apkUrl = o.optString("apk_url").takeIf { it.isNotBlank() },
            force = o.optBoolean("force", false)
        )
    } catch (e: Throwable) {
        Log.w(TAG, "update.json 解析失败：${e.message}")
        null
    }

    /** 本地测试覆盖文件（filesDir 或 外部私有目录 下的 update_override.json） */
    private fun readLocalOverride(context: Context): Declaration? {
        val candidates = listOfNotNull(
            File(context.filesDir, OVERRIDE_FILE),
            context.getExternalFilesDir(null)?.let { File(it, OVERRIDE_FILE) }
        )
        val f = candidates.firstOrNull { it.isFile } ?: return null
        return try {
            parseDeclaration(f.readText(Charsets.UTF_8))
        } catch (e: Throwable) {
            Log.w(TAG, "读取本地覆盖失败：${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 强制更新持久化

    /**
     * 上次记录下来的强制更新（重启后立刻锁屏，不用等网络）。
     *
     * @return 未完成强制更新时返回；已完成（版本号追平）返回 null
     */
    fun pendingMandatory(context: Context): UpdateInfo? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MANDATORY, null) ?: return null
        return try {
            val o = JSONObject(raw)
            val version = o.optString("version")
            val current = currentVersion(context)
            if (version.isBlank() || !isNewer(version, current)) {
                clearMandatory(context)
                return null
            }
            UpdateInfo(
                version = version,
                apkUrl = o.optString("apk_url").takeIf { it.isNotBlank() },
                notes = o.optString("notes"),
                apkSize = o.optLong("apk_size", 0L),
                level = UpdateLevel.MANDATORY
            )
        } catch (e: Throwable) {
            null
        }
    }

    /** 记住「必须更新」，重启后继续锁 */
    fun rememberMandatory(context: Context, info: UpdateInfo) {
        val json = JSONObject().apply {
            put("version", info.version)
            put("apk_url", info.apkUrl ?: "")
            put("notes", info.notes)
            put("apk_size", info.apkSize)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MANDATORY, json.toString())
            .apply()
        Log.i(TAG, "已记录强制更新 v${info.version}")
    }

    /** 清除强制更新标记（更新完成或用户已是最新） */
    fun clearMandatory(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_MANDATORY)
            .apply()
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 版本比较：remote 是否比 current 新。**全项目唯一实现**，UI 层不许再写第二份。
     *
     * 三段式：先比点分数字段，数字全等时再看预发布后缀。
     *
     * 为什么必须处理后缀：0.8.0-alpha1 这种版本号，旧实现把 `0-alpha1` 当成
     * `toIntOrNull()` = null → 0，于是 `0.8.0-alpha2` 和 `0.8.0-alpha1` 都解析成
     * `[0, 8, 0]` 被判成**相等** —— 结果就是「装了 alpha1，检查更新永远说已是最新」。
     * 之前 UI 层和本类各有一份实现，只修了 UI 那份，于是这条链路继续踩坑。
     *
     * 规则：正式版 > 同号预发布版；预发布之间比「前缀 + 序号」（alpha1 < alpha2 < beta1）。
     */
    fun isNewer(remote: String, current: String): Boolean {
        val (r, rPre) = splitVersion(remote)
        val (c, cPre) = splitVersion(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return when {
            rPre == null && cPre == null -> false
            rPre == null -> true   // remote 是正式版、current 是预发布 → 该更新
            cPre == null -> false  // remote 是预发布、current 是正式 → 不新
            else -> comparePrerelease(rPre, cPre) > 0
        }
    }

    /** 拆成（数字段, 预发布后缀），后缀为 null 表示正式版 */
    private fun splitVersion(v: String): Pair<List<Int>, String?> {
        val trimmed = v.trim().removePrefix("v").removePrefix("V")
        val sep = trimmed.indexOfFirst { it == '-' || it == '+' }
        val core = if (sep >= 0) trimmed.substring(0, sep) else trimmed
        val pre = if (sep >= 0) trimmed.substring(sep + 1).ifBlank { null } else null
        return core.split(".").map { it.toIntOrNull() ?: 0 } to pre
    }

    /** alpha1 < alpha2 < beta1：前缀按字典序，序号按数字 */
    private fun comparePrerelease(a: String, b: String): Int {
        val ap = a.takeWhile { !it.isDigit() }
        val bp = b.takeWhile { !it.isDigit() }
        if (ap != bp) return ap.compareTo(bp)
        val an = a.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
        val bn = b.dropWhile { !it.isDigit() }.toIntOrNull() ?: 0
        return an.compareTo(bn)
    }

    /** 当前安装版本名 */
    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Throwable) {
        "0.0.0"
    }

    /** Releases 页面地址（无 APK 时兜底） */
    fun releasesPageUrl(): String = "https://github.com/$REPO/releases"
}
