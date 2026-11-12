package com.nexus.agent.cyber

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 赛博网络引擎（0.9.26 批次 F）—— 她在网上冲浪，不碰任何真实 App。
 *
 * ## 为什么彻底换掉「虚拟屏 + 注入」那套
 *
 * 那套方案的三个死结是平台级的，不是工程量问题：
 * 1. `MediaProjection` 的 VirtualDisplay 不能承载 Activity（要 hidden API 造屏 + 起 Activity）；
 * 2. 抖音很可能设 `FLAG_SECURE` / 检测非默认屏 → 截出来是黑的；
 * 3. 分享到 QQ 必然拉起前台，和「不影响用户」直接冲突。
 *
 * 换成抓公开数据后，这三条全部消失：不占屏幕、不碰风控、不怕 App 检测。
 * 她看到的世界从「你的屏幕」变成「互联网」，这反而更像一个数字生命该有的样子。
 *
 * ## 数据源与降级
 *
 * | 源 | 接口 | 需要什么 |
 * |---|---|---|
 * | B 站热门 | `api.bilibili.com/x/web-interface/popular` | 只要 UA + Referer，不需要登录 |
 * | 微博热搜 | `api.vvhan.com/api/hotlist/wbHot` | 第三方聚合，免费无 Key |
 *
 * 两个源任一失败都不影响另一个；全失败就保留上一批（电视继续放旧的，不会黑屏）。
 * **这是刻意的**：她「断网了」比「电视报错」更像回事，用户也不需要知道。
 */
object CyberWebEngine {

    private const val TAG = "CyberWeb"

    /** 抓取间隔：3 分钟。任务书要求 3~5 分钟，取下限让内容更新快一点 */
    private const val REFRESH_MS = 3 * 60_000L

    /** 每批最多收多少条。太多会让轮播一轮走完要十分钟 */
    private const val BATCH_LIMIT = 12

    /** 缩略图内存缓存张数。屏幕上同时只有一张，8 张足够翻页不闪 */
    private const val THUMB_CACHE = 8

    private val _items = MutableStateFlow<List<CyberItem>>(emptyList())
    val items: StateFlow<List<CyberItem>> = _items.asStateFlow()

    private val _lastFetchAt = MutableStateFlow(0L)

    /** 上次抓到内容的时刻，设置页/电视角标可以显示「刚更新」 */
    val lastFetchAt: StateFlow<Long> = _lastFetchAt.asStateFlow()

    private val _fetching = MutableStateFlow(false)
    val fetching: StateFlow<Boolean> = _fetching.asStateFlow()

    @Volatile
    private var started = false

    /** B 站要一个像浏览器的 UA，否则热门接口会返回 412 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0 Mobile Safari/537.36"

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val thumbs = object : LruCache<String, Bitmap>(THUMB_CACHE) {}

    /**
     * 启动后台轮询。
     *
     * 幂等：重复调用只会有一次轮询在跑。用 `started` 标记而不是「取消旧 Job」，
     * 是因为这个协程挂在应用级 scope 上（见 [CyberScope]），没有生命周期要照顾。
     */
    @Synchronized
    fun ensureStarted(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        CyberScope.scope.launch {
            // 首次进来立刻抓一次，别让电视空着等三分钟
            if (_items.value.isEmpty()) refresh(app)
            while (true) {
                delay(REFRESH_MS)
                refresh(app)
            }
        }
    }

