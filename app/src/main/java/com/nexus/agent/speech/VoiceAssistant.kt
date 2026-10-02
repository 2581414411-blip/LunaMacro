package com.nexus.agent.speech

import android.content.Context
import android.util.Log
import com.nexus.agent.agent.LlmClientFactory
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
            onStateChange?.invoke(if (ok) "语音助手已就绪" else "唤醒启动失败")
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
            onStateChange?.invoke("语音助手已停止")
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
            onStateChange?.invoke("执行完成，播报中…")
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
            onStateChange?.invoke("👂 听到「你好高总」，请说指令…")

            // 2. 启动录音
            if (recorder == null) {
                recorder = VoiceCommandRecorder(appContext!!)
                recorder?.onFinished = { pcm -> onPcmReady(pcm) }
            }
            val ok = recorder?.start() ?: false
            if (!ok) {
                onStateChange?.invoke("录音启动失败（麦克风被占用？）")
                tts?.speak("录音失败")
                restartWake()
            }
        }
    }

    /** 录音结束（静音端点或超时），拿到 PCM */
    private fun onPcmReady(pcm: ByteArray) {
        scope.launch {
            if (pcm.isEmpty()) {
                onStateChange?.invoke("未录到声音")
                tts?.speak("没听清，请再说一遍") { scope.launch { restartWake() } }
                return@launch
            }
            onStateChange?.invoke("识别中…")

            // 复用 qwen 的 DashScope apiKey（同属 dashscope.aliyuncs.com）
            val ctx = appContext ?: run {
                restartWake()
                return@launch
            }
            val apiKey = LlmClientFactory.getConfig(ctx, "qwen").first
            if (apiKey.isBlank()) {
                onStateChange?.invoke("未配置 qwen API Key，无法识别语音")
                tts?.speak("未配置语音识别密钥") { scope.launch { restartWake() } }
                return@launch
            }

            val text = asr.recognize(pcm, 16000, apiKey)
            if (text.isNullOrBlank()) {
                onStateChange?.invoke("没听清")
                tts?.speak("没听清，请再说一遍") { scope.launch { restartWake() } }
            } else {
                onStateChange?.invoke("识别到：$text")
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
        onStateChange?.invoke(if (ok) "已回到待命" else "唤醒重启失败")
    }
}
