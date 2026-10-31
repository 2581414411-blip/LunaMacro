package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视觉路由的**格式契约**测试。
 *
 * 用户报障时只会念一行日志（「我看到最终选择=CloudText」），
 * 所以这一行的字段名、顺序、布尔写法都不能漂 —— 漂了就再也对不上号。
 */
class VisionRouteTest {

    @Test
    fun `路由行格式固定且三要素齐全`() {
        val line = VisionRoute.routeLine("READY", hasShot = true, choice = "Local")
        assertEquals("[路由] 本地模型状态=READY, 截图存在=true, 最终选择=Local", line)
    }

    @Test
    fun `没有截图时布尔写 false 而不是 false 的空串`() {
        val line = VisionRoute.routeLine("NOT_DOWNLOADED", false, "CloudText")
        assertTrue(line.contains("截图存在=false"))
        assertTrue(line.contains("最终选择=CloudText"))
    }

    @Test
    fun `三种选择各自对应正确的后果`() {
        assertEquals("Local", VisionRoute.Choice.LOCAL.label)
        assertEquals("CloudVision", VisionRoute.Choice.CLOUD_VISION.label)
        assertEquals("CloudText", VisionRoute.Choice.CLOUD_TEXT.label)
    }

    @Test
    fun `四个状态标签与用户念的口径一致`() {
        assertEquals("READY", VisionRoute.stateLabel(com.nexus.agent.game.LocalScreenRecognizer.State.READY))
        assertEquals("LOADING", VisionRoute.stateLabel(com.nexus.agent.game.LocalScreenRecognizer.State.LOADING))
        assertEquals(
            "NOT_DOWNLOADED",
            VisionRoute.stateLabel(com.nexus.agent.game.LocalScreenRecognizer.State.NOT_DOWNLOADED)
        )
        assertEquals("FAILED", VisionRoute.stateLabel(com.nexus.agent.game.LocalScreenRecognizer.State.FAILED))
    }

    @Test
    fun `截图失败与本地未就绪各有专属文案`() {
        assertEquals("[路由] 截图失败，降级纯文本", VisionRoute.logShotFailed())
        assertTrue(VisionRoute.logLocalNotReady(com.nexus.agent.game.LocalScreenRecognizer.State.FAILED)
            .startsWith("[路由] 本地模型未就绪（FAILED），降级"))
    }

    @Test
    fun `截图描述会剥掉小模型前缀并压成一行`() {
        val d = VisionRoute.cleanScreenDescription("这张图片显示的是：\n微信聊天界面，用户正在和朋友聊周末去哪玩。")
        assertEquals("微信聊天界面，用户正在和朋友聊周末去哪玩。", d)
    }

    @Test
    fun `截图描述为空或纯空白时返回 null`() {
        assertNull(VisionRoute.cleanScreenDescription(null))
        assertNull(VisionRoute.cleanScreenDescription("   "))
        assertNull(VisionRoute.cleanScreenDescription("描述："))
    }

    @Test
    fun `截图描述超长时截断并加省略号`() {
        val long = "屏".repeat(VisionRoute.SCREEN_DESC_MAX + 50)
        val d = VisionRoute.cleanScreenDescription(long)!!
        assertTrue(d.endsWith("…"))
        assertEquals(VisionRoute.SCREEN_DESC_MAX + 1, d.length)
    }
}
