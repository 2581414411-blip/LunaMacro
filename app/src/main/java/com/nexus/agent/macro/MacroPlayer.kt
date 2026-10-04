package com.nexus.agent.macro

import android.content.Intent
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.tools.ToolSupport
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Macro 回放器。
 *
 * 复用 [ToolSupport] 的注入方法（Shizuku/无障碍降级），按顺序回放
 * [Macro.steps]，支持暂停/恢复/停止，targetPackage 不匹配时先启动目标应用。
 */
object MacroPlayer {

    private const val TAG = "MacroPlayer"

    /** 回放结果 */
    data class PlayResult(
        val total: Int,
        val success: Int,
        val failed: Int,
        val aborted: Boolean,
        val abortedReason: String? = null,
        val elapsedMs: Long = 0
    ) {
        val completed: Int get() = success + failed

        fun summary(): String {
            return if (aborted) {
                "回放中止（${abortedReason ?: "未知原因"}）：已完成 $completed/$total 步，成功 $success，失败 $failed，耗时 ${elapsedMs}ms"
            } else {
                "回放完成：共 $total 步，成功 $success，失败 $failed，耗时 ${elapsedMs}ms"
            }
        }
    }

    @Volatile
    private var stopRequested = false

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var playing = false

    /** 进度回调（current, total） */
    @Volatile
    var onProgress: ((current: Int, total: Int) -> Unit)? = null

    val isPlaying: Boolean get() = playing

    /** 请求停止 */
    fun stop() {
        stopRequested = true
        pauseRequested = false
    }

    /** 请求暂停 */
    fun pause() {
        pauseRequested = true
    }

    /** 请求恢复 */
    fun resume() {
        pauseRequested = false
    }

    /**
     * 回放一个宏。
     */
    suspend fun play(macro: Macro): PlayResult {
        stopRequested = false
        pauseRequested = false
        playing = true

        val startTime = System.currentTimeMillis()
        val steps = macro.steps
        var success = 0
        var failed = 0
        var aborted = false
        var reason: String? = null

        try {
            // 回放前检查：目标包名非空且前台不匹配时，先启动目标应用
            if (!macro.targetPackage.isNullOrBlank()) {
                val current = NexusAccessibilityService.getInstance()?.getCurrentPackageName()
                if (current != macro.targetPackage) {
                    Log.i(TAG, "当前前台 $current 与目标 ${macro.targetPackage} 不匹配，先启动目标应用")
                    openApp(macro.targetPackage)
                    delay(3000)
                }
            }

            for ((i, step) in steps.withIndex()) {
                if (stopRequested) {
                    aborted = true
                    reason = "用户停止"
                    break
                }
                // 暂停等待（每 100ms 检查一次停止标志）
                while (pauseRequested && !stopRequested) {
                    delay(100)
                }
                if (stopRequested) {
                    aborted = true
                    reason = "用户停止"
                    break
                }

                val stepStart = System.currentTimeMillis()
                val ok = try {
                    executeStep(step)
                } catch (e: Throwable) {
                    Log.e(TAG, "executeStep 异常: ${step.type}", e)
                    false
                }
                val elapsed = System.currentTimeMillis() - stepStart

                Log.i(TAG, "step=${i + 1}/${steps.size}, type=${step.type}, params=${step.params}, elapsed_ms=$elapsed, ok=$ok")
                onProgress?.invoke(i + 1, steps.size)

                if (ok) success++ else failed++

                if (step.delayAfterMs > 0 && !stopRequested) {
                    delay(step.delayAfterMs)
                }
            }
        } finally {
            playing = false
        }

        return PlayResult(
            total = steps.size,
            success = success,
            failed = failed,
            aborted = aborted,
            abortedReason = reason,
            elapsedMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 执行单个步骤，返回是否成功。
     */
    private suspend fun executeStep(step: MacroStep): Boolean {
        val p = step.params
        return when (step.type) {
            StepType.TAP -> {
                val x = num(p["x"]) ?: return false
                val y = num(p["y"]) ?: return false
                ToolSupport.injectTap(x, y)
            }
            StepType.LONG_PRESS -> {
                val x = num(p["x"]) ?: return false
                val y = num(p["y"]) ?: return false
                val duration = (p["duration"] as? Number)?.toInt() ?: 800
                ToolSupport.injectLongPress(x, y, duration)
            }
            StepType.SWIPE -> {
                val x1 = num(p["x1"]) ?: return false
                val y1 = num(p["y1"]) ?: return false
                val x2 = num(p["x2"]) ?: return false
                val y2 = num(p["y2"]) ?: return false
                val duration = (p["duration"] as? Number)?.toInt() ?: 300
                ToolSupport.injectSwipe(x1, y1, x2, y2, duration)
            }
            StepType.MULTI_TOUCH -> {
                val points = p["points"] as? List<*> ?: return false
                val jsonList = points.map { pt ->
                    when (pt) {
                        is Map<*, *> -> {
                            val obj = JSONObject()
                            pt.forEach { (k, v) -> obj.put(k.toString(), v) }
                            obj.toString()
                        }
                        is String -> pt
                        else -> pt.toString()
                    }
                }
                ToolSupport.injectMultiTouch(jsonList)
            }
            StepType.WAIT -> {
                val ms = (p["ms"] as? Number)?.toLong() ?: 500L
                delay(ms)
                true
            }
            StepType.SCREENSHOT, StepType.CONDITION -> {
                // 条件分支/截图判断在后续批次实现，本版本跳过
                Log.w(TAG, "${step.type} 暂未实现，跳过")
                true
            }
        }
    }

    private fun num(v: Any?): Float? = (v as? Number)?.toFloat()

    /**
     * 按包名启动应用。
     */
    private fun openApp(pkg: String) {
        val ctx = ShizukuManager.appContext() ?: return
        try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: run {
                Log.w(TAG, "无法获取 $pkg 的启动 Intent")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            Log.i(TAG, "启动目标应用 $pkg")
        } catch (e: Throwable) {
            Log.e(TAG, "openApp $pkg failed", e)
        }
    }
}
