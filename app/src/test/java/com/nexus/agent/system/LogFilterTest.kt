package com.nexus.agent.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键导出日志」的过滤规则回归测试。
 *
 * 事故背景：用户报「点测试视觉模型就回桌面」，导出的日志里**只有几行健康检查**，
 * 因为白名单只收了自家 tag —— `AndroidRuntime`、`libc`、`lk`、`ActivityManager`
 * 这些真正记录死因的行全被滤掉了。白排查了两轮才发现导出本身在骗人。
 *
 * 所以这里把「什么必须留下」钉死。
 */
class LogFilterTest {

    @Test
    fun `Java 崩溃必须留下`() {
        assertTrue(
            LogExporter.keepLine("10-31 17:15:33.100 E/AndroidRuntime: FATAL EXCEPTION: main")
        )
        assertTrue(
            LogExporter.keepLine("10-31 17:15:33.100 E/CrashHandler: 崩溃已落盘")
        )
    }

    @Test
    fun `native 崩溃与 OOM 击杀必须留下`() {
        assertTrue(LogExporter.keepLine("F/DEBUG: *** *** *** *** *** *** *** ***"))
        assertTrue(LogExporter.keepLine("F/libc: Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR)"))
        assertTrue(LogExporter.keepLine("F/libc: Fatal signal 7 (SIGBUS), code 2 (BUS_ADRERR)"))
        assertTrue(LogExporter.keepLine("I/lowmemorykiller: Kill 'com.nexus.agent' (17907), uid 10123"))
        assertTrue(LogExporter.keepLine("I ActivityManager: Killing 17907:com.nexus.agent/u0a123"))
    }

    @Test
    fun `视觉与灵动岛链路必须留下`() {
        assertTrue(LogExporter.keepLine("I/VisionWorker: 视觉子进程已启动 pid=17908"))
        assertTrue(LogExporter.keepLine("W/ShizukuHealth: state=wakeup_failed, reason=still_dead"))
        assertTrue(LogExporter.keepLine("I/LocalScreenRecognizer: 本地模型状态=READY"))
        assertTrue(LogExporter.keepLine("I/CopilotIsland: 灵动岛已接上副驾驶大脑"))
    }

    @Test
    fun `我们的进程名出现在 AMS 行里也要留下`() {
        assertTrue(LogExporter.keepLine("I/ActivityManager: Force stopping com.nexus.agent"))
    }

    @Test
    fun `无关噪音仍然被滤掉`() {
        assertFalse(LogExporter.keepLine("D/SomeRandomLib: hello world"))
        assertFalse(LogExporter.keepLine("V/chatty: uid=10123 com.other.app expire 3 lines"))
    }
}
