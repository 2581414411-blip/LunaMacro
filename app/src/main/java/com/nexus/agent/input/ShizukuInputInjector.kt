package com.nexus.agent.input

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * 基于 Shizuku 的系统级输入注入引擎。
 *
 * 通过 Shizuku 拿到系统 "input" 服务（IInputManager）的 Binder，
 * 再用反射调用其隐藏的 [injectInputEvent] 方法，绕过普通应用
 * 对 InputManager 的权限限制，实现全局点击 / 滑动注入。
 *
 * 关键设计约束（务必严格遵守）：
 * 1. [POINTER_ID] 固定为 19 —— 避开真实手指常用的 0~9，
 *    避免被多指手势识别逻辑误判为真实触控。
 * 2. [DEVICE_ID] 使用 -1 —— 让 InputDispatcher 将注入事件
 *    视为独立输入流（非特定设备），不与物理触摸屏冲突。
 * 3. [PRESSURE] 保持 1.0 —— 让游戏/应用识别为持续有效按压，
 *    避免被判为悬空或误触。
 * 4. 绝不发送 ACTION_CANCEL —— 否则游戏会判定手势无效。
 * 5. 通过 [ShizukuBinderWrapper] 包装 IInputManager 的 Binder，
 *    使远程调用可穿透 Shizuku 的权限校验。
 * 6. 通过反射调用隐藏的 `injectInputEvent` API。
 */
object ShizukuInputInjector {

    private const val TAG = "ShizukuInputInjector"

    /** 注入事件使用的指针 ID（避开 0~9 真实手指） */
    private const val POINTER_ID = 19

    /** 设备 ID：-1 表示独立输入流 */
    private const val DEVICE_ID = -1

    /** 按压压力：1.0 表示持续有效按压 */
    private const val PRESSURE = 1.0f

    /** injectInputEvent 的输入模式：等待注入完成（同步） */
    private const val INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1

    /** 反射缓存：IInputManager 的 injectInputEvent 方法 */
    private var injectInputEventMethod: Method? = null

    /** 反射缓存：IInputManager 代理实例 */
    private var inputManager: Any? = null

    /**
     * 获取通过 Shizuku 包装后的 IInputManager 代理。
     *
     * 流程：SystemServiceHelper.getSystemService("input") 拿到原始 Binder，
     * 用 ShizukuBinderWrapper 包装后，通过反射调用 IInputManager.Stub.asInterface()
     * 得到可在应用进程中安全调用的代理。
     */
    private fun getInputManager(): Any? {
        inputManager?.let { return it }

        val serviceBinder: IBinder? = SystemServiceHelper.getSystemService("input")
        if (serviceBinder == null) {
            Log.e(TAG, "getSystemService(\"input\") returned null")
            return null
        }

        return try {
            // 关键：本类运行在 Shizuku UserService 的 shell 进程（UID 2000），
            // 该进程本身就具备调用 IInputManager 的系统权限，因此这里直接
            // 用原始 binder 反射 asInterface，无需（也不能）再用
            // ShizukuBinderWrapper 包装——后者要求当前进程已持有 Shizuku
            // 客户端 binder，而 shell 进程里没有，会导致
            // "binder haven't been received" 异常。
            val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val manager = asInterface.invoke(null, serviceBinder)
            inputManager = manager
            manager
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to obtain IInputManager proxy", e)
            null
        }
    }

