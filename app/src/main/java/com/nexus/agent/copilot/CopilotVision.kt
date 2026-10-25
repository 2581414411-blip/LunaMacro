package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.game.LocalScreenRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 副驾驶的「眼睛」—— 把当前屏幕截图塞进大模型请求。
 *
 * 两级通道（离线优先，云端兜底）：
 *  1. **本地** Qwen2-VL-2B（MNN，`LocalScreenRecognizer.infer`）：不联网、不花钱，弱在 JSON 遵循度；
 *  2. **云端** 视觉 provider（默认 qwen-vl，OpenAI 兼容多模态 `image_url` data URI）：强在格式与理解。
 *
 * 本地模型可用时先用它；不可用 / 无输出 / 显式要求高质量时退回云端。
 *
 * 截图统一压到 [MAX_DIM] 长边、JPEG q[QUALITY]，Base64 后约 200KB，避免请求体过大。
 */
object CopilotVision {

    private const val TAG = "CopilotVision"

    /** 送模型的截图最长边（像素）。太大会拖慢云端请求、也增加本地显存压力 */
    private const val MAX_DIM = 900

    /** JPEG 压缩质量 */
    private const val QUALITY = 78

    /** 云端失败原因（显式分类，不再统一 null 静默吞掉） */
    enum class CloudError(val label: String) {
        /** 视觉与主 provider 都没有 Key */
        NoKey("未配置 Key"),

        /** 401 / 403 */
        AuthError("授权失败（401/403）"),

        /** 超时 / DNS / 断网 / 其它 HTTP 错误 */
        NetworkError("连接失败"),

        /** 200 但内容为空 */
        EmptyReply("模型返回空内容")
    }

    /** 云端请求结果（要么带内容，要么带明确的失败分类） */
    sealed class CloudResult {
        data class Ok(
            val text: String,
            val provider: String,
            val model: String,
            val usedVision: Boolean
        ) : CloudResult()

        data class Fail(
            val error: CloudError,
            val detail: String? = null
        ) : CloudResult()
    }

    /** 一次图文问答的结果 */
    data class Answer(
        val raw: String?,
        /** "local" / "cloud" / "none" —— 供日志与 UI 提示 */
        val source: String,
        /** 本轮是否真的把截图喂给了模型（false = 纯文本回复） */
        val usedVision: Boolean = false,
        /** 实际使用的模型标识（provider/model），供日志 */
        val modelLabel: String = "",
        /** 需要追加给用户的提示（如「本轮未读取屏幕」），空串表示无 */
        val notice: String = "",
        /** 失败分类（仅 source=="none" 时有值） */
        val error: CloudError? = null,
        /** 失败细节（HTTP 状态码 / 异常名），供日志与错误提示 */
        val errorDetail: String? = null
    ) {
        val ok: Boolean get() = !raw.isNullOrBlank()
    }

