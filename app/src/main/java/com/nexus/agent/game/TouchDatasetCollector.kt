package com.nexus.agent.game

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 触摸采集器。
 *
 * 结合 [TouchEventMonitor] 的触摸事件和截图，生成训练数据：
 * 用户正常玩一遍游戏，每次触摸（节流后）截图 + 记录坐标。
 */
object TouchDatasetCollector {

    private const val TAG = "TouchCollector"
    private const val MAX_COUNT = 2000
    private const val THROTTLE_MS = 500L

    data class CollectionResult(
        val collected: Int,
        val elapsedMs: Long,
        val imagesDir: String,
        val labelsDir: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var collecting = false

    @Volatile
    private var count = 0

    private var lastTouchTime = 0L
    private var startTime = 0L
    private var screenWidth = 0
    private var screenHeight = 0

    /** 当前采集场景（引导标签，写入每帧 label 的 scene 字段） */
    @Volatile
    private var currentScene: CollectScenes.Scene = CollectScenes.ALL.first()

    /** 采集数量回调（UI 更新悬浮窗） */
    @Volatile
    var onCountChanged: ((Int) -> Unit)? = null

    /** 场景切换回调（UI 更新引导浮窗文案） */
    @Volatile
    var onSceneChanged: ((CollectScenes.Scene) -> Unit)? = null

    val isCollecting: Boolean get() = collecting

    /** 当前场景 */
    val scene: CollectScenes.Scene get() = currentScene

    private val touchListener = object : TouchEventMonitor.TouchListener {
        override fun onTouchDown(x: Int, y: Int) {
            handleTouch(x, y)
        }

        override fun onTouchUp(x: Int, y: Int) {
            // down 已记录，up 不重复处理
        }
    }

    /**
     * 启动采集。
     */
    fun startCollection(): Boolean {
        if (collecting) return false
        val ctx = ShizukuManager.appContext() ?: return false

        val dm = ctx.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels

        count = 0
        lastTouchTime = 0L
        startTime = System.currentTimeMillis()
        collecting = true
        // 启动时重置到第一个场景
        currentScene = CollectScenes.ALL.first()
        onSceneChanged?.invoke(currentScene)

        if (!TouchEventMonitor.start(touchListener)) {
            collecting = false
            return false
        }
        Log.i(TAG, "触摸采集已启动，屏幕 ${screenWidth}x$screenHeight")
        return true
    }

    /**
     * 停止采集并返回结果。
     */
    fun stopCollection(): CollectionResult {
        collecting = false
        TouchEventMonitor.stop()
        val result = CollectionResult(
            collected = count,
            elapsedMs = System.currentTimeMillis() - startTime,
            imagesDir = "dataset/images",
            labelsDir = "dataset/labels"
        )
        Log.i(TAG, "采集结束：${result.collected} 张，耗时 ${result.elapsedMs}ms")
        return result
    }

    /**
     * 切换到下一个引导场景（循环）。采集过程中由浮窗按钮触发。
     * 切换后回调 [onSceneChanged]，UI 据此刷新引导语。
     *
     * @return 切换后的场景
     */
    fun nextScene(): CollectScenes.Scene {
        val all = CollectScenes.ALL
        val idx = all.indexOfFirst { it.key == currentScene.key }
        currentScene = all[(idx + 1).coerceAtLeast(0) % all.size]
        onSceneChanged?.invoke(currentScene)
        Log.i(TAG, "切换到场景：${currentScene.key}（${currentScene.title}）")
        return currentScene
    }

    private fun handleTouch(x: Int, y: Int) {
        if (!collecting) return
        if (count >= MAX_COUNT) {
            Log.w(TAG, "已达采集上限 $MAX_COUNT")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTouchTime < THROTTLE_MS) return
        lastTouchTime = now

        scope.launch(Dispatchers.IO) {
            captureAndSave(x, y, now)
        }
    }

    private fun captureAndSave(x: Int, y: Int, ts: Long) {
        val ctx = ShizukuManager.appContext() ?: return
        val root = ctx.getExternalFilesDir("dataset") ?: return
        val imagesDir = File(root, "images").apply { mkdirs() }
        val labelsDir = File(root, "labels").apply { mkdirs() }

        val name = "manual_$ts"

        // 延迟 200ms 等界面响应后截图
        Thread.sleep(200)
        val bitmap = ScreenCapture.captureBitmap(448)
        if (bitmap == null) {
            Log.w(TAG, "截图失败，跳过")
            return
        }

        try {
            val imgFile = File(imagesDir, "$name.jpg")
            FileOutputStream(imgFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bitmap.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "保存截图失败", e)
            return
        }

        // 归一化：用触摸面板 max 值（非屏幕像素），getevent 坐标范围 0..maxX/0..maxY
        val mx = TouchEventMonitor.maxCoordX
        val my = TouchEventMonitor.maxCoordY
        val nx = if (mx > 0) x.toFloat() / (mx + 1) else 0f
        val ny = if (my > 0) y.toFloat() / (my + 1) else 0f
        val label = JSONObject().apply {
            put("timestamp", ts)
            put("touchX", x)
            put("touchY", y)
            put("touchNormalizedX", nx.toDouble())
            put("touchNormalizedY", ny.toDouble())
            put("screenWidth", screenWidth)
            put("screenHeight", screenHeight)
            put("screenshot", "$name.jpg")
            put("scene", currentScene.key)
            put("sceneTitle", currentScene.title)
            put("annotated", false)
        }
        File(labelsDir, "$name.json").writeText(label.toString())

        count++
        onCountChanged?.invoke(count)
        Log.i(TAG, "collected=$count, touch=($x,$y), elapsed_ms=${System.currentTimeMillis() - startTime}")
    }

    /**
     * 导出采集数据（dataset/images + dataset/labels）为 zip，返回文件。
     */
    fun export(): File? {
        val ctx = ShizukuManager.appContext() ?: return null
        val root = ctx.getExternalFilesDir("dataset") ?: return null
        val zipFile = File(ctx.cacheDir, "touch_dataset_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                listOf("images", "labels").forEach { sub ->
                    val dir = File(root, sub)
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { f ->
                            zos.putNextEntry(ZipEntry("$sub/${f.name}"))
                            zos.write(f.readBytes())
                            zos.closeEntry()
                        }
                    }
                }
            }
            Log.i(TAG, "导出完成：${zipFile.name}")
            return zipFile
        } catch (e: Throwable) {
            Log.e(TAG, "导出失败", e)
            return null
        }
    }
}
