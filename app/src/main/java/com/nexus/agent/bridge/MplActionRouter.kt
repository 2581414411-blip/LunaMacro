package com.nexus.agent.bridge

import android.content.Context
import com.nexus.agent.accessibility.NexusAccessibilityService
import com.nexus.agent.agent.tools.OpenAppTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 动作执行器（协议 §5）。
 *
 * ## 0.9.31 只开两个只读动作
 *
 * `read_screen` / `open_app`。会点会输的三个（`click_text` / `input_text` / `run_macro`）
 * 留到 0.9.32 —— 理由不是难写，是**先验证四条腿**：鉴权、幂等、风控、忙闸。
 * 这四条在只读动作上就能压出全部边界，而它们出错的代价远小于"点错一次"。
 *
 * ## 为什么动作都走 Executor 的那套判定，而不是自己判一遍
 *
 * 桥接层是"第二个入口"。它和对话入口必须得出同一个结论，
 * 否则同一件事"跟她说话"不让做、"被 MPL 指挥"却能做 —— 那就等于开了个后门。
 * 所以凡是写动作，最终都要过 [com.nexus.agent.safety.SafetyPolicy.assess]。
 */
object MplActionRouter {

    /** 动作正常结果：data + 可选 warnings */
    data class Outcome(val data: JSONObject, val warnings: List<String> = emptyList())

    suspend fun execute(ctx: Context, req: MplProtocol.Request): Outcome = when (req.action) {
        "read_screen" -> readScreen(ctx, req)
        "open_app" -> openApp(ctx, req)
        else -> throw MplSafety.Denied(
            MplProtocol.Code.UNKNOWN_ACTION,
            "本版本不开放动作「${req.action}」"
        )
    }

    // ---------------------------------------------------------------- read_screen

    private fun readScreen(ctx: Context, req: MplProtocol.Request): Outcome {
        val fg = NexusAccessibilityService.currentForegroundPackage()

        // 敏感界面：ok:true + redacted，**不报错** —— 报错会诱发对面重试
        if (MplSafety.shouldRedact(ctx, fg)) {
            val r = ScreenTextReader.Result.redacted()
            return Outcome(
                JSONObject().apply {
                    put("text", r.text)
                    put("chars", 0)
                    put("truncated", false)
                    put("redacted", true)
                },
                listOf("redacted")
            )
        }

        val service = NexusAccessibilityService.getInstance()
            ?: throw MplSafety.permissionDenied("no_accessibility", "无障碍服务没开，我读不到屏幕")

        val root = service.getRootNode()
            ?: throw MplSafety.permissionDenied("locked", "当前界面拿不到内容（可能锁屏了）")

        val maxChars = (req.args["maxChars"] as? Number)?.toInt() ?: ScreenTextReader.DEFAULT_MAX_CHARS
        val r = ScreenTextReader.read(root, maxChars.coerceIn(200, 8000))

        return Outcome(
            JSONObject().apply {
                put("text", r.text)
                put("chars", r.chars)
                put("truncated", r.truncated)
                put("redacted", false)
            },
            if (r.truncated) listOf("text_truncated") else emptyList()
        )
    }

    // ---------------------------------------------------------------- open_app

    private suspend fun openApp(ctx: Context, req: MplProtocol.Request): Outcome {
        val appName = req.args["appName"]?.toString()?.trim().orEmpty()
        if (appName.isEmpty()) {
            throw MplSafety.Denied(MplProtocol.Code.APP_NOT_FOUND, "缺少 appName")
        }

        val text = withContext(Dispatchers.IO) {
            OpenAppTool.execute(mapOf("appName" to appName))
        }

        return when {
            text.startsWith("成功启动") -> Outcome(
                JSONObject().apply {
                    put("appName", appName)
                    put("result", text)
                }
            )
            // 多个候选：协议里没有「要用户选」这个码，归到 APP_NOT_FOUND 并让他说清
            text.startsWith("找到") -> throw MplSafety.Denied(
                MplProtocol.Code.APP_NOT_FOUND,
                "有好几个应用都叫这个，让他说全名：$text"
            )
            text.startsWith("未找到") -> throw MplSafety.Denied(
                MplProtocol.Code.APP_NOT_FOUND,
                "手机里没有叫「$appName」的应用"
            )
            else -> throw MplSafety.Denied(
                MplProtocol.Code.APP_NOT_FOUND,
                "打开失败：$text"
            )
        }
    }
}
