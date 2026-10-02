package com.nexus.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/**
 * 实时「思考过程」悬浮窗（线程安全重写版）。
 *
 * 用 System Alert Window（需 SYSTEM_ALERT_WINDOW 权限）在其它应用上层
 * 显示一个小面板，实时刷新 Agent 每一步的思考、动作与执行结果。
 *
 * ## 稳定性保证（针对之前"浮窗不稳定"的修复）
 * 1. 所有 WindowManager 操作（addView/updateViewLayout/removeView）**强制走主线程**，
 *    无论调用方从哪个线程调用 show/update/hide，内部都 post 到 mainHandler 串行执行，
 *    避免「ViewRootImpl called from wrong thread」和 add/remove 竞态。
 * 2. addView 失败（权限被撤 / 重复添加 / BadToken）时返回明确错误日志并清理状态，
 *    不再静默吞掉。
 * 3. 用 @Volatile 标记状态，配合主线程串行队列，保证 show/hide 不重叠。
 *
 * 特点：
 * - 可拖动
 * - 半透明深色背景，不遮挡太多内容
 * - 单例，通过 [show]/[hide]/[update]/[append] 控制
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
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 是否正在显示 */
    val isShowing: Boolean get() = rootView != null

    /**
     * 检查悬浮窗权限是否已授予。
     */
    fun hasPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 显示悬浮窗（若已显示则只更新文本）。
     *
     * 线程安全：内部切主线程执行。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, initialText: String = "") {
        val appContext = context.applicationContext

        // 权限检查：无权限直接返回，避免 addView 抛异常
        if (!Settings.canDrawOverlays(appContext)) {
            Log.e(TAG, "show() 失败：未授予悬浮窗权限（SYSTEM_ALERT_WINDOW）")
            return
        }

        mainHandler.post {
            // 已在显示：只更新文本
            val existing = rootView
            if (existing != null) {
                textView?.text = initialText
                return@post
            }

            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            // 半透明圆角背景容器
            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(appContext, 12), dp(appContext, 8), dp(appContext, 12), dp(appContext, 8))
                setBackgroundColor(Color.argb(200, 20, 20, 24))
            }

            val title = TextView(appContext).apply {
                text = "Nexus 思考中…"
                textSize = 12f
                setTextColor(Color.parseColor("#8AB4F8"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val body = TextView(appContext).apply {
                text = initialText
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 6
                setPadding(0, dp(appContext, 4), 0, 0)
            }

            container.addView(title)
            container.addView(body)

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
                layoutParams = lp
                Log.i(TAG, "overlay shown")
            } catch (e: Throwable) {
                // addView 失败：可能是权限被撤、或重复添加。清理状态并记录。
                windowManager = null
                rootView = null
                textView = null
                layoutParams = null
                Log.e(TAG, "addView failed (权限? 重复添加? BadToken?)", e)
            }
        }
    }

    /**
     * 更新悬浮窗文本（线程安全）。
     */
    fun update(text: String) {
        mainHandler.post {
            textView?.text = text
        }
    }

    /**
     * 追加一行（保留最近若干行，线程安全）。
     */
    fun append(text: String) {
        mainHandler.post {
            val tv = textView ?: return@post
            val existing = tv.text?.toString().orEmpty()
            val lines = existing.split("\n").filter { it.isNotBlank() }.takeLast(5)
            tv.text = (lines + text).joinToString("\n")
        }
    }

    /**
     * 隐藏并销毁悬浮窗（线程安全）。
     */
    fun hide() {
        mainHandler.post {
            try {
                val wm = windowManager
                val root = rootView
                if (wm != null && root != null) {
                    wm.removeView(root)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "removeView failed", e)
            }
            // 无论 removeView 是否成功，都清空状态，防止残留引用操作已销毁的 view
            windowManager = null
            rootView = null
            textView = null
            layoutParams = null
        }
    }

    private fun dp(context: Context, v: Int): Int {
        val density = context.resources.displayMetrics.density
        return (v * density).toInt()
    }
}
