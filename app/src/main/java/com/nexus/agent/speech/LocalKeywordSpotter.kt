package com.nexus.agent.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地语音唤醒（sherpa-onnx KeywordSpotter，zipformer 3.3M 中文模型）。
 *
 * 相比自训练的 nexus_kws.onnx，这个用大规模中文语音训练，误触发更低；
 * 唤醒词可自定义（传 keywords 音素串即可，不用重训）。
 * 误判率通过 [threshold] 调节：越高越严格、越不易误触发。
 */
class LocalKeywordSpotter(private val context: Context) {

    companion object {
        private const val TAG = "LocalKWS"
        const val SAMPLE_RATE = 16000
        private const val COOLDOWN_MS = 2000L

        /** 默认唤醒词音素串（你好高总） */
        const val DEFAULT_KEYWORDS = "n ǐ h ǎo g āo z ǒng @你好高总"

        /** 默认触发阈值（误触发高就调大） */
        const val DEFAULT_THRESHOLD = 0.6f
    }

    var onWakeWord: (() -> Unit)? = null

    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var audioRecord: AudioRecord? = null
    private var listenJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    @Volatile
    private var lastWakeAt = 0L

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 初始化模型。
     * @param keywords 唤醒词音素串（如 "n ǐ h ǎo g āo z ǒng @你好高总"）
     * @param threshold 触发阈值，越高越严格
     */
    fun init(keywords: String = DEFAULT_KEYWORDS, threshold: Float = DEFAULT_THRESHOLD): Boolean {
        if (spotter != null) return true
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "kws/encoder.onnx",
                    decoder = "kws/decoder.onnx",
                    joiner = "kws/joiner.onnx",
                ),
                tokens = "kws/tokens.txt",
                modelType = "zipformer2",
            ),
            keywordsFile = "kws/keywords.txt",
            keywordsScore = 2.0f,
            keywordsThreshold = threshold,
        )
        return try {
            val s = KeywordSpotter(context.assets, config)
            val st = s.createStream(keywords)
            spotter = s
            stream = st
            Log.i(TAG, "KWS 模型加载成功，唤醒词阈值=$threshold")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "KWS 初始化失败", e)
            spotter = null
            stream = null
            false
        }
    }

    /**
     * 开始持续监听唤醒词。
     */
    suspend fun start(): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            Log.e(TAG, "缺少 RECORD_AUDIO 权限")
            return@withContext false
        }
        if (running) return@withContext true
        if (spotter == null) {
            if (!init()) return@withContext false
        }
        startRecordingLoop()
    }

    /**
     * 暂停监听（释放麦克风，保留模型）。
     */
    suspend fun pause() {
        if (!running) return
        running = false
        listenJob?.cancel()
        listenJob?.join()
        withContext(Dispatchers.IO) {
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
        }
    }

    /**
     * 恢复监听（模型仍在，不重复加载）。
     */
    suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        if (running) return@withContext true
        if (spotter == null) {
            if (!init()) return@withContext false
        }
        startRecordingLoop()
    }

    /**
     * 停止监听并释放模型。
     */
    suspend fun stop() {
        running = false
        listenJob?.cancel()
        listenJob?.join()
        withContext(Dispatchers.IO) {
            try { audioRecord?.stop(); audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            try { stream?.release() } catch (_: Throwable) {}
            try { spotter?.release() } catch (_: Throwable) {}
            stream = null
            spotter = null
        }
        Log.i(TAG, "KWS 已停止")
    }

    private fun startRecordingLoop(): Boolean {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(1600 * 4)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "创建 AudioRecord 失败", e)
            return false
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording 失败", e)
            try { record.release() } catch (_: Throwable) {}
            return false
        }
        audioRecord = record
        running = true

        listenJob = scope.launch {
            val frame = ShortArray(1600) // 100ms @ 16kHz
            val floats = FloatArray(1600)
            while (isActive && running) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue
                for (i in 0 until read) floats[i] = frame[i] / 32768.0f
                val st = stream ?: break
                val sp = spotter ?: break
                st.acceptWaveform(floats, SAMPLE_RATE)
                sp.decode(st)
                if (sp.isReady(st)) {
                    val result = sp.getResult(st)
                    sp.reset(st)
                    val kw = result.keyword
                    if (!kw.isNullOrEmpty()) {
                        Log.i(TAG, "检测到唤醒词: $kw")
                        onWakeWordDetected()
                    }
                }
            }
        }
        Log.i(TAG, "KWS 监听已启动")
        return true
    }

    private fun onWakeWordDetected() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < COOLDOWN_MS) return
        lastWakeAt = now
        onWakeWord?.invoke()
    }
}
