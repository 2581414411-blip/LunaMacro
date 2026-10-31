package com.nexus.agent.game

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
}
