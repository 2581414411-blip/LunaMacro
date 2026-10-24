package com.nexus.agent.speech

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.overlay.RunningIndicator
import com.nexus.agent.overlay.ThinkingOverlay
import com.nexus.agent.safety.SafetyPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** 识别结果确认显示开关 / 时长配置项 */
    private const val KEY_CONFIRM_ENABLED = "voice_confirm_enabled"
    private const val KEY_CONFIRM_DURATION = "voice_confirm_duration_ms"

    /** 连续对话（执行完保持续听）配置项 */
    private const val KEY_CONTINUOUS_ENABLED = "voice_continuous_enabled"
    private const val KEY_CONTINUOUS_DURATION = "voice_continuous_duration_ms"

    private var appContext: Context? = null
    private var wakeWordDetector: LocalKeywordSpotter? = null
    private var recorder: VoiceCommandRecorder? = null
    private var tts: TtsSpeaker? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 当前状态（取代原 listening/busy 布尔标志） */
    @Volatile
    private var currentState: VoiceState = VoiceState.IDLE

    /** 识别失败重试计数（最多 2 次） */
    private var retryCount = 0

    /** 当前正在执行的指令（澄清后的完整指令），供执行完回填上下文 */
    private var currentCommand: String = ""

    /** 当前语音流程的主协程（供 cancel 打断） */
    private var currentJob: Job? = null

    /** 状态变化回调（用于 UI / 悬浮窗显示），在主线程回调 */
    var onStateChange: ((String) -> Unit)? = null

    /** 识别出指令文本后的回调（由执行层注入，如 MainActivity 交给 AgentRunner） */
    var onCommand: ((String) -> Unit)? = null

    fun isListening(): Boolean = currentState == VoiceState.IDLE

    /**
     * 状态转换（业务转换，带合法性校验）。
     *
     * 合法转换表（在主流程基础上补充「识别失败重试」路径）：
     *   IDLE → LISTENING
     *   LISTENING → TRANSCRIBING / IDLE
     *   TRANSCRIBING → CLARIFYING / EXECUTING / ERROR / LISTENING（重试）
     *   CLARIFYING → EXECUTING / ERROR
     *   EXECUTING → SPEAKING / ERROR
     *   SPEAKING → FOLLOW_UP / IDLE
     *   FOLLOW_UP → LISTENING / IDLE
     *   ERROR → IDLE
     *
     * 非法转换记录日志并忽略。重置类操作（restartWake/stop/cancel）直接置 IDLE，不经此校验。
     */
    private fun transitionTo(newState: VoiceState) {
        if (newState == currentState) return
        val valid = when (currentState) {
            VoiceState.IDLE -> newState == VoiceState.LISTENING
            VoiceState.LISTENING -> newState == VoiceState.TRANSCRIBING || newState == VoiceState.IDLE
            VoiceState.TRANSCRIBING ->
                newState == VoiceState.CLARIFYING || newState == VoiceState.EXECUTING ||
                newState == VoiceState.ERROR || newState == VoiceState.LISTENING
            VoiceState.CLARIFYING -> newState == VoiceState.EXECUTING || newState == VoiceState.ERROR
            VoiceState.EXECUTING -> newState == VoiceState.SPEAKING || newState == VoiceState.ERROR
            VoiceState.SPEAKING -> newState == VoiceState.FOLLOW_UP || newState == VoiceState.IDLE
            VoiceState.FOLLOW_UP -> newState == VoiceState.LISTENING || newState == VoiceState.IDLE
            VoiceState.ERROR -> newState == VoiceState.IDLE
        }
        if (!valid) {
            Log.w(TAG, "非法状态转换：$currentState → $newState，已忽略")
            return
        }
        Log.i(TAG, "状态转换：$currentState → $newState")
        currentState = newState
    }

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
        // 模型初始化移到 IO 线程：避免主线程加载模型导致 ANR/崩溃。
        // 唤醒阶段只加载 KWS（4.6M）；SenseVoice（229M）由 LocalAsr.prepare 仅保存 AssetManager，
        // 推迟到首次识别时才懒加载。
        scope.launch(Dispatchers.IO) {
            try {
                if (wakeWordDetector == null) {
                    val kws = LocalKeywordSpotter(context.applicationContext)
                    kws.onWakeWord = { handleWakeWord() }
                    wakeWordDetector = kws
                    Log.i(TAG, "初始化 KWS 唤醒模型…")
                    kws.init(
                        keywords = WakeWordConfig.getKeywords(context.applicationContext),
                        threshold = WakeWordConfig.getThreshold(context.applicationContext)
                    )
                    Log.i(TAG, "KWS 初始化完成")
                    LocalAsr.prepare(context.applicationContext)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "语音模型初始化异常", e)
            }
            // 切回主线程：初始化 TTS + 启动唤醒监听 + 更新状态
            withContext(Dispatchers.Main) {
                if (tts == null) {
                    tts = TtsSpeaker(context.applicationContext)
                    tts?.init()
                }
                val ok = wakeWordDetector?.start() ?: false
                if (ok) currentState = VoiceState.IDLE
                postStatusBrief(if (ok) "语音助手已就绪" else "唤醒启动失败")
            }
        }
    }

    /**
     * 前台服务在进程重建后调用：若用户之前开启过语音助手，则自动恢复唤醒。
     */
    fun autoRestore(context: Context) {
        val enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        if (enabled && currentState == VoiceState.IDLE) {
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
            currentState = VoiceState.IDLE
            postStatusBrief("语音助手已停止")
        }
    }

    /**
     * 执行层完成任务后回调，触发 TTS 播报结果，播报完重启唤醒。
     */
    fun reportExecuteResult(summary: String) {
        // 回填会话上下文（成功/失败/拦截由结果前缀判断）
        val isBlocked = summary.contains(SafetyPolicy.BLOCKED_MSG)
        val success = !summary.startsWith("任务失败") && !summary.startsWith("失败") && !isBlocked
        ConversationContext.addTurn(currentCommand, summary, success)
        scope.launch {
            transitionTo(VoiceState.SPEAKING)
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
                        // 连续对话：播报完进入续听模式；否则回待命
                        if (isContinuousEnabled()) {
                            enterContinuousListening()
                        } else {
                            restartWake()
                        }
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
     * 取消当前语音流程：取消主协程 + 清理状态 + 回到待机。
     *
     * 供悬浮窗「取消」按钮在 TRANSCRIBING / EXECUTING 状态下打断。
     */
    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        scope.launch {
            currentState = VoiceState.IDLE
            recorder?.stop()
            restartWake()
        }
    }

    /**
     * 用户手动点「停止」按钮时调用。
     *
     * 若正在语音流程中（busy），重置状态并回到待命，避免 busy 卡死
     * 导致后续唤醒一直被「忽略重复唤醒」拦截、无法再次使用。
     */
    fun onUserStop() {
        if (currentState == VoiceState.IDLE) return
        cancel()
    }

    /** 是否显示识别结果确认（默认 true） */
    private fun isConfirmEnabled(): Boolean {
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_CONFIRM_ENABLED, true) ?: true
    }

    /** 识别结果确认显示时长（默认 500ms） */
    private fun confirmDurationMs(): Long {
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getLong(KEY_CONFIRM_DURATION, 500L) ?: 500L
    }

    /** 是否启用连续对话（默认 true） */
    private fun isContinuousEnabled(): Boolean {
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getBoolean(KEY_CONTINUOUS_ENABLED, true) ?: true
    }

    /** 连续对话续听时长（默认 5000ms） */
    private fun continuousDurationMs(): Long {
        return appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.getLong(KEY_CONTINUOUS_DURATION, 5000L) ?: 5000L
    }

    /** 启动录音器（惰性创建），返回是否成功 */
    private fun startRecording(): Boolean {
        if (recorder == null) {
            recorder = VoiceCommandRecorder(appContext!!)
            recorder?.onFinished = { pcm, reason -> onPcmReady(pcm, reason) }
        }
        return recorder?.start() ?: false
    }

    /**
     * 进入续听模式：执行完一个任务后直接启动录音（不喊唤醒词），
     * 检测到说话则进入识别流程；超时无说话则由录音器的 no_speech 回调退出。
     */
    private fun enterContinuousListening() {
        transitionTo(VoiceState.FOLLOW_UP)
        postStatus("还想说什么？")
        recorder?.noSpeechTimeoutMs = continuousDurationMs()
        val ok = startRecording()
        if (ok) {
            transitionTo(VoiceState.LISTENING) // FOLLOW_UP → LISTENING（开始录音）
        } else {
            scope.launch { restartWake() }
        }
    }

    /**
     * 识别失败重试：最多 2 次，重试时直接重新录音（不经过唤醒词）。
     * 超过 2 次则回到待命。
     */
    private fun handleRecognizeRetry() {
        if (retryCount < 2) {
            retryCount++
            postStatus("没听清，请再说一遍（$retryCount/2）")
            scope.launch {
                wakeWordDetector?.pause()
                tts?.speak("没听清，请再说一遍") {
                    scope.launch {
                        delay(300)
                        transitionTo(VoiceState.LISTENING)
                        startRecording()
                    }
                }
            }
        } else {
            retryCount = 0
            postStatus("多次未听清，请重新唤醒")
            speakThenRestart("没听清，请重新唤醒")
        }
    }

    /** 唤醒词触发（WakeWordDetector 的 IO 线程回调） */
    private fun handleWakeWord() {
        if (currentState != VoiceState.IDLE) {
            Log.i(TAG, "正在处理中，忽略重复唤醒")
            return
        }
        transitionTo(VoiceState.LISTENING)
        // 点亮屏幕四周运行灯（语音链路开始）
        appContext?.let { RunningIndicator.acquire(it) }
        currentJob = scope.launch {
            // 1. 立即停 KWS，释放麦克风给录音器
            wakeWordDetector?.stop()

            // 2. 立即启动录音（零空窗：KWS 停止后不做任何耗时操作，不等提示音、不等 delay）
            val ok = startRecording()

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
            transitionTo(VoiceState.TRANSCRIBING)

            // 本地 SenseVoice 识别（离线，无需 API Key）
            val text = recognizeLocal(pcm)
            // 识别失败 / 空 → 重试（最多 2 次）
            if (text.isNullOrBlank()) {
                handleRecognizeRetry()
                return@launch
            }
            val trimmed = text.trim()
            // 异常检查：太短（<2 字）→ 重试
            if (trimmed.length < 2) {
                handleRecognizeRetry()
                return@launch
            }
            // 指令长度不设上限：交由执行层（AgentRunner）处理，长指令直接下发
            // 正常：重置重试计数
            retryCount = 0
            // 确认显示（可配置开关 + 时长）
            if (isConfirmEnabled()) {
                postStatus("你说的是：$trimmed")
                delay(confirmDurationMs())
            }
            // 指令澄清（含指代词才澄清，结合会话历史改写成完整指令）
            val clarified = clarifyIfNeeded(trimmed)
            currentCommand = clarified
            transitionTo(VoiceState.EXECUTING) // 交给执行层
            // 交给执行层（MainActivity 跑 AgentRunner）
            onCommand?.invoke(clarified)
            // 兜底：执行层 30 秒内没回调 reportExecuteResult，强制重置，避免 busy 卡死
            scope.launch {
                delay(30000)
                if (currentState == VoiceState.EXECUTING || currentState == VoiceState.CLARIFYING) {
                    Log.w(TAG, "执行超时，强制重置语音助手")
                    restartWake()
                }
            }
        }
    }

    /**
     * 本地识别：PCM 16bit 字节转 float，调 SenseVoice。
     */
    private suspend fun recognizeLocal(pcm: ByteArray): String {
        if (pcm.size < 2) return ""
        val shorts = ShortArray(pcm.size / 2)
        java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) floats[i] = shorts[i] / 32768.0f
        return LocalAsr.recognize(floats)
    }

    /**
     * 指令澄清：含指代词时，结合会话历史把指令改写成完整独立指令。
     *
     * 不含指代词直接返回原指令；无历史直接返回；澄清失败回退原指令。
     */
    private suspend fun clarifyIfNeeded(command: String): String {
        val pronouns = listOf("再", "刚才", "那个", "它", "接着", "也", "还")
        val hasPronoun = pronouns.any { command.contains(it) }
        if (!hasPronoun) return command
        val history = ConversationContext.toPromptBlock()
        if (history.isEmpty()) return command
        transitionTo(VoiceState.CLARIFYING)
        val ctx = appContext ?: return command
        return try {
            val client = LlmClientFactory.create(LlmClientFactory.getProvider(ctx), ctx)
            val prompt = "以下是本次会话的历史：\n$history\n\n用户刚才说：$command\n\n" +
                "请把用户的指令改写成不依赖上下文的完整指令，只输出改写后的指令，不要加任何解释。如果指令本身已经完整，原样输出。"
            val result = client.chat(
                listOf(
                    LlmClient.Message("system", "你是语音指令改写助手，把指代不清的指令改写成完整的独立指令。"),
                    LlmClient.Message("user", prompt)
                )
            ).trim()
            if (result.isBlank()) command else result
        } catch (e: Throwable) {
            Log.w(TAG, "指令澄清失败，回退原指令", e)
            command
        }
    }

    /** 重启唤醒监听（回到待命状态） */
    private suspend fun restartWake() {
        currentState = VoiceState.IDLE // 重置回待命（不经 transitionTo 校验）
        ConversationContext.clear() // 回到待命 = 会话结束，清空上下文
        delay(400) // 稍等，确保麦克风完全释放
        val ok = wakeWordDetector?.start() ?: false
        // 语音链路结束，熄灭运行灯（与 MainActivity 任务执行侧共用引用计数）
        RunningIndicator.release()
        postStatus(if (ok) "已回到待命" else "唤醒重启失败")
        // 回到待命后短暂显示，3 秒后自动收起浮窗（既不常驻，也不在流程中忽隐忽现）
        delay(3000)
        appContext?.let { ThinkingOverlay.hide() }
    }
}
