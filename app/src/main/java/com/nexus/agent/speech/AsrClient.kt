package com.nexus.agent.speech

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 语音识别客户端（ASR）。
 *
 * 使用阿里云 DashScope 的 paraformer-realtime-v2 实时语音识别 WebSocket 接口。
 *
 * 协议流程（已踩坑确认）：
 *  1. 建立 WebSocket，握手带 Authorization: Bearer {apiKey}
 *  2. 发送 run-task（携带 model / format / sample_rate）
 *  3. 收到 task-started 后，直接发送二进制 PCM 帧（**不要**用 continue-task 包裹）
 *  4. 发送 finish-task
 *  5. 接收 result-generated，累积解析文本
 */
class AsrClient {

    companion object {
        private const val TAG = "AsrClient"

        /** DashScope 实时语音识别 WebSocket 端点（结尾斜杠不能省） */
        private const val WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/"

        private const val MODEL = "paraformer-realtime-v2"

        /** 识别超时（毫秒） */
        private const val TIMEOUT_MS = 30000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 识别一段 PCM 音频，返回识别文本；失败或超时返回 null。
     *
     * @param pcm PCM 16bit 单声道字节
     * @param sampleRate 采样率
     * @param apiKey DashScope API Key
     */
    suspend fun recognize(pcm: ByteArray, sampleRate: Int, apiKey: String): String? {
        if (pcm.isEmpty() || apiKey.isBlank()) return null

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val taskId = UUID.randomUUID().toString()
                val recognized = StringBuilder()
                var done = false

                val request = Request.Builder()
                    .url(WS_URL)
                    .header("Authorization", "Bearer $apiKey")
                    .build()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "WebSocket 已连接，发送 run-task")
                        val runTask = JSONObject().apply {
                            put("header", JSONObject().apply {
                                put("action", "run-task")
                                put("task_id", taskId)
                                put("streaming", "duplex")
                            })
                            put("payload", JSONObject().apply {
                                put("task_group", "audio")
                                put("task", "asr")
                                put("function", "recognition")
                                put("model", MODEL)
                                put("parameters", JSONObject().apply {
                                    put("format", "pcm")
                                    put("sample_rate", sampleRate)
                                    put("disfluency_removal_enabled", false)
                                    put("language_hints", JSONArray().apply { put("zh") })
                                })
                                put("input", JSONObject())
                            })
                        }
                        webSocket.send(runTask.toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val obj = JSONObject(text)
                            // 注意：客户端发送用 header.action，服务端返回用 header.event
                            val event = obj.optJSONObject("header")?.optString("event") ?: ""
                            when (event) {
                                "task-started" -> {
                                    Log.i(TAG, "task-started，开始发送音频帧")
                                    sendPcmFrames(webSocket, pcm)
                                    val finish = JSONObject().apply {
                                        put("header", JSONObject().apply {
                                            put("action", "finish-task")
                                            put("task_id", taskId)
                                            put("streaming", "duplex")
                                        })
                                        put("payload", JSONObject())
                                    }
                                    webSocket.send(finish.toString())
                                }
                                "result-generated" -> {
                                    val sentence = obj.optJSONObject("payload")
                                        ?.optJSONObject("output")
                                        ?.optJSONObject("sentence")
                                    val t = sentence?.optString("text")?.trim()
                                    if (!t.isNullOrEmpty()) {
                                        recognized.append(t)
                                        Log.i(TAG, "识别片段: $t")
                                    }
                                }
                                "task-finished" -> {
                                    Log.i(TAG, "task-finished，识别结束")
                                    webSocket.close(1000, "done")
                                }
                                "task-failed" -> {
                                    Log.e(TAG, "task-failed，服务端返回错误: $text")
                                    webSocket.close(1000, "failed")
                                }
                                else -> {
                                    // 打印未知事件，便于排查协议问题
                                    Log.w(TAG, "未知事件 [$event]: $text")
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "解析消息失败: $text", e)
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        // 服务端一般不回二进制，忽略
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "WebSocket 关闭")
                        finish(recognized.toString())
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "WebSocket 失败: ${t.message}")
                        finish(recognized.toString())
                    }

                    private fun finish(raw: String) {
                        if (done) return
                        done = true
                        val text = raw.trim()
                        Log.i(TAG, "最终识别结果: $text")
                        cont.resume(if (text.isEmpty()) null else text)
                    }
                }

                val ws = client.newWebSocket(request, listener)

                cont.invokeOnCancellation {
                    try { ws.close(1000, "cancel") } catch (_: Throwable) {}
                }
            }
        }
    }

    /** 把 PCM 按 100ms 一帧发送 */
    private fun sendPcmFrames(webSocket: WebSocket, pcm: ByteArray) {
        val frameBytes = 3200 // 16kHz 16bit 单声道：100ms
        var offset = 0
        while (offset < pcm.size) {
            val len = minOf(frameBytes, pcm.size - offset)
            val chunk = pcm.copyOfRange(offset, offset + len)
            webSocket.send(ByteString.of(*chunk))
            offset += len
        }
        Log.i(TAG, "已发送 ${pcm.size} 字节音频")
    }
}