    /**
     * 图文问答。
     *
     * 顺序：**本地优先 → 云端兜底**，但和上一版的区别是：
     *  1. 没有截图也照样问本地（走 [LocalScreenRecognizer.inferText] 纯文本通道），
     *     不再因为 `bitmap == null` 就把本地模型整段跳过；
     *  2. 本地模型在装载（1.3GB，可能几十秒）时通过 [onStage] 把
     *     「本地模型加载中」报给灵动岛，而不是静默等到天荒地老。
     *
     * @param systemPrompt   云端系统提示词（身份声明 / 工具清单 / JSON 契约）
     * @param userText       用户这句话
     * @param bitmap         当前屏幕截图（null = 没截到 / 敏感界面不截）
     * @param localPrompt    本地模型图文提示（有截图时）
     * @param localTextPrompt 本地模型纯文本提示（无截图时）
     * @param preferCloud    跳过本地直接走云端（本地答非所问时重试用）
     * @param onStage        阶段回调（主线程 UI 用）："本地模型加载中" / "本地模型思考中" …
     */
    suspend fun ask(
        context: Context,
        systemPrompt: String,
        userText: String,
        bitmap: Bitmap?,
        localPrompt: String,
        localTextPrompt: String,
        preferCloud: Boolean = false,
        onStage: ((String) -> Unit)? = null
    ): Answer {
        // ---- 1. 本地 Qwen2-VL ----
        if (!preferCloud) {
            if (!LocalScreenRecognizer.isReady()) {
                // 文件在 → 值得等它装载；文件不在 → 直接走云端，不骗用户等
                val willLoad = LocalScreenRecognizer.modelFilesPresent(context)
                if (willLoad) onStage?.invoke("本地模型加载中")
                val loaded = LocalScreenRecognizer.ensureLoaded(context)
                if (loaded != LocalScreenRecognizer.State.READY) {
                    Log.i(
                        TAG,
                        "本地不可用 state=$loaded 原因=${LocalScreenRecognizer.lastError ?: "未知"}"
                    )
                }
            }
            if (LocalScreenRecognizer.isReady()) {
                onStage?.invoke(if (bitmap != null) "本地模型读屏中" else "本地模型思考中")
                val local = try {
                    if (bitmap != null) {
                        LocalScreenRecognizer.infer(bitmap, localPrompt)
                    } else {
                        LocalScreenRecognizer.inferText(localTextPrompt)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "本地推理异常：${e.javaClass.simpleName} ${e.message}")
                    null
                }
                if (!local.isNullOrBlank()) {
                    Log.i(TAG, "本地模型命中（${local.length} 字，图=${bitmap != null}）")
                    return Answer(
                        raw = local,
                        source = "local",
                        usedVision = bitmap != null,
                        modelLabel = "local/qwen2-vl-2b",
                        notice = if (bitmap != null) "" else NO_SHOT_NOTICE
                    )
                }
                Log.i(TAG, "本地模型无输出，尝试云端")
            }
        }

        // ---- 2. 云端兜底（可能是视觉，也可能是纯文本降级）----
        return when (val cloud = askCloud(context, systemPrompt, userText, bitmap)) {
            is CloudResult.Ok -> Answer(
                raw = cloud.text,
                source = "cloud",
                usedVision = cloud.usedVision,
                modelLabel = "${cloud.provider}/${cloud.model}",
                notice = when {
                    cloud.usedVision -> ""
                    // 模型本身不支持视觉
                    !LlmClientFactory.supportsVision(cloud.provider, cloud.model) -> TEXT_ONLY_NOTICE
                    // 模型支持视觉，但这轮没截到图
                    else -> NO_SHOT_NOTICE
                }
            )

            is CloudResult.Fail -> {
                Log.w(TAG, "云端失败：${cloud.error.label} 细节=${cloud.detail ?: "-"}")
                Answer(
                    raw = null,
                    source = "none",
                    error = cloud.error,
                    errorDetail = cloud.detail
                )
            }
        }
    }

