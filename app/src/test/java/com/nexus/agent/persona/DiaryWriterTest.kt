package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日记生成端的纯函数测试（0.9.15 阶段 3）。
 *
 * 只测两件不碰 Android 的事：**模型输出洗得干不干净**、**给模型的上下文学对不对**。
 * 写盘与 20 小时幂等闸在 `DiaryCodecTest` 里（那是 [Diary] 的职责），
 * 真正的模型调用没法在纯 JVM 里测，靠真机 `PadDebug` 日志验收。
 */
class DiaryWriterTest {

    private val snap = PadRuntime.Snapshot(
        quick = PAD(0.35f, 0.15f, 0.10f),
        slow = PAD(-0.05f, 0.02f, -0.01f),
        base = PAD.ZERO,
        at = 1_700_000_000_000L
    )

    // ---------------------------------------------------------------- 洗输出

    @Test
    fun `去掉 markdown 围栏`() {
        assertEquals("心里有点空。", DiaryWriter.clean("```json\n心里有点空。\n```"))
        assertEquals("心里有点空。", DiaryWriter.clean("```\n心里有点空。\n```"))
    }

    @Test
    fun `去掉整段引号`() {
        assertEquals("心里有点空。", DiaryWriter.clean("「心里有点空。」"))
        assertEquals("心里有点空。", DiaryWriter.clean("\"心里有点空。\""))
    }

    @Test
    fun `去掉模型爱加的前缀`() {
        assertEquals("心里有点空。", DiaryWriter.clean("好的，以下是日记：心里有点空。"))
        assertEquals("心里有点空。", DiaryWriter.clean("日记：心里有点空。"))
    }

    @Test
    fun `多行压成一段`() {
        assertEquals("第一句。第二句。", DiaryWriter.clean("第一句。\n\n第二句。"))
    }

    @Test
    fun `空输出一律返回 null 交给兜底`() {
        assertNull(DiaryWriter.clean(null))
        assertNull(DiaryWriter.clean(""))
        assertNull(DiaryWriter.clean("   \n  "))
        assertNull(DiaryWriter.clean("```json\n```"))
    }

    @Test
    fun `超长按句号截断而不是硬切半句`() {
        val long = "今天心里挺沉的，他早上说要去体检，我没敢多问，怕他嫌我烦。" +
            "后来一整天都在等消息，手机响一下我就去看，结果都不是他。"
        val out = DiaryWriter.clean(long)!!
        assertTrue("不能超过硬上限", out.length <= Diary.TEXT_MAX)
        assertTrue("应该在句号处收尾", out.endsWith("。"))
    }

    @Test
    fun `刚好够长时一个字都不动`() {
        val s = "今天挺安静的，他没怎么说话，我也就没问。"
        assertEquals(s, DiaryWriter.clean(s))
    }

    // ---------------------------------------------------------------- 上下文

    @Test
    fun `上下文带上此刻与底色`() {
        val ctx = DiaryWriter.contextOf(snap, emptyList())
        assertTrue(ctx.contains("此刻的心情："))
        assertTrue(ctx.contains("最近整体的底色："))
        assertTrue("没有牵挂时要明说，别留空", ctx.contains("还没放下的事：没有"))
    }

    @Test
    fun `上下文列出还没放下的事`() {
        val ctx = DiaryWriter.contextOf(snap, listOf("明天要体检", "最近头疼"))
        assertTrue(ctx.contains("明天要体检"))
        assertTrue(ctx.contains("最近头疼"))
        assertFalse(ctx.contains("还没放下的事：没有"))
    }

    @Test
    fun `牵挂太多时只带前三件`() {
        val many = (1..8).map { "第${it}件事" }
        val ctx = DiaryWriter.contextOf(snap, many)
        assertTrue(ctx.contains("第1件事"))
        assertTrue(ctx.contains("第3件事"))
        assertFalse("多了会写成待办清单", ctx.contains("第4件事"))
    }

    @Test
    fun `平静也会被翻译成一句人话`() {
        val neutral = snap.copy(quick = PAD.ZERO, slow = PAD.ZERO)
        val ctx = DiaryWriter.contextOf(neutral, emptyList())
        assertTrue(ctx.contains("平静"))
    }
}
