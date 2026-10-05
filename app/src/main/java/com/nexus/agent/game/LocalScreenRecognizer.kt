package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * 本地屏幕识别器（Qwen2-VL-1.5B，MNN 部署）。
 *
 * 用本地小模型识别当前界面类型和按钮。当前阶段：
 * - MNN 依赖尚未引入、Qwen2-VL-1.5B 模型尚未训练
 * - 因此 [recognize] 在模型缺失时返回 null，由上层降级到云端 Qwen-VL
 *
 * 推理流程（待模型 + MNN 就绪后实现）：
 *   Bitmap → 缩放到 448x448 → MNN 输入 → 输出文本 → 解析 screenType + buttons
 */
object LocalScreenRecognizer {

    private const val TAG = "LocalScreenRecognizer"
    private const val MODEL_DIR = "models/qwen2-vl-1.5b-mnn"
    private const val IMAGE_SIZE = 448

    @Volatile
    private var initialized = false

    @Volatile
    private var modelAvailable = false

    /**
     * 初始化：检测本地模型文件是否存在。
     */
    fun init(context: Context) {
        modelAvailable = try {
            val files = context.assets.list(MODEL_DIR)
            files != null && files.isNotEmpty()
        } catch (e: Throwable) {
            false
        }
        initialized = true
        Log.i(TAG, "本地模型${if (modelAvailable) "可用" else "不存在，识别将降级云端 Qwen-VL"}")
    }

    /**
     * 释放资源。
     */
    fun release() {
        initialized = false
        // TODO: 释放 MNN session
    }

    /**
     * 识别一帧截图。
     *
     * @return 识别结果；模型未就绪时返回 null（上层降级云端）
     */
    suspend fun recognize(bitmap: Bitmap): ScreenAnalysis? {
        if (!initialized || !modelAvailable) return null

        // TODO: MNN 推理（等 Qwen2-VL-1.5B 模型训练 + MNN 依赖引入后实现）
        // 1. Bitmap 缩放到 448x448
        // 2. 输入 MNN 模型，输出文本
        // 3. 解析文本提取 screenType + buttons
        // 日志格式：Log.i(TAG, "infer_ms=xxx, screen_type=xxx, buttons=N")
        Log.w(TAG, "MNN 推理尚未实现，返回 null 降级")
        return null
    }
}
