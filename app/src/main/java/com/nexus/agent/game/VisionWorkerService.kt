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

        /**
         * 聊天读屏（0.8.12）：主进程截好图 → 子进程装载 + 推理 → 回传模型原文。
         *
         * 和 [ACTION_TEST] 的区别是**不格式化报告、跑完不停服务**：
         * 模型留在子进程里常驻，下一轮读屏不用再装载 1.4GB。
         */
        const val ACTION_DESCRIBE = "com.nexus.agent.action.VISION_DESCRIBE"

        /**
         * 纯文本推理（0.8.13-alpha11）：副驾在没有截图时也要问本地模型。
         *
         * 以前这条走主进程的 `LocalScreenRecognizer.inferText`，同样被闸挡住了。
         */
        const val ACTION_TEXT = "com.nexus.agent.action.VISION_TEXT"

        /**
         * 一键预热（0.8.18）：**只装载、不推理**。
         *
         * 装载 1.4GB 权重要 20~60 秒，以前这段时间总是藏在「用户说完第一句话之后」，
         * 体感就是「第一句读屏像死机」。现在用户可以先点一下预热，把这几十秒挪到前面。
         * 装载完成后按常规排空闲计时（10 分钟无任务才释放）。
         */
        const val ACTION_WARM = "com.nexus.agent.action.VISION_WARM"

        /** 结果回传用的 ResultReceiver */
        const val EXTRA_RECEIVER = "receiver"

        /** 主进程截好的图（JPEG 绝对路径）。子进程没有无障碍服务，自己截不到屏 */
        const val EXTRA_SHOT_PATH = "shot_path"

        /** 主进程截图失败的原因（子进程拿不到 ScreenCapture 的静态状态） */
        const val EXTRA_SHOT_ERROR = "shot_error"

        /** 读屏用的视觉提示词（[ACTION_DESCRIBE]） */
        const val EXTRA_PROMPT = "prompt"

        /** 结果文本（已格式化的弹窗内容 / 模型原文） */
        const val RESULT_TEXT = "text"

        /** 本次是否成功（缺省 true，兼容老的自测路径） */
        const val EXTRA_OK = "ok"

        /** 失败原因（[EXTRA_OK] 为 false 时给，主进程据此写日志/提示） */
        const val EXTRA_REASON = "reason"

        /** 跑模型装载的线程栈大小：默认 ~1MB 对 MNN 加载 2B 模型偏小 */
        private const val HEAVY_STACK_BYTES = 16L * 1024 * 1024

        /** 读屏的兜底提示词（正常由主进程传，这里只防呆） */
        private const val DEFAULT_DESCRIBE =
            "请用两三句中文描述这张手机截图里是什么应用、主要内容是什么。不要输出 JSON。"

        /**
         * 空闲多久自动卸载模型并退出（0.8.13-alpha11）。
         *
         * 用户的要求：**模型常驻，但别永远占着 1.4GB**。
         * 以前只有自测路径 `stopSelf()`、聊天路径永不退出 —— 两种极端都不对：
         * 自测完立刻释放 → 下次聊天要重装 3 秒；永不退出 → 内存紧张时被系统整段收走，
         * 反而丢得更难看。现在统一成「跑完计时 10 分钟，没新任务才释放」。
         */
        private const val IDLE_STOP_MS = 10 * 60_000L
    }

    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var messenger: Messenger

    /** 空闲释放：任务结束才排，来新任务就取消 */
    private val idleStop = Runnable {
        Log.i(TAG, "空闲 ${IDLE_STOP_MS / 60_000} 分钟无任务，释放模型并退出子进程")
        stopSelf()
    }

    private fun cancelIdleStop() {
        if (::worker.isInitialized) worker.removeCallbacks(idleStop)
    }

    private fun scheduleIdleStop() {
        if (!::worker.isInitialized) return
        worker.removeCallbacks(idleStop)
        worker.postDelayed(idleStop, IDLE_STOP_MS)
        Log.i(TAG, "模型常驻，${IDLE_STOP_MS / 60_000} 分钟无新任务后自动释放")
    }

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

        // 新任务来了：取消空闲释放计时
        cancelIdleStop()

        when (intent?.action) {
            ACTION_TEST -> startSelfTest(receiver, shotPath, shotError)
            ACTION_DESCRIBE -> startDescribe(
                receiver,
                shotPath,
                shotError,
                intent.getStringExtra(EXTRA_PROMPT)
            )
            ACTION_TEXT -> startText(receiver, intent.getStringExtra(EXTRA_PROMPT))
            ACTION_WARM -> startWarm(receiver)
            else -> Log.w(TAG, "未知 action：${intent?.action}")
        }
        return START_NOT_STICKY
    }

    /**
     * 纯文本推理（副驾在没有截图时的本地通道）。
     *
     * 和 [startDescribe] 一样常驻，跑完只排空闲释放。
     */
    private fun startText(receiver: ResultReceiver?, prompt: String?) {
        val p = prompt?.takeIf { it.isNotBlank() }
        if (p == null) {
            reply(receiver, ok = false, text = "缺少 prompt", reason = "缺少 prompt")
            scheduleIdleStop()
            return
        }
        Log.i(TAG, "收到纯文本推理请求（${p.length} 字）")
        val heavy = Thread(
            null,
            Runnable {
                var ok = false
                var text = ""
                var reason: String? = null
                try {
                    runBlocking {
                        val state = LocalScreenRecognizer.ensureLoaded(applicationContext)
                        if (state != LocalScreenRecognizer.State.READY) {
                            reason = LocalScreenRecognizer.lastError ?: "模型装载未完成（$state）"
                        } else {
                            val out = LocalScreenRecognizer.inferText(p)
                            if (out.isNullOrBlank()) {
                                reason = LocalScreenRecognizer.lastInferError ?: "推理返回空"
                            } else {
                                text = out
                                ok = true
                            }
                        }
                    }
                } catch (e: Throwable) {
                    reason = "子进程内部异常：${e.javaClass.simpleName}: " +
                        "${e.message.orEmpty().take(120)}"
                }
                reply(receiver, ok = ok, text = text, reason = reason)
                Log.i(TAG, if (ok) "纯文本推理完成 ${text.length} 字" else "纯文本推理失败：$reason")
                scheduleIdleStop()
            },
            "vision-heavy",
            HEAVY_STACK_BYTES
        )
        heavy.start()
    }

    /**
     * 一键预热：把模型装载进本进程并常驻，**不做推理**。
     *
     * 存在的意义是把「装载 1.4GB」这 20~60 秒从用户的第一句话里挪到前面。
     * 装载成功后排空闲计时，10 分钟内有人调用就直接推理。
     */
    private fun startWarm(receiver: ResultReceiver?) {
        Log.i(TAG, "收到预热请求：只装载模型，不推理")
        val t0 = System.currentTimeMillis()
        val heavy = Thread(
            null,
            Runnable {
                var ok = false
                var text = ""
                var reason: String? = null
                try {
                    runBlocking {
                        VisionSteps.start(applicationContext, "预热：装载模型")
                        val state = LocalScreenRecognizer.ensureLoaded(applicationContext)
                        if (state == LocalScreenRecognizer.State.READY) {
                            val cost = (System.currentTimeMillis() - t0) / 1000
                            ok = true
                            text = "模型就绪（${cost}s），常驻中"
                            VisionSteps.ok(applicationContext, "预热：装载模型", "${cost}s")
                        } else {
                            reason = LocalScreenRecognizer.lastError ?: "装载未完成（$state）"
                            VisionSteps.fail(applicationContext, "预热：装载模型", reason!!)
                        }
                    }
                } catch (e: Throwable) {
                    reason = "预热异常：${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
                }
                reply(receiver, ok = ok, text = text, reason = reason)
                Log.i(TAG, if (ok) "预热完成：$text" else "预热失败：$reason")
                scheduleIdleStop()
            },
            "vision-heavy",
            HEAVY_STACK_BYTES
        )
        heavy.start()
    }

    /** 自测：跑一遍完整报告（[VisionSelfCheck.testInfer]），跑完停服务 */
    private fun startSelfTest(receiver: ResultReceiver?, shotPath: String?, shotError: String?) {
        Log.i(
            TAG,
            "收到视觉自测请求（截图=${shotPath?.takeLast(24) ?: "无"}），开始执行（本进程崩了不影响主进程）"
        )

        // 0.8.8：模型装载 = 深递归 + 大内存，Android 新线程默认栈只有 ~1MB，
        // 栈溢出同样表现为 SIGSEGV。这里单独开一条 16MB 栈的线程跑危险活，
        // 不再借用 HandlerThread（它的栈大小不可控）。
        val heavy = Thread(
            null,
            Runnable {
                val text = try {
                    runBlocking {
                        VisionSelfCheck.testInfer(applicationContext, decode(shotPath), shotError)
                    }.toText()
                } catch (e: Throwable) {
                    "视觉子进程内部异常：${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}"
                }
                runCatching { shotPath?.let { File(it).delete() } }
                reply(receiver, ok = true, text = text)
                Log.i(TAG, "视觉自测结束，已回传 ${text.length} 字")
                // 0.8.13-alpha11：不再 stopSelf。自测完立刻释放模型，
                // 用户紧接着去聊天又要重装 3 秒 —— 统一改成空闲 10 分钟再释放。
                scheduleIdleStop()
            },
            "vision-heavy",
            HEAVY_STACK_BYTES
        )
        heavy.start()
    }

    /**
     * 聊天读屏：装载（首次）+ 推理，回传**模型原文**。
     *
     * 跑完**不 stopSelf**：子进程常驻，模型留在内存里，下一轮读屏直接推理。
     * 系统内存吃紧时它会被单独收走 —— 那正是我们要的隔离效果。
     */
    private fun startDescribe(
        receiver: ResultReceiver?,
        shotPath: String?,
        shotError: String?,
        prompt: String?
    ) {
        val bmp = decode(shotPath)
        if (bmp == null) {
            val why = shotError ?: "主进程传来的截图解码失败"
            Log.w(TAG, "读屏请求没有可用截图：$why")
            reply(receiver, ok = false, text = why, reason = why)
            return
        }
        Log.i(TAG, "收到读屏请求（${bmp.width}×${bmp.height}），开始装载/推理")

        val heavy = Thread(
            null,
            Runnable {
                var ok = false
                var text = ""
                var reason: String? = null
                try {
                    runBlocking {
                        VisionSteps.start(applicationContext, "读屏①装载模型")
                        val state = LocalScreenRecognizer.ensureLoaded(applicationContext)
                        if (state != LocalScreenRecognizer.State.READY) {
                            reason = LocalScreenRecognizer.lastError ?: "模型装载未完成（$state）"
                            VisionSteps.fail(applicationContext, "读屏①装载模型", reason!!)
                        } else {
                            VisionSteps.ok(applicationContext, "读屏①装载模型")
                            VisionSteps.start(applicationContext, "读屏②看图推理")
                            val out = LocalScreenRecognizer.infer(
                                bmp,
                                prompt?.takeIf { it.isNotBlank() } ?: DEFAULT_DESCRIBE
                            )
                            if (out.isNullOrBlank()) {
                                reason = LocalScreenRecognizer.lastInferError ?: "推理返回空"
                                VisionSteps.fail(applicationContext, "读屏②看图推理", reason!!)
                            } else {
                                text = out
                                ok = true
                                VisionSteps.ok(applicationContext, "读屏②看图推理", "${out.length} 字")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    reason = "子进程内部异常：${e.javaClass.simpleName}: " +
                        "${e.message.orEmpty().take(120)}"
                    VisionSteps.fail(applicationContext, "读屏（异常）", reason!!)
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                    runCatching { shotPath?.let { File(it).delete() } }
                }
                reply(receiver, ok = ok, text = text, reason = reason)
                Log.i(
                    TAG,
                    if (ok) "读屏完成，回传 ${text.length} 字（模型留在子进程，下次不用重装）"
                    else "读屏失败：$reason"
                )
                // 模型常驻，但排一个 10 分钟的空闲释放
                scheduleIdleStop()
            },
            "vision-heavy",
            HEAVY_STACK_BYTES
        )
        heavy.start()
    }

    /** 回传结果（text / ok / reason 三件套） */
    private fun reply(
        receiver: ResultReceiver?,
        ok: Boolean,
        text: String,
        reason: String? = null
    ) {
        runCatching {
            receiver?.send(
                Activity.RESULT_OK,
                Bundle().apply {
                    putString(RESULT_TEXT, text)
                    putBoolean(EXTRA_OK, ok)
                    if (reason != null) putString(EXTRA_REASON, reason)
                }
            )
        }
    }

    /** 主进程传来的截图（解码失败返回 null，交给 testInfer 报「截不到屏」） */
    private fun decode(path: String?): android.graphics.Bitmap? {
        if (path == null) return null
        return runCatching { BitmapFactory.decodeFile(path) }
            .onFailure { Log.w(TAG, "截图解码异常：${it.message}") }
            .getOrNull()
            ?: run {
                Log.w(TAG, "主进程传来的截图解码失败：$path")
                null
            }
    }

    override fun onDestroy() {
        runCatching { thread.quitSafely() }
        Log.i(TAG, "视觉子进程退出")
        super.onDestroy()
    }
}
