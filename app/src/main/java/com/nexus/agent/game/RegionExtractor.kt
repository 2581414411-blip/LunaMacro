package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap

/**
 * 屏幕区域（比例坐标 0.0-1.0，相对屏幕宽高）。
 */
data class Region(
    val name: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * 区域提取器。
 *
 * 从整帧截图中按比例裁剪出子区域（血条 / 技能栏 / 小地图 / 资源计数等）。
 * 预定义区域提供默认值，可通过 SharedPreferences 覆盖（不同游戏/分辨率可调）。
 */
object RegionExtractor {

    private const val PREFS = "game_regions"

    /** 预定义区域默认值 */
    val HEALTH_BAR = Region("health_bar", 0.05f, 0.90f, 0.45f, 0.94f)
    val SKILL_BAR = Region("skill_bar", 0.20f, 0.85f, 0.80f, 0.95f)
    val MINIMAP = Region("minimap", 0.85f, 0.05f, 1.00f, 0.25f)
    val RESOURCE_COUNT = Region("resource_count", 0.10f, 0.02f, 0.40f, 0.08f)

    /**
     * 从整帧裁剪出 [region] 对应子区域。
     */
    fun extract(frame: Bitmap, region: Region): Bitmap {
        val x = (region.left * frame.width).toInt()
        val y = (region.top * frame.height).toInt()
        val w = ((region.right - region.left) * frame.width).toInt()
        val h = ((region.bottom - region.top) * frame.height).toInt()

        val cx = x.coerceIn(0, frame.width - 1)
        val cy = y.coerceIn(0, frame.height - 1)
        val cw = w.coerceIn(1, frame.width - cx)
        val ch = h.coerceIn(1, frame.height - cy)

        return Bitmap.createBitmap(frame, cx, cy, cw, ch)
    }

    /**
     * 保存区域配置到 SharedPreferences。
     */
    fun saveRegion(context: Context, region: Region) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat("${region.name}_left", region.left)
            .putFloat("${region.name}_top", region.top)
            .putFloat("${region.name}_right", region.right)
            .putFloat("${region.name}_bottom", region.bottom)
            .apply()
    }

    /**
     * 读取区域配置（未保存过返回 null）。
     */
    fun loadRegion(context: Context, name: String): Region? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val left = prefs.getFloat("${name}_left", -1f)
        if (left < 0f) return null
        val top = prefs.getFloat("${name}_top", 0f)
        val right = prefs.getFloat("${name}_right", 0f)
        val bottom = prefs.getFloat("${name}_bottom", 0f)
        return Region(name, left, top, right, bottom)
    }
}
