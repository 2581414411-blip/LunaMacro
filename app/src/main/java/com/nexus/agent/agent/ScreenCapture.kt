package com.nexus.agent.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.input.ShizukuManager
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 屏幕截图工具。
 *
 * 双通道截图，优先无障碍、fallback Shizuku：
 * 1. **无障碍 `takeScreenshot`**（Android 11+ / API 30+，无需 Shizuku，门槛低）
 * 2. **Shizuku `screencap`**（通用，需 shell 权限；API<30 或无障碍截图失败时兜底）
 *
 * 通过 Shizuku 的 [IUserService.execCommand]（运行在 shell UID 2000 进程）
 * 执行 `screencap -p` 命令，将 PNG 输出重定向到临时文件，再读回为 [Bitmap]，
 * 可按需压缩为 JPEG 并转 Base64。
 *
 * ## 关键：临时文件必须写到 /data/local/tmp
 *
 * shell 进程（UID 2000）**没有权限写应用私有目录**（如 cacheDir，位于
 * /data/user/0/<pkg>/ 下，属主是 app 自身 UID）。若把 screencap 的输出
 * 重定向到 cacheDir，会因无写权限导致文件为空。因此这里统一写到
 * `/data/local/tmp/`（属主 shell，app 可读），文件名带唯一后缀避免冲突。
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    /** 截图临时目录（shell 用户可写、app 可读） */
    private const val TEMP_DIR = "/data/local/tmp"

    /** 截图临时文件名前缀 */
    private const val TEMP_FILE_PREFIX = "nexus_screencap"

    /** JPEG 压缩质量（0~100） */
    private const val JPEG_QUALITY = 80

    /** 截图最长边缩放目标（像素），控制 Base64 体积 */
    private const val MAX_DIMENSION = 1280

    /**
     * 最近一次截图失败的原因（成功则为 null）。
     *
     * 对话链路会把它拼进气泡（「本轮没截到屏幕——无障碍没有截图权限」），
     * 用户不用连电脑抓 logcat 也能判断该修哪一环。
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /**
     * 截取当前屏幕并返回降采样后的 [Bitmap]。
     *
     * 高分辨率屏（如 iQOO 12 的 2800×1260）全尺寸截图会占用大量内存，
     * 且转 Base64 后体积巨大（几十 MB），容易撑爆内存或拖慢 LLM 请求。
     * 因此这里将最长边缩放到 [MAX_DIMENSION]，控制在可接受范围。
     *
     * 优先走无障碍截图（免 Shizuku），失败时回退 Shizuku screencap。
     *
     * @return 截图 Bitmap；失败返回 null（原因见 [lastFailureReason]）
     */
    fun captureBitmap(maxDimension: Int = MAX_DIMENSION): Bitmap? {
        // 优先无障碍截图（API 30+，无需 Shizuku）
        val viaAccessibility = captureViaAccessibility(maxDimension)
        if (viaAccessibility != null) {
            lastFailureReason = null
            return viaAccessibility
        }
        val accReason = accessibilityFailure ?: "无障碍服务未连接"

        // fallback Shizuku screencap
        val viaShizuku = captureViaShizuku(maxDimension)
        if (viaShizuku != null) {
            lastFailureReason = null
            return viaShizuku
        }
        val shizukuReason = shizukuFailure ?: "Shizuku 未连接"

        lastFailureReason = "无障碍：$accReason；Shizuku：$shizukuReason"
        Log.w(TAG, "截图失败 —— $lastFailureReason")
        return null
    }

    /** 最近一次无障碍截图失败原因（成功则清空） */
    @Volatile
    private var accessibilityFailure: String? = null

    /** 最近一次 Shizuku 截图失败原因 */
    @Volatile
    private var shizukuFailure: String? = null

    /**
     * 无障碍截图路径：Android 11+ 且无障碍服务已连接时可用。
     */
    private fun captureViaAccessibility(maxDimension: Int): Bitmap? {
        val service = NexusAccessibilityService.getInstance()
        if (service == null) {
            accessibilityFailure = "无障碍服务未连接（去系统设置里打开宏月的无障碍）"
            return null
        }
        return try {
            val bmp = service.captureScreen(maxDimension)
            accessibilityFailure = if (bmp == null) {
                service.lastCaptureError ?: "系统拒绝截图"
            } else {
                null
            }
            bmp
        } catch (e: Throwable) {
            Log.e(TAG, "captureViaAccessibility failed", e)
            accessibilityFailure = "无障碍截图异常：${e.javaClass.simpleName}"
            null
        }
    }

    /**
     * Shizuku screencap 路径（通用，需 shell 权限）。
     */
    private fun captureViaShizuku(maxDimension: Int): Bitmap? {
        val file = captureToFile() ?: return null
        return try {
            val raw = BitmapFactory.decodeFile(file.absolutePath)
            if (raw == null) {
                shizukuFailure = "screencap 产物解码失败"
                return null
            }
            shizukuFailure = null
            scaleDown(raw, maxDimension)
        } catch (e: Throwable) {
            Log.e(TAG, "decode screenshot bitmap failed", e)
            shizukuFailure = "解码截图异常：${e.javaClass.simpleName}"
            null
        } finally {
            // 用 shell 进程删除文件：app 进程（不同 UID）对 /data/local/tmp
            // 无写权限，file.delete() 会静默失败，必须让 shell 自己 rm。
            deleteTempFile(file)
        }
    }

    /**
     * 将 Bitmap 按最长边缩放到 [maxDimension]（若已小于则原样返回）。
     */
    private fun scaleDown(src: Bitmap, maxDimension: Int): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= maxDimension) return src

        val ratio = maxDimension.toFloat() / maxEdge
        val newW = (src.width * ratio).toInt().coerceAtLeast(1)
        val newH = (src.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, newW, newH, true)
        if (scaled !== src) {
            src.recycle()
        }
        return scaled
    }

    /**
     * 截取当前屏幕并返回 JPEG 压缩后的 Base64 字符串。
     *
     * @return Base64 字符串（不含 data URI 前缀）；失败返回 null
     */
    fun captureBase64(): String? {
        val bitmap = captureBitmap() ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            stream.close()
            Log.d(TAG, "captureBase64: ${bitmap.width}x${bitmap.height} -> ${bytes.size} bytes")
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.e(TAG, "encode screenshot to base64 failed", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 截图结果：Base64 + 降采样后的实际宽高。
     *
     * 视觉模式需要用它把「降采样截图上的坐标」换算回「真实屏幕坐标」。
     */
    data class CaptureResult(val base64: String, val width: Int, val height: Int)

    /**
     * 截取当前屏幕并返回 Base64 + 降采样后的宽高。
     *
     * @return 失败返回 null
     */
    fun captureBase64WithSize(): CaptureResult? {
        val bitmap = captureBitmap() ?: return null
        return try {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            val bytes = stream.toByteArray()
            stream.close()
            val w = bitmap.width
            val h = bitmap.height
            CaptureResult(Base64.encodeToString(bytes, Base64.NO_WRAP), w, h)
        } catch (e: Throwable) {
            Log.e(TAG, "encode screenshot to base64 failed", e)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 执行 `screencap -p` 并将输出写入临时文件。
     *
     * 实现：调用 Shizuku UserService 的 execCommand，把 PNG 二进制
     * 重定向到 /data/local/tmp 下的唯一临时文件。由于 execCommand 返回
     * 字符串（stdout/stderr 文本），二进制 PNG 不能经 stdout 文本透传，
     * 因此这里采用「输出重定向到文件」的方式。
     *
     * @return 写入成功的临时文件；失败返回 null
     */
    private fun captureToFile(): File? {
        val service = ShizukuManager.getService() ?: run {
            Log.e(TAG, "UserService not bound, cannot capture")
            shizukuFailure = "Shizuku 未连接"
            return null
        }

        // 唯一文件名，避免并发 / 残留冲突
        val file = File(TEMP_DIR, "${TEMP_FILE_PREFIX}_${System.currentTimeMillis()}.png")

        return try {
            val command = "screencap -p ${file.absolutePath}"
            val output = service.execCommand(command)
            Log.d(TAG, "screencap output: $output")

            if (file.exists() && file.length() > 0) {
                file
            } else {
                Log.e(TAG, "screencap failed, file empty or missing: ${file.absolutePath}")
                shizukuFailure = "screencap 没写出文件"
                deleteTempFile(file)
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "screencap failed", e)
            shizukuFailure = "screencap 执行异常：${e.javaClass.simpleName}"
            deleteTempFile(file)
            null
        }
    }

    /**
     * 通过 shell 进程删除临时文件。
     *
     * 注意：/data/local/tmp 目录为 `drwxrwx--x`，只有 shell 用户及其 group
     * 有写权限。app 进程（非 shell UID）无法删除该目录下 shell 创建的文件，
     * 因此必须调用 UserService.execCommand("rm -f ...") 让 shell 进程删除。
     */
    private fun deleteTempFile(file: File) {
        try {
            val service = ShizukuManager.getService()
            if (service != null) {
                service.execCommand("rm -f ${file.absolutePath}")
            } else {
                // 兜底：即使失败也无妨，残留文件会在下次截图时被新文件名避开
                file.delete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "deleteTempFile failed", e)
        }
    }
}
