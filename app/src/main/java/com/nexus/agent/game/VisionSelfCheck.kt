package com.nexus.agent.game

import android.content.Context
import android.util.Log
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.ScreenCapture
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
}
