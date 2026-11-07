package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.game.VisionHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 视觉路由 —— 用户发来的那张图「到底由谁看」，统一走这里。
 *
 * ## 为什么要有这个类
 *
 * 0.9.6/0.9.7 的图片处理是散在 [PersonaEngine.respond] 里的一堆 if/else：
 * `canVision` 走云端、否则走本地、都不行给句 notice。逻辑本身没错，
 * 但「选哪条路」和「看图」耦合在一起，用户配了 DeepSeek（纯文本）后：
 *   1. 没有独立的云端视觉 Key 可配（视觉 Key 只能填 `${provider}_api_key`，设置页没入口）；
 *   2. 本地 2B 模型识别率差，失败话术含糊；
 *   3. 全链路没有统一日志，到底哪一步瞎了只能靠猜。
 *
 * 所以把「视觉提取」抽成独立一级：[processImage] 只做一件事 ——
 * 把一张图变成一段文字描述，并明确告诉调用方「走的是哪条腿、成了没有」。
 * 文本推理（DeepSeek）和视觉提取彻底分离，主模型永远不会收到 Base64。
 *
 * ## 级联优先级（用户指定，0.9.7）
 *
 * ```
 * 1. Cloud_VL   —— 有独立视觉 Key（或主模型自己能看图）：调云端多模态，图 → 详细文字描述
 * 2. Local_VL   —— 无云端 Key / 云端超时或失败：本地 Qwen2-VL-2B（:vision 子进程）
 * 3. Failed     —— 两边都失败：返回明确的兜底文案，绝不编造
 * ```
 */
object VisionRouter {

    private const val TAG = "VisionRouter"

    /** 云端视觉请求超时（秒）。超时就降级本地，别让用户干等 */
    private const val CLOUD_TIMEOUT_MS = 25_000L

    /** 云端视觉描述图的提示词 */
    private const val CLOUD_DESCRIBE_PROMPT =
        "请用两三句话客观描述这张图片：画面里有什么、大概在做什么、有什么值得注意的细节。" +
            "不要评价、不要给建议、不要猜测拍摄者的心情。只输出描述本身。"

    /**
     * 一次视觉提取的结果。
     *
     * @param route 走了哪条腿：[Route]
     * @param description 提取出的文字描述（null = 失败，由 [failNotice] 给出人设话术）
     * @param failNotice 失败时给用户的明确提示（route==Failed 时非空）
     * @param detail 附加说明（模型名 / 失败原因），供日志
     */
    data class Result(
        val route: Route,
        val description: String?,
        val failNotice: String? = null,
        val detail: String? = null
    ) {
        val ok: Boolean get() = !description.isNullOrBlank()
    }

    /** 三级路由 */
    enum class Route(val label: String) {
        CLOUD_VL("Cloud_VL"),
        LOCAL_VL("Local_VL"),
        FAILED("Failed")
    }

    /** 两边都失败时的兜底文案（用户点名的确切文本） */
    const val FAIL_NOTICE =
        "（系统：用户发了一张图片，但本地和云端视觉都不可用，我这轮没看成）"

    /**
     * 是否走云端视觉（纯函数，供单测）。
     *
     * 规则：有独立视觉 Key 就走云端；否则主模型自己能看图且配了 Key，也能复用主 Key 走云端。
     */
    internal fun shouldUseCloud(
        visionApiKey: String,
        mainCanVision: Boolean,
        mainApiKey: String
    ): Boolean = visionApiKey.isNotBlank() || (mainCanVision && mainApiKey.isNotBlank())

