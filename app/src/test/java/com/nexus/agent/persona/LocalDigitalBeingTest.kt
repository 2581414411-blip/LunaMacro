package com.nexus.agent.persona

import com.nexus.agent.copilot.PersonaEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 长记忆引擎的纯逻辑测试（不依赖 Android / 网络）。
 *
 * 重点验证：
 *  1. [LocalDigitalBeing.chat] 会记账，大脑不可用时不记（半句不算一轮）
 *  2. 超过 [LocalDigitalBeing.MAX_TURNS] 轮 → 压摘要 → 只移走最旧的 10 轮
 *  3. 摘要失败时**不丢话**（窗口原地保留，等下一轮重试）
 *  4. 摘要清洗 / 合并 / JSON 编解码这些纯函数行为稳定
 *
 * 摘要请求本身需要 LLM，这里统一用 [LocalDigitalBeing.summarizeOnce] 注入假实现，
 * 所以整组测试在纯 JVM 上跑，不需要 Robolectric。
 */
class LocalDigitalBeingTest {

    @Before
    fun setUp() = LocalDigitalBeing.resetForTest()

    @After
    fun tearDown() = LocalDigitalBeing.resetForTest()

    private fun fill(turns: Int) {
        for (i in 1..turns) {
            LocalDigitalBeing.addTurnForTest("问$i", "答$i")
        }
    }

    // ------------------------------------------------------------ chat / 记账

    @Test
    fun `chat 用窗口历史并记账`() = runBlocking {
        LocalDigitalBeing.addTurnForTest("旧问题", "旧回答")
        val brain = object : LocalDigitalBeing.Brain {
            var seenHistory = -1
            var seenSummary = ""
            override suspend fun reply(
                history: List<LocalDigitalBeing.Turn>,
                summary: String
            ): String? {
                seenHistory = history.size
                seenSummary = summary
                return "新回答"
            }
        }

        val reply = LocalDigitalBeing.chat("新问题", brain)

        assertEquals("新回答", reply)
        assertEquals(1, brain.seenHistory)
        assertEquals("", brain.seenSummary)
        assertEquals(2, LocalDigitalBeing.windowSize())
        assertEquals("新问题", LocalDigitalBeing.windowSnapshot().last().user)
    }

    @Test
    fun `大脑返回空时不记账`() = runBlocking {
        val silent = object : LocalDigitalBeing.Brain {
            override suspend fun reply(
                history: List<LocalDigitalBeing.Turn>,
                summary: String
            ): String? = null
        }
        assertNull(LocalDigitalBeing.chat("在吗", silent))
        assertEquals(0, LocalDigitalBeing.windowSize())
    }

    @Test
    fun `没有大脑时 chat 返回 null`() = runBlocking {
        assertNull(LocalDigitalBeing.chat("在吗"))
        assertEquals(0, LocalDigitalBeing.windowSize())
    }

    @Test
    fun `画面描述会作为附加上下文传给大脑`() = runBlocking {
        // 0.9.0 批次一：这是「人格聊天能看见屏幕」的关键一跳 ——
        // 描述必须真的到得了 Brain 手里，中间任何一环断掉都会让它变成「我看不到」。
        var seenNote: String? = null
        val brain = object : LocalDigitalBeing.Brain {
            override suspend fun reply(
                history: List<LocalDigitalBeing.Turn>,
                summary: String
            ): String? = "兜底"

            override suspend fun replyWith(
                history: List<LocalDigitalBeing.Turn>,
                summary: String,
                extras: LocalDigitalBeing.Extras
            ): String? {
                seenNote = extras.screenNote
                return "看到了"
            }
        }

        val reply = LocalDigitalBeing.chat("我在干嘛", brain, screenNote = "  微信聊天界面，用户在跟朋友聊周末  ")

        assertEquals("看到了", reply)
        // 首尾空白要去掉，否则提示词里会多出空行
        assertEquals("微信聊天界面，用户在跟朋友聊周末", seenNote)
    }

