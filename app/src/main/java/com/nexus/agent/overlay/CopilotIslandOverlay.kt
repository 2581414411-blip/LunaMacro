package com.nexus.agent.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.agent.agent.LlmClient
import com.nexus.agent.agent.LlmClientFactory
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 灵动岛 AI 副驾驶悬浮窗。
 *
 * 形态：屏幕顶部居中的黑色胶囊（iPhone 灵动岛风格），整体位于状态栏下方，
 * 不会遮住状态栏图标与前置摄像头挖孔。
 *
 * 三态：
 *  - **待机（DOT）**：一颗 12dp 圆点，呼吸灯闪烁。
 *  - **吐槽（CAPSULE）**：平滑展开成胶囊，文字逐字输出，停留 3 秒。
 *  - **输入**：点击岛本身 → 展开输入框，可与 AI 打字互动（此时临时切为可聚焦以弹出键盘）。
 *
 * 窗口：
 *  - `TYPE_APPLICATION_OVERLAY`（API 26+）/ `TYPE_PHONE`（兼容低版本）
 *  - 默认 `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL`：玩游戏时点岛以外的地方会穿透到游戏，
 *    不会误触；只有点在岛本身才响应。
 *
 * 实现沿用 [ThinkingOverlay] 的原生 View 方案（不用 ComposeView），
 * 避免悬浮窗缺少 ViewTreeLifecycleOwner 导致的崩溃。
 */
object CopilotIslandOverlay {

    private const val TAG = "CopilotIsland"

    private const val DOT_SIZE_DP = 12
    private const val CAPSULE_HEIGHT_DP = 40
    private const val INPUT_HEIGHT_DP = 48
    private const val MAX_WIDTH_DP = 300
    private const val INPUT_WIDTH_DP = 290

    /** 逐字输出速度（毫秒/字） */
    private const val CHAR_DELAY_MS = 38L

    /** 逐字结束后停留时长 */
    private const val HOLD_MS = 3000L

    private val main = Handler(Looper.getMainLooper())

    /** 聊天用协程作用域（与窗口生命周期无关，避免窗口重建时丢回复） */
    private val chatScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var wm: WindowManager? = null
    private var container: LinearLayout? = null
    private var textView: TextView? = null
    private var inputRow: LinearLayout? = null
    private var editText: EditText? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bg: GradientDrawable? = null

    private var breathing: ObjectAnimator? = null
    private var widthAnimator: ValueAnimator? = null
    private var typingRunnable: Runnable? = null
    private var collapseRunnable: Runnable? = null

    private var expanded = false
    private var inputMode = false

    /** 用户在岛上发消息时回调（由 CopilotEngine 接管道） */
    var onUserMessage: ((String) -> Unit)? = null

    /**
     * 点岛时如果副驾驶正在干活，先问它一句「要不要中断」。
     *
     * 返回 true 表示确实中断了（这次点击就被消费掉，不再展开输入框）。
     */
    var onInterrupt: (() -> Boolean)? = null

    /** 副驾驶是否忙碌（忙碌时点岛 = 中断，空闲时点岛 = 提问） */
    var isBusyProvider: (() -> Boolean)? = null

    /** 岛状态变化（供调试/UI 回显） */
    var onStateChanged: ((state: String) -> Unit)? = null

    fun isShowing(): Boolean = container != null

    fun isInputMode(): Boolean = inputMode

    /**
     * 显示灵动岛（幂等）。无悬浮窗权限时返回 false 并记录日志，不抛异常。
     */
    fun show(context: Context): Boolean {
        if (container != null) return true
        val ctx = context.applicationContext
        appContext = ctx

        if (!Settings.canDrawOverlays(ctx)) {
            Log.w(TAG, "缺少悬浮窗权限，灵动岛不显示")
            return false
        }

        return try {
            val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = windowManager

            val params = WindowManager.LayoutParams(
                dp(DOT_SIZE_DP),
                dp(DOT_SIZE_DP),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarHeight(ctx) + dp(6)
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            }
            lp = params

            val shape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#0B0B0B"))
                cornerRadius = dp(DOT_SIZE_DP) / 2f
            }
            bg = shape

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape
                isClickable = true
                setOnClickListener { onIslandClick() }
                setOnLongClickListener { onIslandLongClick() }
            }

