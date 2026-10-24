package com.nexus.agent.game

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 日常收集器（全能助手定位 · 后台静默 · 无上限）。
 *
 * 软件定位从「游戏助手」升级为「全能助手」，日常使用（打开各种 App、浏览、操作）
 * 也要识别精准。本收集器在后台静默定时截图（无障碍 takeScreenshot），
 * **不加提示词、不打扰用户、不设上限**，覆盖日常各种场景。
 *
 * 每帧 label 记录时间戳 + 前台包名（供训练机 Qwen3-20B 自动打标时参考），
 * 分类/内容标注由训练机大模型完成，本收集器只负责「采」。
 */
object DailyCollector {

    private const val TAG = "DailyCollector"
    private const val INTERVAL_MS = 2000L  // 每 2 秒一帧
    private const val IMAGE_SIZE = 448

    @Volatile
    private var collecting = false

    @Volatile
    private var count = 0L  // 无上限

    private var job: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var onStateChanged: ((Boolean) -> Unit)? = null

    @Volatile
    var onCountChanged: ((Long) -> Unit)? = null

    val isCollecting: Boolean get() = collecting
    val getCount: Long get() = count

    /**
     * 启动日常收集。
     * @return 是否成功启动（无障碍或 Shizuku 截图能力都不可用时 false）
     */
    fun start(): Boolean {
        if (collecting) return true
        val ctx = ShizukuManager.appContext() ?: return false
        val hasAccessibility = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            NexusAccessibilityService.isBound()
        val hasShizuku = ShizukuManager.getService() != null
        if (!hasAccessibility && !hasShizuku) {
            Log.e(TAG, "无障碍与 Shizuku 均不可用，无法截图")
            return false
        }
        collecting = true
        onStateChanged?.invoke(true)
        job = scope.launch {
            while (isActive && collecting) {
                delay(INTERVAL_MS)
                if (!collecting) break
                captureAndSave(ctx)
            }
        }
        Log.i(TAG, "日常收集已启动（截图通道=${if (hasAccessibility) "无障碍" else "Shizuku"}）")
        return true
    }

    /**
     * 停止日常收集。
     */
    fun stop() {
        if (!collecting) return
        collecting = false
        job?.cancel()
        job = null
        onStateChanged?.invoke(false)
        Log.i(TAG, "日常收集已停止，共 $count 张")
    }

    private fun captureAndSave(ctx: android.content.Context) {
        val root = ctx.getExternalFilesDir("daily_dataset") ?: return
        val imagesDir = File(root, "images").apply { mkdirs() }
        val labelsDir = File(root, "labels").apply { mkdirs() }

        val bitmap = ScreenCapture.captureBitmap(IMAGE_SIZE) ?: return
        val ts = System.currentTimeMillis()
        val name = "daily_$ts"

        try {
            val imgFile = File(imagesDir, "$name.jpg")
            FileOutputStream(imgFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "保存截图失败", e)
            return
        }

        // 记录前台包名（供训练机标注参考）
        val pkg = try {
            NexusAccessibilityService.getInstance()?.getCurrentPackageName() ?: ""
        } catch (e: Throwable) {
            ""
        }

        val label = JSONObject().apply {
            put("timestamp", ts)
            put("screenshot", "$name.jpg")
            put("package", pkg)
            put("annotated", false)
        }
        File(labelsDir, "$name.json").writeText(label.toString())

        count++
        onCountChanged?.invoke(count)
        if (count % 50 == 0L) {
            Log.i(TAG, "日常收集已采 $count 张（当前前台 $pkg）")
        }
    }
}
