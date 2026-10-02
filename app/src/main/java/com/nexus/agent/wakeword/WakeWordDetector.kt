package com.nexus.agent.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 语音唤醒检测器（ONNX 已接入）。
 *
 * 通过麦克风 16kHz 单声道持续监听，滑动窗口攒够 1000ms 音频后
 * 提取 MFCC 特征送入 ONNX 模型，检测到「你好高总」唤醒词时触发 [onWakeWord]。
 *
 * 与训练端（kws/train.py）严格对齐：
 * - 采样率 16kHz，帧长 32ms(n_fft=512)，帧移 16ms(hop=256)
 * - 40 维 MFCC，mel 滤波器组 128 个，频率范围 0~8kHz
 * - 每段音频固定 1000ms = 61 帧 MFCC
 * - 模型输入 (1, 1, 61, 40)，输出 2 类 logits
 */
class WakeWordDetector(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordDetector"

        /** 采样率 16kHz（唤醒词模型通用输入） */
        const val SAMPLE_RATE = 16000

        /** 单声道 */
        const val CHANNELS = 1

        /** 每帧时长（毫秒） */
        const val FRAME_MS = 20

        /** 每帧采样点数 = 16000 * 0.02 = 320 */
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_MS / 1000

        /** 唤醒后的冷却时间，避免一次唤醒触发多次 */
        private const val COOLDOWN_MS = 2000L

        // ---- MFCC 参数（与 train.py 严格一致） ----
        private const val N_FFT = 512          // 32ms @ 16kHz
        private const val HOP_LENGTH = 256     // 16ms
        private const val N_MFCC = 40
        private const val N_MELS = 128
        private const val WINDOW_MS = 1000     // 每段 1000ms
        private const val N_FRAMES = (WINDOW_MS - 32) / 16 + 1  // 61 帧

        /** 唤醒阈值：softmax 后类别 1（唤醒词）的概率超过此值触发 */
        /** 唤醒判定阈值（真唤醒实测 0.9+，提高到 0.9 减少误触发） */
        private const val WAKE_THRESHOLD = 0.9f
    }

    /** 唤醒词触发回调 */
    var onWakeWord: (() -> Unit)? = null

    /** 检测循环协程 */
    private var detectJob: Job? = null

    /** 检测循环使用的独立作用域，便于统一取消 */
    private val detectScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var audioRecord: AudioRecord? = null

    /** 上次唤醒时间戳（毫秒） */
    @Volatile
    private var lastWakeAt = 0L

    /** ONNX 会话 */
    @Volatile
    private var session: OrtSession? = null

    /** ONNX 环境 */
    private var env: OrtEnvironment? = null

    /** 滑动窗口音频缓冲：攒够 1000ms 才推理 */
    private val audioWindow = FloatArray(SAMPLE_RATE) // 1000ms @ 16kHz
    private var windowFilled = 0

    // 预计算：周期 Hann 窗、mel 滤波器组、FFT（避免每次推理重算）
    private val hannWindow: FloatArray = FloatArray(N_FFT).also { h ->
        for (n in 0 until N_FFT) {
            h[n] = (0.5 - 0.5 * cos(2.0 * PI * n / N_FFT)).toFloat()
        }
    }
    private val melFilter: FloatArray = melFilterbank()
    private val fft: FFT = FFT(N_FFT)

    /**
     * 检查是否已授予录音权限。
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 从 assets 加载 ONNX 唤醒词模型，创建 OrtSession。
     *
     * @return 是否加载成功
     */
    private suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelBytes = context.assets.open("nexus_kws.onnx").readBytes()
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(modelBytes)
            env = e
            session = s
            Log.i(TAG, "唤醒词模型加载成功，输入=${s.inputNames?.joinToString()}, 输出=${s.outputNames?.joinToString()}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "加载唤醒词模型失败", e)
            session = null
            env = null
            false
        }
    }

    /**
     * 开始监听（后台线程循环录音 + 检测）。
     *
     * @return 是否成功启动；无权限或模型未就绪返回 false
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            Log.e(TAG, "缺少 RECORD_AUDIO 权限")
            return@withContext false
        }
        if (running) {
            Log.w(TAG, "已在运行中")
            return@withContext true
        }
        if (!loadModel()) {
            Log.e(TAG, "模型加载失败，无法启动唤醒检测")
            return@withContext false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize.coerceAtLeast(FRAME_SIZE * 2)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "创建 AudioRecord 失败", e)
            return@withContext false
        }

        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "startRecording 失败（麦克风被占用?）", e)
            try { record.release() } catch (_: Throwable) {}
            return@withContext false
        }
        audioRecord = record
        running = true
        windowFilled = 0

        detectJob = detectScope.launch {
            val buffer = ShortArray(FRAME_SIZE)
            while (isActive && running) {
                val read = record.read(buffer, 0, FRAME_SIZE)
                if (read <= 0) continue

                if (detectFrame(buffer)) {
                    onWakeWordDetected()
                }
            }
        }

        Log.i(TAG, "唤醒监听已启动")
        true
    }

    /**
     * 停止监听并释放录音资源。
     */
    suspend fun stop() {
        running = false
        val job = detectJob
        detectJob = null
        job?.cancel()
        // 关键：等待检测循环真正退出，否则它还在用 session/env，
        // 此时关闭资源会与 detectFrame 产生竞态 → NPE / "score a closed session"
        job?.join()
        withContext(Dispatchers.IO) {
            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Throwable) {
                Log.e(TAG, "释放 AudioRecord 失败", e)
            }
            audioRecord = null
            try {
                session?.close()
            } catch (e: Throwable) {
                Log.e(TAG, "关闭 OrtSession 失败", e)
            }
            session = null
            env = null
        }
        Log.i(TAG, "唤醒监听已停止")
    }

    /**
     * 检测一帧音频是否包含唤醒词。
     *
     * 逻辑：
     * 1. 把 ShortArray 转 Float 并归一化到 [-1, 1]
     * 2. 追加到滑动窗口缓冲（环形移位，始终保留最近 1000ms）
     * 3. 窗口未满 1000ms 时返回 false
     * 4. 窗口满了：提取 40 维 MFCC（61 帧）→ 送入模型 → softmax → 类别 1 概率超阈值返回 true
     */
    private fun detectFrame(frame: ShortArray): Boolean {
        val s = session ?: return false
        val e = env ?: return false // 防御：env 被置空时安全返回，避免 createTensor NPE
        if (frame.size != FRAME_SIZE) return false

        // 1. 转 Float 归一化
        val floats = FloatArray(FRAME_SIZE)
        for (i in frame.indices) {
            floats[i] = frame[i] / 32768.0f
        }

        // 2. 追加到滑动窗口（丢弃最旧的 FRAME_SIZE 个样本）
        val shift = FRAME_SIZE
        for (i in 0 until (SAMPLE_RATE - shift)) {
            audioWindow[i] = audioWindow[i + shift]
        }
        for (i in 0 until shift) {
            audioWindow[SAMPLE_RATE - shift + i] = floats[i]
        }
        windowFilled = minOf(windowFilled + shift, SAMPLE_RATE)
        if (windowFilled < SAMPLE_RATE) return false

        // 3. 提取 MFCC → (61, 40)
        val mfcc = computeMfcc(audioWindow) // FloatArray(61 * 40)

        // 4. 构造成 (1, 1, 61, 40) 张量
        val tensor = OnnxTensor.createTensor(
            e,
            java.nio.FloatBuffer.wrap(mfcc),
            longArrayOf(1, 1, N_FRAMES.toLong(), N_MFCC.toLong())
        )

        var result: OrtSession.Result? = null
        return try {
            result = s.run(mapOf("mfcc" to tensor))
            // 取第一个（也是唯一一个）输出：logits，形状 (1, 2)
            val logits = result.get(0).value as Array<FloatArray>
            val row = logits[0]
            // softmax
            val exp0 = Math.exp(row[0].toDouble())
            val exp1 = Math.exp(row[1].toDouble())
            val pWake = (exp1 / (exp0 + exp1)).toFloat()
            // 打印概率分数：只在有反应时打印，便于观察模型对你声音的响应
            if (pWake > 0.1f) {
                Log.i(TAG, "推理概率 pWake=${"%.3f".format(pWake)} (阈值 $WAKE_THRESHOLD)")
            }
            pWake >= WAKE_THRESHOLD
        } catch (e: Throwable) {
            Log.e(TAG, "推理失败", e)
            false
        } finally {
            try { result?.close() } catch (_: Throwable) {}
            tensor.close()
        }
    }

    /**
     * 手写 MFCC，与训练端 librosa.feature.mfcc 默认参数严格对齐。
     *
     * @param audio 1000ms 的 16kHz 单声道 Float 采样（16000 点）
     * @return 展平的 (N_FRAMES, N_MFCC) = (61, 40) 特征，行优先
     */
    private fun computeMfcc(audio: FloatArray): FloatArray {
        val frames = FloatArray(N_FRAMES * N_FFT)

        // 分帧 + 加窗（Hann 窗已预计算）
        for (t in 0 until N_FRAMES) {
            val start = t * HOP_LENGTH
            for (n in 0 until N_FFT) {
                frames[t * N_FFT + n] = audio[start + n] * hannWindow[n]
            }
        }

        // FFT 每帧 → 功率谱 → mel 滤波器组 → log → DCT
        val melEnergies = FloatArray(N_FRAMES * N_MELS)

        for (t in 0 until N_FRAMES) {
            val frame = FloatArray(N_FFT)
            System.arraycopy(frames, t * N_FFT, frame, 0, N_FFT)
            val mag = fft.magnitude(frame) // FloatArray(N_FFT / 2 + 1)
            val power = FloatArray(mag.size)
            for (k in power.indices) {
                power[k] = mag[k] * mag[k]
            }
            // mel 滤波 + 10*log10（与训练端 my_mfcc 一致，不是自然对数 ln）
            for (m in 0 until N_MELS) {
                var sum = 0.0
                for (k in power.indices) {
                    sum += power[k] * melFilter[m * power.size + k]
                }
                melEnergies[t * N_MELS + m] = (10.0 * kotlin.math.log10(sum + 1e-10)).toFloat()
            }
        }

        // DCT-II，取前 N_MFCC 维，norm='ortho'
        val mfcc = FloatArray(N_FRAMES * N_MFCC)
        val norm0 = (1.0 / sqrt(N_MELS.toDouble())).toFloat()
        val normK = (sqrt(2.0 / N_MELS)).toFloat()
        for (t in 0 until N_FRAMES) {
            for (c in 0 until N_MFCC) {
                var sum = 0.0
                for (m in 0 until N_MELS) {
                    sum += melEnergies[t * N_MELS + m] * cos(PI * c * (m + 0.5) / N_MELS)
                }
                val norm = if (c == 0) norm0 else normK
                mfcc[t * N_MFCC + c] = (norm * sum).toFloat()
            }
        }

        return mfcc
    }

    /**
     * 生成 mel 滤波器组矩阵 (N_MELS, N_FFT/2+1)。
     * 频率范围 0~8kHz（librosa 默认 sr/2 = Nyquist）。
     */
    private fun melFilterbank(): FloatArray {
        val nBins = N_FFT / 2 + 1
        val filter = FloatArray(N_MELS * nBins)

        val minMel = hzToMel(0.0)
        val maxMel = hzToMel(SAMPLE_RATE / 2.0)
        val melPoints = DoubleArray(N_MELS + 2)
        for (i in melPoints.indices) {
            melPoints[i] = minMel + (maxMel - minMel) * i / (N_MELS + 1)
        }

        val hzPoints = DoubleArray(N_MELS + 2)
        for (i in hzPoints.indices) {
            hzPoints[i] = melToHz(melPoints[i])
        }

        val binPoints = DoubleArray(N_MELS + 2)
        for (i in binPoints.indices) {
            binPoints[i] = (nBins - 1) * hzPoints[i] / (SAMPLE_RATE / 2.0)
        }

        for (m in 1..N_MELS) {
            val left = binPoints[m - 1]
            val center = binPoints[m]
            val right = binPoints[m + 1]
            for (k in 0 until nBins) {
                val kk = k.toDouble()
                var v = 0.0
                if (kk >= left && kk <= center) {
                    v = if (center == left) 0.0 else (kk - left) / (center - left)
                } else if (kk > center && kk <= right) {
                    v = if (right == center) 0.0 else (right - kk) / (right - center)
                }
                filter[(m - 1) * nBins + k] = v.toFloat()
            }
        }
        return filter
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)

    private fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /**
     * 唤醒触发 + 冷却期处理。
     */
    private fun onWakeWordDetected() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < COOLDOWN_MS) return
        lastWakeAt = now
        Log.i(TAG, "检测到唤醒词！")
        onWakeWord?.invoke()
    }

    /**
     * 迭代 radix-2 FFT（仅取幅度谱）。
     *
     * n 必须是 2 的幂（512 = 2^9）。相比朴素 DFT 的 O(n²)，FFT 是 O(n log n)，
     * 512 点从约 13 万次乘加降到约 4600 次，61 帧推理从 ~2.6s 降到 ~0.1s。
     */
    private class FFT(private val n: Int) {
        private val cosTable: FloatArray
        private val sinTable: FloatArray

        init {
            // twiddle 因子 W_n^i = exp(-2πi·i/n)
            cosTable = FloatArray(n / 2)
            sinTable = FloatArray(n / 2)
            for (i in 0 until n / 2) {
                val angle = -2.0 * PI * i / n
                cosTable[i] = cos(angle).toFloat()
                sinTable[i] = sin(angle).toFloat()
            }
        }

        /** 计算幅度谱，返回 (n/2+1) 维。 */
        fun magnitude(x: FloatArray): FloatArray {
            val re = FloatArray(n)
            val im = FloatArray(n)
            for (i in 0 until n) re[i] = x[i]

            // 位反转置换
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val t = re[i]; re[i] = re[j]; re[j] = t
                }
            }

            // 蝶形运算
            var len = 2
            while (len <= n) {
                val half = len shr 1
                val step = n / len
                var i = 0
                while (i < n) {
                    for (k in 0 until half) {
                        val idx = k * step
                        val c = cosTable[idx]
                        val s = sinTable[idx]
                        val tRe = c * re[i + k + half] - s * im[i + k + half]
                        val tIm = s * re[i + k + half] + c * im[i + k + half]
                        re[i + k + half] = re[i + k] - tRe
                        im[i + k + half] = im[i + k] - tIm
                        re[i + k] = re[i + k] + tRe
                        im[i + k] = im[i + k] + tIm
                    }
                    i += len
                }
                len = len shl 1
            }

            val half = n / 2 + 1
            val mag = FloatArray(half)
            for (k in 0 until half) {
                mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]).toFloat()
            }
            return mag
        }
    }
}
