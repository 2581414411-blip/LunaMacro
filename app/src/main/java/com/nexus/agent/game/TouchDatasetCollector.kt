package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 触摸采集器（分步向导 · 免 Shizuku · 每步手动点开始 · 断点续采 · 批次管理）。
 *
 * 采集流程：
 * - 点「开始采集」→ 检测断点（有未完成进度则提示继续/重新开始）
 * - 每步点「开始」→ 定时截图（1.5s/帧），采满自动停，进入下一步等待态
 * - 一轮 6 步采完不直接停，累计没满 [MAX_COUNT]（2000）就回到第 1 步继续
 * - 累计满 2000 才真正完成
 *
 * **批次管理**：每次「重新开始」会创建新批次目录（dataset/batch_xxx/），
 * 数据按批次隔离。批次列表可查看、导出（上传）、删除，用户手动管理。
 *
 * **断点续采**：进度（批次 + 步骤 + 张数）持久化，中断后下次可「继续」。
 */
object TouchDatasetCollector {

    private const val TAG = "TouchCollector"
    private const val MAX_COUNT = 2000
    private const val INTERVAL_MS = 1500L

    private const val PREFS = "collect_progress"
    private const val KEY_BATCH = "batch_id"
    private const val KEY_STEP = "step_index"
    private const val KEY_STEP_COUNT = "step_count"
    private const val KEY_TOTAL = "total_count"

    data class CollectionResult(
        val collected: Int,
        val elapsedMs: Long,
        val imagesDir: String,
        val labelsDir: String
    )

    /** 断点进度信息 */
    data class ProgressInfo(val totalCount: Int, val stepIndex: Int, val stepCount: Int)

    /** 采集批次信息 */
    data class BatchInfo(val id: String, val time: Long, val count: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var flowActive = false

    @Volatile
    private var collecting = false

    @Volatile
    private var count = 0

    @Volatile
    private var currentStepIndex = 0

    @Volatile
    private var stepCount = 0

    /** 当前批次目录名（batch_时间戳） */
    @Volatile
    private var currentBatchId = ""

    private var startTime = 0L

    @Volatile
    var onCountChanged: ((Int) -> Unit)? = null

    @Volatile
    var onStepReady: ((Int, Int, String, String) -> Unit)? = null

    @Volatile
    var onStepProgress: ((Int, Int, String, String, Int, Int) -> Unit)? = null

    @Volatile
    var onFlowComplete: (() -> Unit)? = null

    val isCollecting: Boolean get() = collecting
    val isFlowActive: Boolean get() = flowActive

    /** 是否有未完成的采集进度（断点） */
    fun hasUnfinishedProgress(): Boolean {
        val ctx = ShizukuManager.appContext() ?: return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_TOTAL, 0) > 0
    }

    /** 获取断点进度信息 */
    fun getProgressInfo(): ProgressInfo {
        val ctx = ShizukuManager.appContext() ?: return ProgressInfo(0, 0, 0)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ProgressInfo(
            totalCount = prefs.getInt(KEY_TOTAL, 0),
            stepIndex = prefs.getInt(KEY_STEP, 0),
            stepCount = prefs.getInt(KEY_STEP_COUNT, 0)
        )
    }

