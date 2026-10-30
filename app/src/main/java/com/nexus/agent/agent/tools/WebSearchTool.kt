package com.nexus.agent.agent.tools

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * web_search —— 联网搜索（解决「今天 / 最新 / 每日任务」这类模糊或时效性任务）。
 *
 * 主源：DuckDuckGo HTML 版（`html.duckduckgo.com/html/?q=`），无需 Key。
 * 备源：Bing 国内站（`cn.bing.com/search?q=`）—— DuckDuckGo 在国内常被墙，
 *      主源失败时自动降级，保证「有网就能搜到点东西」。
 *
 * 只取前 [DEFAULT_LIMIT] 条（标题 + 摘要 + 链接），拼成紧凑文本给 Planner 当参考，
 * 不做正文抓取：① 省时间 ② 避免把 prompt 撑爆 ③ 攻略类信息摘要通常够用。
 *
 * 失败一律返回中文原因（不抛异常），让上层 Planner 能照常规划。
 */
object WebSearchTool : Tool {

    private const val TAG = "WebSearchTool"

    /** 默认取几条 */
    private const val DEFAULT_LIMIT = 3

    /** 拼给模型的文本上限（字符），防止塞爆上下文 */
    private const val MAX_OUTPUT_CHARS = 1500

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override val name = "web_search"

