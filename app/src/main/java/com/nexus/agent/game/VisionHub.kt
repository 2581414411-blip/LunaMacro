package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 本地视觉的**唯一入口**（0.8.18）。
 *
 * ## 为什么要收成一个「集体」
 *
 * 之前每条链路各写各的：聊天读屏、副驾问答、情绪陪玩、Agent 状态、本地规划，
 * 五个地方各自决定「截多大、用哪个 prompt、失败了怎么办」。后果很具体：
 *
 * | 现象 | 真实原因 |
 * |---|---|
 * | 对话看不到屏幕 | 聊天链路自己截 720，其余链路截 640 —— 只有 640 那批出过结果 |
 * | 模型起了却「没看成」 | 每次推理后没人统一重试，空输出就直接放弃 |
 * | 每次用都要等装载 | 没有「预热」概念，模型装完还会被空闲计时器卸掉 |
 *
 * 现在统一成：**一个模型（`:vision` 子进程常驻）→ 一个入口（本对象）→ 谁要谁调用**。
 * 指挥台、语音、副驾、对话全部只认这里，尺寸与重试策略只有一份实现。
 *
 * ## 用法
 * ```kotlin
 * VisionHub.warmUp(context)              // 一键预热（装载并常驻）
 * val r = VisionHub.look(context, bmp)   // 看图
 * val t = VisionHub.ask(context, "...")  // 无图纯文本
 * ```
 */
object VisionHub {

    private const val TAG = "VisionHub"

    /**
     * 送进模型的截图最长边。
     *
     * **640 是唯一验证过能出结果的尺寸**（0.8.13-alpha10 的真机日志里，
     * 两张 288×640 的图分别吐了 314 / 1080 字节真实描述）。
     * 聊天链路原来自己截 720（324×720），同一进程同一条 prompt 却输出 1 字节。
     * 尺寸是不是根因还没定论，但没理由继续用没验证过的那个 —— 先全体对齐到 640。
     */
    const val MAX_EDGE = 640

    /** 宽高都对齐到 8 的倍数（JPEG 色度采样与视觉编码器都按偶数块切，奇数边容易踩坑） */
    private const val ALIGN = 8

    /** 看图失败后的保底 prompt：不约束格式、不要求结构，最容易激活模型 */
    private const val FALLBACK_PROMPT = "这张手机截图里显示的是什么？用一两句话说明。"

    /**
     * 一次看图的结论。
     *
     * @param text   模型描述（[ok] 为 false 时为空）
     * @param reason 失败原因（给用户看的短句，可直接写进日志/聊天提示）
     * @param size   实际送进模型的尺寸，形如 `280x616` —— 排障时最想知道的就是这个
     * @param retried 是否用过保底重试
     */
    data class Look(
        val text: String?,
        val ok: Boolean,
        val reason: String? = null,
        val size: String = "",
        val retried: Boolean = false
    )

    /**
     * 一键预热：把模型装进 `:vision` 子进程并让它常驻。
     *
     * 装载本身要 20~60 秒（1.4GB 权重），以前这几十秒总是藏在「用户说第一句话之后」，
     * 表现就是「第一句读屏特别慢、像死机」。现在可以提前点火。
     *
     * @return 一句人话（给设置页 toast 直接显示）
     */
    suspend fun warmUp(context: Context): String {
        val t0 = System.currentTimeMillis()
        Log.i(TAG, "预热请求：让子进程装载模型并常驻")
        val outcome = VisionWorkerClient.warmUp(context)
        val cost = (System.currentTimeMillis() - t0) / 1000
        return when (outcome) {
            is VisionWorkerClient.Outcome.Done ->
                if (outcome.ok) {
                    Log.i(TAG, "预热完成，用时 ${cost}s：${outcome.text}")
                    "模型已就绪并常驻（用时 ${cost}s），之后读屏不用再等装载"
                } else {
                    Log.w(TAG, "预热失败：${outcome.reason}")
                    "预热失败：${outcome.reason ?: outcome.text}"
                }
            is VisionWorkerClient.Outcome.ProcessDied -> {
                Log.w(TAG, "预热时子进程挂了：${outcome.reason}")
                "预热时子进程被终止：${outcome.reason}"
            }
        }
    }

