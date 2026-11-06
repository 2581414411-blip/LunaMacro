package com.nexus.agent.game

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import com.nexus.agent.agent.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 视觉子进程的客户端（跑在主进程）。
 *
 * 目标只有一个：**主进程永远不因为视觉操作而死**。
 *
 * @see VisionWorkerService
 */
object VisionWorkerClient {

    private const val TAG = "VisionWorker"

    /** 结果：要么拿到文本，要么明确知道子进程死了 */
    sealed class Outcome {
        /**
         * 子进程跑完了（模型有输出 / 明确报错，但进程还活着）。
         *
         * @param text   模型原文；[ok] 为 false 时这里是失败原因
         * @param ok     这次推理是否成功
         * @param reason 失败原因（ok=true 时为 null）
         */
        data class Done(
            val text: String,
            val ok: Boolean = true,
            val reason: String? = null
        ) : Outcome()

        /**
         * 子进程被终止（native 崩溃 / OOM kill / 绑定失败）。
         *
         * 这是好消息里的坏消息：功能没成，但**主进程还活着**，能把原因说出来。
         */
        data class ProcessDied(val reason: String) : Outcome()
    }

    /**
     * 让子进程看一眼**调用方截好的图**，返回模型原文（0.8.12）。
     *
     * 这是聊天读屏的唯一入口：[com.nexus.agent.copilot.PersonaEngine] 只负责截图，
     * 1.4GB 模型的装载与推理全在 `:vision` 里 —— 那里崩了主进程照活。
     *
     * @param shot   主进程截好的图（**所有权仍归调用方**，这里只读不回收）
     * @param prompt 视觉提示词
     * @return [Outcome.Done] 可能是成功也可能是「模型起来了但没输出」；[Outcome.ProcessDied] = 子进程死了
     */
    suspend fun describe(
        context: Context,
        shot: Bitmap,
        prompt: String,
        timeoutMs: Long = DESCRIBE_TIMEOUT_MS
    ): Outcome {
        val app = context.applicationContext
        val shotFile = if (shot.isRecycled) null else writeShot(app, shot)
        if (shotFile == null) {
            return Outcome.Done("截图落盘失败（cacheDir 不可写？）", ok = false, reason = "截图落盘失败")
        }
        Log.i(TAG, "聊天读屏 → 交给子进程：${shotFile.name}（${shot.width}×${shot.height}）")
        val outcome = bindAndRun(
            app = app,
            action = VisionWorkerService.ACTION_DESCRIBE,
            shotFile = shotFile,
            shotError = null,
            prompt = prompt,
            timeoutMs = timeoutMs
        )
        runCatching { shotFile.delete() }
        return withStallInfo(app, outcome)
    }

    /**
     * 子进程死了以后，补一句「死在哪一步」（0.8.13-alpha1）。
     *
     * 光说「子进程被系统终止」没法修 —— 装载死、看图死、纯文本死，三条修法完全不同。
     * [VisionSteps] 是**崩之前**写的，最后一行只有 `▶` 就说明停在那一步。
     */
    private fun withStallInfo(app: Context, outcome: Outcome): Outcome {
        if (outcome !is Outcome.ProcessDied) return outcome
        val step = runCatching { VisionSteps.stalledStep(app) }.getOrNull() ?: return outcome
        Log.w(TAG, "子进程死在：$step（原因：${outcome.reason}）")
        return Outcome.ProcessDied("${outcome.reason}｜死在：$step")
    }

    /**
     * 在子进程里跑一次「截图 + 本地视觉推理」自测。
     *
     * **截图放在主进程做**（0.8.7）：无障碍服务实例只活在主进程，
     * `NexusAccessibilityService.getInstance()` 在 `:vision` 里恒为 null，
     * 子进程自己截图必然报「无法截图」（0.8.6 的现象）。
     * 所以分工改成：**主进程截图 → 写临时 JPEG → 子进程只负责装载与推理**。
     * 危险的活（1.4GB native 加载）仍然留在子进程。
     *
     * @param timeoutMs 总超时（首次要装载 1.3GB 模型，给足 8 分钟）
     */
    suspend fun testInfer(context: Context, timeoutMs: Long = 8 * 60_000L): Outcome {
        val app = context.applicationContext

        // ---- 主进程截图（这一步必须在这里做）----
        val shot = withContext(Dispatchers.IO) {
            runCatching { ScreenCapture.captureBitmap(SHOT_MAX_DIM) }.getOrNull()
        }
        val shotFile = shot?.let { writeShot(app, it) }
        val shotError = when {
            shot == null -> ScreenCapture.lastFailureReason ?: "未知原因"
            shotFile == null -> "截图落盘失败（cacheDir 不可写？）"
            else -> null
        }
        // 自测的图是自己截的，用完就回收
        shot?.let { if (!it.isRecycled) it.recycle() }
        Log.i(
            TAG,
            if (shotFile != null) "主进程截图成功 → ${shotFile.name}，交给子进程推理"
            else "主进程截图失败：$shotError"
        )

        val outcome = bindAndRun(
            app = app,
            action = VisionWorkerService.ACTION_TEST,
            shotFile = shotFile,
            shotError = shotError,
            prompt = null,
            timeoutMs = timeoutMs
        )

        // 结果拿到后清掉临时截图（子进程已经用完）
        runCatching { shotFile?.delete() }
        return outcome
    }

    /**
     * 把截图压成 JPEG 落盘，给子进程读。
     *
     * 压缩质量 85：0.8.10 起截图长边只有 640，用不着 90 那种质量，
     * 降一点能少一截子进程解码时的峰值内存。
     */
    private fun writeShot(context: Context, bitmap: Bitmap): File? = try {
        val file = File(context.cacheDir, "vision_shot_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out)
        }
        file
    } catch (e: Throwable) {
        Log.w(TAG, "截图落盘失败：${e.message}")
        null
    }