            val tv = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(MAX_WIDTH_DP - 28)
                visibility = View.GONE
                setPadding(0, 0, 0, 0)
            }
            root.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            textView = tv

            windowManager.addView(root, params)
            container = root
            startBreathing()
            Log.i(TAG, "灵动岛已显示")
            onStateChanged?.invoke("dot")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "addView 失败", e)
            safeRemove()
            false
        }
    }

    /**
     * 隐藏并释放资源。
     */
    fun hide() {
        main.post {
            cancelTyping()
            stopBreathing()
            widthAnimator?.cancel()
            widthAnimator = null
            safeRemove()
            onStateChanged?.invoke("hidden")
            Log.i(TAG, "灵动岛已隐藏")
        }
    }

    /**
     * 让灵动岛说一句话：展开 → 逐字输出 → 停留 3 秒 → 收缩回圆点。
     *
     * @param text 要显示的内容（建议 ≤ 20 字，超长会截断为两行）
     */
    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        main.post {
            val ctx = appContext ?: ShizukuManager.appContext()
            if (ctx == null) return@post
            if (container == null) {
                if (!show(ctx)) return@post
            }
            if (inputMode) {
                Log.d(TAG, "输入中，暂不展开吐槽")
                return@post
            }
            expandAndType(trimmed)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private fun onIslandClick() {
        if (inputMode) {
            submitInput()
            return
        }
        // 干活中：点一下就是「中断」；想提问请长按
        if (isBusyProvider?.invoke() == true) {
            if (onInterrupt?.invoke() == true) {
                Log.i(TAG, "用户点了灵动岛 → 中断当前任务")
            }
            return
        }
        enterInputMode()
    }

    private fun onIslandLongClick(): Boolean {
        if (inputMode) return false
        // 长按永远可以调出输入框（干活中也可以提问，引擎会回「我还在忙」）
        enterInputMode()
        return true
    }

    private fun expandAndType(text: String) {
        val root = container ?: return
        val tv = textView ?: return
        val params = lp ?: return
        val manager = wm ?: return

        cancelTyping()
        stopBreathing()
        expanded = true
        onStateChanged?.invoke("capsule")

        // 先按最终文案定宽（避免逐字时宽度跳动），再动画从圆点展开
        tv.visibility = View.VISIBLE
        tv.text = text
        root.setPadding(dp(14), dp(8), dp(14), dp(8))
        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(MAX_WIDTH_DP), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetW = root.measuredWidth.coerceAtLeast(dp(72))
        val targetH = dp(CAPSULE_HEIGHT_DP)
        tv.text = ""

        animateSize(params.width, params.height, targetW, targetH, manager, root)

        // 尺寸到位后开始逐字
        main.postDelayed({
            var i = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (container == null || inputMode) return
                    if (i <= text.length) {
                        tv.text = text.substring(0, i)
                        i += 1
                        main.postDelayed(this, CHAR_DELAY_MS)
                    } else {
                        scheduleCollapse()
                    }
                }
            }
            typingRunnable = runnable
            main.post(runnable)
        }, 200L)
    }

    private fun animateSize(
        fromW: Int,
        fromH: Int,
        toW: Int,
        toH: Int,
        manager: WindowManager,
        view: View
    ) {
        widthAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val w = (fromW + (toW - fromW) * t).toInt()
                val h = (fromH + (toH - fromH) * t).toInt()
                lp?.let { p ->
                    p.width = w
                    p.height = h
                    try {
                        manager.updateViewLayout(view, p)
                    } catch (e: Throwable) {
                        Log.w(TAG, "updateViewLayout 失败：${e.message}")
                    }
                    setCornerRadius(h)
                }
            }
        }
        widthAnimator = animator
        animator.start()
    }

    private fun scheduleCollapse() {
        collapseRunnable?.let { main.removeCallbacks(it) }
        val runnable = Runnable { collapse() }
        collapseRunnable = runnable
        main.postDelayed(runnable, HOLD_MS)
    }

    private fun collapse() {
        if (inputMode) return
        val root = container ?: return
        val params = lp ?: return
        val manager = wm ?: return
        val tv = textView ?: return

        cancelTyping()
        expanded = false
        val fromW = params.width
        val fromH = params.height
        animateSize(fromW, fromH, dp(DOT_SIZE_DP), dp(DOT_SIZE_DP), manager, root)
        // 文字等收缩动画结束再隐藏，避免收缩途中文字突然消失
        main.postDelayed({
            if (!expanded && !inputMode) {
                tv.text = ""
                tv.visibility = View.GONE
                root.setPadding(0, 0, 0, 0)
                startBreathing()
            }
            onStateChanged?.invoke("dot")
        }, 240L)
    }

    private fun enterInputMode() {
        val root = container ?: return
        val params = lp ?: return
        val manager = wm ?: return
        val ctx = appContext ?: return
        val tv = textView ?: return
        val shape = bg ?: return

        cancelTyping()
        collapseRunnable?.let { main.removeCallbacks(it) }
        stopBreathing()
        inputMode = true
        expanded = true
        onStateChanged?.invoke("input")

        tv.visibility = View.GONE

        // 输入行：EditText + 关闭
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(ctx).apply {
            hint = "跟副驾驶说点什么…"
            setHintTextColor(Color.parseColor("#8A8A8A"))
            setTextColor(Color.WHITE)
            textSize = 13f
            background = null
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(0, 0, dp(8), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submitInput()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(
            et,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val close = TextView(ctx).apply {
            text = "×"
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 18f
            setPadding(dp(10), dp(2), dp(4), dp(2))
            setOnClickListener { exitInputMode() }
        }
        row.addView(close)
        editText = et
        inputRow = row

        root.removeAllViews()
        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.setPadding(dp(16), 0, dp(10), 0)
        shape.cornerRadius = dp(INPUT_HEIGHT_DP) / 2f

        params.width = dp(INPUT_WIDTH_DP)
        params.height = dp(INPUT_HEIGHT_DP)
        // 输入态必须可聚焦，否则软键盘不会弹出
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        try {
            manager.updateViewLayout(root, params)
        } catch (e: Throwable) {
            Log.w(TAG, "切输入态失败：${e.message}")
        }

        et.requestFocus()
        main.postDelayed({
            try {
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Throwable) {
                Log.w(TAG, "弹键盘失败：${e.message}")
            }
        }, 120L)
    }

    private fun submitInput() {
        val et = editText ?: return
        val text = et.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            exitInputMode()
            return
        }
        et.setText("")
        exitInputMode()
        onUserMessage?.invoke(text)
        // 先给个「思考中」的即时反馈，AI 回复到位后再覆盖
        expandAndType("…")
        if (onUserMessage == null) replyToUser(text)
    }

    /**
     * 用户打字互动：走用户自己配置的 LLM（与 Agent 规划同一套 Key）。
     *
     * 未配置 Key 或调用失败时，回一句人话而不是抛异常——悬浮窗里没有地方显示崩溃。
     */
    private fun replyToUser(userText: String) {
        val ctx = appContext ?: return
        chatScope.launch {
            val reply = try {
                val provider = LlmClientFactory.getProvider(ctx)
                val client = LlmClientFactory.create(provider, ctx)
                client.chat(
                    listOf(
                        LlmClient.Message(
                            "system",
                            "你是玩家手机上的赛博陪玩，说话像网友，短句、有梗、不客套，不超过 30 个字，禁止 AI 腔和自我介绍。"
                        ),
                        LlmClient.Message("user", userText)
                    )
                ).trim()
            } catch (e: Throwable) {
                Log.w(TAG, "AI 回复失败：${e.javaClass.simpleName} ${e.message}")
                ""
            }
            val out = when {
                reply.isNotBlank() -> reply.take(80)
                else -> "先到「设置」里配个 LLM Key，我才能接话"
            }
            speak(out)
        }
    }

    private fun exitInputMode() {
        if (!inputMode) return
        val root = container ?: return
        val params = lp ?: return
        val manager = wm ?: return
        val shape = bg ?: return
        val ctx = appContext ?: return

        inputMode = false
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(root.windowToken, 0)
        } catch (_: Throwable) {
        }

        root.removeAllViews()
        textView?.let { tv ->
            tv.text = ""
            tv.visibility = View.GONE
            root.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        editText = null
        inputRow = null
        root.setPadding(0, 0, 0, 0)

        params.width = dp(DOT_SIZE_DP)
        params.height = dp(DOT_SIZE_DP)
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        shape.cornerRadius = dp(DOT_SIZE_DP) / 2f
        try {
            manager.updateViewLayout(root, params)
        } catch (e: Throwable) {
            Log.w(TAG, "退出输入态失败：${e.message}")
        }
        expanded = false
        startBreathing()
        onStateChanged?.invoke("dot")
    }

    private fun startBreathing() {
        val root = container ?: return
        stopBreathing()
        root.alpha = 1f
        val animator = ObjectAnimator.ofFloat(root, View.ALPHA, 1f, 0.35f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        breathing = animator
        animator.start()
    }

    private fun stopBreathing() {
        breathing?.cancel()
        breathing = null
        container?.alpha = 1f
    }

    private fun cancelTyping() {
        typingRunnable?.let { main.removeCallbacks(it) }
        typingRunnable = null
        collapseRunnable?.let { main.removeCallbacks(it) }
        collapseRunnable = null
    }

    private fun setCornerRadius(heightPx: Int) {
        bg?.cornerRadius = heightPx / 2f
    }

    /** 先捕获当前 rootView，确认仍是同一个实例才 remove，避免误删新建的窗口 */
    private fun safeRemove() {
        val view = container
        container = null
        textView = null
        editText = null
        inputRow = null
        expanded = false
        inputMode = false
        if (view != null) {
            try {
                wm?.removeView(view)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView 失败：${e.message}")
            }
        }
        wm = null
        lp = null
        bg = null
    }

    private fun statusBarHeight(context: Context): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallback = dp(context, 24)
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else fallback
    }

    private fun dp(value: Int): Int {
        val ctx = appContext ?: return value
        return dp(ctx, value)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
