package com.nexus.agent.agent

import android.util.Log
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 基于 OkHttp 的 LLM 客户端（OpenAI 兼容接口）。
 *
 * 支持：
 * - 纯文本对话（content 为 String）
 * - 多模态对话（content 为 ContentPart 数组，含 image_url 的 data URI）
 * - 指数退避重试（1s → 2s → 4s，最多 3 次），401/403 不重试
 */
class LlmClient(
    private val apiKey: String,
    private val baseUrl: String,
    private val modelName: String
) {

    companion object {
        private const val TAG = "LlmClient"
        private const val MAX_RETRIES = 3
    }

    /** 单条消息 */
    data class Message(
        val role: String,       // system / user / assistant
        val content: Any        // String 或 List<ContentPart>
    )

    /** 多模态内容片段 */
    data class ContentPart(
        val type: String,       // "text" 或 "image_url"
        val text: String? = null,
        val imageUrl: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder().build()

    private val stringAdapter: JsonAdapter<String> = moshi.adapter(String::class.java)

    /**
     * 发起一次对话，返回助手回复文本。
     *
     * @param messages 对话消息列表
     * @return 助手回复内容；失败抛出异常
     */
    suspend fun chat(messages: List<Message>): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Throwable? = null

        while (attempt < MAX_RETRIES) {
            try {
                return@withContext doRequest(messages)
            } catch (e: ApiException) {
                // 401/403 鉴权错误不重试，直接抛出
                if (e.statusCode == 401 || e.statusCode == 403) throw e
                lastError = e
            } catch (e: Throwable) {
                lastError = e
            }

            attempt++
            if (attempt < MAX_RETRIES) {
                val backoff = 1000L shl (attempt - 1) // 1s → 2s → 4s
                Log.w(TAG, "retry $attempt/${MAX_RETRIES - 1} after ${backoff}ms")
                delay(backoff)
            }
        }

        throw lastError ?: RuntimeException("chat failed with unknown error")
    }

    /**
     * 单次 HTTP 请求（不重试）。
     */
    private fun doRequest(messages: List<Message>): String {
        val url = buildChatUrl()
        val bodyJson = buildRequestBody(messages)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiException(response.code, body)
            }
            return extractContent(body)
        }
    }

    /**
     * 拼接 chat/completions 地址。
     */
    private fun buildChatUrl(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) {
            base
        } else {
            "$base/chat/completions"
        }
    }

    /**
     * 构造请求体 JSON 字符串。
     *
     * content 根据类型分别处理：
     * - String → 直接作为 content 字段
     * - List<ContentPart> → 多模态数组
     */
    private fun buildRequestBody(messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("{\"model\":").append(jsonQuote(modelName))
        sb.append(",\"messages\":[")

        messages.forEachIndexed { i, msg ->
            if (i > 0) sb.append(",")
            sb.append("{\"role\":").append(jsonQuote(msg.role))
            sb.append(",\"content\":")

            when (val c = msg.content) {
                is String -> sb.append(jsonQuote(c))
                is List<*> -> sb.append(buildContentParts(c))
                else -> sb.append(jsonQuote(c.toString()))
            }

            sb.append("}")
        }

        sb.append("],\"temperature\":0.1,\"max_tokens\":2048}")
        return sb.toString()
    }

    /**
     * 构建多模态 content 数组 JSON。
     */
    private fun buildContentParts(parts: List<*>): String {
        val sb = StringBuilder("[")
        parts.forEachIndexed { i, part ->
            if (i > 0) sb.append(",")
            when (part) {
                is ContentPart -> {
                    sb.append("{\"type\":").append(jsonQuote(part.type))
                    when (part.type) {
                        "text" -> sb.append(",\"text\":").append(jsonQuote(part.text.orEmpty()))
                        "image_url" -> {
                            sb.append(",\"image_url\":{\"url\":")
                                .append(jsonQuote(part.imageUrl.orEmpty()))
                                .append("}")
                        }
                    }
                    sb.append("}")
                }
                else -> sb.append("{}")
            }
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * 从响应体中提取 choices[0].message.content。
     */
    private fun extractContent(body: String): String {
        return try {
            @Suppress("UNCHECKED_CAST")
            val root = moshi.adapter<Map<String, Any>>(
                Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            ).fromJson(body)
            val choices = root?.get("choices") as? List<*>
            val first = choices?.firstOrNull() as? Map<*, *>
            val message = first?.get("message") as? Map<*, *>
            val content = message?.get("content")
            content?.toString() ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "extractContent failed: ${e.message}", e)
            // 兜底：尝试用正则直接从 content 字段抽取
            extractContentFallback(body)
        }
    }

    /**
     * 兜底解析：用正则抽取 "content":"..." 字段。
     */
    private fun extractContentFallback(body: String): String {
        return try {
            val regex = Regex("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            val match = regex.find(body)
            val raw = match?.groupValues?.getOrNull(1) ?: ""
            // 反转义常见转义序列
            stringAdapter.fromJson("\"$raw\"") ?: raw
        } catch (e: Throwable) {
            Log.e(TAG, "extractContentFallback failed", e)
            ""
        }
    }

    /**
     * JSON 字符串转义并加引号。
     */
    private fun jsonQuote(s: String): String {
        val escaped = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> if (ch < ' ') {
                    escaped.append(String.format("\\u%04x", ch.code))
                } else {
                    escaped.append(ch)
                }
            }
        }
        escaped.append("\"")
        return escaped.toString()
    }

    /**
     * 带状态码的 API 异常，用于区分可重试 / 不可重试错误。
     */
    class ApiException(val statusCode: Int, val responseBody: String) :
        Exception("API error $statusCode: $responseBody")
}