    @Test
    fun `没有画面时附加上下文为空`() = runBlocking {
        var extrasSeen: LocalDigitalBeing.Extras? = null
        val brain = object : LocalDigitalBeing.Brain {
            override suspend fun reply(
                history: List<LocalDigitalBeing.Turn>,
                summary: String
            ): String? = null

            override suspend fun replyWith(
                history: List<LocalDigitalBeing.Turn>,
                summary: String,
                extras: LocalDigitalBeing.Extras
            ): String? {
                extrasSeen = extras
                return "好"
            }
        }
        LocalDigitalBeing.chat("在吗", brain)
        assertTrue(extrasSeen!!.isEmpty)
    }

    @Test
    fun `同一轮五秒内重复记账只留一条`() {
        assertTrue(LocalDigitalBeing.recordTurn("在吗", "在"))
        assertFalse(LocalDigitalBeing.recordTurn("在吗", "在"))
        assertEquals(1, LocalDigitalBeing.windowSize())
    }

    @Test
    fun `未 init 时窗口退化为二十轮环形缓冲`() {
        // 没 init（拿不到摘要模型）→ 不能无限长大，按老行为丢最旧的
        for (i in 1..25) LocalDigitalBeing.recordTurn("问$i", "答$i")

        assertEquals(LocalDigitalBeing.MAX_TURNS, LocalDigitalBeing.windowSize())
        assertEquals("问6", LocalDigitalBeing.windowSnapshot().first().user)
        assertEquals("问25", LocalDigitalBeing.windowSnapshot().last().user)
    }

    // ------------------------------------------------------------ 摘要与出窗

    @Test
    fun `超过二十轮时摘要并移走最旧十轮`() = runBlocking {
        fill(21)
        var asked = 0

        val ok = LocalDigitalBeing.summarizeOnce { batch ->
            asked = batch.size
            "用户说他养了只猫"
        }

        assertTrue(ok)
        assertEquals(10, asked)
        // 21 - 10 = 11 轮留在窗口（问11 ~ 问21）
        assertEquals(11, LocalDigitalBeing.windowSize())
        assertEquals("问11", LocalDigitalBeing.windowSnapshot().first().user)
        assertEquals("用户说他养了只猫", LocalDigitalBeing.summaryText())
    }

    @Test
    fun `窗口没超二十轮不触发摘要`() = runBlocking {
        fill(20)
        var called = false
        val ok = LocalDigitalBeing.summarizeOnce {
            called = true
            "不该被调用"
        }
        assertFalse(ok)
        assertFalse(called)
        assertEquals(20, LocalDigitalBeing.windowSize())
    }

    @Test
    fun `摘要失败时窗口原地保留`() = runBlocking {
        fill(21)
        val ok = LocalDigitalBeing.summarizeOnce { null }

        assertFalse(ok)
        assertEquals(21, LocalDigitalBeing.windowSize())
        assertEquals("", LocalDigitalBeing.summaryText())
    }

    @Test
    fun `多次摘要滚动追加`() = runBlocking {
        fill(21)
        LocalDigitalBeing.summarizeOnce { "第一段" }
        fill(10) // 再补 10 轮，凑够 21
        LocalDigitalBeing.summarizeOnce { "第二段" }

        val summary = LocalDigitalBeing.summaryText()
        assertTrue(summary.startsWith("第一段"))
        assertTrue(summary.endsWith("第二段"))
    }

    // ------------------------------------------------------------ 纯函数

    @Test
    fun `摘要清洗剥前缀并截断到一百字`() {
        val long = "很长的内容".repeat(50)
        val cleaned = LocalDigitalBeing.sanitizeSummary("摘要：$long")

        assertFalse(cleaned.startsWith("摘要"))
        assertEquals(LocalDigitalBeing.SUMMARY_MAX_CHARS, cleaned.length)
    }

