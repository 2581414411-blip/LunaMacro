package com.nexus.agent.macro

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Macro 录制器。
 *
 * 录制用户的操作序列，生成 Macro 对象并保存。
 *
 * 两条数据来源：
 * 1. 无障碍事件（TYPE_VIEW_CLICKED / LONG_CLICKED / TEXT_CHANGED）——
 *    对暴露节点的普通 App 有效，记录节点中心坐标。
 * 2. 手动记录坐标（[addManualTap]）——游戏/Canvas 界面不暴露节点时，
 *    由悬浮窗引导用户手动指定坐标。
 *
 * 滑动录制（onGesture）依赖 touch exploration 模式，会干扰正常操作且
 * 游戏场景拿不到坐标，故本版本不实现，由「手动添加步骤」（管理界面）补齐。
 */
object MacroRecorder {

    private const val TAG = "MacroRecorder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recording = false

    private var name = ""
    private var description = ""
    private var targetPackage: String? = null

    private val steps = mutableListOf<MacroStep>()

    /** 上一个事件的时间戳（毫秒），用于推算 delayAfterMs */
    private var lastEventTime = 0L

    /** 步骤数变化回调（供悬浮窗刷新） */
    @Volatile
    var onStepCountChanged: ((Int) -> Unit)? = null

    val isRecording: Boolean get() = recording

    fun stepCount(): Int = steps.size

    /**
     * 开始录制。
     */
    fun startRecording(name: String, description: String = "", targetPackage: String? = null) {
        if (recording) {
            Log.w(TAG, "已在录制中，忽略重复 start")
            return
        }
        recording = true
        this.name = name
        this.description = description
        this.targetPackage = targetPackage
        synchronized(steps) { steps.clear() }
        lastEventTime = 0
        Log.i(TAG, "开始录制: $name")
    }

    /**
     * 停止录制，生成 Macro 并保存。
     *
     * @param onSaved 保存完成后的回调（参数为生成的 Macro）
     */
    fun stopRecording(onSaved: (Macro) -> Unit = {}) {
        if (!recording) return
        recording = false
        val macro = synchronized(steps) {
            Macro(
                name = name.ifBlank { "未命名宏" },
                description = description,
                targetPackage = targetPackage,
                steps = steps.toList()
            )
        }
        scope.launch {
            try {
                MacroStore.save(macro)
                Log.i(TAG, "录制完成并保存: ${macro.name}（${macro.steps.size} 步）")
                onSaved(macro)
            } catch (e: Throwable) {
                Log.e(TAG, "保存宏失败", e)
            }
        }
    }

    /**
     * 取消录制（不保存）。
     */
    fun cancelRecording() {
        recording = false
        synchronized(steps) { steps.clear() }
        lastEventTime = 0
        Log.i(TAG, "录制已取消")
    }

    /**
     * 无障碍事件入口，由 [com.nexus.agent.accessibility.NexusAccessibilityService] 调用。
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!recording) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val step = buildNodeStep(StepType.TAP, event) ?: return
                recordStep(step)
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                val step = buildNodeStep(StepType.LONG_PRESS, event) ?: return
                recordStep(step)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val step = buildTextStep(event) ?: return
                recordStep(step)
            }
        }
    }

    /**
     * 手动记录一次点击坐标（游戏界面兜底）。
     */
    fun addManualTap(x: Float, y: Float) {
        if (!recording) return
        recordStep(
            MacroStep(
                type = StepType.TAP,
                params = mapOf("x" to x.toDouble(), "y" to y.toDouble())
            )
        )
    }

    /**
     * 手动添加一个任意步骤（供管理界面/录制兜底使用）。
     */
    fun addManualStep(type: StepType, params: Map<String, Any>) {
        if (!recording) return
        recordStep(MacroStep(type = type, params = params))
    }

    /**
     * 记录步骤，并把与上一步的时间间隔写进上一步的 delayAfterMs。
     */
    private fun recordStep(step: MacroStep) {
        val now = System.currentTimeMillis()
        synchronized(steps) {
            if (steps.isNotEmpty() && lastEventTime > 0) {
                val delay = now - lastEventTime
                val last = steps.last()
                steps[steps.size - 1] = last.copy(delayAfterMs = delay.coerceAtMost(10_000L))
            }
            steps.add(step)
        }
        lastEventTime = now
        onStepCountChanged?.invoke(steps.size)
    }

    /**
     * 从无障碍事件节点构建 TAP/LONG_PRESS 步骤。
     */
    private fun buildNodeStep(type: StepType, event: AccessibilityEvent): MacroStep? {
        val source = event.source ?: return null
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            val params = mutableMapOf<String, Any>(
                "x" to bounds.exactCenterX().toDouble(),
                "y" to bounds.exactCenterY().toDouble()
            )
            if (type == StepType.LONG_PRESS) params["duration"] = 800
            return MacroStep(type = type, params = params)
        } catch (e: Throwable) {
            Log.e(TAG, "buildNodeStep 失败", e)
            return null
        } finally {
            source.recycle()
        }
    }

    /**
     * 从文本变化事件构建步骤（降级为点击该输入框位置，文本内容存 params 备查）。
     */
    private fun buildTextStep(event: AccessibilityEvent): MacroStep? {
        val source = event.source ?: return null
        try {
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return null
            val text = event.text?.joinToString("") ?: ""
            return MacroStep(
                type = StepType.TAP,
                params = mapOf(
                    "x" to bounds.exactCenterX().toDouble(),
                    "y" to bounds.exactCenterY().toDouble(),
                    "text" to text
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "buildTextStep 失败", e)
            return null
        } finally {
            source.recycle()
        }
    }
}
