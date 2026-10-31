package com.nexus.agent.game

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.ScreenCapture
import com.nexus.agent.copilot.VisionRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读屏自检 —— 一次性回答「它到底能不能看见屏幕」。
 *
 * 背景：用户反馈「读不了屏」已经反复出现过，但代码里能失败的地方有五处
 * （无障碍没连 / 系统拒绝截图 / 截图超时 / 视觉模型没下载 / 云端模型不支持视觉），
 * 隔着屏幕靠猜是猜不准的。这个自检把五处串起来跑一遍，把**结论**给用户，
 * 不用装 adb、不用抓 logcat。
 *
 * 不做的事：不装载 1.3GB 模型（太慢），只 stat 文件；不改任何状态。
 */
object VisionSelfCheck {

    private const val TAG = "VisionSelfCheck"

    private const val SHOT_MAX_DIM = 1280

    data class Report(
        /** 无障碍服务连接状态 */
        val accessibility: String,
        /** 截图结果（成功给尺寸，失败给原因） */
        val shot: String,
        /** 本地视觉模型状态 */
        val localModel: String,
        /** 一句话结论 + 下一步该做什么 */
        val verdict: String
    ) {
        fun toText(): String = buildString {
            appendLine("无障碍服务：$accessibility")
            appendLine("截图：$shot")
            appendLine("本地视觉模型：$localModel")
            appendLine()
            append("结论：$verdict")
        }
    }

    /**
     * 跑一次自检。**必须在 IO 线程调用**（内部会真的截一张图，约 100~3000ms）。
     */
    suspend fun run(context: Context): Report = withContext(Dispatchers.IO) {
        // ---- 1. 无障碍服务 ----
        val service = NexusAccessibilityService.getInstance()
        val accessibility = if (service != null) {
            "已连接"
        } else {
            "未连接 —— 系统设置 → 无障碍 → 宏月，打开开关"
        }

        // ---- 2. 截图 ----
        var shotOk = false
        val shot = try {
            val bmp = ScreenCapture.captureBitmap(SHOT_MAX_DIM)
            if (bmp != null) {
                shotOk = true
                val text = "${bmp.width}×${bmp.height} 截图成功"
                if (!bmp.isRecycled) bmp.recycle()
                text
            } else {
                "失败 —— ${ScreenCapture.lastFailureReason ?: "未知原因（日志里搜 ScreenCapture）"}"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "截图自检异常", e)
            "异常 —— ${e.javaClass.simpleName}: ${e.message.orEmpty().take(60)}"
        }

        // ---- 3. 本地视觉模型（只查文件，不装载）----
        val missing = runCatching { LocalScreenRecognizer.missingModelFiles(context) }
            .getOrElse { listOf("（无法读取模型目录：${it.javaClass.simpleName}）") }
        val localModel = when {
            missing.isEmpty() && LocalScreenRecognizer.isReady() -> "就绪（已装载）"
            missing.isEmpty() -> "文件齐全，但还没装载（第一次读屏时会自动加载，约 10~60 秒）"
            else -> "缺 ${missing.size} 个文件：${missing.joinToString("、")}\n" +
                "　→ 设置 → 下载模型"
        }

        val verdict = when {
            !shotOk -> "截不到屏，读屏一定是坏的。先按「截图」那行提示修无障碍，修好再试。"
            missing.isNotEmpty() -> "能截到屏，但本地视觉模型没下载 —— 装好模型才能真正「看」界面；" +
                "在那之前只能靠云端视觉（需配 qwen-vl 这类支持图片的模型）。"
            LocalScreenRecognizer.state == LocalScreenRecognizer.State.FAILED ->
                "能截到屏、模型文件也齐，但装载失败：${LocalScreenRecognizer.lastError ?: "未知"}。" +
                    "去「导出模型初始化日志」拿完整报告。"
            else -> "截图 + 模型都正常。如果对话里还是答不出屏幕上有什么，" +
                "多半是你配的云端模型不支持图片（deepseek 就是纯文本），本地模型装载完就能读。"
        }

        Report(accessibility, shot, localModel, verdict)
    }

    // ------------------------------------------------------------ 视觉实拍测试（0.8.2）

    /** 「测试视觉模型」的结果 —— 直接把模型原文糊给用户看，不加工、不总结 */
    data class InferReport(
        val ok: Boolean,
        val elapsedMs: Long,
        /** 这一轮的路由结论（和对话里同格式） */
        val route: String,
        /** 截图情况（成功给尺寸，失败给原因） */
        val shotInfo: String,
        /** 成功 = 模型原文；失败 = 失败原因 */
        val body: String,
        /** 失败时给一句「怎么修」 */
        val hint: String = ""
    ) {
        fun toText(): String = buildString {
            appendLine(if (ok) "✅ 本地视觉模型有输出" else "❌ 本地视觉模型没看成")
            appendLine("耗时：${"%.1f".format(elapsedMs / 1000f)} 秒")
            appendLine("路由：$route")
            appendLine("截图：$shotInfo")
            appendLine()
            appendLine(if (ok) "—— 模型原文 ——" else "—— 失败原因 ——")
            append(body.trim())
            if (hint.isNotBlank()) {
                appendLine()
                appendLine()
                append("怎么修：$hint")
            }
        }
    }

