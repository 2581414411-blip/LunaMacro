package com.nexus.agent.persona

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * FlexiMem 纯逻辑测试（不碰 Android / Room / 网络）。
 *
 * 覆盖三件最容易写错、又最影响体验的事：
 *  1. 关键词提取别把「我们」「这个」这种虚词当关键词 —— 那会让任何一句话都召回成功；
 *  2. 周记挑选必须挑到「决定 / 偏好 / 情绪」这类句子，而不是「打开微信」；
 *  3. 一周的边界必须是**周一 00:00**，否则周记会串周。
 */
class FlexiMemTest {

    // ------------------------------------------------------------ 关键词

    @Test
    fun `提取关键词会滤掉虚词`() {
        val k = FlexiMem.extractKeywords("我们这个周末去看马拉松吧")
        assertTrue("应该提到马拉松：$k", k.any { it.contains("马拉松") })
        assertFalse("不该把「我们」当关键词：$k", k.any { it == "我们" })
        assertFalse("不该把「这个」当关键词：$k", k.any { it == "这个" })
    }

    @Test
    fun `英文与数字整词也算关键词`() {
        val k = FlexiMem.extractKeywords("帮我把 ComfyUI 的版本升到 0.9.0")
        assertTrue("应该认出 comfyui：$k", k.any { it.equals("comfyui", ignoreCase = true) })
    }

    @Test
    fun `关键词不会同时保留长词和它的子串`() {
        val k = FlexiMem.extractKeywords("说过要跑马拉松的")
        // 「马拉松」和「拉松」不该同时出现：后者是前者的子串，召回时纯属噪音
        assertFalse("不该出现子串：$k", k.contains("拉松"))
    }

    @Test
    fun `空白输入提不出关键词`() {
        assertTrue(FlexiMem.extractKeywords("   ").isEmpty())
        assertTrue(FlexiMem.extractKeywords("").isEmpty())
    }

    // ------------------------------------------------------------ 检索打分

    @Test
    fun `用户原话命中比助手原话命中更值钱`() {
        val key = listOf("马拉松")
        val userHit = FlexiMem.ArchiveTurn("我要去跑马拉松", "加油", 1000, "2026-11-01")
        val botHit = FlexiMem.ArchiveTurn("今天天气不错", "你上次说想跑马拉松", 1000, "2026-11-02")
        assertTrue(FlexiMem.scoreTurn(userHit, key) > FlexiMem.scoreTurn(botHit, key))
    }

    @Test
    fun `长关键词命中得分更高`() {
        val short = FlexiMem.ArchiveTurn("我去跑步了", "", 0, "d")
        val long = FlexiMem.ArchiveTurn("我去跑马拉松了", "", 0, "d")
        assertTrue(
            FlexiMem.scoreTurn(long, listOf("马拉松")) > FlexiMem.scoreTurn(short, listOf("跑步"))
        )
    }

    @Test
    fun `召回句子带日期且截断过长原话`() {
        val long = "啊".repeat(120)
        val line = FlexiMem.formatRecall(FlexiMem.ArchiveTurn(long, "好", 0, "2026-11-01"))
        assertTrue(line.contains("你说：「"))
        assertTrue("超长要截断", line.length < 120)
        assertTrue("截断要有省略号", line.endsWith("…」") || line.contains("…」"))
    }

    // ------------------------------------------------------------ 周记规则

    @Test
    fun `计划与承诺类句子会进周记`() {
        val picks = FlexiMem.pickHighlights(
            listOf(
                "打开微信",
                "我们说好下周去爬山，周六早上七点出发",
                "在吗"
            )
        )
        assertEquals(1, picks.size)
        assertTrue(picks[0].contains("爬山"))
    }

    @Test
    fun `纯操作指令不会进周记`() {
        val picks = FlexiMem.pickHighlights(
            listOf("打开微信", "点一下那个按钮", "返回", "划一下")
        )
        assertTrue("指令类不该进周记：$picks", picks.isEmpty())
    }

    @Test
    fun `周记最多六条且不重复`() {
        val scenes = listOf(
            "我决定从明天开始每天跑步五公里",
            "我决定从明天开始每天跑步六公里",
            "我最喜欢吃的是火锅，尤其麻辣的",
            "我最讨厌早起，闹钟响了要赖十分钟",
            "今天被领导骂了，心里挺委屈的",
            "周末约好和朋友去看电影，看那个新上映的",
            "以后晚上十一点之后别提醒我干活了",
            "我准备把旧手机卖掉换一个新的"
        )
        val picks = FlexiMem.pickHighlights(scenes, max = 6)
        assertTrue("不能超过上限：${picks.size}", picks.size <= 6)
        assertEquals("去重后应该少一条", picks.size, picks.distinct().size)
    }

    @Test
    fun `打分规则偏好与情绪都算数`() {
        assertTrue(FlexiMem.scoreUtterance("我决定明年换工作") >= 4)
        assertTrue(FlexiMem.scoreUtterance("我喜欢吃辣的") >= 3)
        assertTrue(FlexiMem.scoreUtterance("今天真的好累啊") >= 2)
        assertTrue(FlexiMem.scoreUtterance("返回") < 2)
    }

    // ------------------------------------------------------------ 时间与编解码

    @Test
    fun `一周的起点是周一零点`() {
        // 2026-11-07 是周六
        val sat = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.NOVEMBER, 7, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = FlexiMem.weekStart(sat) }
        assertEquals(Calendar.MONDAY, start.get(Calendar.DAY_OF_WEEK))
        assertEquals(2, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, start.get(Calendar.MINUTE))
    }

    @Test
    fun `周日晚上仍属于同一周的周一`() {
        val sunday = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.NOVEMBER, 8, 23, 59, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val start = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = FlexiMem.weekStart(sunday) }
        assertEquals(2, start.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `档案行编解码往返不丢内容`() {
        val u = "他说：\"这行有引号\"\n还有换行\\和反斜杠"
        val a = "好的😀"
        val line = FlexiMem.encodeLine(u, a, 1730000000000L)
        val back = FlexiMem.decodeLine(line, "2026-11-07")
        assertNotNull(back)
        assertEquals(u, back!!.user)
        assertEquals(a, back.assistant)
        assertEquals(1730000000000L, back.at)
        assertEquals("2026-11-07", back.day)
    }

    @Test
    fun `坏行直接丢掉不炸`() {
        assertNull(FlexiMem.decodeLine("", "d"))
        assertNull(FlexiMem.decodeLine("不是 JSON", "d"))
        assertNull(FlexiMem.decodeLine("{\"u\":\"只有用户\"}", "d"))
    }

    @Test
    fun `超长句子按标点收尾而不是硬切`() {
        val s = "我决定从明天开始每天跑步五公里，然后晚上不吃夜宵，早上六点起床。"
        val cut = FlexiMem.trimSentence(s, 20)
        assertTrue("不该是半截话：$cut", cut.endsWith("，") || cut.endsWith("…") || cut.length < 20)
        assertTrue(cut.length <= 21)
    }
}
