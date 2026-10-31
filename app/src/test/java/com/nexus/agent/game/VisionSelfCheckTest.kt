package com.nexus.agent.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「测试视觉模型」弹窗文案的单测。
 *
 * 这个弹窗是用户唯一能自助验证「本地视觉到底能不能用」的入口，
 * 成功/失败的措辞必须一眼分得清 —— 失败却显示得像成功，比不给测更糟。
 */
class VisionSelfCheckTest {

    @Test
    fun `成功时展示模型原文与耗时`() {
        val r = VisionSelfCheck.InferReport(
            ok = true,
            elapsedMs = 2345,
            route = "[路由] 本地模型状态=READY, 截图存在=true, 最终选择=Local",
            shotInfo = "成功 1080×2400",
            body = "微信聊天界面，用户正和朋友聊周末去哪玩。"
        )
        val t = r.toText()
        assertTrue(t.contains("✅ 本地视觉模型有输出"))
        assertTrue(t.contains("2.3 秒"))
        assertTrue(t.contains("最终选择=Local"))
        assertTrue(t.contains("微信聊天界面"))
        assertFalse(t.contains("怎么修"))
    }

    @Test
    fun `失败时必须给出原因和修法`() {
        val r = VisionSelfCheck.InferReport(
            ok = false,
            elapsedMs = 61000,
            route = "[路由] 本地模型状态=NOT_DOWNLOADED, 截图存在=true, 最终选择=CloudText",
            shotInfo = "成功 1080×2400",
            body = "本地视觉模型文件不全，缺 6 个：llm.mnn、llm.mnn.weight……",
            hint = "设置 → 下载模型"
        )
        val t = r.toText()
        assertTrue(t.contains("❌ 本地视觉模型没看成"))
        assertTrue(t.contains("61.0 秒"))
        assertTrue(t.contains("缺 6 个"))
        assertTrue(t.contains("怎么修：设置 → 下载模型"))
    }

    @Test
    fun `失败但没写修法时不出现空的怎么修`() {
        val r = VisionSelfCheck.InferReport(
            ok = false,
            elapsedMs = 100,
            route = "x",
            shotInfo = "失败：无障碍未连接",
            body = "截不到屏幕"
        )
        assertFalse(r.toText().contains("怎么修"))
    }

    // ------------------------------------------------ 0.8.10 崩溃现场摘要

    @Test
    fun `没有崩溃记录时不显示崩溃现场`() {
        val r = VisionSelfCheck.Report(
            accessibility = "已连接",
            shot = "1080×2400 截图成功",
            localModel = "就绪（已装载）",
            verdict = "截图 + 模型都正常。",
            lastNative = "无（上次 native 调用正常结束）"
        )
        val t = r.toText()
        assertFalse(t.contains("崩溃现场"))
        assertTrue(t.contains("上次 native 调用：无"))
    }

    @Test
    fun `有崩溃记录时把信号和栈帧摆进弹窗`() {
        val r = VisionSelfCheck.Report(
            accessibility = "已连接",
            shot = "720×1280 截图成功",
            localModel = "就绪（已装载）",
            verdict = "推理阶段被系统终止。",
            lastNative = "native:recognize（10-31 20:31）",
            nativeCrash = "=== native crash: signal 11 (SIGSEGV) addr=0x0 pid=1 tid=2 ===\n  #00 libllm.so(+0x1234)"
        )
        val t = r.toText()
        assertTrue(t.contains("上次 native 崩溃现场"))
        assertTrue(t.contains("SIGSEGV"))
        assertTrue(t.contains("libllm.so(+0x1234)"))
    }

    @Test
    fun `多段崩溃只取最后一次并限制帧数`() {
        val raw = buildString {
            appendLine("=== native crash: signal 11 (SIGSEGV) addr=0x1 pid=1 tid=1 ===")
            appendLine("  #00 old.so(+0x1)")
            appendLine("=== native crash: signal 7 (SIGBUS) addr=0x2 pid=1 tid=1 ===")
            for (i in 0 until 30) appendLine("  #%02d new.so(+0x%x)".format(i, i))
        }
        val out = VisionSelfCheck.summarizeNativeCrash(raw, maxFrames = 3)
        assertFalse("不能把旧那次也带上：$out", out.contains("old.so"))
        assertTrue("要保留最新那次的信号", out.contains("SIGBUS"))
        // 头部 1 行 + 3 帧
        assertEquals(4, out.lines().size)
    }

    @Test
    fun `摘要超长时截断并加省略号`() {
        val raw = "=== native crash: signal 11 (SIGSEGV) ===\n" + "x".repeat(3000)
        val out = VisionSelfCheck.summarizeNativeCrash(raw, maxFrames = 10, maxChars = 100)
        assertTrue(out.endsWith("…"))
        assertEquals(101, out.length)
    }

    // ------------------------------------------------ 0.8.10 推理前内存门槛

    @Test
    fun `内存不足时拒绝推理并说明要多少`() {
        val why = LocalScreenRecognizer.inferMemoryVerdict(320)
        assertTrue("必须给出原因：$why", why != null && why.contains("320MB"))
        assertTrue(why!!.contains("${LocalScreenRecognizer.MIN_INFER_RAM_MB}MB"))
    }

    @Test
    fun `内存够时放行`() {
        assertEquals(null, LocalScreenRecognizer.inferMemoryVerdict(LocalScreenRecognizer.MIN_INFER_RAM_MB))
        assertEquals(null, LocalScreenRecognizer.inferMemoryVerdict(4096))
    }

    @Test
    fun `读不到内存时不拦`() {
        // -1 = ActivityManager 读失败。宁可试一把，也不要因为数据缺失把功能锁死。
        assertEquals(null, LocalScreenRecognizer.inferMemoryVerdict(-1))
    }
}