    /**
     * 看一张图（截图）。
     *
     * 统一做三件事：
     * 1. **归一化尺寸**：长边压到 [MAX_EDGE]、宽高对齐 8 —— 不管调用方给多大；
     * 2. 交给 `:vision` 子进程推理；
     * 3. 输出为空时**用保底 prompt 再试一次**（模型偶尔对「不要输出 JSON」这类约束直接 EOS）。
     *
     * @param shot   截图（所有权归调用方，这里只读）
     * @param prompt 视觉提示词
     */
    suspend fun look(
        context: Context,
        shot: Bitmap,
        prompt: String,
        timeoutMs: Long = 90_000L
    ): Look {
        if (shot.isRecycled) return Look(null, false, "截图已被回收", retried = false)

        val firstTry = runOnce(context, shot, prompt, timeoutMs)
        if (firstTry.first != null) return firstTry.second

        Log.w(TAG, "首次看图无输出（${firstTry.second.reason}），用保底 prompt 重试一次")
        val second = runOnce(context, shot, FALLBACK_PROMPT, timeoutMs)
        return if (second.first != null) second.second.copy(retried = true)
        else firstTry.second.copy(retried = true, reason = firstTry.second.reason ?: second.second.reason)
    }

    /** 纯文本问本地模型（没有截图时用） */
    suspend fun ask(context: Context, prompt: String, timeoutMs: Long = 90_000L): Look {
        return when (val o = VisionWorkerClient.inferText(context, prompt, timeoutMs)) {
            is VisionWorkerClient.Outcome.Done ->
                if (o.ok && o.text.isNotBlank()) Look(o.text, true)
                else Look(null, false, o.reason ?: "模型没有输出")
            is VisionWorkerClient.Outcome.ProcessDied -> Look(null, false, o.reason)
        }
    }

    /** 模型当前是否已装载并常驻（子进程活着） */
    fun isResident(): Boolean = LocalScreenRecognizer.isReady()

    /** 归一化后的尺寸说明（日志/UI 用） */
    fun describeSize(shot: Bitmap): String = "${targetWidth(shot)}x${targetHeight(shot)}"

    // ------------------------------------------------------------------ 内部

    /** 跑一次推理，返回 (原文, 结论) */
    private suspend fun runOnce(
        context: Context,
        shot: Bitmap,
        prompt: String,
        timeoutMs: Long
    ): Pair<String?, Look> {
        val normalized = normalize(shot)
        val size = "${normalized.width}x${normalized.height}"
        val outcome = try {
            VisionWorkerClient.describe(
                context = context,
                shot = normalized,
                prompt = prompt,
                timeoutMs = timeoutMs
            )
        } finally {
            if (normalized !== shot && !normalized.isRecycled) normalized.recycle()
        }
        return when (outcome) {
            is VisionWorkerClient.Outcome.Done -> {
                val text = outcome.text.trim()
                if (outcome.ok && text.isNotEmpty()) {
                    Log.i(TAG, "看图成功（$size，${text.length} 字）")
                    text to Look(text, true, size = size)
                } else {
                    val why = outcome.reason ?: "模型返回空（${outcome.text.length} 字节）"
                    Log.w(TAG, "看图失败（$size）：$why")
                    null to Look(null, false, why, size)
                }
            }
            is VisionWorkerClient.Outcome.ProcessDied -> {
                Log.w(TAG, "看图时子进程挂了（$size）：${outcome.reason}")
                null to Look(null, false, outcome.reason, size)
            }
        }
    }

    /**
     * 缩到统一尺寸。已经合规就直接返回原图（不复制，省一次 640 级别的内存）。
     */
    private fun normalize(src: Bitmap): Bitmap {
        val tw = targetWidth(src)
        val th = targetHeight(src)
        if (tw == src.width && th == src.height) return src
        return Bitmap.createScaledBitmap(src, tw, th, true)
    }

    private fun targetWidth(src: Bitmap): Int {
        val scale = scaleOf(src)
        val w = (src.width * scale).roundToInt()
        return alignDown(w)
    }

    private fun targetHeight(src: Bitmap): Int {
        val scale = scaleOf(src)
        val h = (src.height * scale).roundToInt()
        return alignDown(h)
    }

    private fun scaleOf(src: Bitmap): Float {
        val edge = max(src.width, src.height)
        return if (edge <= MAX_EDGE) 1f else MAX_EDGE.toFloat() / edge
    }

    private fun alignDown(v: Int): Int = max(ALIGN, v / ALIGN * ALIGN)
}
