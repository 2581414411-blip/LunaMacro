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
import android.hardware.display.DisplayManager
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
import com.nexus.agent.copilot.CopilotConfig
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

    // ---------------------------------------------------------------- 位置（0.7.11 可拖动）

    /** 位置存储：归一化比例，抗屏幕旋转 / 分辨率变化 */
    private const val POS_PREFS = "luna_island_pos"
    private const val KEY_X_RATIO = "x_ratio"
    private const val KEY_Y_RATIO = "y_ratio"

    /** 吸附边："left" / "right" / ""（未吸附，自由位置） */
    private const val KEY_EDGE = "edge"
    private const val EDGE_LEFT = "left"
    private const val EDGE_RIGHT = "right"
    private const val EDGE_NONE = ""

    /** 贴边时的留白，避免圆点被屏幕边缘裁掉一半 */
    private const val EDGE_MARGIN_DP = 2

    /** 判定为「拖窗口」而非「滚文字」的位移阈值倍数（乘 touchSlop） */
    private const val DRAG_SLOP_FACTOR = 1.0f

    /** 吸附回弹动画时长 */
    private const val SNAP_ANIM_MS = 180L

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

    /** 吸附回弹动画（再次拖动时先取消，避免两条动画抢同一个 x） */
    private var snapAnimator: ValueAnimator? = null

    /** 滚动链世代号：防止旧的延迟任务再拉起第二条滚动链（会越滚越快） */
    private var scrollGen = 0

    /** 用户手指正按在岛上（暂停自动滚动，别跟人抢） */
    private var userTouching = false

    /** 最后一次松手的时间，用来给用户留出看完一屏的时间 */
    private var lastTouchAt = 0L

    // ---------------------------------------------------------------- 拖动状态（0.7.11）

    /**
     * 当前吸附边。
     *
     * 决定窗口变宽时往哪边生长：靠右吸附时必须让左边界跟着收，否则整块会跑出屏幕。
     */
    private var snapEdge: String = EDGE_NONE

    /** 当前手势是否已判定为「拖窗口」（一次手势只判定一次，中途不再改判） */
    private var gestureDecided = false

    /** 当前手势判定结果：true = 拖窗口，false = 滚文字 */
    private var gestureIsDrag = false

    /** 手势起点（raw 坐标）与窗口起点（layout 参数），算位移用 */
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0

    /** 旋转监听：横竖屏切换后按归一化比例把岛摆回原相对位置 */
    private var displayListener: DisplayManager.DisplayListener? = null

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

        // 0.7.12：设置里的开关是**唯一权威**。
        //
        // 之前只有 showTemporary / 启动恢复两处自觉判开关，而 CopilotEngine.ask() 里的
        // showStatus("思考中") 是无条件调用的，于是「设置里关掉灵动岛，问一句话它照样弹出来」，
        // 开关形同虚设。现在把闸门收到 show() 这个唯一入口：
        // 关掉之后，副驾驶思考中、陪玩弹幕、语音提示一律不许再把窗口拉起来。
        if (!CopilotConfig.isIslandEnabled(ctx)) {
            Log.i(TAG, "灵动岛已在设置中关闭，忽略本次显示请求")
            return false
        }

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
                // 用 TOP|START（左对齐）而不是 CENTER_HORIZONTAL：
                // 拖动和吸附都要精确控制左右边界，从屏幕左边算 x 比"相对中心偏移"简单得多。
                gravity = Gravity.TOP or Gravity.START
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
            }
            // 圆点态没有文字可滚，整块都能拖；点击/长按在触摸判定里手动触发
            attachDragTouch(root)

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

            // 摆回上次的位置（没存过就顶部居中），必须在 addView 前算好，
            // 否则会先闪一下居中原位再跳过去
            applyStoredPosition(params)

            windowManager.addView(root, params)
            container = root
            attachDisplayListener()
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
        // 开关关掉后直接丢弃，连窗口创建都不要尝试（show() 里还有一道兜底，
        // 这里提前返回是为了不让每次「思考中」都刷一条忽略日志）
        val guardCtx = appContext ?: runCatching { ShizukuManager.appContext() }.getOrNull()
        if (guardCtx != null && !CopilotConfig.isIslandEnabled(guardCtx)) return
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

    /**
     * 文字区手势：上下滑 = 手动翻看，横向滑 = 拖动窗口，轻点 / 长按 = 提问 / 中断。
     *
     * 方向判定只做一次（[gestureDecided]），一次手势中途不改判，
     * 否则手抖一下就会「先滚一段再突然挪走」。
     */
    private fun attachScrollTouch(tv: TextView) {
        val slop = ViewConfiguration.get(tv.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var lastY = 0f
        var downAt = 0L
        tv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    lastY = ev.rawY
                    downAt = SystemClock.uptimeMillis()
                    gestureDecided = false
                    gestureIsDrag = false
                    dragOriginX = lp?.x ?: 0
                    dragOriginY = lp?.y ?: 0
                    userTouching = true
                    // 手一碰就停掉平滑动画，不然会跟手指打架
                    scrollAnimator?.cancel()
                    scrollAnimator = null
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!gestureDecided && (abs(dx) > slop || abs(dy) > slop)) {
                        gestureDecided = true
                        // 展开态：**横向位移更大才算拖窗口**，纵向一律留给文字滚动。
                        // 这条规则就是「文本可正常滑动、不误触窗口」的保证。
                        gestureIsDrag = abs(dx) > abs(dy)
                    }
                    if (gestureIsDrag) {
                        // 横向拖动只改 x，纵向锁死——避免想横向挪一下就顺手把岛拖到别处
                        moveWindowToX(dragOriginX + dx.toInt())
                    } else {
                        val step = lastY - ev.rawY
                        lastY = ev.rawY
                        val max = maxScroll(tv)
                        if (max > 0) tv.scrollTo(0, (tv.scrollY + step.toInt()).coerceIn(0, max))
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    lastTouchAt = SystemClock.uptimeMillis()
                    if (gestureIsDrag) {
                        snapToNearestEdge()
                    } else {
                        // 滑过就不当点击；没滑才算轻点/长按
                        val dur = SystemClock.uptimeMillis() - downAt
                        if (!gestureDecided) {
                            if (dur >= LONG_PRESS_MS) onIslandLongClick() else onIslandClick()
                        }
                    }
                    // 恢复自动滚动交给循环里的自旋，这里不要重复投递 runnable，
                    // 否则会有两条滚动链同时跑，越滚越快
                }
            }
            true
        }
    }

    /**
     * 圆点态的整块拖拽（此时没有文字可滚，横纵都直接移窗口）。
     *
     * 这里没挂 OnClickListener：触摸被本监听器消费后点击事件不会再派发，
     * 所以轻点 / 长按在 ACTION_UP 里按位移与时长手动判定。
     */
    private fun attachDragTouch(root: LinearLayout) {
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop * DRAG_SLOP_FACTOR
        var downAt = 0L
        root.setOnTouchListener { _, ev ->
            // 输入态交给 EditText，别跟输入法抢
            if (inputMode) return@setOnTouchListener false
            val p = lp ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    dragOriginX = p.x
                    dragOriginY = p.y
                    downAt = SystemClock.uptimeMillis()
                    gestureDecided = false
                    gestureIsDrag = false
                    userTouching = true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!gestureDecided && (abs(dx) > slop || abs(dy) > slop)) {
                        gestureDecided = true
                        gestureIsDrag = true
                    }
                    if (gestureIsDrag) {
                        p.x = clampX(dragOriginX + dx.toInt(), p.width)
                        p.y = clampY(dragOriginY + dy.toInt(), p.height)
                        runCatching { wm?.updateViewLayout(root, p) }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    userTouching = false
                    lastTouchAt = SystemClock.uptimeMillis()
                    if (gestureIsDrag) {
                        snapToNearestEdge()
                    } else {
                        val dur = SystemClock.uptimeMillis() - downAt
                        if (dur >= LONG_PRESS_MS) onIslandLongClick() else onIslandClick()
                    }
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------- 位置计算

    private fun posPrefs() =
        appContext?.getSharedPreferences(POS_PREFS, Context.MODE_PRIVATE)

    /** 屏幕真实尺寸（含状态栏与导航栏区域） */
    private fun screenSize(): Pair<Int, Int> {
        val ctx = appContext ?: return 1080 to 1920
        val mgr = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 1080 to 1920
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = mgr.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = android.util.DisplayMetrics().also { mgr.defaultDisplay.getRealMetrics(it) }
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 窗口左上角可取的 y 下限：压在状态栏上会挡住时间电量 */
    private fun minY(): Int = (appContext?.let { statusBarHeight(it) } ?: dp(24)) + dp(2)

    private fun clampX(x: Int, width: Int): Int {
        val (sw, _) = screenSize()
        return x.coerceIn(0, (sw - width).coerceAtLeast(0))
    }

    private fun clampY(y: Int, height: Int): Int {
        val (_, sh) = screenSize()
        val top = minY()
        val bottom = (sh - height - dp(8)).coerceAtLeast(top)
        return y.coerceIn(top, bottom)
    }

    /** 把存储的归一化比例还原成像素坐标（没存过就顶部居中） */
    private fun applyStoredPosition(p: WindowManager.LayoutParams) {
        val prefs = posPrefs()
        val (sw, sh) = screenSize()
        val margin = dp(EDGE_MARGIN_DP)
        snapEdge = prefs?.getString(KEY_EDGE, EDGE_NONE) ?: EDGE_NONE

        val maxX = (sw - p.width).coerceAtLeast(0)
        val defaultY = (appContext?.let { statusBarHeight(it) } ?: dp(24)) + dp(6)

        p.x = when (snapEdge) {
            EDGE_LEFT -> margin
            EDGE_RIGHT -> (maxX - margin).coerceAtLeast(margin)
            else -> {
                val ratio = prefs?.getFloat(KEY_X_RATIO, -1f) ?: -1f
                if (ratio >= 0f) (ratio * maxX).toInt() else maxX / 2
            }
        }.coerceIn(0, maxX)

        val yRatio = prefs?.getFloat(KEY_Y_RATIO, -1f) ?: -1f
        p.y = if (yRatio >= 0f) {
            (yRatio * (sh - p.height).coerceAtLeast(0)).toInt()
        } else {
            defaultY
        }
        // 存的比例可能来自另一套分辨率的设备，落点必须再夹一次
        p.y = clampY(p.y, p.height)
    }

    /** 把当前位置按比例存下来（吸附边单独存，展开时要知道往哪边长） */
    private fun savePosition() {
        val p = lp ?: return
        val prefs = posPrefs() ?: return
        val (sw, sh) = screenSize()
        val availX = (sw - p.width).coerceAtLeast(1)
        val availY = (sh - p.height).coerceAtLeast(1)
        prefs.edit()
            .putFloat(KEY_X_RATIO, (p.x.toFloat() / availX).coerceIn(0f, 1f))
            .putFloat(KEY_Y_RATIO, (p.y.toFloat() / availY).coerceIn(0f, 1f))
            .putString(KEY_EDGE, snapEdge)
            .apply()
    }

    /** 只改横向位置并立即生效（拖动过程中用，不存盘） */
    private fun moveWindowToX(x: Int) {
        val p = lp ?: return
        val root = container ?: return
        val target = clampX(x, p.width)
        if (p.x == target) return
        p.x = target
        runCatching { wm?.updateViewLayout(root, p) }
    }

    /**
     * 松手吸附：过中线贴右，否则贴左。
     *
     * 吸附边会记下来，下次展开变宽时右吸附要跟着收回左边界，否则整块跑出屏幕。
     */
    private fun snapToNearestEdge() {
        val p = lp ?: return
        val root = container ?: return
        val (sw, _) = screenSize()
        val margin = dp(EDGE_MARGIN_DP)
        val centerX = p.x + p.width / 2f
        val toLeft = centerX < sw / 2f
        snapEdge = if (toLeft) EDGE_LEFT else EDGE_RIGHT
        val target = if (toLeft) margin else (sw - p.width - margin).coerceAtLeast(margin)

        if (p.x == target) {
            savePosition()
            return
        }
        val from = p.x
        val anim = ValueAnimator.ofInt(from, target).apply {
            duration = SNAP_ANIM_MS
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                runCatching { wm?.updateViewLayout(root, p) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    savePosition()
                }
            })
        }
        snapAnimator?.cancel()
        snapAnimator = anim
        anim.start()
    }

    /** 宽度变化后校正吸附边：右吸附必须让左边界跟着收，左吸附不用动 */
    private fun syncXForWidth(newWidth: Int) {
        val p = lp ?: return
        if (snapEdge != EDGE_RIGHT) return
        val (sw, _) = screenSize()
        val margin = dp(EDGE_MARGIN_DP)
        val target = (sw - newWidth - margin).coerceAtLeast(0)
        if (p.x != target) p.x = target
    }

    /** 横竖屏切换后按存的比例把岛摆回相对位置 */
    private fun attachDisplayListener() {
        if (displayListener != null) return
        val ctx = appContext ?: return
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                main.postDelayed({
                    val p = lp ?: return@postDelayed
                    val root = container ?: return@postDelayed
                    // 按当前比例重算，宽高已随新分辨率变化
                    val savedEdge = snapEdge
                    val (sw, sh) = screenSize()
                    p.x = when (savedEdge) {
                        EDGE_LEFT -> dp(EDGE_MARGIN_DP)
                        EDGE_RIGHT -> (sw - p.width - dp(EDGE_MARGIN_DP)).coerceAtLeast(0)
                        else -> p.x.coerceIn(0, (sw - p.width).coerceAtLeast(0))
                    }
                    p.y = clampY(p.y, p.height)
                    runCatching { wm?.updateViewLayout(root, p) }
                    savePosition()
                }, 320L) // 等系统把新尺寸刷新完再摆，太早会拿到旧值
            }
        }
        runCatching { dm.registerDisplayListener(listener, main) }
            .onSuccess { displayListener = listener }
            .onFailure { Log.w(TAG, "注册旋转监听失败：${it.message}") }
    }

    private fun detachDisplayListener() {
        val ctx = appContext
        val listener = displayListener
        if (ctx != null && listener != null) {
            runCatching {
                (ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(listener)
            }
        }
        displayListener = null
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
                    // 靠右吸附时窗口变宽会往右顶出屏幕，这里让左边界跟着收
                    if (snapEdge == EDGE_RIGHT) {
                        val (sw, _) = screenSize()
                        p.x = (sw - w - dp(EDGE_MARGIN_DP)).coerceAtLeast(0)
                    }
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
        // 靠右吸附时窗口变宽会顶出屏幕，缩回左边界
        syncXForWidth(params.width)
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
        syncXForWidth(params.width)
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
