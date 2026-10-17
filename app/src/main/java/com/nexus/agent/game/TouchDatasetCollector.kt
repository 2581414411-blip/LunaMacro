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
 * 触摸采集器（分步向导版 · 免 Shizuku）。
 *
 * 按 [CollectFlow.STEPS] 分步采集训练数据：
 * - 点击开始 → 浮窗引导「打开原神」→ 检测到用户切到游戏后自动进入第一步
 * - 每一步**定时截图**（每 1.5 秒一帧）到目标张数后自动进下一步
 * - 截图优先走**无障碍 takeScreenshot**（Android 11+，无需 Shizuku），
 *   fallback Shizuku screencap
 * - 每帧 label 写入 scene 标签（不含触摸坐标——训练按钮坐标由视觉模型标注）
 * - 全部步骤完成自动停止，回调通知 UI
 *
 * 设计动机：绕开 Shizuku 门槛，让普通用户「只开无障碍」即可参与众包采集。
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
     * 步骤进度回调。
     * 参数：步骤索引(0-based)、总步数、标题、引导语、本步已采集、本步目标张数
     */
    @Volatile
    var onStepProgress: ((Int, Int, String, String, Int, Int) -> Unit)? = null

    /** 全部步骤完成回调（UI 隐藏浮窗、同步开关状态） */
    @Volatile
    var onFlowComplete: (() -> Unit)? = null

    val isCollecting: Boolean get() = collecting

    /**
     * 启动采集。
     *
     * @return 是否成功启动；截图能力（无障碍或 Shizuku）都不可用时返回 false
     */
    fun startCollection(): Boolean {
        if (collecting) return false
        val ctx = ShizukuManager.appContext() ?: return false

        // 截图能力检查：无障碍(Android 11+) 或 Shizuku，二选一即可
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
        collecting = true

        val steps = CollectFlow.STEPS
        val s = steps.first()
        onStepProgress?.invoke(0, steps.size, s.title, s.hint, 0, s.targetCount)

        scope.launch { collectionLoop() }
        Log.i(TAG, "采集已启动，共 ${steps.size} 步，截图通道=${if (hasAccessibility) "无障碍" else "Shizuku"}")
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
     * 定时采集循环。
     */
    private suspend fun collectionLoop() {
        while (collecting) {
            delay(INTERVAL_MS)
            if (!collecting) break

            val step = CollectFlow.STEPS[currentStepIndex]

            // 引导步骤（目标 0 张）：检测前台包名，用户切到游戏后推进
            if (step.targetCount == 0) {
                if (isForegroundGame()) {
                    advanceStep()
                }
                continue
            }

            // 正常步骤：截一帧
            captureAndSave(step.sceneKey, step.title)
        }
    }

    /**
     * 判断用户是否已切离本应用（即切到了游戏）。
     * 依赖无障碍服务的前台包名监听。
     */
    private fun isForegroundGame(): Boolean {
        val ctx = ShizukuManager.appContext() ?: return false
        val pkg = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
        return !pkg.isNullOrBlank() && pkg != ctx.packageName
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

    private fun captureAndSave(sceneKey: String, sceneTitle: String) {
        if (count >= MAX_COUNT) {
            Log.w(TAG, "已达采集上限 $MAX_COUNT")
            return
        }
        val ctx = ShizukuManager.appContext() ?: return
        val root = ctx.getExternalFilesDir("dataset") ?: return
        val imagesDir = File(root, "images").apply { mkdirs() }
        val labelsDir = File(root, "labels").apply { mkdirs() }

        val ts = System.currentTimeMillis()
        val name = "manual_$ts"

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

        synchronized(this) {
            stepCount++
            val step = CollectFlow.STEPS[currentStepIndex]
            if (stepCount >= step.targetCount) {
                advanceStep()
            } else {
                onStepProgress?.invoke(currentStepIndex, CollectFlow.STEPS.size, step.title, step.hint, stepCount, step.targetCount)
            }
        }
        Log.i(TAG, "collected=$count, step=${currentStepIndex + 1}, scene=$sceneKey")
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