    /**
     * 云端请求：**视觉 provider 优先 → 主 provider 兜底 → 不支持视觉就纯文本**。
     *
     * 关键修复（P0）：上一版硬读 `getVisionProvider()`（默认 `qwen-vl`），
     * 而设置页只写主 provider 的 key，于是 `qwen-vl_api_key` 永远为空、
     * 连 HTTP 请求都不会发出，用户配好 DeepSeek 也毫无反应。
     *
     * 现在四种失败原因分别返回，UI 能给出「没配 Key」「401」「网络」这类可执行提示。
     */
    private suspend fun askCloud(
        context: Context,
        systemPrompt: String,
        userText: String,
        bitmap: Bitmap?
    ): CloudResult = withContext(Dispatchers.IO) {
        val visionProvider = LlmClientFactory.getEffectiveVisionProvider(context)
        var provider = visionProvider
        var (apiKey, _, model) = LlmClientFactory.getConfig(context, visionProvider)

        if (apiKey.isBlank()) {
            // 显式指定的视觉 provider 没配 Key → 回退主 provider
            val main = LlmClientFactory.getProvider(context)
            if (main != visionProvider) {
                val cfg = LlmClientFactory.getConfig(context, main)
                Log.i(TAG, "视觉 provider($visionProvider) 无 Key，回退主 provider=$main")
                provider = main
                apiKey = cfg.first
                model = cfg.third
            }
        }
        if (apiKey.isBlank()) {
            Log.w(TAG, "云端无 Key：视觉=$visionProvider 主=${LlmClientFactory.getProvider(context)}")
            return@withContext CloudResult.Fail(
                CloudError.NoKey,
                "视觉=$visionProvider 主=${LlmClientFactory.getProvider(context)}"
            )
        }

        val canVision = LlmClientFactory.supportsVision(provider, model)
        val sendImage = bitmap != null && canVision
        if (bitmap != null && !canVision) {
            Log.i(TAG, "provider=$provider model=$model 不支持视觉，本轮降级纯文本")
        }

        val parts = mutableListOf<LlmClient.ContentPart>()
        if (sendImage) {
            val shot = bitmap!!
            val b64 = toBase64(shot)
            if (b64 == null) {
                return@withContext CloudResult.Fail(CloudError.EmptyReply, "截图编码失败")
            }
            parts.add(
                LlmClient.ContentPart(
                    type = "image_url",
                    imageUrl = "data:image/jpeg;base64,$b64"
                )
            )
        }
        parts.add(LlmClient.ContentPart(type = "text", text = userText))

        // 不支持视觉时，必须换掉「你能看到屏幕」的声明，否则模型会对着空气描述画面
        val system = if (sendImage) systemPrompt else systemPrompt.replace(VISION_HEADER, TEXT_ONLY_HEADER)

        try {
            val client = LlmClientFactory.create(provider, context)
            Log.i(TAG, "云端请求 provider=$provider model=$model 图=$sendImage")
            val raw = client.chat(
                listOf(
                    LlmClient.Message("system", system),
                    LlmClient.Message("user", parts)
                )
            ).trim()
            if (raw.isBlank()) {
                CloudResult.Fail(CloudError.EmptyReply, "${provider}/${model} 返回空")
            } else {
                CloudResult.Ok(raw, provider, model, sendImage)
            }
        } catch (e: LlmClient.ApiException) {
            val kind = if (e.statusCode == 401 || e.statusCode == 403) {
                CloudError.AuthError
            } else {
                CloudError.NetworkError
            }
            Log.w(TAG, "云端 HTTP ${e.statusCode} → ${kind.label}")
            CloudResult.Fail(kind, "HTTP ${e.statusCode}: ${e.responseBody.take(120)}")
        } catch (e: Throwable) {
            Log.w(TAG, "云端请求异常 → ${CloudError.NetworkError.label}：${e.javaClass.simpleName} ${e.message}")
            CloudResult.Fail(
                CloudError.NetworkError,
                "${e.javaClass.simpleName}: ${e.message?.take(100) ?: ""}"
            )
        }
    }