    /**
     * 端到端实拍：**截图 → 本地视觉模型 → 原样返回模型输出**。
     *
     * 和 [run] 的区别：那个只查环境（不装载、不推理，怕用户等），这个真的跑一遍
     * 1.3GB 模型。所以它是唯一能证明「本地视觉到底能不能用」的入口 ——
     * 对话里读不了屏时，先用它把「模型不行」和「路由不行」分开。
     *
     * **必须在 IO 线程调用**：首次会装载模型（数十秒）。
     */
    suspend fun testInfer(context: Context): InferReport = withContext(Dispatchers.IO) {
        val started = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - started

        // ---- 1. 截图 ----
        val bmp = try {
            ScreenCapture.captureBitmap(SHOT_MAX_DIM)
        } catch (e: Throwable) {
            Log.e(TAG, "实拍测试截图异常", e)
            null
        }
        if (bmp == null) {
            val reason = ScreenCapture.lastFailureReason ?: "未知原因"
            return@withContext InferReport(
                ok = false,
                elapsedMs = elapsed(),
                route = VisionRoute.logShotFailed(reason),
                shotInfo = "失败：$reason",
                body = "截不到屏幕，视觉测试无法进行。",
                hint = "先在首页开启无障碍服务（或连上 Shizuku），拿到截图再回来测。"
            )
        }
        val sizeText = "${bmp.width}×${bmp.height}"
        Log.i(TAG, "[实拍] 截图成功 $sizeText，开始装载/推理")

        // ---- 2. 模型文件 ----
        val missing = runCatching { LocalScreenRecognizer.missingModelFiles(context) }
            .getOrElse { listOf("（读不到模型目录：${it.javaClass.simpleName}）") }
        if (missing.isNotEmpty()) {
            if (!bmp.isRecycled) bmp.recycle()
            VisionRoute.logLocalNotReady(
                LocalScreenRecognizer.State.NOT_DOWNLOADED,
                "缺 ${missing.size} 个文件"
            )
            return@withContext InferReport(
                ok = false,
                elapsedMs = elapsed(),
                route = VisionRoute.routeLine("NOT_DOWNLOADED", true, "CloudText"),
                shotInfo = "成功 $sizeText",
                body = "本地视觉模型文件不全，缺 ${missing.size} 个：\n${missing.joinToString("、")}",
                hint = "设置 → 下载模型，装完再点一次。"
            )
        }

        // ---- 3. 装载 ----
        val state = runCatching { LocalScreenRecognizer.ensureLoaded(context) }
            .getOrElse {
                if (!bmp.isRecycled) bmp.recycle()
                return@withContext InferReport(
                    ok = false,
                    elapsedMs = elapsed(),
                    route = VisionRoute.routeLine("FAILED", true, "CloudText"),
                    shotInfo = "成功 $sizeText",
                    body = "装载模型时异常：${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}",
                    hint = "设置 → 导出模型初始化日志，把报告发出来。"
                )
            }
        if (state != LocalScreenRecognizer.State.READY) {
            if (!bmp.isRecycled) bmp.recycle()
            val why = LocalScreenRecognizer.lastError ?: "未知"
            VisionRoute.logLocalNotReady(state, why)
            return@withContext InferReport(
                ok = false,
                elapsedMs = elapsed(),
                route = VisionRoute.routeLine(VisionRoute.stateLabel(state), true, "CloudText"),
                shotInfo = "成功 $sizeText",
                body = "模型装载失败：$why",
                hint = "设置 → 导出模型初始化日志（里面有每个 so 的真实加载异常）。"
            )
        }

        // ---- 4. 真推理 ----
        VisionRoute.log(
            LocalScreenRecognizer.State.READY,
            hasShot = true,
            choice = VisionRoute.Choice.LOCAL,
            detail = "视觉自测：截图 $sizeText"
        )
        val raw = try {
            LocalScreenRecognizer.infer(bmp, VisionRoute.SCREEN_DESCRIBE_PROMPT)
        } catch (e: Throwable) {
            Log.e(TAG, "[实拍] 推理异常", e)
            null
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
        }

        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            val why = LocalScreenRecognizer.lastInferError ?: "推理返回空"
            VisionRoute.logLocalVisionFailed(why)
            return@withContext InferReport(
                ok = false,
                elapsedMs = elapsed(),
                route = VisionRoute.routeLine("READY", true, "CloudText"),
                shotInfo = "成功 $sizeText",
                body = "模型装载成功、截图也拿到了，但推理没输出：\n$why",
                hint = "多半是显存不够或 native 内部错误。先杀掉后台大应用再试；" +
                    "还不行就导出模型初始化日志。"
            )
        }

        Log.i(TAG, "[实拍] 成功，输出 ${text.length} 字，耗时 ${elapsed()}ms")
        InferReport(
            ok = true,
            elapsedMs = elapsed(),
            route = VisionRoute.routeLine("READY", true, "Local"),
            shotInfo = "成功 $sizeText",
            body = text
        )
    }
}
