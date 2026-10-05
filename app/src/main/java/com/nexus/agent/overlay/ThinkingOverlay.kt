package com.nexus.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
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
import android.widget.FrameLayout
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

    // ---- 录制模式状态 ----
    /** 录制模式「停止」按钮回调 */
    @Volatile
    var onRecordStop: (() -> Unit)? = null

    /** 录制模式「取消」按钮回调 */
    @Volatile
    var onRecordCancel: (() -> Unit)? = null

    /** 录制模式「手动记录坐标」按钮回调 */
    @Volatile
    var onManualCapture: (() -> Unit)? = null

    /** 录制模式步骤数文本 */
    @Volatile
    private var recordCountText: TextView? = null

    /** 录制模式红点文本 */
    @Volatile
    private var recordDotText: TextView? = null

    /** 红点闪烁定时任务 */
    private var blinkRunnable: Runnable? = null

    /** 采集模式停止回调 */
    @Volatile
    var onCollectStop: (() -> Unit)? = null

    /** 采集模式计数文本 */
    @Volatile
    private var collectCountText: TextView? = null

    /** 十字准星确认回调（返回屏幕坐标） */
    @Volatile
    var onCrosshairConfirm: ((x: Int, y: Int) -> Unit)? = null

    /** 十字准星取消回调 */
    @Volatile
    var onCrosshairCancel: (() -> Unit)? = null

    /** 十字准星全屏 root */
    @Volatile
    private var crosshairRoot: View? = null

    /** 十字准星 WindowManager */
    @Volatile
    private var crosshairWm: WindowManager? = null

    /** 十字准星视图 */
    @Volatile
    private var crosshairView: CrosshairView? = null

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
     * 显示录制模式悬浮窗（红点闪烁 + 步骤数 + 停止/取消/手动记录坐标）。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showRecording(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) {
            Log.e(TAG, "showRecording() 失败：未授予悬浮窗权限（SYSTEM_ALERT_WINDOW）")
            return
        }

        mainHandler.post {
            // 若已显示普通模式，先移除，避免叠加
            if (rootView != null && recordDotText == null) {
                windowManager?.let { wm ->
                    rootView?.let { v -> try { wm.removeView(v) } catch (_: Throwable) {} }
                }
                windowManager = null; rootView = null; textView = null
                scrollView = null; layoutParams = null
            }
            // 已在录制模式则不重复创建
            if (recordDotText != null) return@post

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(appContext, 14), dp(appContext, 10), dp(appContext, 14), dp(appContext, 10))
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(235, 20, 20, 24))
                    cornerRadius = dp(appContext, 14).toFloat()
                }
            }

            // 标题行：红点 + 录制中 + 步骤数
            val topRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dot = TextView(appContext).apply {
                text = "●"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#FF5252"))
            }
            val title = TextView(appContext).apply {
                text = " 录制中"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val count = TextView(appContext).apply {
                text = "0 步"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                setPadding(dp(appContext, 12), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            topRow.addView(dot)
            topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(count)

            // 按钮行
            val btnRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(appContext, 6), 0, 0)
            }
            val manualBtn = TextView(appContext).apply {
                text = "标记坐标"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                setPadding(0, 0, dp(appContext, 14), 0)
                setOnClickListener { showCrosshair(appContext) }
            }
            val stopBtn = TextView(appContext).apply {
                text = "停止"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#69F0AE"))
                setPadding(0, 0, dp(appContext, 14), 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setOnClickListener { onRecordStop?.invoke() }
            }
            val cancelBtn = TextView(appContext).apply {
                text = "取消"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                setOnClickListener { onRecordCancel?.invoke() }
            }
            btnRow.addView(manualBtn)
            btnRow.addView(stopBtn)
            btnRow.addView(cancelBtn)

            container.addView(topRow)
            container.addView(btnRow)

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
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(appContext, 60)
            }

            // 拖动逻辑（与 show() 一致）
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
                recordDotText = dot
                recordCountText = count
                startBlink()
                Log.i(TAG, "录制模式悬浮窗已显示")
            } catch (e: Throwable) {
                windowManager = null
                rootView = null
                recordDotText = null
                recordCountText = null
                Log.e(TAG, "录制模式 addView failed", e)
            }
        }
    }

    /**
     * 更新录制模式步骤数。
     */
    fun updateRecordingCount(count: Int) {
        mainHandler.post { recordCountText?.text = "$count 步" }
    }

    /**
     * 启动红点闪烁（每 500ms 切换透明度）。
     */
    private fun startBlink() {
        blinkRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                recordDotText?.alpha = if ((recordDotText?.alpha ?: 1f) > 0.5f) 0.15f else 1f
                blinkRunnable = this
                mainHandler.postDelayed(this, 500)
            }
        }
        blinkRunnable = r
        mainHandler.postDelayed(r, 500)
    }

    /**
     * 显示采集模式悬浮窗（● 采集中 + N/1000 + 停止按钮）。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showCollecting(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return

        mainHandler.post {
            if (collectCountText != null) return@post
            hide()

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(appContext, 14), dp(appContext, 10), dp(appContext, 14), dp(appContext, 10))
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.argb(235, 20, 20, 24))
                    cornerRadius = dp(appContext, 14).toFloat()
                }
            }

            val topRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dot = TextView(appContext).apply {
                text = "●"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
            }
            val title = TextView(appContext).apply {
                text = " 采集中"
                textSize = 13f
                setTextColor(android.graphics.Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val count = TextView(appContext).apply {
                text = "0/1000"
                textSize = 13f
                setTextColor(android.graphics.Color.parseColor("#8AB4F8"))
                setPadding(dp(appContext, 12), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val stopBtn = TextView(appContext).apply {
                text = "停止"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#FF8A80"))
                setPadding(dp(appContext, 14), 0, 0, 0)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setOnClickListener {
                    onCollectStop?.invoke()
                    hide()
                }
            }
            topRow.addView(dot)
            topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(count)
            topRow.addView(stopBtn)
            container.addView(topRow)

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
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(appContext, 60)
            }

            var startX = 0
            var startY = 0
            var touchStartX = 0f
            var touchStartY = 0f
            var dragging = false
            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x; startY = lp.y; touchStartX = event.rawX; touchStartY = event.rawY; dragging = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragging = true
                        if (dragging) {
                            lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt()
                            try { wm.updateViewLayout(container, lp) } catch (_: Throwable) {}
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
                collectCountText = count
                Log.i(TAG, "采集模式悬浮窗已显示")
            } catch (e: Throwable) {
                windowManager = null
                rootView = null
                collectCountText = null
                Log.e(TAG, "采集模式 addView failed", e)
            }
        }
    }

    /**
     * 更新采集模式计数。
     */
    fun updateCollectCount(count: Int, max: Int = 1000) {
        mainHandler.post { collectCountText?.text = "$count/$max" }
    }

    /**
     * 显示全屏十字准星（录制模式下手动标记坐标）。
     * 用户拖动准星到目标位置，点「确认坐标」记录。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showCrosshair(context: Context) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return

        mainHandler.post {
            if (crosshairRoot != null) return@post

            // 隐藏录制模式悬浮窗（确认/取消后由调用方重新 showRecording）
            hide()

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = appContext.resources.displayMetrics

            val crossView = CrosshairView(appContext).apply {
                centerX = dm.widthPixels / 2f
                centerY = dm.heightPixels / 2f
            }
            crossView.onDrag = { x, y ->
                crossView.centerX = x
                crossView.centerY = y
                crossView.invalidate()
            }

            val root = FrameLayout(appContext).apply {
                setBackgroundColor(android.graphics.Color.argb(30, 0, 0, 0))
            }

            val btnRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(appContext, 40))
            }
            val confirmBtn = TextView(appContext).apply {
                text = "确认坐标"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(appContext, 18), dp(appContext, 10), dp(appContext, 18), dp(appContext, 10))
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#2E7D32"))
                    cornerRadius = dp(appContext, 20).toFloat()
                }
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setOnClickListener {
                    val x = crossView.centerX.toInt()
                    val y = crossView.centerY.toInt()
                    val cb = onCrosshairConfirm
                    hideCrosshair()
                    cb?.invoke(x, y)
                }
            }
            val cancelBtn = TextView(appContext).apply {
                text = "取消"
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(dp(appContext, 18), dp(appContext, 10), dp(appContext, 18), dp(appContext, 10))
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#616161"))
                    cornerRadius = dp(appContext, 20).toFloat()
                }
                setOnClickListener {
                    val cb = onCrosshairCancel
                    hideCrosshair()
                    cb?.invoke()
                }
            }
            btnRow.addView(confirmBtn)
            btnRow.addView(
                cancelBtn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(appContext, 16) }
            )

            root.addView(
                crossView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            root.addView(
                btnRow,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )

            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            try {
                wm.addView(root, lp)
                crosshairWm = wm
                crosshairRoot = root
                crosshairView = crossView
                Log.i(TAG, "十字准星已显示")
            } catch (e: Throwable) {
                crosshairWm = null
                crosshairRoot = null
                crosshairView = null
                Log.e(TAG, "十字准星 addView failed", e)
            }
        }
    }

    /**
     * 隐藏十字准星。
     */
    fun hideCrosshair() {
        mainHandler.post {
            val wm = crosshairWm
            val root = crosshairRoot
            if (wm != null && root != null) {
                try {
                    wm.removeView(root)
                } catch (e: Throwable) {
                    Log.e(TAG, "hideCrosshair removeView failed", e)
                }
            }
            crosshairWm = null
            crosshairRoot = null
            crosshairView = null
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
            blinkRunnable?.let { mainHandler.removeCallbacks(it) }
            blinkRunnable = null

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
            recordDotText = null
            recordCountText = null
            collectCountText = null
        }
    }

    private fun dp(context: Context, v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }

    /**
     * 十字准星绘制视图（全屏，画十字线 + 中心点，可拖动）。
     */
    private class CrosshairView(context: Context) : View(context) {
        var centerX = 0f
        var centerY = 0f
        var onDrag: ((Float, Float) -> Unit)? = null

        private val linePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF5252")
            strokeWidth = 3f
        }
        private val centerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#FF5252")
            style = android.graphics.Paint.Style.FILL
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    onDrag?.invoke(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP -> return true
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(centerX, 0f, centerX, h, linePaint)
            canvas.drawLine(0f, centerY, w, centerY, linePaint)
            canvas.drawCircle(centerX, centerY, 8f, centerPaint)
        }
    }
}
