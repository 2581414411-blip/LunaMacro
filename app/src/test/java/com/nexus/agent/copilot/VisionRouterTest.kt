package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VisionRouter] 的纯逻辑单测。
 *
 * `processImage` 本身依赖网络 / 本地 MNN 模型，纯 JVM 测不了，所以这里只覆盖
 * 可以脱离 Android 环境验证的决策逻辑：云端可用性判断、路由标签、失败兜底文案。
 */
class VisionRouterTest {

    // ---------------------------------------------------------- 云端可用性

    @Test
    fun `有独立视觉 Key 时优先走云端`() {
        assertTrue(
            VisionRouter.shouldUseCloud(
                visionApiKey = "sk-xxx",
                mainCanVision = false,
                mainApiKey = ""
            )
        )
    }

    @Test
    fun `主模型自己会看图且有 Key 时也能走云端`() {
        assertTrue(
            VisionRouter.shouldUseCloud(
                visionApiKey = "",
                mainCanVision = true,
                mainApiKey = "sk-deepseek"
            )
        )
    }

    @Test
    fun `主模型是纯文本且没有独立视觉 Key 时不走云端`() {
        assertFalse(
            VisionRouter.shouldUseCloud(
                visionApiKey = "",
                mainCanVision = false,
                mainApiKey = "sk-deepseek"
            )
        )
    }

    @Test
    fun `主模型会看图但没配 Key 时也不走云端`() {
        assertFalse(
            VisionRouter.shouldUseCloud(
                visionApiKey = "",
                mainCanVision = true,
                mainApiKey = ""
            )
        )
    }

    // ---------------------------------------------------------- 路由标签

    @Test
    fun `三个路由标签与用户要求的命名一致`() {
        assertEquals("Cloud_VL", VisionRouter.Route.CLOUD_VL.label)
        assertEquals("Local_VL", VisionRouter.Route.LOCAL_VL.label)
        assertEquals("Failed", VisionRouter.Route.FAILED.label)
    }

    // ---------------------------------------------------------- 失败兜底文案

    @Test
    fun `失败兜底文案明确说明两边都不可用`() {
        assertTrue(VisionRouter.FAIL_NOTICE.contains("本地和云端视觉都不可用"))
        assertTrue(VisionRouter.FAIL_NOTICE.contains("没看成"))
    }

    @Test
    fun `Result 的 ok 只认非空描述`() {
        assertTrue(VisionRouter.Result(VisionRouter.Route.CLOUD_VL, "一只橘猫").ok)
        assertFalse(VisionRouter.Result(VisionRouter.Route.FAILED, null, VisionRouter.FAIL_NOTICE).ok)
        assertFalse(VisionRouter.Result(VisionRouter.Route.LOCAL_VL, "  ").ok)
    }
}