    /**
     * 列出所有采集批次（按时间倒序）。
     */
    fun listBatches(): List<BatchInfo> {
        val ctx = ShizukuManager.appContext() ?: return emptyList()
        val root = ctx.getExternalFilesDir("dataset") ?: return emptyList()
        val batches = mutableListOf<BatchInfo>()
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name.startsWith("batch_")) {
                val images = File(dir, "images")
                val count = images.listFiles()?.size ?: 0
                val time = dir.name.removePrefix("batch_").toLongOrNull() ?: 0L
                batches.add(BatchInfo(dir.name, time, count))
            }
        }
        return batches.sortedByDescending { it.time }
    }

    /**
     * 删除指定批次。
     */
    fun deleteBatch(id: String): Boolean {
        val ctx = ShizukuManager.appContext() ?: return false
        val root = ctx.getExternalFilesDir("dataset") ?: return false
        val dir = File(root, id)
        return dir.exists() && dir.deleteRecursively()
    }

    /**
     * 导出指定批次为 zip。
     */
    fun exportBatch(id: String): File? {
        val ctx = ShizukuManager.appContext() ?: return null
        val root = ctx.getExternalFilesDir("dataset") ?: return null
        val batchDir = File(root, id)
        if (!batchDir.exists()) return null
        val zipFile = File(ctx.cacheDir, "${id}_${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                listOf("images", "labels").forEach { sub ->
                    val dir = File(batchDir, sub)
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { f ->
                            zos.putNextEntry(ZipEntry("$sub/${f.name}"))
                            zos.write(f.readBytes())
                            zos.closeEntry()
                        }
                    }
                }
            }
            Log.i(TAG, "批次 $id 导出完成：${zipFile.name}")
            return zipFile
        } catch (e: Throwable) {
            Log.e(TAG, "批次导出失败", e)
            return null
        }
    }

    /**
     * 启动采集流程。
     *
     * @param resume true=从断点继续；false=从头开始（清断点 + 新建批次）
     */
    fun startFlow(resume: Boolean = false): Boolean {
        if (flowActive) return false
        val ctx = ShizukuManager.appContext() ?: return false

        val hasAccessibility = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            NexusAccessibilityService.isBound()
        val hasShizuku = ShizukuManager.getService() != null
        if (!hasAccessibility && !hasShizuku) {
            Log.e(TAG, "无障碍与 Shizuku 均不可用，无法截图")
            return false
        }

        if (resume) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            currentBatchId = prefs.getString(KEY_BATCH, "").orEmpty()
            currentStepIndex = prefs.getInt(KEY_STEP, 0).coerceIn(0, CollectFlow.STEPS.size - 1)
            stepCount = prefs.getInt(KEY_STEP_COUNT, 0)
            count = prefs.getInt(KEY_TOTAL, 0)
            if (currentBatchId.isBlank()) {
                currentBatchId = newBatchId()
            }
        } else {
            clearProgress()
            currentBatchId = newBatchId()
            count = 0
            stepCount = 0
            currentStepIndex = 0
        }
        startTime = System.currentTimeMillis()
        flowActive = true
        collecting = false

        val steps = CollectFlow.STEPS
        val s = steps[currentStepIndex]
        onStepReady?.invoke(currentStepIndex, steps.size, s.title, s.hint)
        onCountChanged?.invoke(count)
        saveProgress()
        Log.i(TAG, "采集流程启动${if (resume) "（续采，批次 $currentBatchId，第 ${currentStepIndex + 1} 步，共 $count 张）" else "（新批次 $currentBatchId）"}")
        return true
    }

    /**
     * 用户点「开始」：开始采集当前步骤。
     */
    fun startStep() {
        if (!flowActive || collecting) return
        val step = CollectFlow.STEPS[currentStepIndex]

        if (step.targetCount == 0) {
            advanceToNext()
            return
        }

        collecting = true
        stepCount = 0
        saveProgress()
        scope.launch { collectCurrentStep() }
        Log.i(TAG, "开始采集步骤 ${currentStepIndex + 1}/${CollectFlow.STEPS.size}：${step.title}")
    }

    private suspend fun collectCurrentStep() {
        val step = CollectFlow.STEPS[currentStepIndex]
        while (collecting && stepCount < step.targetCount && count < MAX_COUNT) {
            delay(INTERVAL_MS)
            if (!collecting) break
            if (captureAndSave(step.sceneKey, step.title)) {
                stepCount++
                onStepProgress?.invoke(
                    currentStepIndex, CollectFlow.STEPS.size,
                    step.title, step.hint, stepCount, step.targetCount
                )
                saveProgress()
            }
        }
        collecting = false

        if (count >= MAX_COUNT) {
            flowActive = false
            clearProgress()
            Log.i(TAG, "累计采满 $MAX_COUNT 张，完成")
            onFlowComplete?.invoke()
            return
        }
        if (stepCount >= step.targetCount) {
            advanceToNext()
        }
    }

    private fun advanceToNext() {
        val steps = CollectFlow.STEPS
        val next = currentStepIndex + 1
        if (next >= steps.size) {
            if (count >= MAX_COUNT) {
                flowActive = false
                collecting = false
                clearProgress()
                onFlowComplete?.invoke()
            } else {
                currentStepIndex = 0
                stepCount = 0
                saveProgress()
                val s = steps.first()
                onStepReady?.invoke(0, steps.size, s.title, s.hint)
                Log.i(TAG, "一轮采完（共 $count 张），回到第 1 步继续，目标 $MAX_COUNT 张")
            }
            return
        }
        currentStepIndex = next
        stepCount = 0
        saveProgress()
        val s = steps[next]
        onStepReady?.invoke(next, steps.size, s.title, s.hint)
        Log.i(TAG, "进入步骤 ${next + 1}/${steps.size}：${s.title}")
    }

    /**
     * 用户手动停止采集（保留断点，下次可续）。
     */
    fun stopCollection(): CollectionResult {
        if (!flowActive && !collecting) {
            return CollectionResult(count, System.currentTimeMillis() - startTime, "dataset/$currentBatchId/images", "dataset/$currentBatchId/labels")
        }
        flowActive = false
        collecting = false
        if (count > 0) saveProgress() else clearProgress()
        val result = CollectionResult(
            collected = count,
            elapsedMs = System.currentTimeMillis() - startTime,
            imagesDir = "dataset/$currentBatchId/images",
            labelsDir = "dataset/$currentBatchId/labels"
        )
        Log.i(TAG, "采集停止：${result.collected} 张（已保留断点，可续采）")
        return result
    }

    private fun newBatchId(): String = "batch_${System.currentTimeMillis()}"

    private fun saveProgress() {
        val ctx = ShizukuManager.appContext() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_BATCH, currentBatchId)
            .putInt(KEY_STEP, currentStepIndex)
            .putInt(KEY_STEP_COUNT, stepCount)
            .putInt(KEY_TOTAL, count)
            .apply()
    }

    private fun clearProgress() {
        val ctx = ShizukuManager.appContext() ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * 截一帧并写 label（存到当前批次目录）。
     */
    private fun captureAndSave(sceneKey: String, sceneTitle: String): Boolean {
        if (count >= MAX_COUNT) {
            Log.w(TAG, "已达采集上限 $MAX_COUNT")
            return false
        }
        val ctx = ShizukuManager.appContext() ?: return false
        val root = ctx.getExternalFilesDir("dataset") ?: return false
        val batchDir = File(root, currentBatchId).apply { mkdirs() }
        val imagesDir = File(batchDir, "images").apply { mkdirs() }
        val labelsDir = File(batchDir, "labels").apply { mkdirs() }

        val ts = System.currentTimeMillis()
        val name = "manual_$ts"

        val bitmap = ScreenCapture.captureBitmap(448)
        if (bitmap == null) {
            Log.w(TAG, "截图失败，跳过")
            return false
        }

        try {
            val imgFile = File(imagesDir, "$name.jpg")
            FileOutputStream(imgFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            bitmap.recycle()
        } catch (e: Throwable) {
            Log.e(TAG, "保存截图失败", e)
            return false
        }

        val label = JSONObject().apply {
            put("timestamp", ts)
            put("screenshot", "$name.jpg")
            put("scene", sceneKey)
            put("sceneTitle", sceneTitle)
            put("annotated", false)
        }
        File(labelsDir, "$name.json").writeText(label.toString())

        count++
        onCountChanged?.invoke(count)
        Log.i(TAG, "collected=$count, step=${currentStepIndex + 1}, scene=$sceneKey")
        return true
    }
}