    /**
     * Bitmap → Base64（按 [MAX_DIM] 缩放后 JPEG 压缩）。
     *
     * 不回收传入的 bitmap（调用方负责）。
     */
    fun toBase64(bitmap: Bitmap): String? = try {
        val maxEdge = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxEdge > MAX_DIM) {
            val ratio = MAX_DIM.toFloat() / maxEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            null
        }
        val target = scaled ?: bitmap
        val out = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        val bytes = out.toByteArray()
        out.close()
        if (scaled != null && scaled !== bitmap && !scaled.isRecycled) scaled.recycle()
        Log.d(TAG, "截图编码 ${target.width}x${target.height} → ${bytes.size / 1024}KB")
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } catch (e: Throwable) {
        Log.e(TAG, "截图编码失败", e)
        null
    }

    // ---------------------------------------------------------------- 提示词

    /** 身份声明：让模型知道自己「看得见」屏幕 */
    const val VISION_HEADER =
        "你是宏月 AI 助手。你可以看到用户当前的手机屏幕截图。请结合屏幕内容回答用户的问题。" +
            "如果用户问你\u201C屏幕里是什么\u201D或\u201C我现在在干嘛\u201D，请根据截图直接回答。"

    /** 纯文本降级时替换掉 [VISION_HEADER]，防止模型对着空气描述画面 */
    const val TEXT_ONLY_HEADER =
        "你是宏月 AI 助手。本轮没有屏幕截图，你看不到屏幕内容；" +
            "请不要假装看得见，也不要描述画面，只根据用户文字回答。"

    /** 模型不支持视觉时的用户提示 */
    const val TEXT_ONLY_NOTICE = "（注意：当前模型不支持视觉，本轮未读取屏幕，仅纯文本回复）"

    /** 有视觉能力但这轮没截到图时的用户提示 */
    const val NO_SHOT_NOTICE = "（注意：本轮没截到屏幕，仅纯文本回复）"

    /**
     * 云端完整提示词：视觉 + 全部工具 Schema + JSON 输出契约。
     *
     * 工具清单由 [com.nexus.agent.agent.tools.ToolRegistry.toJsonSchema] 生成，
     * 新增工具自动并入，不需要改这里。
     */
    fun buildFullSystemPrompt(toolSchemaJson: String, foregroundApp: String?): String = buildString {
        append(VISION_HEADER).append("\n\n")
        append("当前前台应用：").append(foregroundApp ?: "未知").append("\n\n")
        append("你可调用的工具（JSON Schema）：\n")
        append(toolSchemaJson).append("\n\n")
        append("【输出格式】必须只输出一个 JSON 对象，不要 Markdown 代码块、不要额外解释：\n")
        append("1) 普通聊天 / 吐槽 / 回答问题：\n")
        append("{\"type\":\"chat\",\"reply\":\"哈哈，被炸飞了吧\"}\n")
        append("2) 让某个工具做一件事（单步操作，如打开应用、返回、滑动、点击文字）：\n")
        append("{\"type\":\"action\",\"tool\":\"open_app\",\"params\":{\"appName\":\"抖音\"},\"reply\":\"好的，正在为你打开抖音\"}\n")
        append("3) 需要多步才能完成的任务（如\u201C帮我把原神的每日委托领了\u201D\u201C去设置里连上 WiFi\u201D）：\n")
        append("{\"type\":\"task\",\"goal\":\"打开设置并连接 WiFi\",\"reply\":\"好，我去弄\"}\n\n")
        append("规则：\n")
        append("- reply 是你对用户说的话，口语化、不超过 40 个字；\n")
        append("- 能用单步工具解决就别用 task；打开应用一律用 open_app；\n")
        append("- 参数值必须是字符串 / 数字，不要写表达式；\n")
        append("- 如果用户只是闲聊、感慨、提问，一律 chat，不要硬找工具。")
    }

    /**
     * 本地小模型用的精简提示词。
     *
     * 2B 模型扛不住完整 Schema，这里只给「工具名 + 一句话用途」，并反复强调只输出 JSON。
     */
    fun buildLocalPrompt(toolNames: String, userText: String, foregroundApp: String?): String =
        buildString {
            append(VISION_HEADER).append("\n")
            append("当前前台应用：").append(foregroundApp ?: "未知").append("\n")
            append("可用工具：").append(toolNames).append("\n")
            append("只输出一个 JSON，不要解释，不要代码块。格式二选一：\n")
            append("{\"type\":\"chat\",\"reply\":\"你的回答\"}\n")
            append("{\"type\":\"action\",\"tool\":\"工具名\",\"params\":{},\"reply\":\"要对用户说的话\"}\n")
            append("用户说：").append(userText).append("\n")
            append("你的 JSON：")
        }

    /**
     * 本地小模型的**纯文本**提示词（没截到屏幕时用）。
     *
     * native 接口必须喂一张图，调用方会传一张纯黑占位图；
     * 这里明确告诉模型"本轮没有截图，别编"，避免它对着黑图瞎描述。
     */
    fun buildLocalTextPrompt(toolNames: String, userText: String, foregroundApp: String?): String =
        buildString {
            append("你现在没有屏幕截图（图是一张纯黑占位图，请忽略图片内容，不要描述画面）。\n")
            append("当前前台应用：").append(foregroundApp ?: "未知").append("\n")
            append("可用工具：").append(toolNames).append("\n")
            append("只输出一个 JSON，不要解释，不要代码块。格式二选一：\n")
            append("{\"type\":\"chat\",\"reply\":\"你的回答\"}\n")
            append("{\"type\":\"action\",\"tool\":\"工具名\",\"params\":{},\"reply\":\"要对用户说的话\"}\n")
            append("用户说：").append(userText).append("\n")
            append("你的 JSON：")
        }
}