    /**
     * 反射获取 IInputManager.injectInputEvent 方法。
     *
     * 方法签名（隐藏 API，不同 Android 版本一致）：
     * `boolean injectInputEvent(InputEvent event, int mode)`
     */
    private fun getInjectMethod(): Method? {
        injectInputEventMethod?.let { return it }

        val manager = getInputManager() ?: return null

        return try {
            val m = manager.javaClass.getMethod(
                "injectInputEvent",
                android.view.InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            injectInputEventMethod = m
            m
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to reflect injectInputEvent", e)
            null
        }
    }

    /**
     * 执行一次输入事件注入。
     *
     * @return 是否注入成功
     */
    private fun injectEvent(event: MotionEvent): Boolean {
        val method = getInjectMethod() ?: return false
        val manager = getInputManager() ?: return false

        return try {
            val result = method.invoke(
                manager,
                event,
                INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT
            ) as? Boolean
            Log.e(TAG, "injectEvent result=$result action=${event.action}")
            result == true
        } catch (e: Throwable) {
            Log.e(TAG, "injectEvent failed", e)
            false
        }
    }

    /**
     * 构造一个 MotionEvent。
     */
    private fun obtainEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val props = arrayOfNulls<MotionEvent.PointerProperties>(1)
        props[0] = MotionEvent.PointerProperties().apply {
            id = POINTER_ID
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }

        val coords = arrayOfNulls<MotionEvent.PointerCoords>(1)
        coords[0] = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = PRESSURE
            this.size = 1.0f
        }

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,                       // pointerCount
            props,                   // pointerProperties
            coords,                  // pointerCoords
            0,                       // metaState
            0,                       // buttonState
            PRESSURE,                // xPrecision
            PRESSURE,                // yPrecision
            DEVICE_ID,               // deviceId
            0,                       // edgeFlags
            InputDevice.SOURCE_TOUCHSCREEN, // source
            0                        // flags
        )
    }

    /**
     * 注入一次点击（tap）。
     *
     * 只发送 ACTION_DOWN + ACTION_UP，绝不发送 ACTION_CANCEL。
     *
     * @param x 屏幕 X 坐标（像素）
     * @param y 屏幕 Y 坐标（像素）
     */
    fun injectTap(x: Float, y: Float): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = obtainEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        val up = obtainEvent(MotionEvent.ACTION_UP, x, y, downTime, downTime + 10)

        return try {
            val okDown = injectEvent(down)
            val okUp = injectEvent(up)
            okDown && okUp
        } finally {
            // 每次注入后释放资源
            down.recycle()
            up.recycle()
        }
    }

    /**
     * 注入一次滑动（swipe）。
     *
     * 通过 ACTION_MOVE 逐步移动实现，全程保持 DOWN 状态，
     * 结束后发送 ACTION_UP 完成手势，绝不发送 ACTION_CANCEL。
     *
     * @param x1 起点 X 坐标
     * @param y1 起点 Y 坐标
     * @param x2 终点 X 坐标
     * @param y2 终点 Y 坐标
     * @param duration 滑动持续时间（毫秒）
     */
    fun injectSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val start = downTime

        val down = obtainEvent(MotionEvent.ACTION_DOWN, x1, y1, downTime, downTime)
        val downOk = injectEvent(down)
        down.recycle()
        if (!downOk) return false

        // 插值步数：至少 10 步，最多 200 步
        val steps = (duration / 10).coerceIn(10, 200)
        var move: MotionEvent? = null
        var up: MotionEvent? = null
        var ok = true

        return try {
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val cx = x1 + (x2 - x1) * progress
                val cy = y1 + (y2 - y1) * progress
                val eventTime = start + (duration * progress).toLong()
                move = obtainEvent(MotionEvent.ACTION_MOVE, cx, cy, downTime, eventTime)
                if (!injectEvent(move)) ok = false
                move.recycle()
                move = null
            }

            val upTime = start + duration
            up = obtainEvent(MotionEvent.ACTION_UP, x2, y2, downTime, upTime)
            if (!injectEvent(up)) ok = false
            ok
        } finally {
            move?.recycle()
            up?.recycle()
        }
    }

    /**
     * 注入一次长按（long press）。
     *
     * ACTION_DOWN 后保持按压 duration 毫秒，再 ACTION_UP。
     * 用于触发长按菜单等。
     */
    fun injectLongPress(x: Float, y: Float, duration: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = obtainEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        val downOk = injectEvent(down)
        down.recycle()
        if (!downOk) return false

        // 保持按压
        try {
            Thread.sleep(duration.toLong().coerceAtLeast(0))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val up = obtainEvent(MotionEvent.ACTION_UP, x, y, downTime, downTime + duration)
        val upOk = injectEvent(up)
        up.recycle()
        return upOk
    }

    /**
     * 注入一次精确拖拽（drag）。
     *
     * 与 [injectSwipe] 的区别：drag 是「精确移动」语义（起点终点精确、匀速），
     * swipe 是「惯性滑动」语义。两者注入方式同为 DOWN→MOVE→UP，但 drag 插值
     * 步数下限更小（2 步），保证短距离也够平滑，约 10ms 一步。
     */
    fun injectDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, duration: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        val down = obtainEvent(MotionEvent.ACTION_DOWN, fromX, fromY, downTime, downTime)
        val downOk = injectEvent(down)
        down.recycle()
        if (!downOk) return false

        val steps = (duration / 10).coerceIn(2, 200)
        var move: MotionEvent? = null
        var up: MotionEvent? = null
        var ok = true
        return try {
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                val cx = fromX + (toX - fromX) * progress
                val cy = fromY + (toY - fromY) * progress
                val eventTime = downTime + (duration * progress).toLong()
                move = obtainEvent(MotionEvent.ACTION_MOVE, cx, cy, downTime, eventTime)
                if (!injectEvent(move)) ok = false
                move.recycle()
                move = null
            }
            val upTime = downTime + duration
            up = obtainEvent(MotionEvent.ACTION_UP, toX, toY, downTime, upTime)
            if (!injectEvent(up)) ok = false
            ok
        } finally {
            move?.recycle()
            up?.recycle()
        }
    }

    /**
     * 注入多指手势。
     *
     * [points] 是一个事件序列，每项含坐标和动作（DOWN/UP/MOVE），按顺序执行。
     * 维护「当前按下的指针集合」，pointer id 从 [POINTER_ID](19) 递增。
     * DOWN/UP 通过坐标就近匹配活动指针。
     *
     * 示例（双指点击）：down(200,1500) → down(800,800) → up(200,1500) → up(800,800)
     */
    fun injectMultiTouch(points: List<TouchPoint>): Boolean {
        if (points.isEmpty()) return false
        val downTime = SystemClock.uptimeMillis()
        // active：按加入顺序保存 [pointerId, x, y]
        val active = ArrayList<FloatArray>()
        var nextId = POINTER_ID
        var ev: MotionEvent? = null
        var ok = true

        return try {
            for (pt in points) {
                val t = SystemClock.uptimeMillis()
                when (pt.action) {
                    TouchPoint.ACTION_DOWN -> {
                        val id = nextId++
                        active.add(floatArrayOf(id.toFloat(), pt.x, pt.y))
                        val index = active.size - 1
                        val action = if (active.size == 1) MotionEvent.ACTION_DOWN
                            else MotionEvent.ACTION_POINTER_DOWN or
                                (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        ev?.recycle()
                        ev = obtainMultiTouchEvent(action, active, downTime, t)
                        if (ev?.let { injectEvent(it) } != true) ok = false
                    }

                    TouchPoint.ACTION_MOVE -> {
                        val idx = findClosestPointer(active, pt.x, pt.y)
                        if (idx >= 0) {
                            active[idx][1] = pt.x
                            active[idx][2] = pt.y
                        }
                        ev?.recycle()
                        ev = obtainMultiTouchEvent(MotionEvent.ACTION_MOVE, active, downTime, t)
                        if (ev?.let { injectEvent(it) } != true) ok = false
                    }

                    TouchPoint.ACTION_UP -> {
                        val idx = findClosestPointer(active, pt.x, pt.y)
                        if (idx >= 0) {
                            val action = if (active.size == 1) MotionEvent.ACTION_UP
                                else MotionEvent.ACTION_POINTER_UP or
                                    (idx shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                            ev?.recycle()
                            ev = obtainMultiTouchEvent(action, active, downTime, t)
                            if (ev?.let { injectEvent(it) } != true) ok = false
                            active.removeAt(idx)
                        }
                    }
                }
            }
            ok
        } finally {
            ev?.recycle()
        }
    }

    /**
     * 构造多指针 MotionEvent。
     *
     * @param action action 值（可能是 ACTION_POINTER_DOWN/UP 带 index 的组合值）
     * @param active 活动指针列表，每项 [pointerId, x, y]
     */
    private fun obtainMultiTouchEvent(
        action: Int,
        active: List<FloatArray>,
        downTime: Long,
        eventTime: Long
    ): MotionEvent {
        val count = active.size
        val props = arrayOfNulls<MotionEvent.PointerProperties>(count)
        val coords = arrayOfNulls<MotionEvent.PointerCoords>(count)
        for (i in 0 until count) {
            props[i] = MotionEvent.PointerProperties().apply {
                id = active[i][0].toInt()
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coords[i] = MotionEvent.PointerCoords().apply {
                x = active[i][1]
                y = active[i][2]
                pressure = PRESSURE
                size = 1.0f
            }
        }
        return MotionEvent.obtain(
            downTime, eventTime, action, count, props, coords,
            0, 0, PRESSURE, PRESSURE, DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
    }

    /**
     * 在活动指针列表中找坐标最接近的指针索引。
     */
    private fun findClosestPointer(active: List<FloatArray>, x: Float, y: Float): Int {
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in active.indices) {
            val dx = active[i][1] - x
            val dy = active[i][2] - y
            val d = dx * dx + dy * dy
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    /**
     * 多指手势的事件点。
     *
     * @param x      屏幕 X 坐标
     * @param y      屏幕 Y 坐标
     * @param action DOWN / UP / MOVE
     */
    data class TouchPoint(val x: Float, val y: Float, val action: Int) {
        companion object {
            const val ACTION_DOWN = 0
            const val ACTION_UP = 1
            const val ACTION_MOVE = 2
        }
    }
}
