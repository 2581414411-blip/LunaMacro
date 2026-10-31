package com.nexus.agent.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 灵动岛手势收尾判定 —— 真机崩溃的回归测试。
 *
 * 崩溃现场（vivo V2307A / Android 16，0.8.0~0.8.2）：
 * ```
 * IllegalStateException: The specified child already has a parent
 *   at CopilotIslandOverlay.exitInputMode(1191)      ← addView
 *   at CopilotIslandOverlay.submitInput(1147)
 *   at CopilotIslandOverlay.onIslandClick(477)       ← 由 ACTION_CANCEL 触发
 *   ...
 *   at CopilotIslandOverlay.enterInputMode(1108)     ← removeAllViews 派发 cancel
 * ```
 * 一句话：`removeAllViews()` 摘子 View 时系统会补一个 ACTION_CANCEL，
 * 旧代码把它当成点击，于是在改视图树的中途又去改一次视图树。
 *
 * 这里锁死的就是「取消永远不是点击」这条规则。
 */
class IslandGestureTest {

    @Test
    fun `系统取消的手势绝不能当成点击`() {
        assertEquals(
            GestureEnd.NONE,
            decideGestureEnd(cancelled = true, isDrag = false, decided = false, durationMs = 60)
        )
    }

    @Test
    fun `取消时即使已经在拖拽也不做吸附`() {
        assertEquals(
            GestureEnd.NONE,
            decideGestureEnd(cancelled = true, isDrag = true, decided = true, durationMs = 800)
        )
    }

    @Test
    fun `拖动结束吸附到边`() {
        assertEquals(
            GestureEnd.SNAP,
            decideGestureEnd(cancelled = false, isDrag = true, decided = true, durationMs = 300)
        )
    }

    @Test
    fun `滚过文字后抬手不算点击`() {
        assertEquals(
            GestureEnd.NONE,
            decideGestureEnd(cancelled = false, isDrag = false, decided = true, durationMs = 200)
        )
    }

    @Test
    fun `轻点是点击`() {
        assertEquals(
            GestureEnd.CLICK,
            decideGestureEnd(cancelled = false, isDrag = false, decided = false, durationMs = 80)
        )
    }

    @Test
    fun `按够时长算长按`() {
        assertEquals(
            GestureEnd.LONG_PRESS,
            decideGestureEnd(cancelled = false, isDrag = false, decided = false, durationMs = 600)
        )
    }

    @Test
    fun `长按阈值边界：499 算点击、500 算长按`() {
        val threshold = ISLAND_LONG_PRESS_MS
        assertEquals(
            GestureEnd.CLICK,
            decideGestureEnd(false, false, false, threshold - 1)
        )
        assertEquals(
            GestureEnd.LONG_PRESS,
            decideGestureEnd(false, false, false, threshold)
        )
    }
}