    @Test
    fun `摘要合并且超长时保留最近的`() {
        val old = "旧".repeat(400)
        val merged = LocalDigitalBeing.mergeSummary(old, "新的一段")

        assertTrue(merged.length <= LocalDigitalBeing.SUMMARY_TOTAL_MAX)
        assertTrue(merged.endsWith("新的一段"))
    }

    @Test
    fun `空摘要不污染已有滚动摘要`() {
        val merged = LocalDigitalBeing.mergeSummary("原有摘要", "   ")
        assertEquals("原有摘要", merged)
    }

    @Test
    fun `JSON 编解码往返`() = runBlocking {
        fill(21)
        LocalDigitalBeing.summarizeOnce { "用户在做安卓开发" }
        val json = LocalDigitalBeing.encode()
        val summary = LocalDigitalBeing.summaryText()
        val window = LocalDigitalBeing.windowSnapshot()

        // 换一个干净状态再解回来（模拟杀进程重启）
        LocalDigitalBeing.resetForTest()
        assertTrue(LocalDigitalBeing.decode(json))

        assertEquals(summary, LocalDigitalBeing.summaryText())
        assertEquals(window.size, LocalDigitalBeing.windowSize())
        assertEquals(window.first().user, LocalDigitalBeing.windowSnapshot().first().user)
        assertEquals(window.last().assistant, LocalDigitalBeing.windowSnapshot().last().assistant)
    }

    @Test
    fun `坏 JSON 不会把记忆清空`() {
        LocalDigitalBeing.addTurnForTest("还在吗", "在")
        assertFalse(LocalDigitalBeing.decode("{不是 json"))
        assertEquals(1, LocalDigitalBeing.windowSize())
    }

    @Test
    fun `清空后窗口与摘要都归零`() = runBlocking {
        fill(21)
        LocalDigitalBeing.summarizeOnce { "一段摘要" }
        LocalDigitalBeing.clear()

        assertEquals(0, LocalDigitalBeing.windowSize())
        assertEquals("", LocalDigitalBeing.summaryText())
    }

    // ------------------------------------------------- 0.9.10 灵魂缝合（情绪下沉）

    @Test
    fun `一轮对话会推进好感度与心情`() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
        val before = PersonaEngine.profileSnapshot().affinity

        // 生产环境由 recordTurn → onTurnRecorded 传入真实 context；
        // 这里直接打靶 propagateTurn（context 传 null 也能算）。
        LocalDigitalBeing.propagateTurn(null, "谢谢你，你真好")

        assertEquals("被夸一次应 +1", before + 1, PersonaEngine.profileSnapshot().affinity)
        assertEquals("心情应变成开心", "开心", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `被凶一次会扣好感度`() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
        val before = PersonaEngine.profileSnapshot().affinity

        LocalDigitalBeing.propagateTurn(null, "滚，烦死了")

        assertEquals(before - 1, PersonaEngine.profileSnapshot().affinity)
        assertEquals("难过", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `PersonaEngine_recordTurn 不再自己加好感度`() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
        val before = PersonaEngine.profileSnapshot().affinity

        // appCtx 为 null 时 LocalDigitalBeing 不会推进状态。
        // 如果谁又把这个副作用加回 PersonaEngine.recordTurn，这条会红。
        PersonaEngine.recordTurn("谢谢你，真棒", "不客气呀")

        assertEquals(
            "副作用应只在 LocalDigitalBeing.recordTurn 里发生一次",
            before,
            PersonaEngine.profileSnapshot().affinity
        )
    }

    @Test
    fun `空回复不推进情绪`() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
        val before = PersonaEngine.profileSnapshot().affinity

        PersonaEngine.recordTurn("谢谢你", "")

        assertEquals("没回完一句不该改心情", before, PersonaEngine.profileSnapshot().affinity)
    }
}
