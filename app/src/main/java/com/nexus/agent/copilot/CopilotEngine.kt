package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.overlay.CopilotIslandOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 情绪陪玩引擎 —— 看画面、判突变、喊吐槽。
 *
 * 省电策略（不跑就不会耗电）：
 *  1. 每 [SAMPLE_INTERVAL_MS] 抓一帧（降采样到 720p）；
 *  2. 压成 32×32 灰度图，与上一帧算 **MAD（平均绝对差）**；
 *  3. MAD < [MAD_THRESHOLD] 视为画面没大变化 → 直接丢弃，**不做推理**；
 *  4. MAD ≥ 15（爆炸 / 死亡 / 大招）才唤醒本地 Qwen2-VL 生成弹幕；
 *  5. 触发一次后强制冷却 [COOLDOWN_MS]，避免刷屏与连续推理烧电。
 *
 * 隐私熔断：前台应用命中银行 / 支付 / 密码类关键词时立即停止采样（连截图都不抓）。
 */
object CopilotEngine {

    private const val TAG = "CopilotEngine"

    /** 采样间隔（毫秒）：约 0.8 fps，足够捕捉突变又不太耗电 */
    private const val SAMPLE_INTERVAL_MS = 1200L

    /** 截图降采样最大边长 */
    private const val CAPTURE_MAX_DIM = 720

    /** 灰度缩略图边长 */
    private const val THUMB_SIZE = 32

    /** 画面突变阈值（0~255 灰度平均绝对差） */
    private const val MAD_THRESHOLD = 15

    /** 触发后冷却时间 */
    private const val COOLDOWN_MS = 5000L

    /** 弹幕最大字数（提示词要求 20 字，这里留点余量做兜底截断） */
    private const val MAX_BUBBLE_CHARS = 30

    /** 赛博陪玩提示词（用户指定口径：毒舌网友、不带 AI 废话） */
    private const val EMOTION_PROMPT =
        "你是玩家的赛博陪玩。用不超过20个字发弹幕。" +
            "如果玩家死了/受击，幸灾乐祸（如：'哈哈，被苦力怕炸飞了吧'）。" +
            "如果秀操作，疯狂夸赞（如：'666，这波走位无敌'）。" +
            "语气要像网友，不带AI废话。"

    /** 隐私熔断关键词（包名或应用名命中即停） */
    private val PRIVATE_KEYWORDS = listOf(
        "银行", "支付", "证券", "基金", "理财", "保险", "贷款", "金融", "借贷",
        "钱包", "支付宝", "财付通", "云闪付", "密码", "安全中心",
        "wallet", "pay", "bank", "password", "keychain"
    )

    @Volatile
    var isRunning: Boolean = false
        private set

    /** 最近一次跳过的原因（供 UI 提示，如「本地模型未就绪」） */
    @Volatile
    var lastSkipReason: String? = null
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var appContext: Context? = null
    private var lastFrame: IntArray? = null
    private var lastTriggerAt = 0L

