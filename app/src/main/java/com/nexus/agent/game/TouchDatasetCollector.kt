package com.nexus.agent.game

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
 * 触摸采集器（分步向导版 · 免 Shizuku · 每步手动点开始）。
 *
 * 按 [CollectFlow.STEPS] 分步采集训练数据，**每一步都需用户点「开始」**，不会让用户赶：
 * - 点「开始采集」→ 浮窗提示第 1 步（如「打开原神」）
 * - 用户准备好后点浮窗「开始」→ 采集当前步骤（定时截图，1.5s/帧）
 * - 采满目标张数后**自动停止**，浮窗提示「下一步该干什么」
 * - 用户准备好再点「开始」→ 继续下一步，如此循环
 * - 最后一步采满 → 自动完成，回调通知 UI
 *
 * 截图优先走**无障碍 takeScreenshot**（Android 11+，无需 Shizuku），
 * fallback Shizuku screencap。每帧 label 写 scene 标签（不含触摸坐标）。
 */
object TouchDatasetCollector {

    private const val TAG = "TouchCollector"
    private const val MAX_COUNT = 2000
    private const val INTERVAL_MS = 1500L

    data class CollectionResult(
        val collected: Int,
        val elapsedMs: Long,
        val imagesDir: String,
        val labelsDir: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 整个采集流程是否激活 */
    @Volatile
    private var flowActive = false

    /** 当前步骤是否正在采集 */
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

    private var startTime = 0L

    /** 采集总数回调（UI 更新悬浮窗总计数） */
    @Volatile
    var onCountChanged: ((Int) -> Unit)? = null

    /**
     * 步骤就绪回调（进入某一步的「等待点开始」状态）。
     * 参数：步骤索引(0-based)、总步数、标题、引导语
     */
    @Volatile
    var onStepReady: ((Int, Int, String, String) -> Unit)? = null

    /**
     * 步骤进度回调（采集中）。
     * 参数：步骤索引(0-based)、总步数、标题、引导语、本步已采集、本步目标张数
     */
    @Volatile
    var onStepProgress: ((Int, Int, String, String, Int, Int) -> Unit)? = null

    /** 全部步骤完成回调（UI 隐藏浮窗、同步开关状态） */
    @Volatile
    var onFlowComplete: (() -> Unit)? = null

    val isCollecting: Boolean get() = collecting
    val isFlowActive: Boolean get() = flowActive

    /**
     * 启动整个采集流程：进入第 1 步的「等待点开始」状态，**不立即采集**。
     *
     * @return 是否成功启动；截图能力（无障碍或 Shizuku）都不可用时返回 false
     */
    fun startFlow(): Boolean {
        if (flowActive) return false
        val ctx = ShizukuManager.appContext() ?: return false

        val hasAccessibility = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            NexusAccessibilityService.isBound()
        val hasShizuku = ShizukuManager.getService() != null
        if (!hasAccessibility && !hasShizuku) {
            Log.e(TAG, "无障碍与 Shizuku 均不可用，无法截图")
            return false
        }

        count = 0
        stepCount = 0
        currentStepIndex = 0
        startTime = System.currentTimeMillis()
        flowActive = true
        collecting = false

        val steps = CollectFlow.STEPS
        val s = steps.first()
        onStepReady?.invoke(0, steps.size, s.title, s.hint)
        Log.i(TAG, "采集流程启动，共 ${steps.size} 步，截图通道=${if (hasAccessibility) "无障碍" else "Shizuku"}")
        return true
    }

    /**
     * 用户点「开始」：开始采集当前步骤。
     * 引导步骤（目标 0 张）直接跳到下一步等待态。
     */
    fun startStep() {
        if (!flowActive || collecting) return
        val step = CollectFlow.STEPS[currentStepIndex]

        // 引导步骤（目标 0 张）：直接进入下一步
        if (step.targetCount == 0) {
            advanceToNext()
            return
        }

        collecting = true
        stepCount = 0
        scope.launch { collectCurrentStep() }
        Log.i(TAG, "开始采集步骤 ${currentStepIndex + 1}/${CollectFlow.STEPS.size}：${step.title}")
    }

    /**
     * 采集当前步骤，直到采满目标张数（或用户停止），然后进入下一步等待态。
     */
    private suspend fun collectCurrentStep() {
        val step = CollectFlow.STEPS[currentStepIndex]
        while (collecting && stepCount < step.targetCount) {
            delay(INTERVAL_MS)
            if (!collecting) break
            if (captureAndSave(step.sceneKey, step.title)) {
                stepCount++
                onStepProgress?.invoke(
                    currentStepIndex, CollectFlow.STEPS.size,
                    step.title, step.hint, stepCount, step.targetCount
                )
            }
        }
        collecting = false
        // 采满（未被手动停止）→ 进入下一步等待态
        if (stepCount >= step.targetCount) {
            advanceToNext()
        }
    }

    /**
     * 进入下一步的「等待点开始」状态；若已是最后一步，则完成整个流程。
     */
    private fun advanceToNext() {
        val steps = CollectFlow.STEPS
        val next = currentStepIndex + 1
        if (next >= steps.size) {
            flowActive = false
            collecting = false
            Log.i(TAG, "全部步骤采集完成")
            onFlowComplete?.invoke()
            return
        }
        currentStepIndex = next
        stepCount = 0
        val s = steps[next]
        onStepReady?.invoke(next, steps.size, s.title, s.hint)
        Log.i(TAG, "进入步骤 ${next + 1}/${steps.size}：${s.title}（目标 ${s.targetCount} 张），等待点开始")
    }

    /**
     * 用户手动停止整个采集流程。
     */
    fun stopCollection(): CollectionResult {
        if (!flowActive && !collecting) {
            return CollectionResult(count, System.currentTimeMillis() - startTime, "dataset/images", "dataset/labels")
        }
        flowActive = false
        collecting = false
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
     * 截一帧并写 label。
     * @return 是否成功采集一帧
     */
    private fun captureAndSave(sceneKey: String, sceneTitle: String): Boolean {
        if (count >= MAX_COUNT) {
            Log.w(TAG, "已达采集上限 $MAX_COUNT")
            return false
        }
        val ctx = ShizukuManager.appContext() ?: return false
        val root = ctx.getExternalFilesDir("dataset") ?: return false
        val imagesDir = File(root, "images").apply { mkdirs() }
        val labelsDir = File(root, "labels").apply { mkdirs() }

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
