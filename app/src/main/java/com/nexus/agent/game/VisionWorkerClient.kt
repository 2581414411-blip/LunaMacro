package com.nexus.agent.game

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        data class Done(val text: String) : Outcome()

        /**
         * 子进程被终止（native 崩溃 / OOM kill / 绑定失败）。
         *
         * 这是好消息里的坏消息：功能没成，但**主进程还活着**，能把原因说出来。
         */
        data class ProcessDied(val reason: String) : Outcome()
    }

    /**
     * 在子进程里跑一次「截图 + 本地视觉推理」自测。
     *
     * @param timeoutMs 总超时（首次要装载 1.3GB 模型，给足 8 分钟）
     */
    suspend fun testInfer(context: Context, timeoutMs: Long = 8 * 60_000L): Outcome =
        withContext(Dispatchers.Main) {
            val app = context.applicationContext
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
                            finish(
                                Outcome.Done(
                                    resultData?.getString(VisionWorkerService.RESULT_TEXT).orEmpty()
                                )
                            )
                        }
                    }

                    val conn = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            bound = true
                            Log.i(TAG, "视觉子进程已连接，投递自测任务")
                            val intent = Intent(app, VisionWorkerService::class.java).apply {
                                action = VisionWorkerService.ACTION_TEST
                                putExtra(VisionWorkerService.EXTRA_RECEIVER, receiver)
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
}
