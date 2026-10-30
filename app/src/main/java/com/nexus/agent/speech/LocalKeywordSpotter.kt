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

    private val crashGuard = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "KWS 协程未捕获异常（已兜底）", e)
        running = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + crashGuard)


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
    fun init(keywords: String = DEFAULT_KEYWORDS, threshold: Float = DEFAULT_THRESHOLD): Boolean =
        synchronized(this) {
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
        try {
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
        val alreadyRunning = synchronized(this@LocalKeywordSpotter) { running }
        if (alreadyRunning) return@withContext true
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

    @Synchronized
    private fun startRecordingLoop(): Boolean {
        // 已有录音在跑就绝不再建第二个 AudioRecord：同一进程两个 AudioRecord
        // 并发录音会导致 native 对象被重复释放（真机 SIGABRT 的根因之一）。
        if (running && audioRecord != null) {
            Log.i(TAG, "已在监听中，忽略重复启动")
            return true
        }
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
            // 全静音自检：Android 11+ 在后台若无 microphone 型前台服务，
            // AudioRecord 照常返回数据但全是 0，唤醒词永远不触发。
            // 连续 5 秒读到全 0 就在日志里点明，别再让人瞎猜。
            var silentFrames = 0
            var silentWarned = false
            try {
                while (isActive && running) {
                    val read = try {
                        record.read(frame, 0, frame.size)
                    } catch (e: Throwable) {
                        Log.e(TAG, "读麦克风失败，停止监听", e)
                        break
                    }
                    if (read <= 0) continue
                    var nonZero = false
                    for (i in 0 until read) {
                        val v = frame[i]
                        if (v.toInt() != 0) nonZero = true
                        floats[i] = v / 32768.0f
                    }
                    if (nonZero) {
                        silentFrames = 0
                        silentWarned = false
                    } else {
                        silentFrames++
                        if (silentFrames >= 50 && !silentWarned) {
                            silentWarned = true
                            Log.w(
                                TAG,
                                "麦克风连续 5 秒全为静音：检查 ①前台服务是否带 microphone 类型 " +
                                    "②录音权限 ③系统是否在隐私里禁用了本应用麦克风"
                            )
                        }
                    }
                    val st = stream ?: break
                    val sp = spotter ?: break
                    // 只把本次真正读到的样本喂进去。read < frame.size 时若整块喂，
                    // 会把上一帧的残留样本重复计入，导致帧数计算错乱。
                    val samples = if (read == floats.size) floats else floats.copyOf(read)
                    try {
                        st.acceptWaveform(samples, SAMPLE_RATE)
                        // 【绝对不能无条件 decode】
                        // sherpa-onnx/csrc/features.cc 的 GetFrames() 里有：
                        //     if (frame_index + n > NumFramesReady()) {
                        //         SHERPA_ONNX_LOGE("%d + %d > %d\n", ...);
                        //         SHERPA_ONNX_EXIT(-1);   // 直接 exit 整个进程
                        //     }
                        // zipformer2 一次解码需要 45 帧（450ms），而第一次 read 通常只有
                        // 90ms 左右（9 帧），此时 decode 就会打印 "0 + 45 > 9" 并 exit(-1)。
                        // exit 会跑静态析构，把锁销毁；渲染线程随后锁到已销毁的 mutex，
                        // 于是报 FORTIFY: pthread_mutex_lock called on a destroyed mutex
                        // → Fatal signal 6 (SIGABRT)。正确用法：先 isReady 再 decode。
                        var guard = 0
                        while (sp.isReady(st) && guard++ < 16) {
                            sp.decode(st)
                            val kw = sp.getResult(st).keyword
                            if (!kw.isNullOrEmpty()) {
                                Log.i(TAG, "检测到唤醒词: $kw")
                                sp.reset(st)
                                onWakeWordDetected()
                                break
                            }
                        }
                    } catch (e: Throwable) {
                        // 单帧解码异常不能升级成进程级崩溃：跳过该帧继续监听
                        Log.e(TAG, "唤醒词解码异常（已跳过该帧）", e)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "KWS 监听循环异常退出", e)
            } finally {
                running = false
                Log.i(TAG, "KWS 监听循环结束")
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
