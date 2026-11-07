package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.game.LocalScreenRecognizer
import com.nexus.agent.game.VisionHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
 * 把一张图变成一段文字描述，并明确告诉调用方「走的是哪条腿、成了没有、为什么没成」。
 * 文本推理（DeepSeek）和视觉提取彻底分离，主模型永远不会收到 Base64。
 *
 * ## 级联优先级（用户指定，0.9.7）
 *
 * ```
 * 1. Cloud_VL   —— 有独立视觉 Key（或主模型自己能看图）：调云端多模态，图 → 详细文字描述
 * 2. Local_VL   —— 无云端 Key / 云端超时或失败：本地 Qwen2-VL-2B（:vision 子进程）
 * 3. Failed     —— 两边都失败：返回明确的兜底文案 + [FailKind] 原因，绝不编造
 * ```
 *
 * ## 0.9.9：可观测性
 *
 * 每一站都打 `VisionDebug` 日志（用户一条 `adb logcat -s VisionDebug` 能看完整条链路），
 * 失败一律归到 [FailKind]，UI 据此弹红字系统气泡。云端请求**必须**打印 HTTP 状态码和返回体，
 * 401/超时要在日志里大声喊出来 —— 以前异常被 `runCatching{}.getOrNull()` 吞掉，
 * 只剩下「云端视觉失败或超时」一句话，等于没有信息。
 */
object VisionRouter {

    /** 全链路日志 TAG —— 用户 grep `VisionRoute` 能一网打尽（0.9.7 批次三） */
    private const val TAG = "VisionRoute"

    /**
     * 发图全链路排障用的统一 TAG（0.9.9）。
     *
     * `VisionRoute` 是「视觉怎么选的」，`VisionDebug` 是「图走到哪一步了」：
     * UI 选了图 → 解码 → 落盘 → 交给 Engine → 路由 → 提取 → 拼 Prompt。
     * 排查「发了图她装傻」只需要一条命令：
     *
     * ```
     * adb logcat -s VisionDebug
     * ```
     *
     * 任何一步断了都会在日志里留下 `✗` 或 `[断点]`，不再有静默的环节。
     */
    const val DEBUG_TAG = "VisionDebug"

    /** 云端视觉请求超时（秒）。超时就降级本地，别让用户干等 */
    private const val CLOUD_TIMEOUT_MS = 25_000L

    /** 云端视觉描述图的提示词 */
    private const val CLOUD_DESCRIBE_PROMPT =
        "请用两三句话客观描述这张图片：画面里有什么、大概在做什么、有什么值得注意的细节。" +
            "不要评价、不要给建议、不要猜测拍摄者的心情。只输出描述本身。"

    /** 本地视觉描述图的提示词 */
    private const val IMAGE_DESCRIBE_PROMPT =
        "这是主人发来的一张图片。请用两三句话客观描述：画面里有什么、大概在做什么。" +
            "不要评价、不要给建议、不要猜主人的心情。"

    private const val IMAGE_DESCRIBE_FALLBACK = "这张图片里有什么？用两三句话描述。"

    /**
     * 一次视觉提取的结果。
     *
     * @param route 走了哪条腿：[Route]
     * @param description 提取出的文字描述（null = 失败，由 [failNotice] 给出人设话术）
     * @param failNotice 失败时注入提示词的说明（route==Failed 时非空）
     * @param detail 附加说明（模型名 / 失败原因），供日志
     * @param failKind 失败分类（决定 UI 红字怎么说）
     * @param userHint 失败时给用户看的**红字系统气泡**文案（route==Failed 时非空）
     */
    data class Result(
        val route: Route,
        val description: String?,
        val failNotice: String? = null,
        val detail: String? = null,
        val failKind: FailKind? = null,
        val userHint: String? = null
    ) {
        val ok: Boolean get() = !description.isNullOrBlank()
    }

