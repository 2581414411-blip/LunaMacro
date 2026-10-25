package com.nexus.agent.copilot

import android.content.Context
import android.util.Log
import com.nexus.agent.speech.LocalAsr
import com.nexus.agent.speech.VoiceCommandRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 主控台麦克风：一次性录音 → 本地 SenseVoice 识别 → 交回文字。
 *
 * 与 [com.nexus.agent.speech.VoiceAssistant] 的区别：
 *  - Assistant 是「常驻唤醒词 + 连续对话」链路，开销大、要一直占麦克风；
 *  - 这里是「按一下说一句」，不常驻，随时可用。
 *
 * 两者共用同一个 [VoiceCommandRecorder]，因此**不能同时用**：
 * 唤醒词正在监听时按麦克风，录音器起不来，会如实提示用户。
 */
object CopilotVoiceInput {

    private const val TAG = "CopilotVoiceInput"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var recorder: VoiceCommandRecorder? = null

    @Volatile
    private var busy = false

    /**
     * 按一下说一句。
     *
     * @param onStatus UI 状态回调（"正在听…" / "识别中…" / 失败原因），主线程
     * @param onText   识别出的文字，主线程
     */
    fun listenOnce(
        context: Context,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit
    ) {
        val ctx = context.applicationContext
        if (busy) {
            onStatus("还在听上一句…")
            return
        }

        LocalAsr.prepare(ctx)

        val rec = recorder ?: VoiceCommandRecorder(ctx).also { recorder = it }
        if (!rec.hasRecordPermission()) {
            onStatus("没有录音权限，去设置里给一下")
            return
        }

        rec.onFinished = { pcm, reason ->
            scope.launch {
                if (reason == VoiceCommandRecorder.REASON_NO_SPEECH || pcm.isEmpty()) {
                    busy = false
                    onStatus("没听到声音")
                    return@launch
                }
                onStatus("识别中…")
                val text = try {
                    recognize(ctx, pcm)
                } catch (e: Throwable) {
                    Log.e(TAG, "识别异常", e)
                    ""
                }
                busy = false
                if (text.isBlank()) {
                    onStatus("没听清，再说一遍")
                } else {
                    Log.i(TAG, "语音识别：$text")
                    onStatus("")
                    onText(text)
                }
            }
        }

        busy = true
        onStatus("正在听…")
        if (!rec.start()) {
            busy = false
            onStatus("麦克风被占用（先把语音唤醒关掉）")
        }
    }

    /**
     * PCM（16kHz / 16bit / 单声道）→ FloatArray → 本地 SenseVoice。
     */
    private suspend fun recognize(context: Context, pcm: ByteArray): String {
        if (pcm.size < 2) return ""
        val shorts = ShortArray(pcm.size / 2)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val samples = FloatArray(shorts.size) { shorts[it] / 32768f }
        return LocalAsr.recognize(samples).trim()
    }
}