    override val schema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词，如「原神 今日委托 攻略」")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "返回条数，默认 3，最多 5")
            })
        })
        put("required", JSONArray().put("query"))
    }

    /**
     * 工具执行入口。
     *
     * 接口是同步的，这里用 `runBlocking(Dispatchers.IO)` 包一层：
     * 即使上层误在主线程调用，也不会触发 NetworkOnMainThreadException。
     */
    override fun execute(args: Map<String, Any?>): String {
        val query = args["query"]?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return "错误：缺少 query 参数"
        val limit = (args["limit"]?.toString()?.toIntOrNull() ?: DEFAULT_LIMIT).coerceIn(1, 5)
        return runBlocking { withContext(Dispatchers.IO) { search(query, limit) } }
    }

    /**
     * 供 Planner 直接调用（已经在协程里就用这个，别走 [execute] 的 runBlocking）。
     *
     * @return 可直接塞进 prompt 的搜索摘要；失败返回 null
     */
    suspend fun searchForPrompt(query: String, limit: Int = DEFAULT_LIMIT): String? {
        val text = withContext(Dispatchers.IO) { search(query, limit) }
        return if (text.startsWith("搜索失败") || text.startsWith("错误")) null else text
    }

    // ------------------------------------------------------------------ 搜索主流程

    private fun search(query: String, limit: Int): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        // 主源：DuckDuckGo HTML
        val ddg = runCatching { fetchDdg(encoded, limit) }
            .onFailure { Log.w(TAG, "DDG 搜索失败：${it.message}") }
            .getOrNull()
        if (!ddg.isNullOrEmpty()) return format("DuckDuckGo", query, ddg)

        // 备源：Bing（国内可直连）
        val bing = runCatching { fetchBing(encoded, limit) }
            .onFailure { Log.w(TAG, "Bing 搜索失败：${it.message}") }
            .getOrNull()
        if (!bing.isNullOrEmpty()) return format("Bing", query, bing)

        return "搜索失败：两个搜索源都没取到结果（可能无网络或站点被拦截）。" +
            "请按已有常识继续，不要重复搜索。"
    }

    private fun format(source: String, query: String, items: List<Result>): String {
        val sb = StringBuilder()
        sb.append("搜索「").append(query).append("」· 来源 ").append(source).append("\n")
        items.forEachIndexed { i, r ->
            sb.append("${i + 1}. ${r.title}\n")
            if (r.snippet.isNotBlank()) sb.append("   ${r.snippet}\n")
            if (r.url.isNotBlank()) sb.append("   ${r.url}\n")
        }
        val out = sb.toString().trim()
        return if (out.length <= MAX_OUTPUT_CHARS) out else out.take(MAX_OUTPUT_CHARS) + "\n…（已截断）"
    }

    private data class Result(val title: String, val url: String, val snippet: String)

    // ------------------------------------------------------------------ 源一：DuckDuckGo

    private fun fetchDdg(encodedQuery: String, limit: Int): List<Result> {
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encodedQuery")
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .build()

        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "DDG HTTP ${resp.code}")
                return emptyList()
            }
            resp.body?.string().orEmpty()
        }
        if (html.isBlank()) return emptyList()

        val results = mutableListOf<Result>()
        var pendingTitle = ""
        var pendingUrl = ""

        ANCHOR_RE.findAll(html).forEach { m ->
            if (results.size >= limit) return@forEach
            val attrs = m.groupValues[1]
            val inner = m.groupValues[2]
            when {
                attrs.contains("result__a", ignoreCase = true) -> {
                    pendingTitle = stripHtml(inner)
                    pendingUrl = normalizeUrl(extractAttr(attrs, "href"))
                }
                attrs.contains("result__snippet", ignoreCase = true) -> {
                    if (pendingTitle.isNotEmpty()) {
                        results.add(Result(pendingTitle, pendingUrl, stripHtml(inner)))
                        pendingTitle = ""
                        pendingUrl = ""
                    }
                }
            }
        }
        // 有标题没摘要的也收进来
        if (results.size < limit && pendingTitle.isNotEmpty()) {
            results.add(Result(pendingTitle, pendingUrl, ""))
        }
        return results.take(limit)
    }

    // ------------------------------------------------------------------ 源二：Bing（备）

    private fun fetchBing(encodedQuery: String, limit: Int): List<Result> {
        val req = Request.Builder()
            .url("https://cn.bing.com/search?q=$encodedQuery&setlang=zh-CN")
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        val html = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Bing HTTP ${resp.code}")
                return emptyList()
            }
            resp.body?.string().orEmpty()
        }
        if (html.isBlank()) return emptyList()

        val out = mutableListOf<Result>()
        // 每条结果在 <li class="b_algo"> 里，标题是第一个 <a href=...>，摘要在 <p> 里
        html.split("<li class=\"b_algo").drop(1).forEach { block ->
            if (out.size >= limit) return@forEach
            val a = ANCHOR_RE.find(block) ?: return@forEach
            val url = normalizeUrl(extractAttr(a.groupValues[1], "href"))
            val title = cleanTitle(stripHtml(a.groupValues[2]))
            val snippet = PARA_RE.find(block)?.let { stripHtml(it.groupValues[1]) } ?: ""
            if (title.isNotEmpty()) out.add(Result(title, url, snippet))
        }
        return out
    }

    // ------------------------------------------------------------------ 解析工具

    private val ANCHOR_RE = Regex(
        """<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val PARA_RE = Regex(
        """<p\b[^>]*>(.*?)</p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /** 取标签属性值（支持单/双引号） */
    private fun extractAttr(attrs: String, key: String): String {
        val m = Regex("""\b$key\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)
        return m?.groupValues?.get(1).orEmpty()
    }

    /** 去掉 HTML 标签 + 解实体 + 压空白 */
    private fun stripHtml(raw: String): String {
        var s = raw.replace(Regex("""<[^>]+>"""), " ")
        s = s.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&hellip;", "…")
        s = s.replace(Regex("""\s+"""), " ")
        return s.trim()
    }

    /**
     * 清洗标题：Bing 的 `<a>` 里会带面包屑（"站点名 https://xxx › 路径"），
     * 只保留前面的真实标题部分。
     */
    private fun cleanTitle(raw: String): String {
        var t = raw
        val idx = t.indexOf(" http")
        if (idx > 0) t = t.substring(0, idx)
        // 去掉正文里残留的引用符号
        t = t.replace("›", " ").replace(Regex("""\s+"""), " ")
        return t.trim()
    }

    /**
     * 还原真实链接：
     * - DDG 跳转 `//duckduckgo.com/l/?uddg=<encoded>&rut=...` → 取 uddg
     * - 协议相对 `//example.com` → 补 https
     */
    private fun normalizeUrl(url: String): String {
        if (url.isBlank()) return ""
        var u = url
        if (u.startsWith("//")) u = "https:$u"
        if (u.contains("uddg=")) {
            val encoded = u.substringAfter("uddg=").substringBefore("&")
            u = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: Throwable) {
                u
            }
        }
        return u
    }
}