    /** 三级路由 */
    enum class Route(val label: String) {
        CLOUD_VL("Cloud_VL"),
        LOCAL_VL("Local_VL"),
        FAILED("Failed")
    }

    /**
     * 失败原因。分类的意义在于「用户该改哪里」：
     * 鉴权失败要去重填 Key，超时要去换网络，本地缺模型要去下载 —— 三者的话术完全不同。
     */
    enum class FailKind(val code: String) {
        /** 连图都没有（解码失败 / 位图被回收） */
        NO_BITMAP("no_bitmap"),

        /** 没配独立视觉 Key，主模型又是纯文本 */
        NO_VISION_AT_ALL("no_vision"),

        /** 云端 401/403：Key 错或过期 */
        CLOUD_AUTH("cloud_auth"),

        /** 云端超时（25s 预算用尽） */
        CLOUD_TIMEOUT("cloud_timeout"),

        /** 云端连不上：DNS / 断网 / 其它 IO */
        CLOUD_NETWORK("cloud_network"),

        /** 云端 200 但内容是空的 */
        CLOUD_EMPTY("cloud_empty"),

        /** 图片编码成 base64 失败 */
        CLOUD_ENCODE("cloud_encode"),

        /** 本地视觉模型文件不齐 */
        LOCAL_MISSING("local_missing"),

        /** 本地子进程跑失败 / 没输出 */
        LOCAL_FAILED("local_failed")
    }

    /** 两边都失败时注入提示词的兜底文案（模型据此知道自己没看成） */
    const val FAIL_NOTICE =
        "（系统：用户发了一张图片，但本地和云端视觉都不可用，我这轮没看成）"

    /** 给用户看的红字前缀（UI 系统气泡统一用它，方便测试时肉眼/Robot 匹配） */
    const val FAIL_UI_PREFIX = "⚠️ 用户发了一张图片，但视觉解析失败"

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
     * 失败时的红字文案（纯函数，供单测）。
     *
     * 用户原话是「绝对不能装死」，所以每条都必须回答三件事：
     * ① 发生了什么（视觉解析失败）；② 因为什么（分类 + 细节）；③ 该去哪修。
     */
    internal fun userHintOf(kind: FailKind, detail: String? = null): String {
        val why = when (kind) {
            FailKind.NO_BITMAP -> "这张图没读出来"
            FailKind.NO_VISION_AT_ALL ->
                "没配云端视觉 Key，本地视觉模型也不可用"
            FailKind.CLOUD_AUTH ->
                "云端视觉 Key 无效（${detail ?: "401/403"}）"
            FailKind.CLOUD_TIMEOUT ->
                "云端视觉超时（${detail ?: "25 秒"}）"
            FailKind.CLOUD_NETWORK ->
                "云端视觉连不上（${detail ?: "网络错误"}）"
            FailKind.CLOUD_EMPTY -> "云端模型返回了空内容"
            FailKind.CLOUD_ENCODE -> "图片编码失败"
            FailKind.LOCAL_MISSING -> "本地视觉模型不完整（缺 ${detail ?: "模型文件"}）"
            FailKind.LOCAL_FAILED -> "本地视觉推理失败（${detail ?: "无输出"}）"
        }
        val fix = when (kind) {
            FailKind.NO_VISION_AT_ALL, FailKind.CLOUD_AUTH ->
                "请前往 设置 → LLM 配置 → 云端视觉模型 检查 Key。"
            FailKind.CLOUD_TIMEOUT, FailKind.CLOUD_NETWORK, FailKind.CLOUD_EMPTY ->
                "请检查网络，或在设置里配一个可用的云端视觉 Key。"
            FailKind.LOCAL_MISSING ->
                "请前往 设置 → 下载模型，装好视觉模型后重发。"
            else -> "请在 设置 里检查视觉配置后重发。"
        }
        return "$FAIL_UI_PREFIX（$why），$fix"
    }

