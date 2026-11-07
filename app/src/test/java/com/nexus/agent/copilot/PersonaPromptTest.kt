package com.nexus.agent.copilot

import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.persona.EmotionEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 提示词拼装测试（0.9.2）。
 *
 * 这一层最容易出的问题不是崩溃，而是**某段被后写的段落盖掉**：
 * 好感度写着「冷淡」，后面又来一句「温柔一点」，模型两边都读，最后听谁的全看运气。
 * 所以这里断言的是「该有的都在」以及「不该有的别来」。
 */
class PersonaPromptTest {

    private val cold = PresenceSense.Now(hour = 14, minute = 0, batteryPercent = 80)
    private val lateNight = PresenceSense.Now(hour = 23, minute = 40, batteryPercent = 62)
    private val lowBattery = PresenceSense.Now(hour = 15, minute = 10, batteryPercent = 8)

    @Before
    fun setUp() {
        PersonaEngine.resetForTest()
        EmotionEngine.resetForTest()
    }

    private fun prompt(
        presence: PresenceSense.Now = cold,
        automation: Boolean = true
    ): String = PersonaEngine.buildReplyPrompt(
        foregroundApp = null,
        toolResult = null,
        hasImage = false,
        presence = presence,
        automationEnabled = automation
    )

    /** 直接改内存档案（saveProfile 会把名字同步到 CopilotConfig，单测里没 Context） */
    private fun setAffinity(affinity: Int, mood: String = "平静") {
        PersonaEngine.setProfileForTest(
            ProfileSnapshot("宏月", "", "", affinity, mood)
        )
    }

    @Test
    fun `低好感度的提示词要求冷淡`() {
        setAffinity(12, "难过")
        val p = prompt()
        assertTrue(p.contains("有点冷"))
        assertTrue(p.contains("八个字"))
        assertTrue(p.contains("难过"))
    }

    @Test
    fun `中好感度是正常语气`() {
        setAffinity(50)
        val p = prompt()
        assertTrue(p.contains("还算正常"))
        assertFalse(p.contains("有点冷"))
        assertFalse(p.contains("很亲近"))
    }

    @Test
    fun `高好感度要求亲昵主动`() {
        setAffinity(95, "开心")
        val p = prompt()
        assertTrue(p.contains("很亲近"))
        assertTrue(p.contains("主动分享"))
        assertTrue(p.contains("开心"))
    }

    @Test
    fun `任何档位都不许把好感度数字说出口`() {
        listOf(5, 50, 95).forEach { a ->
            setAffinity(a)
            assertTrue("$a 分档缺了禁止说数字", prompt().contains("绝对不要说出这些数字"))
        }
    }

    @Test
    fun `提示词里带当前时间`() {
        val p = prompt(lateNight)
        assertTrue("要写现在是几点", p.contains("23:40"))
        assertTrue("要有世界设定段", p.contains("【现在你所在的世界】"))
    }

    @Test
    fun `深夜会带上困意与早睡的约束`() {
        val p = prompt(lateNight)
        assertTrue(p.contains("困意"))
        assertTrue(p.contains("早点睡"))
    }

    @Test
    fun `白天不硬塞困意`() {
        val p = prompt(cold)
        assertFalse("下午两点提示词里不该出现困意", p.contains("困意"))
    }

    @Test
    fun `低电量会提醒充电`() {
        val p = prompt(lowBattery)
        assertTrue(p.contains("8%"))
        assertTrue(p.contains("充电"))
        assertTrue("个位数要更急", p.contains("个位数"))
    }

    @Test
    fun `纯数字生命模式下仍然有临场感`() {
        // 关掉自动化工具不该把「几点、多少电」一起关掉 —— 那是两件无关的事
        val p = prompt(presence = lateNight, automation = false)
        assertTrue(p.contains("23:40"))
        assertTrue(p.contains("困意"))
        assertTrue("纯聊天的契约也要在", p.contains("只能"))
    }

    @Test
    fun `语气段只出现一次不会被后面覆盖`() {
        setAffinity(10, "难过")
        val p = prompt()
        // 「有点冷」只该出现一次：出现两次说明有两套语气规则在打架
        var count = 0
        var idx = 0
        while (true) {
            idx = p.indexOf("有点冷", idx)
            if (idx < 0) break
            count++
            idx++
        }
        assertTrue("冷淡约束出现了 $count 次", count == 1)
    }

    // -------------------------------------------------- 主动问候的理由（0.9.4）

    @Test
    fun `有话题种子时问候提示词点名那件事`() {
        setAffinity(60)
        val p = PersonaEngine.buildGreetingPrompt(cold, "明天要体检")
        assertTrue("没把话题写进提示词，她只能凭空找话题", p.contains("明天要体检"))
        assertTrue("需要明确「顺着这件事说」", p.contains("记挂着"))
    }

    @Test
    fun `没有话题种子时不留空壳段落`() {
        setAffinity(60)
        val p = PersonaEngine.buildGreetingPrompt(cold, null)
        assertFalse("没有种子时不该出现「你一直记挂着」的空段", p.contains("一直记挂着"))
        // 泛泛问候的规则仍然要在
        assertTrue(p.contains("不是他在问你"))
    }

    @Test
    fun `问候提示词不夹带屏幕与工具`() {
        // 主动问候时既没有屏幕内容也没有工具结果，混进来只会让它自问自答
        setAffinity(60)
        val p = PersonaEngine.buildGreetingPrompt(cold, "最近头疼")
        assertFalse(p.contains("工具结果"))
        assertFalse(p.contains("本轮"))
    }
}
