package com.nexus.agent.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 语音播报器（结果 TTS）。
 *
 * 封装 Android 自带 [TextToSpeech]，离线、免费、无需额外依赖。
 * 中文播报，若系统无中文引擎则回退英文。
 */
class TtsSpeaker(private val context: Context) {

    companion object {
        private const val TAG = "TtsSpeaker"
    }

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    /** 播报完成回调 */
    var onDone: (() -> Unit)? = null

    /**
     * 初始化 TTS 引擎。异步完成，返回后 [ready] 变为可用。
     */
    fun init(onReady: (() -> Unit)? = null) {
        if (ready) {
            onReady?.invoke()
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ready) {
                    // 回退英文
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(1.0f)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "播报完成")
                        this@TtsSpeaker.onDone?.invoke()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "播报出错")
                        this@TtsSpeaker.onDone?.invoke()
                    }
                })
                Log.i(TAG, "TTS 初始化成功")
                onReady?.invoke()
            } else {
                Log.e(TAG, "TTS 初始化失败: $status")
                ready = false
                onReady?.invoke()
            }
        }
    }

    /**
     * 播报一段文字。
     *
     * @param text 要播报的内容
     * @param onDone 播报完成回调（可选，覆盖默认的 [onDone]）
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val t = tts
        if (t == null || !ready) {
            Log.w(TAG, "TTS 未就绪，跳过播报")
            onDone?.invoke()
            return
        }
        val done = onDone ?: this.onDone
        if (done != null) {
            // 通过 utteranceId 关联本次播报的完成回调
            val utteranceId = "nexus_${System.currentTimeMillis()}"
            // 简单实现：直接用全局 onDone
            this.onDone = done
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    /** 停止当前播报 */
    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    /** 释放 TTS 资源 */
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {}
        tts = null
        ready = false
    }
}
