package com.nexus.agent.speech

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 语音助手状态机协调器。
 *
 * 串联完整语音链路：
 *  唤醒词 → 停 KWS → 录音 → 静音端点 → ASR → 指令 →（执行层）→ 结果 → TTS 播报 → 重启 KWS
 *
 * 本类不直接执行任务（执行交给 AgentRunner），只负责语音侧的编排；
 * 执行完成后由执行层调用 [reportExecuteResult] 触发播报与唤醒重启。
 *
 * 单例设计：进程存活期间常驻，便于后续迁移到前台服务保活。
 */
object VoiceAssistant {

    private const val TAG = "VoiceAssistant"

    /** 开启状态持久化（供前台服务在进程重建后自动恢复唤醒） */
    private const val PREFS = "voice_assistant"
    private const val KEY_ENABLED = "enabled"

    private var appContext: Context? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var recorder: VoiceCommandRecorder? = null
    private var tts: TtsSpeaker? = null
    private val asr = AsrClient()

    /** 唤醒提示音（系统 beep，<200ms） */
    private var toneGen: ToneGenerator? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var listening = false

    @Volatile
    private var busy = false // 正在处理一次指令（录音/ASR/执行中），避免重复触发

    /** 状态变化回调（用于 UI / 悬浮窗显示），在主线程回调 */
    var onStateChange: ((String) -> Unit)? = null

    /** 识别出指令文本后的回调（由执行层注入，如 MainActivity 交给 AgentRunner） */
    var onCommand: ((String) -> Unit)? = null

    fun isListening(): Boolean = listening

    /**
     * 显示语音状态：确保悬浮窗可见（show 幂等）并更新文本，同时通知外部回调。
     */
    private fun postStatus(msg: String) {
        val ctx = appContext
        if (ctx != null) {
            ThinkingOverlay.show(ctx, msg)
        }
        onStateChange?.invoke(msg)
    }

    /** 播放唤醒提示音（失败静默，不影响主流程） */
    private fun playWakeTone() {
        try {
            if (toneGen == null) {
                toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            }
            toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        } catch (e: Throwable) {
            Log.w(TAG, "播放提示音失败", e)
        }
    }

    /**
     * 启动语音助手（初始化 TTS + 启动唤醒监听）。
     */
    fun start(context: Context) {
        appContext = context.applicationContext
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, true).apply()
        if (wakeWordDetector == null) {
            wakeWordDetector = WakeWordDetector(context.applicationContext)
            wakeWordDetector?.onWakeWord = { handleWakeWord() }
        }
        if (tts == null) {
            tts = TtsSpeaker(context.applicationContext)
            tts?.init()
        }
        scope.launch {
            val ok = wakeWordDetector?.start() ?: false
            listening = ok
            postStatus(if (ok) "语音助手已就绪" else "唤醒启动失败")
        }
    }

    /**
     * 前台服务在进程重建后调用：若用户之前开启过语音助手，则自动恢复唤醒。
     */
    fun autoRestore(context: Context) {
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        if (enabled && !listening) {
            Log.i(TAG, "进程重建，自动恢复语音助手")
            start(context)
        }
    }

    /**
     * 停止语音助手（停止唤醒 + 录音 + 播报）。
     */
    fun stop() {
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, false)?.apply()
        scope.launch {
            wakeWordDetector?.stop()
            recorder?.stop()
            tts?.stop()
            listening = false
            busy = false
            postStatus("语音助手已停止")
        }
    }

    /**
     * 执行层完成任务后回调，触发 TTS 播报结果，播报完重启唤醒。
     */
    fun reportExecuteResult(summary: String) {
        scope.launch {
            busy = false
            if (summary.isBlank()) {
                restartWake()
                return@launch
            }
            postStatus("执行完成，播报中…")
            tts?.speak(summary) {
                scope.launch { restartWake() }
            }
        }
    }

    // ===== 内部状态机 =====

    /** 唤醒词触发（WakeWordDetector 的 IO 线程回调） */
    private fun handleWakeWord() {
        if (busy) {
            Log.i(TAG, "正在处理中，忽略重复唤醒")
            return
        }
        busy = true
        scope.launch {
            // 1. 停唤醒，释放麦克风给录音器
            wakeWordDetector?.stop()
            listening = false
            playWakeTone()
            postStatus("👂 听到「你好高总」，请说指令…")

            // 2. 启动录音
            if (recorder == null) {
                recorder = VoiceCommandRecorder(appContext!!)
                recorder?.onFinished = { pcm -> onPcmReady(pcm) }
            }
            val ok = recorder?.start() ?: false
            if (!ok) {
                postStatus("录音启动失败（麦克风被占用？）")
                tts?.speak("录音失败")
                restartWake()
            }
        }
    }

    /** 录音结束（静音端点或超时），拿到 PCM */
    private fun onPcmReady(pcm: ByteArray) {
        scope.launch {
            if (pcm.isEmpty()) {
                postStatus("未录到声音")
                tts?.speak("没听清，请再说一遍") { scope.launch { restartWake() } }
                return@launch
            }
            postStatus("识别中…")

            // 复用 qwen 的 DashScope apiKey（同属 dashscope.aliyuncs.com）
            val ctx = appContext ?: run {
                restartWake()
                return@launch
            }
            val apiKey = LlmClientFactory.getConfig(ctx, "qwen").first
            if (apiKey.isBlank()) {
                postStatus("未配置 qwen API Key，无法识别语音")
                tts?.speak("未配置语音识别密钥") { scope.launch { restartWake() } }
                return@launch
            }

            val text = asr.recognize(pcm, 16000, apiKey)
            if (text.isNullOrBlank()) {
                // 区分：音频过短=没听清；音频正常但识别失败=网络/服务问题
                val audioMs = pcm.size / 32 // 16kHz 16bit = 32 字节/毫秒
                if (audioMs < 400) {
                    postStatus("声音太短，没听清")
                    tts?.speak("没听清，请再说一遍") { scope.launch { restartWake() } }
                } else {
                    postStatus("识别失败（检查网络或语音服务）")
                    tts?.speak("识别失败，请检查网络") { scope.launch { restartWake() } }
                }
            } else {
                postStatus("识别到：$text")
                // 交给执行层（MainActivity 跑 AgentRunner）
                onCommand?.invoke(text)
            }
        }
    }

    /** 重启唤醒监听（回到待命状态） */
    private suspend fun restartWake() {
        busy = false
        delay(400) // 稍等，确保麦克风完全释放
        val ok = wakeWordDetector?.start() ?: false
        listening = ok
        postStatus(if (ok) "已回到待命" else "唤醒重启失败")
    }
}
