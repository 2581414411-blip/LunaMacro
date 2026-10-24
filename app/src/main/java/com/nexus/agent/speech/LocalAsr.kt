package com.nexus.agent.speech

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地语音识别（sherpa-onnx + SenseVoice INT8）。
 *
 * 完全离线，中文识别，替换云端 DashScope paraformer。
 */
object LocalAsr {

    private const val TAG = "LocalAsr"
    const val SAMPLE_RATE = 16000

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var assetManager: AssetManager? = null

    /**
     * 仅保存 AssetManager，不加载模型（懒加载准备）。
     * 开启语音助手时只调用 prepare，SenseVoice 模型推迟到首次识别时才加载，
     * 避免开启瞬间在主线程加载 229M 模型导致卡顿/崩溃。
     */
    fun prepare(am: AssetManager) {
        assetManager = am
    }

    /**
     * 加载 SenseVoice 模型（懒加载，首次识别时调用）。
     */
    private fun ensureInit(): Boolean {
        if (recognizer != null) return true
        val am = assetManager ?: return false
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "sensevoice/model.int8.onnx",
                    useInverseTextNormalization = true,
                ),
                tokens = "sensevoice/tokens.txt",
                numThreads = 2,
            ),
        )
        return try {
            recognizer = OfflineRecognizer(am, config)
            Log.i(TAG, "SenseVoice 模型加载成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "SenseVoice 初始化失败", e)
            recognizer = null
            false
        }
    }

    /**
     * 初始化 SenseVoice 模型（保留旧接口：保存 + 立即加载）。
     */
    fun init(am: AssetManager): Boolean {
        prepare(am)
        return ensureInit()
    }

    /**
     * 识别一段音频（float 采样，归一化 [-1,1]，16kHz 单声道）。
     * @return 识别文本；失败返回空串
     */
    suspend fun recognize(samples: FloatArray): String = withContext(Dispatchers.IO) {
        if (!ensureInit()) return@withContext ""
        val r = recognizer ?: return@withContext ""
        return@withContext try {
            val stream = r.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(stream)
            val result = r.getResult(stream)
            stream.release()
            result.text.trim()
        } catch (e: Throwable) {
            Log.e(TAG, "本地识别失败", e)
            ""
        }
    }

    /**
     * 释放模型。
     */
    fun release() {
        try { recognizer?.release() } catch (e: Throwable) { Log.e(TAG, "释放失败", e) }
        recognizer = null
    }
}
