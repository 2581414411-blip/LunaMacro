package com.nexus.agent.speech

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * 唤醒后的语音指令录音器。
 *
 * 职责：
 * 1. 录音（16kHz / 单声道 / PCM 16bit）
 * 2. 静音端点检测：检测到持续静音（如 800ms）即认为用户说完，自动停止
 * 3. 超时保护：最长录 8 秒，避免一直等
 *
 * 注意：本类与 [WakeWordDetector] 不能同时占用麦克风。
 * 调用方必须在启动本录音器前先停止唤醒监听（释放 AudioRecord）。
 */
class VoiceCommandRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommandRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 每帧时长（毫秒） */
        private const val FRAME_MS = 20

        /** 噪声采样期时长（毫秒）：录音开始后前 500ms 采集环境噪声 */
        private const val NOISE_SAMPLE_MS = 500L

        /** 动态静音阈值下限 / 上限（RMS） */
        private const val MIN_THRESHOLD = 300.0
        private const val MAX_THRESHOLD = 2000.0

        /** 采样期结束后，多少毫秒内无说话判定为 no_speech */
        private const val NO_SPEECH_TIMEOUT_MS = 3000L

        /** 连续多少帧（每帧 20ms）高于阈值判定为「开始说话」 */
        private const val SPEAK_START_FRAMES = 3

        /** 开始说话后，连续多少帧低于阈值判定为「说完」（60 帧 = 1.2 秒） */
        private const val SILENCE_END_FRAMES = 60

        /** 最长录音时长（毫秒），兜底超时（放宽以支持长指令，正常由静音检测提前结束） */
        private const val DEFAULT_MAX_DURATION_MS = 30000L

        /** 结束原因 */
        const val REASON_SILENCE = "silence"
        const val REASON_NO_SPEECH = "no_speech"
        const val REASON_TIMEOUT = "timeout"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null

    /** 录音开始回调（用于 UI 显示「正在听…」） */
    var onStarted: (() -> Unit)? = null

    /** 录音结束回调：返回录到的 PCM 字节（16kHz 16bit 单声道）和结束原因（silence/no_speech/timeout） */
    var onFinished: ((ByteArray, String) -> Unit)? = null

    /** 静音端点触发（用户说完）回调 */
    var onSilence: (() -> Unit)? = null

    /** 超时触发回调 */
    var onTimeout: (() -> Unit)? = null

    /** 采样期结束后无说话的判定超时（默认 3 秒，续听模式可调长） */
    @Volatile
    var noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_MS

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isRecording(): Boolean = recording

    /**
     * 开始录音。
     *
     * @return 是否成功启动；无权限 / 麦克风被占用返回 false
     */
    fun start(): Boolean {
        if (!hasRecordPermission()) {
            Log.e(TAG, "缺少 RECORD_AUDIO 权限")
            return false
        }
        if (recording) {
            Log.w(TAG, "已在录音中")
            return true
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val frameSize = SAMPLE_RATE * FRAME_MS / 1000 * 2 // 每帧字节数（16bit）
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL, ENCODING,
                minBuf.coerceAtLeast(frameSize * 4)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "创建 AudioRecord 失败", e)
            return false
        }

        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording 失败（麦克风被占用?）", e)
            try { record.release() } catch (_: Throwable) {}
            return false
        }

        audioRecord = record
        recording = true
        onStarted?.invoke()

        recordJob = scope.launch {
            val pcm = ByteArrayOutputStream()
            val frame = ShortArray(SAMPLE_RATE * FRAME_MS / 1000)
            var totalMs = 0L
            var lastTime = System.currentTimeMillis()

            // 自适应端点检测状态
            val noiseRms = ArrayList<Double>() // 噪声采样期 RMS 集合
            var noiseFloor = 0.0
            var silenceThreshold = 0.0
            var noiseFloorComputed = false
            var startedSpeaking = false
            var speakFrames = 0   // 连续高于阈值的帧数
            var silenceFrames = 0 // 连续低于阈值的帧数
            var reason = REASON_TIMEOUT

            try {
                while (isActive && recording) {
                    val read = record.read(frame, 0, frame.size)
                    if (read <= 0) continue

                    // 累积 PCM
                    for (i in 0 until read) {
                        val v = frame[i]
                        pcm.write(v.toInt() and 0xFF)
                        pcm.write((v.toInt() shr 8) and 0xFF)
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTime
                    lastTime = now
                    totalMs += elapsed

                    val rmsVal = rms(frame, read)

                    // 阶段 1：噪声采样期，只收集 RMS，不做说话判定
                    if (totalMs < NOISE_SAMPLE_MS) {
                        noiseRms.add(rmsVal)
                        continue
                    }

                    // 采样期结束，计算动态阈值（只算一次）
                    if (!noiseFloorComputed) {
                        noiseFloor = median(noiseRms)
                        silenceThreshold = (noiseFloor * 2.5).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
                        noiseFloorComputed = true
                        Log.i(TAG, "noise_floor=${"%.0f".format(noiseFloor)}, threshold=${"%.0f".format(silenceThreshold)}, rms=${"%.0f".format(rmsVal)}, state=detecting")
                    }

                    if (!startedSpeaking) {
                        // 阶段 2：检测说话开始（连续 N 帧高于阈值）
                        if (rmsVal > silenceThreshold) {
                            speakFrames++
                            if (speakFrames >= SPEAK_START_FRAMES) {
                                startedSpeaking = true
                                silenceFrames = 0
                                Log.i(TAG, "noise_floor=${"%.0f".format(noiseFloor)}, threshold=${"%.0f".format(silenceThreshold)}, rms=${"%.0f".format(rmsVal)}, state=speaking")
                            }
                        } else {
                            speakFrames = 0
                        }
                        // 无说话超时：采样期结束后 N 秒仍无说话
                        if (totalMs >= NOISE_SAMPLE_MS + noSpeechTimeoutMs) {
                            reason = REASON_NO_SPEECH
                            Log.i(TAG, "采样期后无说话，超时退出")
                            break
                        }
                    } else {
                        // 阶段 3：检测说话结束（连续 N 帧低于阈值 = 1.2 秒静音）
                        if (rmsVal < silenceThreshold) {
                            silenceFrames++
                            if (silenceFrames >= SILENCE_END_FRAMES) {
                                reason = REASON_SILENCE
                                Log.i(TAG, "持续静音，结束录音")
                                break
                            }
                        } else {
                            silenceFrames = 0
                        }
                    }

                    // 总超时兜底
                    if (totalMs >= DEFAULT_MAX_DURATION_MS) {
                        reason = REASON_TIMEOUT
                        Log.i(TAG, "录音超时，强制结束")
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "录音循环异常", e)
            } finally {
                releaseRecord()
                recording = false
                val data = pcm.toByteArray()
                onFinished?.invoke(data, reason)
            }
        }

        return true
    }

    /** 主动停止录音（返回已录数据） */
    fun stop() {
        recording = false
        recordJob?.cancel()
        recordJob = null
    }

    private fun releaseRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {}
        try {
            audioRecord?.release()
        } catch (_: Throwable) {}
        audioRecord = null
    }

    /** 计算 RMS（均方根） */
    private fun rms(samples: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    /** 取中位数（对噪声采样值排序取中间值，抗突发噪声） */
    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
