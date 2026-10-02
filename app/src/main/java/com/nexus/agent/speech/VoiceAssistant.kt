package com.nexus.agent.speech

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.overlay.RunningIndicator
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
     * 显示语音状态：持续显示到悬浮窗（不自动隐藏），用于语音流程进行中的状态，
     * 避免 showTemporary 4 秒自动隐藏导致浮窗在录音/识别阶段忽隐忽现。
     *
     * 流程结束统一由 [restartWake] 延迟收起浮窗。
     */
    private fun postStatus(msg: String) {
        val ctx = appContext
        if (ctx != null) {
            ThinkingOverlay.show(ctx, msg)
        }
        onStateChange?.invoke(msg)
    }

    /**
     * 短暂显示状态（自动隐藏），用于 start/stop 这类一次性提示。
     */
    private fun postStatusBrief(msg: String) {
        val ctx = appContext
        if (ctx != null) {
            ThinkingOverlay.showTemporary(ctx, msg)
        }
        onStateChange?.invoke(msg)
    }

    /** 播放唤醒提示音（80ms 短音，播完释放，不阻塞录音启动） */
    private fun playWakeTone() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            // 音调播放完（80ms）后释放，避免 ToneGenerator 泄漏；不阻塞录音
            scope.launch {
                delay(100)
                try { tone.release() } catch (_: Throwable) {}
            }
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
            postStatusBrief(if (ok) "语音助手已就绪" else "唤醒启动失败")
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
            postStatusBrief("语音助手已停止")
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
            var finished = false
            val finishOnce = {
                if (!finished) {
                    finished = true
                    scope.launch {
                        delay(1500) // 等播报尾音完全过去，避免自我唤醒
                        restartWake()
                    }
                }
            }
            // 播报前暂停 KWS，避免播报声音进入麦克风造成自我唤醒
            wakeWordDetector?.pause()
            tts?.speak(summary) { finishOnce() }
            // 兜底：若 TTS 回调迟迟不触发（引擎异常/静音等），10 秒后强制回到待命
            scope.launch {
                delay(10000)
                finishOnce()
            }
        }
    }

    /**
     * 播报一段提示文字并回到待命：播报前暂停 KWS（互斥），
     * 播报完成后延迟 1500ms 再 restartWake（其中恢复 KWS 监听）。
     */
    private fun speakThenRestart(text: String) {
        scope.launch {
            wakeWordDetector?.pause()
            tts?.speak(text) {
                scope.launch {
                    delay(1500)
                    restartWake()
                }
            }
        }
    }

    // ===== 内部状态机 =====

    /**
     * 用户手动点「停止」按钮时调用。
     *
     * 若正在语音流程中（busy），重置状态并回到待命，避免 busy 卡死
     * 导致后续唤醒一直被「忽略重复唤醒」拦截、无法再次使用。
     */
    fun onUserStop() {
        if (!busy) return
        scope.launch {
            recorder?.stop()
            restartWake()
        }
    }

    /** 唤醒词触发（WakeWordDetector 的 IO 线程回调） */
    private fun handleWakeWord() {
        if (busy) {
            Log.i(TAG, "正在处理中，忽略重复唤醒")
            return
        }
        busy = true
        // 点亮屏幕四周运行灯（语音链路开始）
        appContext?.let { RunningIndicator.acquire(it) }
        scope.launch {
            // 1. 立即停 KWS，释放麦克风给录音器
            wakeWordDetector?.stop()
            listening = false

            // 2. 立即启动录音（零空窗：KWS 停止后不做任何耗时操作，不等提示音、不等 delay）
            if (recorder == null) {
                recorder = VoiceCommandRecorder(appContext!!)
                recorder?.onFinished = { pcm, reason -> onPcmReady(pcm, reason) }
            }
            val ok = recorder?.start() ?: false

            // 3. 并行播放提示音（不阻塞录音）
            playWakeTone()

            // 4. 显示状态
            postStatus("👂 正在听…")

            if (!ok) {
                postStatus("录音启动失败（麦克风被占用？）")
                speakThenRestart("录音失败")
            }
        }
    }

    /** 录音结束（静音端点或超时），拿到 PCM 和结束原因 */
    private fun onPcmReady(pcm: ByteArray, reason: String) {
        scope.launch {
            // 采样期后无说话 → 直接回待命（不播报，避免打扰）
            if (reason == VoiceCommandRecorder.REASON_NO_SPEECH) {
                postStatus("未听到指令")
                restartWake()
                return@launch
            }
            if (pcm.isEmpty()) {
                postStatus("未录到声音")
                speakThenRestart("没听清，请再说一遍")
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
                speakThenRestart("未配置语音识别密钥")
                return@launch
            }

            val text = asr.recognize(pcm, 16000, apiKey)
            if (text.isNullOrBlank()) {
                // 区分：音频过短=没听清；音频正常但识别失败=网络/服务问题
                val audioMs = pcm.size / 32 // 16kHz 16bit = 32 字节/毫秒
                if (audioMs < 400) {
                    postStatus("声音太短，没听清")
                    speakThenRestart("没听清，请再说一遍")
                } else {
                    postStatus("识别失败（检查网络或语音服务）")
                    speakThenRestart("识别失败，请检查网络")
                }
            } else {
                postStatus("识别到：$text")
                // 交给执行层（MainActivity 跑 AgentRunner）
                onCommand?.invoke(text)
                // 兜底：执行层 30 秒内没回调 reportExecuteResult，强制重置，避免 busy 卡死
                scope.launch {
                    delay(30000)
                    if (busy) {
                        Log.w(TAG, "执行超时，强制重置语音助手")
                        restartWake()
                    }
                }
            }
        }
    }

    /** 重启唤醒监听（回到待命状态） */
    private suspend fun restartWake() {
        busy = false
        delay(400) // 稍等，确保麦克风完全释放
        val ok = wakeWordDetector?.start() ?: false
        listening = ok
        // 语音链路结束，熄灭运行灯（与 MainActivity 任务执行侧共用引用计数）
        RunningIndicator.release()
        postStatus(if (ok) "已回到待命" else "唤醒重启失败")
        // 回到待命后短暂显示，3 秒后自动收起浮窗（既不常驻，也不在流程中忽隐忽现）
        delay(3000)
        appContext?.let { ThinkingOverlay.hide() }
    }
}