    /**
     * 开始感知循环（幂等）。
     */
    fun start(context: Context) {
        if (isRunning) return
        val ctx = context.applicationContext
        appContext = ctx
        if (!CopilotConfig.isCompanionEnabled(ctx)) {
            Log.d(TAG, "情绪陪玩开关未开，不启动")
            return
        }
        isRunning = true
        lastFrame = null
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            Log.i(TAG, "情绪陪玩引擎启动（MAD ≥ $MAD_THRESHOLD 才推理）")
            try {
                loop(ctx)
            } catch (e: Throwable) {
                Log.e(TAG, "感知循环异常退出", e)
            } finally {
                isRunning = false
                Log.i(TAG, "情绪陪玩引擎停止")
            }
        }
    }

    /**
     * 停止感知循环。
     */
    fun stop() {
        if (!isRunning && job == null) return
        isRunning = false
        job?.cancel()
        job = null
        scope = null
        lastFrame = null
        Log.i(TAG, "已请求停止情绪陪玩引擎")
    }

    // ---------------------------------------------------------------- 内部实现

    private suspend fun loop(ctx: Context) {
        while (scope?.isActive == true && isRunning) {
            delay(SAMPLE_INTERVAL_MS)

            if (!CopilotConfig.isCompanionEnabled(ctx)) {
                Log.i(TAG, "开关被关闭，退出循环")
                return
            }

            // ---- 隐私熔断：命中敏感应用连截图都不做 ----
            val pkg = NexusAccessibilityService.currentForegroundPackage()
            if (isPrivateApp(ctx, pkg)) {
                lastFrame = null
                lastSkipReason = "检测到敏感应用，已暂停读屏"
                continue
            }
            if (pkg != null && pkg == ctx.packageName) {
                // 自己的界面没必要吐槽
                lastFrame = null
                continue
            }

            val bitmap = try {
                ScreenCapture.captureBitmap(CAPTURE_MAX_DIM)
            } catch (e: Throwable) {
                Log.w(TAG, "截图失败：${e.message}")
                null
            } ?: continue

            val gray = toGrayThumb(bitmap)

            val prev = lastFrame
            lastFrame = gray
            if (prev == null) {
                bitmap.recycle()
                continue
            }

            val mad = mad(prev, gray)
            if (mad < MAD_THRESHOLD) {
                bitmap.recycle()
                continue
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastTriggerAt < COOLDOWN_MS) {
                bitmap.recycle()
                Log.d(TAG, "突变 mad=${"%.1f".format(mad)} 但仍在冷却，跳过")
                continue
            }
            lastTriggerAt = now
            Log.i(TAG, "画面突变 mad=${"%.1f".format(mad)} → 触发本地推理")

            // 推理用原始 720p 彩色帧（32×32 灰度只用于差分判定）
            val bubble = try {
                generateBubble(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            if (bubble.isNullOrBlank()) continue

            CopilotConfig.setLastBubble(ctx, bubble)
            withContext(Dispatchers.Main) {
                if (!CopilotIslandOverlay.isShowing()) {
                    CopilotIslandOverlay.show(ctx)
                }
                CopilotIslandOverlay.speak(bubble)
            }
        }
    }

    /**
     * 调本地 Qwen2-VL 生成一句弹幕。
     *
     * 模型没下载 / 未就绪时返回 null（不降级云端：吐槽这类高频调用走云端既慢又费钱）。
     *
     * @param frame 触发突变的那一帧原图（720p 彩色），不是 32×32 灰度缩略图
     */
    private suspend fun generateBubble(frame: Bitmap): String? {
        val raw = LocalScreenRecognizer.infer(frame, EMOTION_PROMPT)
        if (raw.isNullOrBlank()) {
            lastSkipReason = "本地模型未就绪（设置 → 下载本地模型）"
            Log.w(TAG, "本地推理无输出，跳过本次吐槽")
            return null
        }
        lastSkipReason = null
        return cleanBubble(raw)
    }

    /** 清洗模型输出：去引号/换行/前缀，截断到合理长度 */
    private fun cleanBubble(raw: String): String {
        var s = raw.trim()
        // 去掉常见的模型自述前缀
        listOf("弹幕：", "弹幕:", "输出：", "输出:", "回答：", "回答:").forEach { s = s.removePrefix(it) }
        s = s.replace("\r", "").replace("\n", " ").trim()
        s = s.trim('"', '\'', '“', '”', '「', '」', '《', '》', ' ')
        if (s.length > MAX_BUBBLE_CHARS) s = s.take(MAX_BUBBLE_CHARS)
        return s
    }

    /** 降采样到 32×32 灰度（亮度加权，与肉眼观感更接近） */
    private fun toGrayThumb(src: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(src, THUMB_SIZE, THUMB_SIZE, true)
        val pixels = IntArray(THUMB_SIZE * THUMB_SIZE)
        small.getPixels(pixels, 0, THUMB_SIZE, 0, 0, THUMB_SIZE, THUMB_SIZE)
        if (small !== src && !small.isRecycled) small.recycle()
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        return out
    }

    /** 平均绝对差（MAD） */
    private fun mad(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 255.0
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    /** 前台应用是否为银行 / 支付 / 密码类（命中即暂停读屏） */
    private fun isPrivateApp(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val lower = pkg.lowercase()
        if (PRIVATE_KEYWORDS.any { lower.contains(it.lowercase()) }) return true
        val label = try {
            val info = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(info).toString()
        } catch (e: Throwable) {
            ""
        }
        return label.isNotEmpty() && PRIVATE_KEYWORDS.any { label.contains(it) }
    }
}
