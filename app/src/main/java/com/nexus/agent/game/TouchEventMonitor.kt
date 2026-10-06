package com.nexus.agent.game

import android.util.Log
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 触摸事件监听器。
 *
 * 通过 Shizuku（shell UID 2000）运行 `getevent -lt` 持续监听触摸事件，
 * 解析出坐标，回调给上层。采用「后台进程输出到文件 + 轮询读取解析」方案，
 * 规避 execCommand 同步阻塞无法处理持续输出流的问题。
 */
object TouchEventMonitor {

    private const val TAG = "TouchMonitor"
    private const val TOUCH_LOG = "/data/local/tmp/touch_monitor.log"

    /** 触摸监听回调 */
    interface TouchListener {
        fun onTouchDown(x: Int, y: Int)
        fun onTouchUp(x: Int, y: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    private var job: Job? = null

    @Volatile
    private var listener: TouchListener? = null

    @Volatile
    private var touchDevice: String? = null

    /** 触摸面板 X 坐标最大值（归一化用，非屏幕像素） */
    @Volatile
    private var maxX = -1

    /** 触摸面板 Y 坐标最大值（归一化用，非屏幕像素） */
    @Volatile
    private var maxY = -1

    val isRunning: Boolean get() = running

    val maxCoordX: Int get() = maxX
    val maxCoordY: Int get() = maxY

    /**
     * 启动触摸监听。
     *
     * @return 是否成功启动（探测不到触摸设备或 getevent 启动失败返回 false）
     */
    fun start(l: TouchListener): Boolean {
        if (running) return true
        val svc = ShizukuManager.getService() ?: run {
            Log.e(TAG, "Shizuku 未连接，无法启动触摸监听")
            return false
        }

        listener = l

        // 1. 探测触摸设备节点
        val device = detectTouchDevice(svc)
        if (device == null) {
            Log.e(TAG, "探测触摸设备失败，可能 shell 无权限读 /dev/input")
            return false
        }
        touchDevice = device
        Log.i(TAG, "触摸设备：$device")

        // 2. 启动后台 getevent 输出到文件
        try {
            svc.execCommand("rm -f $TOUCH_LOG; nohup getevent -lt $device > $TOUCH_LOG 2>&1 &")
        } catch (e: Throwable) {
            Log.e(TAG, "启动 getevent 失败", e)
            return false
        }

        // 3. 启动轮询协程
        running = true
        job = scope.launch {
            while (running) {
                try {
                    pollAndParse(svc)
                } catch (e: Throwable) {
                    Log.e(TAG, "轮询解析失败", e)
                }
                delay(200)
            }
        }
        Log.i(TAG, "触摸监听已启动")
        return true
    }

    /**
     * 停止触摸监听。
     */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        val svc = ShizukuManager.getService()
        try {
            svc?.execCommand("pkill -f 'getevent -lt'; rm -f $TOUCH_LOG")
        } catch (e: Throwable) {
            Log.w(TAG, "清理 getevent 失败", e)
        }
        listener = null
        touchDevice = null
        Log.i(TAG, "触摸监听已停止")
    }

    /**
     * 探测触摸设备节点：遍历 /dev/input/event*，用 getevent -pl 找含
     * ABS_MT_POSITION 或 touch 的设备。
     */
    private fun detectTouchDevice(svc: com.nexus.agent.userservice.IUserService): String? {
        return try {
            val ls = svc.execCommand("ls /dev/input/event* 2>/dev/null")
            val events = ls.lines().map { it.trim() }.filter { it.startsWith("/dev/input/event") }
            for (event in events) {
                val info = svc.execCommand("getevent -pl $event 2>/dev/null")
                if (info.contains("ABS_MT_POSITION_X", ignoreCase = true) &&
                    info.contains("BTN_TOUCH", ignoreCase = true)
                ) {
                    maxX = extractMax(info, "ABS_MT_POSITION_X")
                    maxY = extractMax(info, "ABS_MT_POSITION_Y")
                    return event
                }
            }
            null
        } catch (e: Throwable) {
            Log.e(TAG, "探测触摸设备异常", e)
            null
        }
    }

    /**
     * 轮询读取日志文件并解析，读完清空文件避免无限增长。
     */
    private fun pollAndParse(svc: com.nexus.agent.userservice.IUserService) {
        val content = try {
            svc.execCommand("tail -n 500 $TOUCH_LOG 2>/dev/null")
        } catch (e: Throwable) {
            return
        }
        // 清空文件（tail 读到的内容已拿到）
        try {
            svc.execCommand(": > $TOUCH_LOG")
        } catch (_: Throwable) {
        }
        if (content.isBlank()) return
        parseLines(content.lines())
    }

    /**
     * 解析 getevent -lt 输出行，识别 BTN_TOUCH DOWN/UP + ABS_MT_POSITION_X/Y。
     */
    private fun parseLines(lines: List<String>) {
        var pendingX = -1
        var pendingY = -1
        val l = listener ?: return
        for (line in lines) {
            when {
                line.contains("ABS_MT_POSITION_X") -> pendingX = parseHex(line)
                line.contains("ABS_MT_POSITION_Y") -> pendingY = parseHex(line)
                line.contains("BTN_TOUCH") && line.contains("DOWN") -> {
                    if (pendingX >= 0 && pendingY >= 0) {
                        Log.i(TAG, "x=$pendingX, y=$pendingY, action=down")
                        l.onTouchDown(pendingX, pendingY)
                    }
                }
                line.contains("BTN_TOUCH") && line.contains("UP") -> {
                    if (pendingX >= 0 && pendingY >= 0) {
                        Log.i(TAG, "x=$pendingX, y=$pendingY, action=up")
                        l.onTouchUp(pendingX, pendingY)
                    }
                }
            }
        }
    }

    /**
     * 提取行尾的十六进制值（getevent 的坐标值是十六进制）。
     */
    private fun parseHex(line: String): Int {
        val parts = line.trim().split(Regex("\\s+"))
        val last = parts.lastOrNull() ?: return -1
        return last.toIntOrNull(16) ?: -1
    }

    /**
     * 从 getevent -pl 输出中提取某字段的 max 值（十进制）。
     */
    private fun extractMax(info: String, field: String): Int {
        val regex = Regex("$field\\s*:.*?max\\s+(\\d+)")
        val m = regex.find(info) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }
}
