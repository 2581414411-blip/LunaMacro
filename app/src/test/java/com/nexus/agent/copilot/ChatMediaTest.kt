package com.nexus.agent.copilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样率计算（0.9.6）。
 *
 * 这段逻辑的作用是「在解码前把尺寸降下来」：`BitmapFactory` 的 `inSampleSize` 只能是
 * 2 的幂，算小了内存顶不住（4000×3000 的 ARGB 是 48MB），算大了图糊成马赛克。
 * 所以断言两条：**解码后的长边不低于目标**，并且**能降就尽量降**。
 */
class ChatMediaTest {

    @Test
    fun `小图不采样`() {
        assertEquals(1, ChatMedia.sampleSizeFor(640, 480, 1280))
        assertEquals(1, ChatMedia.sampleSizeFor(1280, 720, 1280))
    }

    @Test
    fun `大图按二的幂降采样`() {
        // 4000 长边压到 1280：除 2 已到 2000（仍 ≥ 目标）；再除一档就是 1000，比目标还小
        assertEquals(2, ChatMedia.sampleSizeFor(4000, 3000, 1280))
        // 8000 → 4000 → 2000，sample=4 时仍是 2000 ≥ 1280
        assertEquals(4, ChatMedia.sampleSizeFor(8000, 6000, 1280))
    }

    @Test
    fun `采样后长边不低于目标`() {
        intArrayOf(1920, 3000, 4000, 6000, 8000, 12000).forEach { longest ->
            val sample = ChatMedia.sampleSizeFor(longest, longest / 2, 640)
            val after = longest / sample
            assertTrue("$longest 采样 $sample 后只有 $after，比目标 640 还小", after >= 640)
            // 且不能再降一档，否则就低于目标了
            assertTrue("$longest 还能再降一档，说明采样率算保守了", after / 2 < 640)
        }
    }

    @Test
    fun `非法尺寸兜底为不采样`() {
        assertEquals(1, ChatMedia.sampleSizeFor(0, 0, 1280))
        assertEquals(1, ChatMedia.sampleSizeFor(-1, 100, 1280))
        assertEquals(1, ChatMedia.sampleSizeFor(100, 100, 0))
    }
}