    /**
     * 纯文本推理（0.8.13-alpha11）。
     *
     * 副驾在没有截图时也要问本地模型 —— 以前这条走主进程 `inferText`，
     * 被「主进程禁止装载」的闸挡住，于是永远走云端兜底。
     */
    suspend fun inferText(
        context: Context,
        prompt: String,
        timeoutMs: Long = DESCRIBE_TIMEOUT_MS
    ): Outcome {
        val app = context.applicationContext
        Log.i(TAG, "纯文本推理 → 交给子进程（${prompt.length} 字）")
        val outcome = bindAndRun(
            app = app,
            action = VisionWorkerService.ACTION_TEXT,
            shotFile = null,
            shotError = null,
            prompt = prompt,
            timeoutMs = timeoutMs
        )
        return withStallInfo(app, outcome)
    }

    /**
     * 一键预热（0.8.18）：让子进程装载模型并常驻，**不推理**。
     *
     * 首次装载 1.4GB 要 20~60 秒；提前点火后，第一句读屏就不用再等。
     * 超时给 8 分钟 —— 和自测一致，慢设备也够。
     */
    suspend fun warmUp(context: Context, timeoutMs: Long = 8 * 60_000L): Outcome {
        val app = context.applicationContext
        Log.i(TAG, "预热 → 交给子进程（只装载，不推理）")
        val outcome = bindAndRun(
            app = app,
            action = VisionWorkerService.ACTION_WARM,
            shotFile = null,
            shotError = null,
            prompt = null,
            timeoutMs = timeoutMs
        )
        return withStallInfo(app, outcome)
    }

    private suspend fun bindAndRun(
        app: Context,
        action: String,
        shotFile: File?,
        shotError: String?,
        prompt: String?,
        timeoutMs: Long
    ): Outcome =
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Outcome> { cont ->
                    var bound = false
                    var unbindAction: () -> Unit = {}

                    fun finish(outcome: Outcome) {
                        if (cont.isActive) cont.resume(outcome)
                    }

                    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            unbindAction()
                            val ok = resultData?.getBoolean(VisionWorkerService.EXTRA_OK, true) ?: true
                            finish(
                                Outcome.Done(
                                    text = resultData?.getString(VisionWorkerService.RESULT_TEXT).orEmpty(),
                                    ok = ok,
                                    reason = resultData?.getString(VisionWorkerService.EXTRA_REASON)
                                )
                            )
                        }
                    }

                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            bound = true
                            Log.i(TAG, "视觉子进程已连接，投递任务 action=$action")
                            val intent = Intent(app, VisionWorkerService::class.java).apply {
                                this.action = action
                                putExtra(VisionWorkerService.EXTRA_RECEIVER, receiver)
                                shotFile?.let { putExtra(VisionWorkerService.EXTRA_SHOT_PATH, it.absolutePath) }
                                shotError?.let { putExtra(VisionWorkerService.EXTRA_SHOT_ERROR, it) }
                                prompt?.let { putExtra(VisionWorkerService.EXTRA_PROMPT, it) }
                            }
                            runCatching { app.startService(intent) }
                                .onFailure {
                                    unbindAction()
                                    finish(Outcome.ProcessDied("子进程启动失败：${it.message}"))
                                }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            bound = false
                            Log.w(TAG, "视觉子进程断开（多半是 native 崩溃或 OOM 被系统收走）")
                            finish(Outcome.ProcessDied("子进程被系统终止（native 崩溃或内存不足）"))
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            bound = false
                            Log.w(TAG, "视觉子进程 binding died")
                            finish(Outcome.ProcessDied("子进程崩溃（binding died）"))
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            bound = false
                            finish(Outcome.ProcessDied("子进程无法绑定"))
                        }
                    }

                    unbindAction = {
                        if (bound) {
                            bound = false
                            runCatching { app.unbindService(conn) }
                        }
                    }

                    val ok = runCatching {
                        app.bindService(
                            Intent(app, VisionWorkerService::class.java),
                            conn,
                            Context.BIND_AUTO_CREATE
                        )
                    }.getOrDefault(false)
                    if (!ok) finish(Outcome.ProcessDied("绑定视觉子进程失败"))

                    cont.invokeOnCancellation { unbindAction() }
                }
            } ?: Outcome.ProcessDied("等太久没回应（子进程可能卡死在模型装载里）")
        }

    /**
     * 送进模型前的截图最长边（与 [VisionSelfCheck] 保持一致）。
     *
     * 0.8.10 从 1280 降到 640：qwen2-vl 的 `image_size` 是 420，
     * 1280 长边会被切成 4 个左右的 420 瓦片，视觉 token 与中间张量成倍上涨 ——
     * 真机上正是这一跳把进程顶死了。640 只切 1～2 块，界面文字仍看得清。
     */
    private const val SHOT_MAX_DIM = 640

    /** 截图的 JPEG 压缩质量 */
    private const val SHOT_QUALITY = 85

    /**
     * 聊天读屏的等待上限（0.8.12）。
     *
     * 首次要把 1.4GB 模型装进子进程（真机 20~60 秒），之后子进程常驻、推理只要几秒。
     * 给 90 秒：够装载，又不至于让用户觉得「AI 死机了」。
     * 超时按 [Outcome.ProcessDied] 处理 —— 主进程照常说话并提示这轮没看成。
     */
    private const val DESCRIBE_TIMEOUT_MS = 90_000L
}
