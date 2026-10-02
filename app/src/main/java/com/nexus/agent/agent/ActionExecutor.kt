package com.nexus.agent.agent

import android.util.Base64
import android.util.Log
import com.nexus.agent.input.ShizukuManager

/**
 * 动作执行器。
 *
 * 把 [ActionParser.Action] 转成实际的系统级操作：
 * - click  → 通过 Shizuku UserService 的 injectTap
 * - swipe  → 通过 Shizuku UserService 的 injectSwipe
 * - input  → 通过 execCommand 执行 `input text` 输入文字
 * - launch → 通过 execCommand 执行 `am start` 启动应用
 * - home   → 通过 execCommand 执行 `input keyevent KEYCODE_HOME`
 * - back   → 通过 execCommand 发送 `input keyevent KEYCODE_BACK`
 * - finish → 无操作
 *
 * 执行结果通过回调返回（成功/失败 + 描述），供主循环记录日志。
 */
object ActionExecutor {

    private const val TAG = "ActionExecutor"

    /** 单步执行结果 */
    data class ExecuteResult(
        val success: Boolean,
        val description: String
    )

    /**
     * 执行一个动作。
     *
     * @param action 待执行的动作
     * @return 执行结果
     */
    fun execute(action: ActionParser.Action): ExecuteResult {
        return when (action.type) {
            "click" -> doClick(action)
            "swipe" -> doSwipe(action)
            "input" -> doInput(action)
            "launch" -> doLaunch(action)
            "home" -> doHome()
            "back" -> doBack()
            "finish" -> ExecuteResult(true, "任务完成：${action.thought}")
            else -> ExecuteResult(false, "未知动作类型: ${action.type}")
        }
    }

    private fun doClick(action: ActionParser.Action): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        return try {
            service.injectTap(action.x, action.y)
            ExecuteResult(true, "点击 (${action.x}, ${action.y})")
        } catch (e: Throwable) {
            Log.e(TAG, "click failed", e)
            ExecuteResult(false, "点击失败: ${e.message}")
        }
    }

    private fun doSwipe(action: ActionParser.Action): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        return try {
            service.injectSwipe(action.x, action.y, action.x2, action.y2, action.duration)
            ExecuteResult(
                true,
                "滑动 (${action.x},${action.y}) → (${action.x2},${action.y2}) ${action.duration}ms"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "swipe failed", e)
            ExecuteResult(false, "滑动失败: ${e.message}")
        }
    }

    private fun doBack(): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        return try {
            service.execCommand("input keyevent KEYCODE_BACK")
            ExecuteResult(true, "返回键")
        } catch (e: Throwable) {
            Log.e(TAG, "back failed", e)
            ExecuteResult(false, "返回键失败: ${e.message}")
        }
    }

    /**
     * 输入文字。前提是当前已有输入框获得焦点。
     *
     * 用 `input text` 注入，中文等特殊字符走 shell 命令可能有编码问题，
     * 因此这里用 base64 包裹再解码，规避 shell 引号/空格/特殊字符转义问题。
     */
    private fun doInput(action: ActionParser.Action): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        val text = action.text
        if (text.isBlank()) {
            return ExecuteResult(false, "输入内容为空")
        }
        return try {
            // 用 base64 传递，避免 shell 特殊字符与空格被拆分
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val output = service.execCommand("echo $encoded | base64 -d | input text")
            ExecuteResult(true, "输入文字：$text${if (output.isNotBlank()) " ($output)" else ""}")
        } catch (e: Throwable) {
            Log.e(TAG, "input failed", e)
            ExecuteResult(false, "输入失败: ${e.message}")
        }
    }

    /**
     * 启动应用。text 为包名。
     */
    private fun doLaunch(action: ActionParser.Action): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        val pkg = action.text.trim()
        if (pkg.isBlank()) {
            return ExecuteResult(false, "启动应用缺少包名")
        }
        return try {
            val output = service.execCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            ExecuteResult(true, "启动应用：$pkg${if (output.isNotBlank()) " ($output)" else ""}")
        } catch (e: Throwable) {
            Log.e(TAG, "launch failed", e)
            ExecuteResult(false, "启动应用失败: ${e.message}")
        }
    }

    private fun doHome(): ExecuteResult {
        val service = ShizukuManager.getService()
        if (service == null) {
            return ExecuteResult(false, "UserService 未绑定")
        }
        return try {
            service.execCommand("input keyevent KEYCODE_HOME")
            ExecuteResult(true, "回到桌面")
        } catch (e: Throwable) {
            Log.e(TAG, "home failed", e)
            ExecuteResult(false, "回到桌面失败: ${e.message}")
        }
    }
}
