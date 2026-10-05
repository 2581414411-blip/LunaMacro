package com.nexus.agent.game

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * SFT 训练数据自动采集器。
 *
 * 在 Agent/游戏运行过程中，随 GamePerceptionLoop 截图自动采集：
 * - 截图保存到 `getExternalFilesDir("dataset")/images/{ts}_{screenType}.jpg`（压缩到最长边 448）
 * - 同时保存一份空 ScreenAnalysis 到 `labels/`（screenType 默认「其他」，待手动标注补充）
 *
 * 采集上限默认 1000 张，超过自动停止。保存走 IO 协程异步，不拖慢截图循环。
 */
object DatasetCollector {

    private const val TAG = "DatasetCollector"
    private const val MAX_COUNT = 1000
    private const val IMAGE_SIZE = 448
    private const val MIN_INTERVAL_MS = 500L

    @Volatile
    private var collecting = false

    @Volatile
    private var collectedCount = 0

    private var lastCollectTime = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val analysisAdapter = moshi.adapter(ScreenAnalysis::class.java)

    /** 采集开关变化回调 */
    @Volatile
    var onStateChanged: ((Boolean) -> Unit)? = null

    /** 计数变化回调 */
    @Volatile
    var onCountChanged: ((Int) -> Unit)? = null

    /**
     * 启用/停止采集。
     */
    fun enableCollection(enabled: Boolean) {
        if (enabled == collecting) return
        collecting = enabled
        if (enabled) {
            collectedCount = 0
            lastCollectTime = 0
        }
        onStateChanged?.invoke(enabled)
        Log.i(TAG, "采集${if (enabled) "开启" else "关闭"}")
    }

    fun isCollecting(): Boolean = collecting

    fun getCollectedCount(): Int = collectedCount

    /**
     * 截图钩子，由 [GamePerceptionLoop] 调用。
     */
    fun onFrame(bitmap: Bitmap) {
        if (!collecting) return
        if (collectedCount >= MAX_COUNT) {
            collecting = false
            onStateChanged?.invoke(false)
            Log.i(TAG, "达到采集上限 $MAX_COUNT，自动停止")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCollectTime < MIN_INTERVAL_MS) return
        lastCollectTime = now

        // 复制 bitmap（异步保存，避免截图循环 recycle 竞态）
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        scope.launch { saveFrame(copy) }
    }

    private fun saveFrame(bitmap: Bitmap) {
        try {
            val ctx = ShizukuManager.appContext() ?: return
            val root = ctx.getExternalFilesDir("dataset") ?: return
            val imagesDir = File(root, "images").apply { mkdirs() }
            val labelsDir = File(root, "labels").apply { mkdirs() }

            val screenType = "其他"
            val name = "${System.currentTimeMillis()}_$screenType"

            // 压缩到最长边 448（与模型输入一致）
            val scaled = scaleTo(bitmap, IMAGE_SIZE)
            val imgFile = File(imagesDir, "$name.jpg")
            FileOutputStream(imgFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }

            // 保存空 ScreenAnalysis（待标注）
            val analysis = ScreenAnalysis(screenType = screenType)
            File(labelsDir, "$name.json").writeText(analysisAdapter.toJson(analysis))

            collectedCount++
            onCountChanged?.invoke(collectedCount)
            Log.i(TAG, "collected=$collectedCount, screen_type=$screenType")
        } catch (e: Throwable) {
            Log.e(TAG, "保存采集帧失败", e)
        } finally {
            bitmap.recycle()
        }
    }

    private fun scaleTo(src: Bitmap, size: Int): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= size) return src
        val ratio = size.toFloat() / maxEdge
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }
}
