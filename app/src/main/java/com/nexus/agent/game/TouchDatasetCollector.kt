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
 * 触摸采集器（分步向导版）。
 *
 * 结合 [TouchEventMonitor] 的触摸事件和截图，按 [CollectFlow.STEPS] 分步采集：
 * - 点击开始 → 浮窗引导「打开原神」
 * - 每一步停留采集到目标张数后，自动进入下一步
 * - 每帧 label 写入当前步骤的 scene 标签，供标注/SFT 精准过滤
 * - 全部步骤完成自动停止，回调通知 UI
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

    /** 当前步骤索引（0-based） */
    @Volatile
    private var currentStepIndex = 0

    /** 当前步骤已采集张数 */
    @Volatile
    private var stepCount = 0

    private var lastTouchTime = 0L
    private var startTime = 0L
    private var screenWidth = 0
    private var screenHeight = 0

    /** 采集总数回调（UI 更新悬浮窗总计数） */
    @Volatile
    var onCountChanged: ((Int) -> Unit)? = null

    /**
     * 步骤进度回调。
     * 参数：步骤索引(0-based)、总步数、标题、引导语、本步已采集、本步目标张数
     */
    @Volatile
    var onStepProgress: ((Int, Int, String, String, Int, Int) -> Unit)? = null

    /** 全部步骤完成回调（UI 隐藏浮窗、同步开关状态） */
    @Volatile
    var onFlowComplete: (() -> Unit)? = null

    val isCollecting: Boolean get() = collecting

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
        stepCount = 0
        currentStepIndex = 0
        lastTouchTime = 0L
        startTime = System.currentTimeMillis()
        collecting = true

        // 通知初始步骤
        val steps = CollectFlow.STEPS
        val s = steps.first()
        onStepProgress?.invoke(0, steps.size, s.title, s.hint, 0, s.targetCount)

        if (!TouchEventMonitor.start(touchListener)) {
            collecting = false
            return false
        }
        Log.i(TAG, "触摸采集已启动，屏幕 ${screenWidth}x$screenHeight，共 ${steps.size} 步")
        return true
    }

    /**
     * 停止采集并返回结果。
     */
    fun stopCollection(): CollectionResult {
        if (!collecting) {
            return CollectionResult(count, System.currentTimeMillis() - startTime, "dataset/images", "dataset/labels")
        }
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
     * 推进到下一步；若已是最后一步，则完成并停止采集。
     */
    private fun advanceStep() {
        val steps = CollectFlow.STEPS
        val next = currentStepIndex + 1
        if (next >= steps.size) {
            Log.i(TAG, "全部步骤采集完成，自动停止")
            stopCollection()
            onFlowComplete?.invoke()
            return
        }
        currentStepIndex = next
        stepCount = 0
        val s = steps[next]
        onStepProgress?.invoke(next, steps.size, s.title, s.hint, 0, s.targetCount)
        Log.i(TAG, "进入步骤 ${next + 1}/${steps.size}：${s.title}（目标 ${s.targetCount} 张）")
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

        val step = CollectFlow.STEPS[currentStepIndex]

        // 引导步骤（目标 0 张）：第一次触摸即进入下一步，不采集
        if (step.targetCount == 0) {
            advanceStep()
            return
        }

        // 采集当前步骤的一帧（快照步骤信息，避免异步期间步骤推进导致标签错位）
        val sceneKey = step.sceneKey
        val sceneTitle = step.title
        scope.launch(Dispatchers.IO) {
            captureAndSave(x, y, now, sceneKey, sceneTitle)
        }
    }

    private fun captureAndSave(x: Int, y: Int, ts: Long, sceneKey: String, sceneTitle: String) {
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
            put("scene", sceneKey)
            put("sceneTitle", sceneTitle)
            put("annotated", false)
        }
        File(labelsDir, "$name.json").writeText(label.toString())

        count++
        onCountChanged?.invoke(count)

        // 本步计数 +1，达标自动推进（串行保护，避免多协程竞态）
        synchronized(this) {
            stepCount++
            val step = CollectFlow.STEPS[currentStepIndex]
            if (stepCount >= step.targetCount) {
                advanceStep()
            } else {
                onStepProgress?.invoke(currentStepIndex, CollectFlow.STEPS.size, step.title, step.hint, stepCount, step.targetCount)
            }
        }
        Log.i(TAG, "collected=$count, step=${currentStepIndex + 1}, touch=($x,$y), elapsed_ms=${System.currentTimeMillis() - startTime}")
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
