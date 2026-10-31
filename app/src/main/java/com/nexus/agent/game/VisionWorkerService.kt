package com.nexus.agent.game

import android.app.Activity
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Messenger
import android.os.ResultReceiver
import android.util.Log
import com.nexus.agent.input.ShizukuManager
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 视觉子进程（`:vision`）里的执行者。
 *
 * ## 为什么要单独开一个进程
 *
 * 「点测试视觉模型 → 直接弹回桌面」这个现象查到最后，证据都指向同一件事：
 * **进程被干掉了**，而不是 Java 抛异常（CrashHandler 一个字节都没写出来）。
 * 能这样无声杀进程的只有两类：
 *  - native 崩溃：`libllm.so` 加载 1.4GB 权重时 SIGSEGV / SIGBUS；
 *  - 内存不足：1.4GB 权重 + KV cache + 视觉编码器，被系统 OOM Killer 直接收走。
 *
 * 这两类都没法在 Java 层用 try/catch 兜住。**唯一可靠的解法就是"别把鸡蛋放在一个篮子里"**：
 * 把这段危险操作搬进独立进程，它死了主进程照样活着，还能把死因告诉你。
 *
 * ## 通信
 *
 * 极简：`bindService` 负责"感知子进程死活"（死了解绑回调会触发），
 * `startService` 负责投递任务，结果通过 [ResultReceiver] 回传（Parcelable，跨进程可用）。
 * 不引入 AIDL，够用且不容易写错。
 */
class VisionWorkerService : Service() {

    companion object {
        private const val TAG = "VisionWorker"

        /** 执行一次「截图 + 本地视觉推理」自测 */
        const val ACTION_TEST = "com.nexus.agent.action.VISION_TEST"

        /** 结果回传用的 ResultReceiver */
        const val EXTRA_RECEIVER = "receiver"

        /** 主进程截好的图（JPEG 绝对路径）。子进程没有无障碍服务，自己截不到屏 */
        const val EXTRA_SHOT_PATH = "shot_path"

        /** 主进程截图失败的原因（子进程拿不到 ScreenCapture 的静态状态） */
        const val EXTRA_SHOT_ERROR = "shot_error"

        /** 结果文本（已格式化的弹窗内容） */
        const val RESULT_TEXT = "text"
    }

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("vision-worker").also { it.start() }
        worker = Handler(thread.looper)
        messenger = Messenger(worker)
        // 子进程里 NexusApplication 会提前 return，ShizukuManager 的 appContext 是空的；
        // LocalScreenRecognizer 落临时图要用它取 cacheDir，这里补上（只存引用，不干别的）。
        runCatching { ShizukuManager.init(applicationContext) }
        Log.i(TAG, "视觉子进程已启动 pid=${android.os.Process.myPid()}")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        @Suppress("DEPRECATION")
        val receiver = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RECEIVER, ResultReceiver::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RECEIVER)
        }
        val shotPath = intent?.getStringExtra(EXTRA_SHOT_PATH)
        val shotError = intent?.getStringExtra(EXTRA_SHOT_ERROR)

        if (intent?.action == ACTION_TEST) {
            Log.i(
                TAG,
                "收到视觉自测请求（截图=${shotPath?.takeLast(24) ?: "无"}），开始执行（本进程崩了不影响主进程）"
            )
            worker.post {
                // 截图由主进程提供：无障碍服务实例只存在于主进程
                val shot = shotPath?.let { path ->
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                }
                if (shotPath != null && shot == null) {
                    Log.w(TAG, "主进程传来的截图解码失败：$shotPath")
                }
                val text = try {
                    runBlocking {
                        VisionSelfCheck.testInfer(applicationContext, shot, shotError)
                    }.toText()
                } catch (e: Throwable) {
                    "视觉子进程内部异常：${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}"
                }
                runCatching { shotPath?.let { File(it).delete() } }
                runCatching {
                    receiver?.send(Activity.RESULT_OK, Bundle().apply { putString(RESULT_TEXT, text) })
                }
                Log.i(TAG, "视觉自测结束，已回传 ${text.length} 字")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { thread.quitSafely() }
        Log.i(TAG, "视觉子进程退出")
        super.onDestroy()
    }
}
