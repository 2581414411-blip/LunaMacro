package com.nexus.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nexus.agent.agent.AgentRunner

/**
 * 实时「思考过程」悬浮窗（增强版）。
 *
 * 增强点（相对旧版）：
 * - 圆角背景 + 阴影
 * - 多行文本，内容超出时自动滚动到底部
 * - 「停止」按钮，点击后取消当前任务（AgentRunner.stop）并隐藏
 * - 显示时从顶部滑入，隐藏时向上滑出
 *
 * 稳定性保证（保留）：
 * - 所有 WindowManager 操作强制走主线程，串行执行
 * - addView/updateViewLayout/removeView 失败时清理状态并记录
 */
object ThinkingOverlay {

    private const val TAG = "ThinkingOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var windowManager: WindowManager? = null

    @Volatile
    private var rootView: View? = null

    @Volatile
    private var textView: TextView? = null

    @Volatile
    private var scrollView: ScrollView? = null

    @Volatile
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 是否正在显示 */
    val isShowing: Boolean get() = rootView != null

    /** 「停止」按钮点击时的回调（由 MainActivity 设置，取消正在运行的 collect 协程） */
    @Volatile
    var onStopRequested: (() -> Unit)? = null

    /** 自动隐藏定时任务（showTemporary 用） */
    private var autoHideRunnable: Runnable? = null

    /**
     * 检查悬浮窗权限是否已授予。
     */
    fun hasPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 显示悬浮窗（若已显示则只更新文本）。
     * 线程安全：内部切主线程执行，带滑入动画。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, initialText: String = "") {
        val appContext = context.applicationContext

        if (!Settings.canDrawOverlays(appContext)) {
            Log.e(TAG, "show() 失败：未授予悬浮窗权限（SYSTEM_ALERT_WINDOW）")
            return
        }

        mainHandler.post {
            // show 是「持续显示」语义，取消任何待执行的自动隐藏
            autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            autoHideRunnable = null

            val existing = rootView
            if (existing != null) {
                textView?.text = initialText
                return@post
            }

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 圆角背景容器
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(appContext, 14), dp(appContext, 8), dp(appContext, 14), dp(appContext, 8))
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(230, 20, 20, 24))
                    cornerRadius = dp(appContext, 14).toFloat()
                }
                elevation = dp(appContext, 8).toFloat()
            }

            // 顶部：标题 + 停止按钮
            val topRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(appContext).apply {
                text = appContext.getString(com.nexus.agent.R.string.overlay_title)
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val stopBtn = TextView(appContext).apply {
                text = appContext.getString(com.nexus.agent.R.string.overlay_stop)
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                setPadding(dp(appContext, 12), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setOnClickListener {
                    Log.i(TAG, "用户点击停止，取消当前任务")
                    onStopRequested?.invoke()
                    AgentRunner.stop()
                    hide()
                }
            }
            topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(stopBtn)

            // 正文：ScrollView 包 TextView，多行超出可滚动
            val body = TextView(appContext).apply {
                text = initialText
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, dp(appContext, 4), 0, 0)
            }
            val scroll = ScrollView(appContext).apply {
                addView(body)
                isVerticalScrollBarEnabled = true
            }

            container.addView(topRow)
            container.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(appContext, 160)
            ))

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(appContext, 16)
                y = dp(appContext, 120)
            }

            // 拖动逻辑
            var startX = 0
            var startY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            var dragging = false
            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        dragging = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragging = true
                        if (dragging) {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            try {
                                wm.updateViewLayout(container, lp)
                            } catch (e: Throwable) {
                                Log.e(TAG, "updateViewLayout failed", e)
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            try {
                wm.addView(container, lp)
                windowManager = wm
                rootView = container
                textView = body
                scrollView = scroll
                layoutParams = lp
                Log.i(TAG, "overlay shown")

                // 滑入动画：从顶部滑入
                container.translationY = -dp(appContext, 200).toFloat()
                container.animate()
                    .translationY(0f)
                    .setDuration(250)
                    .start()
            } catch (e: Throwable) {
                windowManager = null
                rootView = null
                textView = null
                scrollView = null
                layoutParams = null
                Log.e(TAG, "addView failed (权限? 重复添加? BadToken?)", e)
            }
        }
    }

    /**
     * 临时显示悬浮窗，[durationMs] 毫秒后自动隐藏。
     *
     * 用于语音助手等「短暂状态提示」场景——避免像 show() 那样常驻屏幕、
     * 导致「任务完成后浮窗停不下来」的问题。
     *
     * 连续调用会重置自动隐藏定时器（状态连续刷新时保持可见，最后一次后自动收起）。
     */
    fun showTemporary(context: Context, text: String, durationMs: Long = 4000) {
        show(context, text)
        mainHandler.post {
            autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable { hide() }
            autoHideRunnable = r
            mainHandler.postDelayed(r, durationMs)
        }
    }

    /**
     * 更新悬浮窗文本（线程安全），并自动滚动到底部。
     */
    fun update(text: String) {
        mainHandler.post {
            textView?.text = text
            scrollToBottom()
        }
    }

    /**
     * 追加一行（保留最近若干行，线程安全），并自动滚动到底部。
     */
    fun append(text: String) {
        mainHandler.post {
            val tv = textView ?: return@post
            val existing = tv.text?.toString().orEmpty()
            val lines = existing.split("\n").filter { it.isNotBlank() }.takeLast(5)
            tv.text = (lines + text).joinToString("\n")
            scrollToBottom()
        }
    }

    /**
     * 滚动到正文底部。
     */
    private fun scrollToBottom() {
        scrollView?.post {
            scrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * 隐藏并销毁悬浮窗（线程安全）。
     *
     * 直接同步 removeView（不做滑出动画），避免「动画延迟 removeView 期间又 show」
     * 造成的 view 叠加/闪烁竞态，保证 hide→show 快速切换时稳定。
     */
    fun hide() {
        mainHandler.post {
            autoHideRunnable?.let { mainHandler.removeCallbacks(it) }
            autoHideRunnable = null

            val wm = windowManager
            val root = rootView
            if (wm != null && root != null) {
                try {
                    wm.removeView(root)
                } catch (e: Throwable) {
                    Log.e(TAG, "removeView failed", e)
                }
            }
            // 无论 removeView 是否成功，都清空状态，防止残留引用
            windowManager = null
            rootView = null
            textView = null
            scrollView = null
            layoutParams = null
        }
    }

    private fun dp(context: Context, v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
