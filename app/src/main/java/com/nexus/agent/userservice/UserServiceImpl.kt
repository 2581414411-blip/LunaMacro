package com.nexus.agent.userservice

import android.util.Log
import com.nexus.agent.input.ShizukuInputInjector
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Nexus Agent 的 Shizuku UserService 实现。
 *
 * ## 运行环境（重要）
 *
 * 这个类运行在 **Shizuku 的 shell 进程**（UID 2000，即 shell 用户）中，
 * 而不是应用的普通进程。因此它拥有比普通应用高得多的系统权限：
 * - 可以直接注入全局输入事件（InputManager.injectInputEvent）
 * - 可以以 shell 权限执行系统命令（Runtime.exec）
 *
 * ## Shizuku 13.x 的绑定方式
 *
 * UserService 直接继承 AIDL 生成的 [IUserService.Stub]，由 Shizuku 服务端
 * 拉起并管理其生命周期。宿主应用通过 `Shizuku.bindUserService()` 绑定后，
 * 拿到本 Stub 的代理并调用其中的方法。
 *
 * 注意：不要继承 [rikka.shizuku.ShizukuBinderWrapper]——那是「远程 binder
 * 直调」场景（如反射调 IInputManager）用的包装器，不是 UserService 基类。
 */
class UserServiceImpl : IUserService.Stub() {

    companion object {
        private const val TAG = "UserServiceImpl"
    }

    override fun injectTap(x: Float, y: Float) {
        Log.e(TAG, "injectTap($x, $y) [shell UID 2000]")
        ShizukuInputInjector.injectTap(x, y)
    }

    override fun injectSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Int
    ) {
        Log.d(TAG, "injectSwipe($x1,$y1 -> $x2,$y2, ${duration}ms) [shell UID 2000]")
        ShizukuInputInjector.injectSwipe(x1, y1, x2, y2, duration)
    }

    override fun execCommand(command: String): String {
        Log.d(TAG, "execCommand: $command [shell UID 2000]")
        return runShellCommand(command)
    }

    /**
     * 通过 Runtime.exec 执行 shell 命令并返回合并后的 stdout/stderr。
     * 运行在 shell 权限（UID 2000）下。
     */
    private fun runShellCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            process.waitFor()
            if (stderr.isNotBlank()) "$stdout\n$stderr" else stdout
        } catch (e: Throwable) {
            Log.e(TAG, "execCommand failed", e)
            "ERROR: ${e.message}"
        }
    }
}
