package com.nexus.agent.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * 屏幕四周一圈的彩色「运行灯」光带。
 *
 * 用途：作为 Agent 是否正在工作的全局视觉指示——任务执行、语音录音/识别/播报期间，
 * 屏幕边缘会亮起一圈彩虹跑马灯光带；空闲时熄灭。
 *
 * 关键设计：
 * - 引用计数（acquire/release）：语音链路与任务执行可能嵌套（唤醒→录音→识别→执行→播报），
 *   用引用计数保证「还有人在干活就不灭灯」。
 * - FLAG_NOT_TOUCHABLE：纯视觉，不拦截任何触摸，不影响用户操作。
 * - 所有 WindowManager 操作强制主线程串行，避免线程/竞态问题。
 */
object RunningIndicator {

    private const val TAG = "RunningIndicator"

    /** 光带宽度（dp） */
    private const val BAND_DP = 7

    /** 一个完整彩虹周期的长度（px），决定跑马灯颜色密度 */
    private const val RAINBOW_PERIOD_PX = 220f

    /** 跑完一个彩虹周期的时间（ms） */
    private const val CYCLE_MS = 1600L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var view: IndicatorView? = null

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var animator: ValueAnimator? = null

    /** 引用计数 */
    private var refCount = 0

    val isShowing: Boolean get() = view != null

    /**
     * 申请点亮光带（引用计数 +1，首次从 0 变 1 时真正显示）。
     */
    fun acquire(context: Context) {
        synchronized(this) {
            refCount++
        }
        show(context)
    }

    /**
     * 释放光带（引用计数 -1，归零时隐藏）。
     */
    fun release() {
        val shouldHide: Boolean = synchronized(this) {
            refCount--
            if (refCount <= 0) {
                refCount = 0
                true
            } else {
                false
            }
        }
        if (shouldHide) hide()
    }

    private fun show(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) {
            return
        }
        mainHandler.post {
            if (view != null) return@post // 已显示，幂等

            val v = IndicatorView(appContext, dp(appContext, BAND_DP).toFloat())
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                wm.addView(v, lp)
                windowManager = wm
                view = v
                startAnimator(v)
                Log.i(TAG, "运行灯已点亮")
            } catch (e: Throwable) {
                windowManager = null
                view = null
                Log.e(TAG, "运行灯 addView 失败", e)
            }
        }
    }

    private fun hide() {
        mainHandler.post {
            stopAnimator()
            val wm = windowManager
            val v = view
            if (wm != null && v != null) {
                try {
                    wm.removeView(v)
                } catch (e: Throwable) {
                    Log.e(TAG, "运行灯 removeView 失败", e)
                }
            }
            windowManager = null
            view = null
        }
    }

    private fun startAnimator(v: IndicatorView) {
        stopAnimator()
        val anim = ValueAnimator.ofFloat(0f, RAINBOW_PERIOD_PX)
        anim.duration = CYCLE_MS
        anim.repeatCount = ValueAnimator.INFINITE
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            v.phase = a.animatedValue as Float
            v.invalidate()
        }
        anim.start()
        animator = anim
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
    }

    private fun dp(context: Context, v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }

    /**
     * 全屏透明 View，只在屏幕四周一圈绘制彩虹跑马灯光带。
     */
    private class IndicatorView(context: Context, private val band: Float) : View(context) {

        /** 当前相位（0 ~ RAINBOW_PERIOD_PX），由动画驱动 */
        @Volatile
        var phase: Float = 0f

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            // 上边（左→右）
            drawEdge(canvas, 0f, 0f, w, band, phase, horizontal = true, reverse = false)
            // 右边（上→下）
            drawEdge(canvas, w - band, 0f, band, h, phase, horizontal = false, reverse = true)
            // 下边（右→左）
            drawEdge(canvas, 0f, h - band, w, band, phase, horizontal = true, reverse = true)
            // 左边（下→上）
            drawEdge(canvas, 0f, 0f, band, h, phase, horizontal = false, reverse = false)
        }

        private fun drawEdge(
            canvas: Canvas,
            x: Float,
            y: Float,
            w: Float,
            h: Float,
            phase: Float,
            horizontal: Boolean,
            reverse: Boolean
        ) {
            val colors = rainbowColors()
            val shader = LinearGradient(
                0f, 0f,
                if (horizontal) RAINBOW_PERIOD_PX else 0f,
                if (horizontal) 0f else RAINBOW_PERIOD_PX,
                colors, null, Shader.TileMode.REPEAT
            )
            paint.shader = shader

            canvas.save()
            val shift = if (reverse) phase else -phase
            if (horizontal) {
                canvas.translate(shift, 0f)
            } else {
                canvas.translate(0f, shift)
            }
            canvas.drawRect(x, y, x + w, y + h, paint)
            canvas.restore()
        }

        private fun rainbowColors(): IntArray {
            // 红→橙→黄→绿→青→蓝→紫→红，首尾同色保证 REPEAT 无缝循环
            val hues = intArrayOf(0, 40, 80, 130, 180, 230, 280, 330, 360)
            return hues.map { h ->
                Color.HSVToColor(255, floatArrayOf(h.toFloat(), 1f, 1f))
            }.toIntArray()
        }
    }
}