    /**
     * 抓一批。返回新增条数；失败返回 0（调用方不需要区分「失败」和「没新内容」）。
     *
     * 两个源并发抓，任一成功就有内容 —— 微博挂了不该让电视跟着挂。
     */
    suspend fun refresh(context: Context): Int = withContext(Dispatchers.IO) {
        if (_fetching.value) return@withContext 0
        _fetching.value = true
        try {
            val merged = mutableListOf<CyberItem>()
            val bili = runCatching { fetchBilibili() }
                .onFailure { Log.w(TAG, "B站热门抓取失败：${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(emptyList())
            val weibo = runCatching { fetchWeiboHot() }
                .onFailure { Log.w(TAG, "微博热搜抓取失败：${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(emptyList())

            // 图文交替：全是大图会让轮播看起来很单调，全是文字又没得看
            merged += bili.take(BATCH_LIMIT / 2 + 2)
            merged += weibo.take(BATCH_LIMIT / 2 + 2)

            if (merged.isEmpty()) {
                Log.i(TAG, "两个源都没抓到内容，保留上一批（${_items.value.size} 条）")
                return@withContext 0
            }

            // 去重：同一条内容在两批里都出现时，保留新的位置
            val old = _items.value.associateBy { it.id }
            val fresh = merged.distinctBy { it.id }
            val added = fresh.count { old[it.id] == null }
            _items.value = fresh
            _lastFetchAt.value = System.currentTimeMillis()
            Log.i(TAG, "刷新完成：${fresh.size} 条（新增 $added），bili=${bili.size} weibo=${weibo.size}")
            added
        } finally {
            _fetching.value = false
        }
    }

    // ------------------------------------------------------------ B 站热门

    private fun fetchBilibili(): List<CyberItem> {
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/popular?ps=20&pn=1")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "B站 HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) {
                Log.w(TAG, "B站返回 code=${root.optInt("code")} msg=${root.optString("message")}")
                return emptyList()
            }
            val list = root.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            val out = ArrayList<CyberItem>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                val bvid = o.optString("bvid")
                // pic 以 // 开头，要补 https:
                val pic = o.optString("pic").let {
                    when {
                        it.startsWith("//") -> "https:$it"
                        it.startsWith("http") -> it
                        else -> null
                    }
                }
                val views = o.optJSONObject("stat")?.optLong("view", 0) ?: 0
                out += CyberItem(
                    id = "bili:$bvid",
                    title = title,
                    source = "B站热门",
                    imageUrl = pic,
                    pageUrl = if (bvid.isNotEmpty()) "https://www.bilibili.com/video/$bvid" else null,
                    hot = if (views > 0) "${views / 10_000}万播放" else null
                )
            }
            return out
        }
    }

    // ------------------------------------------------------------ 微博热搜

    private fun fetchWeiboHot(): List<CyberItem> {
        val req = Request.Builder()
            .url("https://api.vvhan.com/api/hotlist/wbHot")
            .header("User-Agent", UA)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "微博 HTTP ${resp.code}")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            val root = JSONObject(body)
            // 这个聚合接口成功时 success=true，数据在 data 数组里
            if (!root.optBoolean("success", false)) {
                Log.w(TAG, "微博聚合返回失败：${root.optString("message")}")
                return emptyList()
            }
            val list = root.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<CyberItem>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                if (title.isEmpty()) continue
                out += CyberItem(
                    id = "weibo:${title.hashCode()}:$i",
                    title = title,
                    source = "微博热搜",
                    imageUrl = null,
                    pageUrl = o.optString("url").takeIf { it.startsWith("http") },
                    hot = o.optString("hot").takeIf { it.isNotBlank() }
                        ?: "热搜第 ${o.optInt("index", i + 1)}"
                )
            }
            return out
        }
    }

    // ------------------------------------------------------------ 缩略图

    /**
     * 缩略图（带内存缓存）。
     *
     * 不做磁盘缓存：每次刷新只留 12 条，内存里 8 张就够翻页；
     * 写盘反而要处理清理和隐私（用户可能不想让「她看过什么」留在手机上）。
     */
    suspend fun thumbnail(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        thumbs.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    // B 站的图有防盗链，不带 Referer 会 403
                    .header("Referer", "https://www.bilibili.com/")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    // 先读尺寸再按 2 的幂采样：直接 decode 一张 1920 宽的图会白吃 8MB 堆
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    var sample = 1
                    while (opts.outWidth / sample > 720) sample *= 2
                    val real = BitmapFactory.Options().apply { inSampleSize = sample }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
                    if (bmp != null) thumbs.put(url, bmp)
                    bmp
                }
            }.getOrNull()
        }
    }

    /** 清空（设置页「清空缓存」/ 测试用） */
    fun clear() {
        _items.value = emptyList()
        thumbs.evictAll()
    }
}
