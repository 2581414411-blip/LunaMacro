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
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.nexus.agent.input.ShizukuManager
import kotlin.math.abs
import kotlin.math.ceil

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

    /** 一屏显示的行数：多了就靠自动滚动播，不再截断 */
    private const val DISPLAY_LINES = 2

    /** 胶囊上下内边距（dp），算总高时要加回来 */
    private const val CAPSULE_PAD_V_DP = 8

    /** 行间距倍数（中文两行贴太近不好读） */
    private const val LINE_SPACING_MULT = 1.1f

    /** 每滚一行后停留时长 */
    private const val SCROLL_HOLD_MS = 1700L

    /** 用户手动滑动后，隔多久恢复自动滚动 */
    private const val TOUCH_RESUME_MS = 2500L

    /** 长按判定阈值 */
    private const val LONG_PRESS_MS = 500L

    /** 超长内容的停留上限，避免岛一直挂着不走 */
    private const val MAX_HOLD_MS = 20000L

    /** 逐字输出速度（毫秒/字） */
    private const val CHAR_DELAY_MS = 38L

    /** 逐字结束后停留时长 */
    private const val HOLD_MS = 3000L

    /** 正常胶囊底色 */
    private const val NORMAL_BG = "#0B0B0B"

    /** 错误态底色（红） */
    private const val ERROR_BG = "#B3261E"

    /** 错误提示停留时长（比普通弹幕长，留出读原因的时间） */
    private const val ERROR_HOLD_MS = 5000L

    /** 「思考中…」省略号刷新间隔 */
    private const val DOTS_INTERVAL_MS = 450L

    /** 状态胶囊的展示类型 */
    enum class StatusKind {
        /** 思考中 / 加载中：持续转圈，不自动收缩 */
        THINKING,

        /** 错误：红底白字，停留 5 秒后收缩 */
        ERROR
    }

    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var wm: WindowManager? = null
    private var container: LinearLayout? = null

    /** 文字视口：高度锁 2 行，文本照常排到 N 行，靠 scrollY 一屏一屏滚 */
    private var textView: TextView? = null

    private var inputRow: LinearLayout? = null
    private var editText: EditText? = null
    private var lp: WindowManager.LayoutParams? = null
    private var bg: GradientDrawable? = null

    private var breathing: ObjectAnimator? = null
    private var widthAnimator: ValueAnimator? = null
    private var typingRunnable: Runnable? = null
    private var collapseRunnable: Runnable? = null

    /** 自动滚动循环 */
    private var scrollRunnable: Runnable? = null

    /** 平滑滚动的动画器（手指滑动时立刻取消，别跟人抢） */
    private var scrollAnimator: ValueAnimator? = null

    /** 滚动链世代号：防止旧的延迟任务再拉起第二条滚动链（会越滚越快） */
    private var scrollGen = 0

    /** 用户手指正按在岛上（暂停自动滚动，别跟人抢） */
    private var userTouching = false

    /** 最后一次松手的时间，用来给用户留出看完一屏的时间 */
    private var lastTouchAt = 0L

    private var expanded = false
    private var inputMode = false

    /** 是否处于「思考中 / 错误」状态展示（此状态不自动收缩） */
    @Volatile
    private var statusActive = false

    /** 思考中省略号动画 */
    private var statusAnim: Runnable? = null

    /** 输入态期间被挤下来的回复，退出输入态后补播（避免用户的话掉在地上） */
    private var pendingSpeech: String? = null

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
                // 不再截断：文本照常排版到 N 行，视口只露 2 行，其余靠 scrollY 滚
                maxLines = Int.MAX_VALUE
                ellipsize = null
                maxWidth = dp(MAX_WIDTH_DP - 28)
                setPadding(0, 0, 0, 0)
                setLineSpacing(0f, LINE_SPACING_MULT)
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                visibility = View.GONE
                isClickable = true
            }
            attachScrollTouch(tv)

            // 视口固定 2 行高，多出来的靠滚
            root.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    lineHeightPx(tv) * DISPLAY_LINES
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
            stopAutoScroll()
            statusActive = false
            // 窗口都要没了，暂存的回复没地方播，直接清掉避免下次误弹旧内容
            pendingSpeech = null
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
                // 输入态弹胶囊会跟输入框打架，但回复绝不能丢：先存着，退出输入态立刻补播。
                // 历史问题：这里曾经直接 return，用户等待期间再点岛，回复就"掉在地上"了。
                pendingSpeech = trimmed
                Log.i(TAG, "输入中，回复暂存 ${trimmed.length} 字")
                return@post
            }
            // 正文到位：顶掉「思考中」状态
            statusActive = false
            stopStatusAnim()
            bg?.setColor(Color.parseColor(NORMAL_BG))
            expandAndType(trimmed)
        }
    }

    /**
     * 展示「思考中 / 错误」状态。
     *
     * 与 [speak] 的区别：THINKING **不会自动收缩**，一直转到 [hideStatus] 或
     * 有正文回复（[speak]）把它顶掉为止；ERROR 红底白字停留 [ERROR_HOLD_MS] 后自动收缩。
     *
     * 之前只有 `expandAndType("…")`：3 秒后必然缩回小圆点，
     * 模型一旦慢一点，用户看到的就是「发完没反应」。
     */
    fun showStatus(text: String, kind: StatusKind = StatusKind.THINKING) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        main.post {
            val ctx = appContext ?: ShizukuManager.appContext() ?: return@post
            if (container == null && !show(ctx)) return@post
            val root = container ?: return@post
            val tv = textView ?: return@post
            val params = lp ?: return@post
            val manager = wm ?: return@post

            cancelTyping()
            stopStatusAnim()
            stopBreathing()
            statusActive = true
            expanded = true
            onStateChanged?.invoke("status:${kind.name}")

            when (kind) {
                StatusKind.THINKING -> {
                    bg?.setColor(Color.parseColor(NORMAL_BG))
                    layoutCapsule("$trimmed.", root, tv, params, manager)
                    // 省略号原地跳动，告诉用户「还活着，在等模型」
                    statusAnim = object : Runnable {
                        private var dots = 1
                        override fun run() {
                            if (!statusActive || container == null) return
                            dots = dots % 3 + 1
                            textView?.text = text + ".".repeat(dots)
                            main.postDelayed(this, DOTS_INTERVAL_MS)
                        }
                    }.also { main.postDelayed(it, DOTS_INTERVAL_MS) }
                }

                StatusKind.ERROR -> {
                    bg?.setColor(Color.parseColor(ERROR_BG))
                    layoutCapsule(trimmed, root, tv, params, manager)
                    val r = Runnable {
                        statusActive = false
                        bg?.setColor(Color.parseColor(NORMAL_BG))
                        collapse()
                    }
                    collapseRunnable = r
                    main.postDelayed(r, ERROR_HOLD_MS)
                }
            }
            Log.i(TAG, "状态展示（${kind.name}）：$trimmed")
        }
    }

    /** 收起状态展示（思考结束、没有正文可播时用） */
    fun hideStatus() {
        main.post {
            if (!statusActive) return@post
            statusActive = false
            stopStatusAnim()
            bg?.setColor(Color.parseColor(NORMAL_BG))
            collapse()
        }
    }

    /**
     * 一轮请求结束的兜底收起。
     *
     * 若已经有正文在逐字输出（[typingRunnable] 非空）就不动它，
     * 避免「刚收到回复就被收起」的竞态。
     */
    fun endThinking() {
        main.post {
            if (typingRunnable != null) return@post
            if (!statusActive) return@post
            statusActive = false
            stopStatusAnim()
            bg?.setColor(Color.parseColor(NORMAL_BG))
            collapse()
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
        layoutCapsule(text, root, tv, params, manager)
        tv.text = ""

        // 尺寸到位后开始逐字
        main.postDelayed({
            var i = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (container == null || inputMode) return
                    if (i <= text.length) {
                        tv.text = text.substring(0, i)
                        // 逐字时视口跟着最新文字走，不然新字都长在可视区外
                        tv.post { followBottom(tv) }
                        i += 1
                        main.postDelayed(this, CHAR_DELAY_MS)
                    } else {
                        // 输出完了：内容超过两行就循环滚动，而不是停在截断处
                        startAutoScroll()
                        scheduleCollapse()
                    }
                }
            }
            typingRunnable = runnable
            main.post(runnable)
        }, 200L)
    }

    /** 让视口跟到最新一行（逐字输出期间用） */
    private fun followBottom(tv: TextView) {
        if (tv.height <= 0) return
        val max = maxScroll(tv)
        if (max > 0) tv.scrollTo(0, max)
    }

    /**
     * 自动滚动：一次滚一行，滚到底回开头循环。
     *
     * 用户手指按着的时候暂停（别跟人抢滚动），松手 [TOUCH_RESUME_MS] 后自动恢复。
     */
    private fun startAutoScroll() {
        stopAutoScroll()
        val tv = textView ?: return
        val gen = scrollGen
        // 等布局落位再量高度，否则此时 height 往往还是 0，会误判成「两行内不用滚」
        main.postDelayed({
            if (gen != scrollGen) return@postDelayed   // 已被新一轮内容/收起取代
            if (container == null || inputMode) return@postDelayed
            if (maxScroll(tv) <= 0) return@postDelayed // 两行内放得下，不用滚
            val step = lineHeightPx(tv)
            val runnable = object : Runnable {
                override fun run() {
                    if (container == null || inputMode) return
                    val t = textView ?: return
                    if (userTouching) {
                        main.postDelayed(this, 260L)
                        return
                    }
                    // 刚松手：先让人把这一屏看完，别立刻把内容滚走
                    if (SystemClock.uptimeMillis() - lastTouchAt < TOUCH_RESUME_MS) {
                        main.postDelayed(this, 300L)
                        return
                    }
                    val max = maxScroll(t)
                    if (max <= 0) return
                    if (t.scrollY >= max - 2) {
                        // 到底了：回到第一屏重播，让用户有机会补看开头
                        smoothScrollTo(t, 0)
                        main.postDelayed(this, SCROLL_HOLD_MS + 400L)
                    } else {
                        smoothScrollTo(t, t.scrollY + step)
                        main.postDelayed(this, SCROLL_HOLD_MS)
                    }
                }
            }
            scrollRunnable = runnable
            main.postDelayed(runnable, SCROLL_HOLD_MS)
        }, 120L)
    }

    private fun stopAutoScroll() {
        scrollGen++
        scrollRunnable?.let { main.removeCallbacks(it) }
        scrollRunnable = null
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    /** 文字区手势：上下滑 = 手动翻看，轻点 / 长按 = 原来那套（提问 / 中断） */
    private fun attachScrollTouch(tv: TextView) {
        val slop = ViewConfiguration.get(tv.context).scaledTouchSlop
        var downY = 0f
        var lastY = 0f
        var downAt = 0L
        var moved = false
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    lastY = ev.rawY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                    userTouching = true
                    // 手一碰就停掉平滑动画，不然会跟手指打架
                    scrollAnimator?.cancel()
                    scrollAnimator = null
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = lastY - ev.rawY
                    lastY = ev.rawY
                    if (!moved && abs(ev.rawY - downY) > slop) moved = true
                    if (moved) {
                        val max = maxScroll(tv)
                        if (max > 0) tv.scrollTo(0, (tv.scrollY + dy.toInt()).coerceIn(0, max))
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    lastTouchAt = SystemClock.uptimeMillis()
                    // 滑过就不当点击；没滑才算轻点/长按
                    if (!moved) {
                        val dur = SystemClock.uptimeMillis() - downAt
                        if (dur >= LONG_PRESS_MS) onIslandLongClick() else onIslandClick()
                    }
                    // 恢复自动滚动交给循环里的自旋，这里不要重复投递 runnable，
                    // 否则会有两条滚动链同时跑，越滚越快
                }
            }
            true
        }
    }

    /** 当前内容占几屏（用于估算停留时长） */
    private fun pageCount(): Int {
        val tv = textView ?: return 1
        val contentH = tv.layout?.height ?: 0
        if (tv.height <= 0 || contentH <= 0) return 1
        return ceil(contentH.toDouble() / tv.height).toInt().coerceAtLeast(1)
    }

    /**
     * 按 [text] 定尺寸把胶囊展开到位（不含逐字动画）。
     *
     * 抽出公共方法，供 [expandAndType] 与 [showStatus] 共用。
     */
    private fun layoutCapsule(
        text: String,
        root: LinearLayout,
        tv: TextView,
        params: WindowManager.LayoutParams,
        manager: WindowManager
    ) {
        tv.text = text
        tv.visibility = View.VISIBLE
        tv.scrollTo(0, 0)
        root.setPadding(dp(14), dp(CAPSULE_PAD_V_DP), dp(14), dp(CAPSULE_PAD_V_DP))

        // 先量出这段文字实际占几行：一行就一行高，超过两行才锁在两行（滚动播）
        val oneLine = lineHeightPx(tv)
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(dp(MAX_WIDTH_DP - 28), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val viewportH = tv.measuredHeight.coerceIn(oneLine, oneLine * DISPLAY_LINES)
        (tv.layoutParams as? LinearLayout.LayoutParams)?.let { p ->
            p.height = viewportH
            tv.layoutParams = p
        }

        root.measure(
            View.MeasureSpec.makeMeasureSpec(dp(MAX_WIDTH_DP), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val targetW = root.measuredWidth.coerceAtLeast(dp(72))
        // 高度 = 实际行高 + 内边距：以前写死 40dp，第二行直接被裁掉
        val targetH = (viewportH + dp(CAPSULE_PAD_V_DP * 2))
            .coerceAtLeast(dp(CAPSULE_HEIGHT_DP))
        animateSize(params.width, params.height, targetW, targetH, manager, root)
    }

    /** 内容可滚动的最大偏移（文本总高 - 视口高） */
    private fun maxScroll(tv: TextView): Int {
        val contentH = tv.layout?.height ?: 0
        return (contentH - tv.height).coerceAtLeast(0)
    }

    /** 平滑滚到指定位置（220ms），手指一碰就取消 */
    private fun smoothScrollTo(tv: TextView, targetY: Int) {
        val to = targetY.coerceIn(0, maxScroll(tv))
        val from = tv.scrollY
        if (from == to) return
        scrollAnimator?.cancel()
        val anim = ValueAnimator.ofInt(from, to).apply {
            duration = 220L
            addUpdateListener { a -> tv.scrollTo(0, a.animatedValue as Int) }
        }
        scrollAnimator = anim
        anim.start()
    }

    /** 单行高度（px），跟随 TextView 的 textSize 与行距 */
    private fun lineHeightPx(tv: TextView): Int {
        val fm = tv.paint.fontMetrics
        val h = (fm.descent - fm.ascent + fm.leading) * LINE_SPACING_MULT
        return if (h.isFinite() && h > 0f) ceil(h.toDouble()).toInt() else dp(16)
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
        // 内容超过一屏就按页数延长停留，别让人没看完就收
        val pages = pageCount()
        val delay = if (pages > 1) {
            (pages * SCROLL_HOLD_MS + HOLD_MS).coerceAtMost(MAX_HOLD_MS)
        } else {
            HOLD_MS
        }
        val runnable = Runnable { collapse() }
        collapseRunnable = runnable
        main.postDelayed(runnable, delay)
    }

    private fun collapse() {
        statusActive = false
        bg?.setColor(Color.parseColor(NORMAL_BG))
        if (inputMode) return
        val root = container ?: return
        val params = lp ?: return
        val manager = wm ?: return
        val tv = textView ?: return

        cancelTyping()
        stopAutoScroll()
        expanded = false
        val fromW = params.width
        val fromH = params.height
        animateSize(fromW, fromH, dp(DOT_SIZE_DP), dp(DOT_SIZE_DP), manager, root)
        // 文字等收缩动画结束再隐藏，避免收缩途中文字突然消失
        main.postDelayed({
            if (!expanded && !inputMode) {
                tv.text = ""
                tv.visibility = View.GONE
                tv.scrollTo(0, 0)
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
        stopAutoScroll()
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
        // 新问题已发出：上一轮暂存回复直接丢弃，交给下面新立起的「思考中」
        exitInputMode(flushPending = false)
        // 先立起「思考中」，再把请求交给引擎；引擎忙/失败时由它自己的状态覆盖
        showStatus("思考中", StatusKind.THINKING)
        val handler = onUserMessage
        if (handler == null) {
            // 没接线（进程被系统拉起来但没走 MainActivity）——如实报，不再走老的纯文本旁路
            showStatus("副驾驶没接上，重开一次 App", StatusKind.ERROR)
            Log.w(TAG, "onUserMessage 未接线，消息被丢弃：$text")
        } else {
            handler(text)
        }
    }

    /**
     * 退出输入态。
     *
     * @param flushPending true = 把输入期间攒下的回复补播出来（正常收起时）；
     *                     false = 丢弃（用户已经问了新问题，旧回复不该顶掉新一轮的「思考中」）
     */
    private fun exitInputMode(flushPending: Boolean = true) {
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
            tv.scrollTo(0, 0)
            tv.visibility = View.GONE
            root.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    lineHeightPx(tv) * DISPLAY_LINES
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

        // 输入期间攒下的回复：正常收起时补播；用户已发起新问题时不打扰新一轮状态
        if (flushPending) {
            pendingSpeech?.let { queued ->
                pendingSpeech = null
                // 等动画落位再弹，避免刚播就被新一轮「思考中」盖掉
                main.postDelayed({ speak(queued) }, 150L)
            }
        } else if (pendingSpeech != null) {
            Log.i(TAG, "用户已发起新问题，丢弃上一轮暂存回复")
            pendingSpeech = null
        }
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
        // 新内容/收起都要先把旧滚动链停掉，否则会和新一轮抢滚动
        stopAutoScroll()
        stopStatusAnim()
    }

    /** 停止「思考中」省略号动画 */
    private fun stopStatusAnim() {
        statusAnim?.let { main.removeCallbacks(it) }
        statusAnim = null
    }

    private fun setCornerRadius(heightPx: Int) {
        bg?.cornerRadius = heightPx / 2f
    }

    /** 先捕获当前 rootView，确认仍是同一个实例才 remove，避免误删新建的窗口 */
    private fun safeRemove() {
        stopAutoScroll()
        userTouching = false
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
