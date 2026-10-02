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

        /** 静音判定阈值（RMS），低于此值视为静音 */
        private const val DEFAULT_SILENCE_THRESHOLD = 500.0

        /** 检测到声音后，持续静音多久（毫秒）判定为用户说完 */
        private const val DEFAULT_SILENCE_DURATION_MS = 1200L

        /** 最长录音时长（毫秒），超时自动停止（含等待用户开口的时间） */
        private const val DEFAULT_MAX_DURATION_MS = 6000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    @Volatile
    private var recording = false

    private var audioRecord: AudioRecord? = null

    /** 录音开始回调（用于 UI 显示「正在听…」） */
    var onStarted: (() -> Unit)? = null

    /** 录音结束回调：返回录到的 PCM 字节（16kHz 16bit 单声道） */
    var onFinished: ((ByteArray) -> Unit)? = null

    /** 静音端点触发（用户说完）回调 */
    var onSilence: (() -> Unit)? = null

    /** 超时触发回调 */
    var onTimeout: (() -> Unit)? = null

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
            var silenceMs = 0L
            var totalMs = 0L
            var hasSpeech = false
            var lastTime = System.currentTimeMillis()

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

                    // 静音端点检测：检测到声音后才开始静音倒计时，
                    // 避免用户还没开口就被误判为「说完了」
                    val rms = rms(frame, read)
                    if (rms >= DEFAULT_SILENCE_THRESHOLD) {
                        hasSpeech = true
                        silenceMs = 0
                    } else if (hasSpeech) {
                        silenceMs += elapsed
                    }

                    if (hasSpeech && silenceMs >= DEFAULT_SILENCE_DURATION_MS) {
                        Log.i(TAG, "检测到持续静音 ${silenceMs}ms，结束录音")
                        onSilence?.invoke()
                        break
                    }
                    if (totalMs >= DEFAULT_MAX_DURATION_MS) {
                        Log.i(TAG, "录音超时 ${totalMs}ms，强制结束")
                        onTimeout?.invoke()
                        break
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "录音循环异常", e)
            } finally {
                releaseRecord()
                recording = false
                val data = pcm.toByteArray()
                if (data.isNotEmpty()) {
                    onFinished?.invoke(data)
                } else {
                    Log.w(TAG, "未录到有效音频")
                    onFinished?.invoke(ByteArray(0))
                }
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
}
