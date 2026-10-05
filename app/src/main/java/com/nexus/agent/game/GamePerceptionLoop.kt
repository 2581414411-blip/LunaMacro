package com.nexus.agent.game

import android.graphics.Bitmap
import android.util.Log
import com.nexus.agent.agent.ScreenCapture

/**
 * 游戏高频感知循环。
 *
 * 独立线程持续截图（Shizuku screencap → 降采样到最长边 720px），
 * 结果放入容量为 3 的环形缓冲区。供规则引擎 / 状态识别按需取帧。
 *
 * 内存约定：环形缓冲区只保留 3 帧，覆盖写入时自动 recycle 被顶掉的旧帧，
 * 避免截图循环跑久了内存泄漏。调用方拿到的 Bitmap 是缓冲区内的引用，
 * 请勿 recycle，否则会破坏缓冲区。
 */
object GamePerceptionLoop {

    private const val TAG = "GamePerception"
    private const val MAX_DIMENSION = 720
    private const val BUFFER_SIZE = 3

    private val buffer = arrayOfNulls<Bitmap>(BUFFER_SIZE)
    private var bufferIndex = 0
    private var frameCount = 0L

    @Volatile
    private var running = false

    @Volatile
    private var fps = 5

    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    /**
     * 启动截图循环。
     *
     * @param fps 帧率（1-10，默认 5），自动 clamp 到合法区间
     */
    @Synchronized
    fun start(fps: Int = 5) {
        if (running) return
        this.fps = fps.coerceIn(1, 10)
        running = true
        val t = Thread {
            while (running) {
                val start = System.currentTimeMillis()
                val bitmap = ScreenCapture.captureBitmap(MAX_DIMENSION)
                if (bitmap != null) {
                    pushFrame(bitmap)
                    DatasetCollector.onFrame(bitmap)
                    frameCount++
                    Log.i(TAG, "fps=${this.fps}, frame=$frameCount, elapsed_ms=${System.currentTimeMillis() - start}")
                } else {
                    Log.w(TAG, "截图失败，跳过本帧")
                }
                // 帧率控制
                val interval = 1000L / this.fps
                val sleepMs = interval - (System.currentTimeMillis() - start)
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }.apply { name = "GamePerceptionLoop" }
        thread = t
        t.start()
        Log.i(TAG, "感知循环启动 fps=${this.fps}")
    }

    /**
     * 停止截图循环并释放缓冲区内存。
     */
    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        synchronized(buffer) {
            for (i in buffer.indices) {
                buffer[i]?.recycle()
                buffer[i] = null
            }
        }
        bufferIndex = 0
        Log.i(TAG, "感知循环已停止")
    }

    /**
     * 获取最新一帧。
     *
     * @return 最新帧 Bitmap（缓冲区引用，勿 recycle）；缓冲区空时返回 null
     */
    fun getLatestFrame(): Bitmap? {
        synchronized(buffer) {
            if (buffer.all { it == null }) return null
            val idx = (bufferIndex - 1 + BUFFER_SIZE) % BUFFER_SIZE
            return buffer[idx]
        }
    }

    /**
     * 按相对序号取帧。
     *
     * @param index 0=最新，1=次新，2=最旧
     * @return 对应帧；越界或未填充返回 null
     */
    fun getFrameAt(index: Int): Bitmap? {
        if (index < 0 || index >= BUFFER_SIZE) return null
        synchronized(buffer) {
            val idx = (bufferIndex - 1 - index + BUFFER_SIZE * 2) % BUFFER_SIZE
            return buffer[idx]
        }
    }

    /**
     * 写入一帧，覆盖旧帧时自动 recycle。
     */
    private fun pushFrame(bitmap: Bitmap) {
        synchronized(buffer) {
            buffer[bufferIndex]?.recycle()
            buffer[bufferIndex] = bitmap
            bufferIndex = (bufferIndex + 1) % BUFFER_SIZE
        }
    }
}