    /**
     * 视觉提取的唯一入口：把一张图变成文字描述。
     *
     * 云端视觉优先 → 本地兜底 → 失败兜底。全程打 `VisionRoute` 日志，
     * 每一步的成败和原因都能在 logcat 里 grep `[视觉]` 看到。
     *
     * @param bitmap 用户发来的图（不回收，调用方负责）
     */
    suspend fun processImage(context: Context, bitmap: Bitmap?): Result {
        // 空图 / 已回收：直接失败，别往下走
        if (bitmap == null || bitmap.isRecycled) {
            Log.w(TAG, "[视觉] 没有图可看（bitmap=${bitmap ?: "null"}）")
            return Result(Route.FAILED, null, FAIL_NOTICE, "bitmap 为空")
        }
        Log.i(TAG, "[视觉] 检测到图片 ${bitmap.width}×${bitmap.height}")

        // ---- 第一优先级：云端视觉 ----
        val cloudKey = LlmClientFactory.getVisionApiKey(context)
        val mainProvider = LlmClientFactory.getProvider(context)
        val mainConfig = LlmClientFactory.getConfig(context, mainProvider)
        val mainCanVision = LlmClientFactory.supportsVision(mainProvider, mainConfig.third)

        // 云端可用条件：有独立视觉 Key，或主模型自己能看图（复用主 Key）
        val useCloud = shouldUseCloud(cloudKey, mainCanVision, mainConfig.first)

        if (useCloud) {
            val cloud = runCatching {
                withTimeoutOrNull(CLOUD_TIMEOUT_MS) { describeCloud(context, bitmap) }
            }.getOrNull()
            if (cloud != null) {
                val desc = VisionRoute.cleanScreenDescription(cloud) ?: cloud.trim()
                Log.i(
                    TAG,
                    "[视觉] 云端提取成功 route=Cloud_VL，${desc.length} 字：" +
                        desc.take(80)
                )
                return Result(Route.CLOUD_VL, desc, detail = "云端多模态")
            }
            Log.w(TAG, "[视觉] 云端视觉失败或超时，降级本地")
        } else {
            Log.i(TAG, "[视觉] 无云端视觉 Key（独立 Key 空，主模型 ${mainProvider} " +
                "${if (mainCanVision) "能看图但没 Key" else "不支持视觉"}），跳过云端")
        }

        // ---- 第二优先级：本地视觉 ----
        val local = runCatching {
            describeLocal(context, bitmap)
        }.onFailure {
            Log.w(TAG, "[视觉] 本地视觉异常：${it.javaClass.simpleName} ${it.message}")
        }.getOrNull()
        if (local != null) {
            val desc = local.text?.trim()?.takeIf { it.isNotEmpty() }
            if (desc != null) {
                Log.i(TAG, "[视觉] 本地提取成功 route=Local_VL，${desc.length} 字：" + desc.take(80))
                return Result(Route.LOCAL_VL, desc, detail = "本地 Qwen2-VL-2B")
            }
            Log.w(TAG, "[视觉] 本地视觉无输出：${local.reason ?: "未知原因"}")
        }

        // ---- 第三优先级：失败兜底 ----
        Log.e(TAG, "[视觉] 云端和本地都失败，route=Failed")
        return Result(
            Route.FAILED,
            null,
            FAIL_NOTICE,
            "云端${if (useCloud) "尝试过但失败" else "不可用"}，本地无输出"
        )
    }

    /** 云端多模态：把图直接发给视觉模型，拿文字描述 */
    private suspend fun describeCloud(context: Context, bitmap: Bitmap): String? =
        withContext(Dispatchers.IO) {
            val cloudKey = LlmClientFactory.getVisionApiKey(context)
            val baseUrl = LlmClientFactory.getVisionBaseUrl(context)
            val model = LlmClientFactory.getVisionModel(context)

            // 独立视觉 Key 优先；没有就用主 provider 的 Key（此时主模型必能看图）
            val (apiKey, provider, actualModel) = if (cloudKey.isNotBlank()) {
                Triple(cloudKey, "vision", model)
            } else {
                val main = LlmClientFactory.getProvider(context)
                val cfg = LlmClientFactory.getConfig(context, main)
                Triple(cfg.first, main, cfg.third)
            }

            if (apiKey.isBlank()) return@withContext null

            val b64 = CopilotVision.toBase64(bitmap) ?: run {
                Log.w(TAG, "[视觉] 云端：图片编码失败")
                return@withContext null
            }

            // 独立视觉 Key 走独立 baseUrl + model；复用主 Key 时用主 provider 的默认地址
            val client = if (cloudKey.isNotBlank()) {
                LlmClient(apiKey = apiKey, baseUrl = baseUrl, modelName = actualModel)
            } else {
                LlmClientFactory.create(provider, context)
            }

            Log.i(TAG, "[视觉] 云端请求 model=$actualModel 图=${bitmap.width}×${bitmap.height} " +
                "b64=${b64.length} 字符")

            val parts = listOf(
                LlmClient.ContentPart(type = "image_url", imageUrl = "data:image/jpeg;base64,$b64"),
                LlmClient.ContentPart(type = "text", text = CLOUD_DESCRIBE_PROMPT)
            )
            val messages = listOf(
                LlmClient.Message("system", "你是图片描述助手，只输出画面描述。"),
                LlmClient.Message("user", parts)
            )
            val raw = client.chat(messages).trim()
            if (raw.isBlank()) null else raw
        }

    /** 本地视觉：走 :vision 子进程的 Qwen2-VL-2B */
    private suspend fun describeLocal(context: Context, bitmap: Bitmap): VisionHub.Look? {
        // 模型文件不齐直接放弃，别起子进程白等
        val missing = runCatching {
            LocalScreenRecognizer.missingModelFiles(context)
        }.getOrDefault(emptyList())
        if (missing.isNotEmpty()) {
            Log.w(TAG, "[视觉] 本地模型不齐（缺 ${missing.joinToString("、")}），跳过本地")
            return null
        }
        return VisionHub.look(
            context = context,
            shot = bitmap,
            prompt = IMAGE_DESCRIBE_PROMPT,
            fallbackPrompt = IMAGE_DESCRIBE_FALLBACK
        )
    }

    /** 本地视觉描述图的提示词 */
    private const val IMAGE_DESCRIBE_PROMPT =
        "这是主人发来的一张图片。请用两三句话客观描述：画面里有什么、大概在做什么。" +
            "不要评价、不要给建议、不要猜主人的心情。"

    private const val IMAGE_DESCRIBE_FALLBACK = "这张图片里有什么？用两三句话描述。"
}
