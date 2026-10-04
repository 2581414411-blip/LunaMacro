package com.nexus.agent.game

import android.graphics.Bitmap
import android.util.Log

/**
 * 游戏状态（从一帧截图识别出的结构化信息）。
 */
data class GameState(
    val timestamp: Long,
    val healthPercent: Int = 0,
    val skillCooldowns: Map<String, Int> = emptyMap(),
    val resources: Map<String, Int> = emptyMap(),
    val customFields: Map<String, Any> = emptyMap()
)

/**
 * 游戏状态识别器。
 *
 * 第一版只实现「血条识别」（颜色检测），其余识别（技能冷却、资源计数、
 * 模板匹配、OCR）留接口，后续版本扩展。
 */
object GameStateRecognizer {

    private const val TAG = "GameStateRecognizer"

    /**
     * 识别一帧截图的状态。
     */
    suspend fun recognize(frame: Bitmap): GameState {
        val health = detectHealth(frame)
        Log.d(TAG, "health=$health")
        return GameState(
            timestamp = System.currentTimeMillis(),
            healthPercent = health,
            skillCooldowns = emptyMap(),
            resources = emptyMap(),
            customFields = emptyMap()
        )
    }

    /**
     * 血条识别（颜色检测）：统计血条区域红色像素占比，近似血量百分比。
     */
    private fun detectHealth(frame: Bitmap): Int {
        val bar = RegionExtractor.extract(frame, RegionExtractor.HEALTH_BAR)
        var sampled = 0
        var redCount = 0
        try {
            for (x in 0 until bar.width step 2) {
                for (y in 0 until bar.height step 2) {
                    sampled++
                    val pixel = bar.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r > 150 && g < 100 && b < 100) redCount++
                }
            }
        } finally {
            bar.recycle()
        }
        if (sampled == 0) return 0
        return (redCount * 100 / sampled).coerceIn(0, 100)
    }
}