    /**
     * 视觉提取的唯一入口：把一张图变成文字描述。
     *
     * 云端视觉优先 → 本地兜底 → 失败兜底。全程打 `VisionDebug` / `VisionRoute` 日志，
     * 每一步的成败、HTTP 状态码、耗时、失败原因都能在 logcat 里看到。
     *
     * @param bitmap 用户发来的图（不回收，调用方负责）
     */
    suspend fun processImage(context: Context, bitmap: Bitmap?): Result {
        // 空图 / 已回收：直接失败，别往下走
        if (bitmap == null || bitmap.isRecycled) {
            val why = if (bitmap == null) "bitmap 为空" else "位图已被回收（recycled）"
            Log.e(DEBUG_TAG, "[断点] 路由层: 没有图可看 —— $why")
            return failed(FailKind.NO_BITMAP, why)
        }

        // ---- 现场情况：谁在看、能不能看 ----
        val cloudKey = LlmClientFactory.getVisionApiKey(context)
        val mainProvider = LlmClientFactory.getProvider(context)
        val mainConfig = LlmClientFactory.getConfig(context, mainProvider)
        val mainCanVision = LlmClientFactory.supportsVision(mainProvider, mainConfig.third)
        val useCloud = shouldUseCloud(cloudKey, mainCanVision, mainConfig.first)
        val localMissing = runCatching {
            LocalScreenRecognizer.missingModelFiles(context)
        }.getOrDefault(emptyList())

        Log.d(
            DEBUG_TAG,
            "路由层: 检测到图片 ${bitmap.width}×${bitmap.height}（${bitmap.byteCount / 1024}KB），" +
                "主模型=$mainProvider/${mainConfig.third}，是否支持视觉: $mainCanVision"
        )
        Log.d(
            DEBUG_TAG,
            "路由层: 云端视觉 Key=${if (cloudKey.isBlank()) "未配置" else "已配置(…${cloudKey.takeLast(4)})"}，" +
                "云端可用=$useCloud，本地模型=${if (localMissing.isEmpty()) "齐全" else "缺 ${localMissing.joinToString("、")}"}"
        )
        // 致命防范（用户点名）：纯文本主模型绝不允许收到 base64。
        // 这一次修改后，图片这一路的 b64 恒为 null —— 这行日志就是「真的没塞」的凭据。
        Log.d(
            DEBUG_TAG,
            "路由层: 致命防范生效 —— 主模型 $mainProvider 本轮只收文字，" +
                "base64 不会进 messages（图片只发给视觉模型）"
        )

        // ---- 第一优先级：云端视觉 ----
        var cloudFail: FailKind = FailKind.NO_VISION_AT_ALL
        var cloudDetail: String? = null

        if (useCloud) {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            // 异常必须单独留一份：runCatching 吞掉后再看「attempt==null」，
            // 会把「抛了异常」误报成「超时」—— 这正是以前查不出原因的坑。
            var cloudError: Throwable? = null
            val attempt = runCatching {
                withTimeoutOrNull(CLOUD_TIMEOUT_MS) { describeCloud(context, bitmap) }
            }.onFailure {
                if (it is CancellationException) throw it
                cloudError = it
                Log.e(
                    DEBUG_TAG,
                    "路由层: 云端视觉调用抛异常 ${it.javaClass.simpleName}: ${it.message}"
                )
            }.getOrNull()

            val cost = android.os.SystemClock.elapsedRealtime() - startedAt
            when {
                attempt == null && cloudError != null -> {
                    cloudFail = FailKind.CLOUD_NETWORK
                    cloudDetail = "${cloudError?.javaClass?.simpleName}: " +
                        (cloudError?.message ?: "").take(80)
                    Log.e(
                        DEBUG_TAG,
                        "路由层: ✗ 云端视觉异常中止，耗时 ${cost}ms：$cloudDetail，降级本地"
                    )
                }
                attempt == null -> {
                    // withTimeoutOrNull 返回 null 且没有异常 = 25 秒预算用尽
                    cloudFail = FailKind.CLOUD_TIMEOUT
                    cloudDetail = "预算 ${CLOUD_TIMEOUT_MS}ms / 实际 ${cost}ms"
                    Log.e(
                        DEBUG_TAG,
                        "路由层: ✗ 云端视觉超时（预算 ${CLOUD_TIMEOUT_MS}ms，实际 ${cost}ms），降级本地"
                    )
                }
                attempt.text != null -> {
                    val desc = VisionRoute.cleanScreenDescription(attempt.text) ?: attempt.text.trim()
                    Log.d(
                        DEBUG_TAG,
                        "路由层: ✓ 云端视觉成功 HTTP ${attempt.code ?: 200}，耗时 ${cost}ms，" +
                            "描述 ${desc.length} 字：${desc.take(80)}"
                    )
                    Log.i(
                        TAG,
                        "[视觉] 云端提取成功 route=Cloud_VL，${desc.length} 字：" + desc.take(80)
                    )
                    return Result(Route.CLOUD_VL, desc, detail = "云端多模态")
                }
                else -> {
                    cloudFail = attempt.kind ?: FailKind.CLOUD_NETWORK
                    cloudDetail = attempt.reason
                    Log.e(
                        DEBUG_TAG,
                        "路由层: ✗ 云端视觉失败 kind=${cloudFail.code} " +
                            "HTTP=${attempt.code ?: "无"} 耗时=${cost}ms：" +
                            (attempt.reason ?: "未知") + "，降级本地"
                    )
                }
            }
        } else {
            cloudFail = FailKind.NO_VISION_AT_ALL
            cloudDetail = if (cloudKey.isBlank() && !mainCanVision) {
                "无独立视觉 Key，主模型 $mainProvider 纯文本"
            } else {
                "云端条件不满足"
            }
            Log.w(
                DEBUG_TAG,
                "路由层: 跳过云端 —— 无独立视觉 Key，主模型 $mainProvider " +
                    "${if (mainCanVision) "能看图但没 Key" else "不支持视觉"}"
            )
        }

        // ---- 第二优先级：本地视觉 ----
        var localFail: FailKind = FailKind.LOCAL_FAILED
        var localDetail: String? = null

        val local = runCatching {
            describeLocal(context, bitmap, localMissing)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(DEBUG_TAG, "路由层: 本地视觉抛异常 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()

        if (local != null) {
            val desc = local.text?.trim()?.takeIf { it.isNotEmpty() }
            if (desc != null) {
                Log.d(
                    DEBUG_TAG,
                    "路由层: ✓ 本地视觉成功（${local.size}，重试=${local.retried}），" +
                        "描述 ${desc.length} 字：${desc.take(80)}"
                )
                Log.i(TAG, "[视觉] 本地提取成功 route=Local_VL，${desc.length} 字：" + desc.take(80))
                return Result(Route.LOCAL_VL, desc, detail = "本地 Qwen2-VL-2B")
            }
            localFail = if (localMissing.isNotEmpty()) FailKind.LOCAL_MISSING else FailKind.LOCAL_FAILED
            localDetail = local.reason ?: "无输出"
            Log.e(
                DEBUG_TAG,
                "路由层: ✗ 本地视觉无输出（${local.size}，重试=${local.retried}）：" +
                    "${local.reason ?: "未知原因"}"
            )
        } else {
            localFail = if (localMissing.isNotEmpty()) FailKind.LOCAL_MISSING else FailKind.LOCAL_FAILED
            localDetail = if (localMissing.isNotEmpty()) {
                localMissing.joinToString("、")
            } else {
                "本地视觉不可用（未返回结果）"
            }
            Log.e(DEBUG_TAG, "路由层: ✗ 本地视觉没跑起来：$localDetail")
        }

        // ---- 第三优先级：失败兜底 ----
        // 什么时候报「什么都没配」：云端是因为没 Key 才跳过的，本地又缺模型 —— 这时候说
        // 「超时」会让人白查网络，得直接指向「去配置 / 去下载模型」。
        val kind = if (cloudFail == FailKind.NO_VISION_AT_ALL &&
            localFail == FailKind.LOCAL_MISSING
        ) {
            FailKind.NO_VISION_AT_ALL
        } else {
            cloudFail
        }
        val detail = when (kind) {
            FailKind.NO_VISION_AT_ALL ->
                if (localFail == FailKind.LOCAL_MISSING) {
                    "$cloudDetail；本地模型缺 ${localDetail.orEmpty()}"
                } else {
                    "$cloudDetail；本地视觉也失败（${localDetail.orEmpty()}）"
                }
            else -> "$cloudDetail；本地（${localFail.code}：${localDetail.orEmpty()}）"
        }
        // 红字气泡只说「这一条腿」的原因，别把云端的 401 和本地的缺文件混成一句
        val hintDetail = if (kind == cloudFail) cloudDetail else localDetail
        Log.e(DEBUG_TAG, "[断点] 路由层: ✗ 云端和本地都失败，route=Failed kind=${kind.code} —— $detail")
        Log.e(TAG, "[视觉] 云端和本地都失败，route=Failed kind=${kind.code}")
        return Result(
            route = Route.FAILED,
            description = null,
            failNotice = buildString {
                append(FAIL_NOTICE)
                append("原因：").append(kind.code)
                if (!detail.isNullOrBlank()) append(" —— ").append(detail.take(80))
            },
            detail = detail,
            failKind = kind,
            userHint = userHintOf(kind, hintDetail)
        )
    }

    /** 组装「连图都没有」这种最简失败结果 */
    private fun failed(kind: FailKind, detail: String?): Result = Result(
        route = Route.FAILED,
        description = null,
        failNotice = "$FAIL_NOTICE 原因：${kind.code} —— ${detail.orEmpty()}",
        detail = detail,
        failKind = kind,
        userHint = userHintOf(kind, detail)
    )

    /**
     * 云端多模态：把图直接发给视觉模型，拿文字描述。
     *
     * **所有失败都必须带出 HTTP 状态码或异常类型**（0.9.9）。以前这层异常被外层
     * `runCatching{}.getOrNull()` 吃掉，日志里只剩「云端视觉失败或超时」——
     * Key 错了、欠费了、被墙了看起来一模一样。
     */
    private suspend fun describeCloud(context: Context, bitmap: Bitmap): CloudAttempt =
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

            if (apiKey.isBlank()) {
                Log.e(DEBUG_TAG, "路由层: 云端视觉没有可用 Key，放弃")
                return@withContext CloudAttempt(null, null, FailKind.NO_VISION_AT_ALL, "无 Key")
            }

            val b64 = CopilotVision.toBase64(bitmap) ?: run {
                Log.e(DEBUG_TAG, "路由层: 图片编码 base64 失败（图太大或压缩异常）")
                return@withContext CloudAttempt(
                    null, null, FailKind.CLOUD_ENCODE, "base64 编码失败"
                )
            }

            // 独立视觉 Key 走独立 baseUrl + model；复用主 Key 时用主 provider 的默认地址
            val client = if (cloudKey.isNotBlank()) {
                LlmClient(apiKey = apiKey, baseUrl = baseUrl, modelName = actualModel)
            } else {
                LlmClientFactory.create(provider, context)
            }

            Log.d(
                DEBUG_TAG,
                "路由层: 请求云端视觉 model=$actualModel baseUrl=$baseUrl " +
                    "图=${bitmap.width}×${bitmap.height} b64=${b64.length} 字符"
            )

            val parts = listOf(
                LlmClient.ContentPart(type = "image_url", imageUrl = "data:image/jpeg;base64,$b64"),
                LlmClient.ContentPart(type = "text", text = CLOUD_DESCRIBE_PROMPT)
            )
            val messages = listOf(
                LlmClient.Message("system", "你是图片描述助手，只输出画面描述。"),
                LlmClient.Message("user", parts)
            )
            try {
                val raw = client.chat(messages).trim()
                if (raw.isBlank()) {
                    Log.e(DEBUG_TAG, "路由层: 云端视觉 HTTP 200 但返回空内容")
                    CloudAttempt(null, 200, FailKind.CLOUD_EMPTY, "返回空内容")
                } else {
                    CloudAttempt(raw, 200, null, null)
                }
            } catch (e: LlmClient.ApiException) {
                // ← 这一行就是用户要的「大声喊」：状态码 + 返回体，401 一眼可见
                Log.e(
                    DEBUG_TAG,
                    "路由层: ✗ 云端视觉 HTTP ${e.statusCode} " +
                        "（${httpHint(e.statusCode)}）返回体: ${e.responseBody.take(300)}"
                )
                val kind = when (e.statusCode) {
                    401, 403 -> FailKind.CLOUD_AUTH
                    408, 504 -> FailKind.CLOUD_TIMEOUT
                    else -> FailKind.CLOUD_NETWORK
                }
                CloudAttempt(null, e.statusCode, kind, "HTTP ${e.statusCode}")
            } catch (e: SocketTimeoutException) {
                Log.e(DEBUG_TAG, "路由层: ✗ 云端视觉读超时：${e.message}")
                CloudAttempt(null, null, FailKind.CLOUD_TIMEOUT, "SocketTimeout")
            } catch (e: UnknownHostException) {
                Log.e(DEBUG_TAG, "路由层: ✗ 云端视觉域名解析失败（多半是断网/被墙）：${e.message}")
                CloudAttempt(null, null, FailKind.CLOUD_NETWORK, "UnknownHost")
            } catch (e: IOException) {
                Log.e(DEBUG_TAG, "路由层: ✗ 云端视觉 IO 异常：${e.javaClass.simpleName} ${e.message}")
                CloudAttempt(null, null, FailKind.CLOUD_NETWORK, e.javaClass.simpleName)
            }
        }

    /** 一次性返回云端的成/败与原因（内部用） */
    private data class CloudAttempt(
        val text: String?,
        val code: Int?,
        val kind: FailKind?,
        val reason: String?
    )

    /** 状态码人话翻译，写进日志方便非开发同学也能看懂 */
    private fun httpHint(code: Int): String = when (code) {
        401 -> "Key 无效或过期，去设置重填视觉 Key"
        403 -> "Key 没有该模型权限"
        404 -> "Base URL 或模型名不对"
        429 -> "限流 / 欠费"
        in 500..599 -> "服务端故障"
        else -> "请求被拒"
    }

    /** 本地视觉：走 :vision 子进程的 Qwen2-VL-2B */
    private suspend fun describeLocal(
        context: Context,
        bitmap: Bitmap,
        missing: List<String>
    ): VisionHub.Look? {
        // 模型文件不齐直接放弃，别起子进程白等
        if (missing.isNotEmpty()) {
            Log.e(DEBUG_TAG, "路由层: 本地模型不齐（缺 ${missing.joinToString("、")}），跳过本地")
            return VisionHub.Look(null, false, "模型文件不齐：${missing.joinToString("、")}")
        }
        Log.d(DEBUG_TAG, "路由层: 开始调本地视觉（:vision 子进程，可能需要 5~15 秒）")
        val look = VisionHub.look(
            context = context,
            shot = bitmap,
            prompt = IMAGE_DESCRIBE_PROMPT,
            fallbackPrompt = IMAGE_DESCRIBE_FALLBACK
        )
        Log.d(
            DEBUG_TAG,
            "路由层: 本地视觉返回 ok=${look.ok} size=${look.size} 重试=${look.retried} " +
                "reason=${look.reason ?: "无"} 文本=${look.text?.length ?: 0} 字"
        )
        return look
    }
}
