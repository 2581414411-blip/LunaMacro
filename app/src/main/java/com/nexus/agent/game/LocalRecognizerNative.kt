package com.nexus.agent.game

import android.util.Log

/**
 * MNN 本地识别器的 JNI 绑定。
 *
 * 对应 cpp/mnn_recognizer.cpp 的 native 方法。
 * 加载顺序：MNN → MNN_Express → llm → mnn_recognizer（依赖顺序）。
 */
object LocalRecognizerNative {

    private const val TAG = "LocalRecognizerNative"

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("MNN")
            System.loadLibrary("MNN_Express")
            System.loadLibrary("llm")
            System.loadLibrary("mnn_recognizer")
            loaded = true
            Log.i(TAG, "MNN 库加载成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "MNN 库加载失败", e)
            false
        }
    }

    /**
     * 初始化模型（modelDir 为模型目录，含 config.json）。
     */
    external fun init(modelDir: String): Boolean

    /**
     * 识别一帧截图。imagePath 为截图文件路径，prompt 为指令文本。
     * @return 识别结果文本；失败返回 null
     */
    external fun recognize(imagePath: String, prompt: String): String?

    /**
     * 释放模型。
     */
    external fun release()
}
