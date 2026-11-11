package com.nexus.agent.copilot

import com.nexus.agent.data.db.PadState
import com.nexus.agent.data.db.ProfileSnapshot
import com.nexus.agent.persona.Diary
import com.nexus.agent.persona.EmotionEngine
import com.nexus.agent.persona.PAD
import com.nexus.agent.persona.PadRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        automation: Boolean = true,
        foregroundApp: String? = null,
        islandEnabled: Boolean? = null,
        companionEnabled: Boolean? = null
    ): String = PersonaEngine.buildReplyPrompt(
        foregroundApp = foregroundApp,
        toolResult = null,
        hasImage = false,
        presence = presence,
        automationEnabled = automation,
        islandEnabled = islandEnabled,
        companionEnabled = companionEnabled
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
        assertTrue("要有环境感知段", p.contains("环境感知"))
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

    // ---------------------------------------------------------- 环境感知（0.9.7 批次二）

    @Test
    fun `前台应用会进提示词`() {
        val p = prompt(foregroundApp = "哔哩哔哩")
        assertTrue("要知道他开着什么", p.contains("哔哩哔哩"))
        assertTrue("要提示她能顺口接一句", p.contains("又在刷视频"))
    }

    @Test
    fun `没前台应用时不硬塞应用提示`() {
        val p = prompt(foregroundApp = null)
        assertFalse("没前台应用就不该出现「刷视频」示例", p.contains("又在刷视频"))
    }

    @Test
    fun `灵动岛开着会提「我一直都在」`() {
        val p = prompt(islandEnabled = true)
        assertTrue(p.contains("灵动岛"))
        assertTrue(p.contains("我一直都在"))
    }

    @Test
    fun `灵动岛关着不提`() {
        val p = prompt(islandEnabled = false)
        assertFalse("岛没开就不该提", p.contains("灵动岛"))
    }

    @Test
    fun `身份定位说明她活在手机里`() {
        val p = prompt()
        assertTrue("要有数字生命定位", p.contains("数字生命"))
        assertTrue("要说明她感知得到设备状态", p.contains("活在") || p.contains("手机里"))
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

    // ---------------------------------------------------------- 多模态（0.9.6）

    @Test
    fun `他发来的图必须写进提示词`() {
        setAffinity(60)
        val p = PersonaEngine.buildReplyPrompt(
            foregroundApp = null,
            toolResult = null,
            hasImage = false,
            presence = cold,
            userImageNote = "一只橘猫趴在键盘上，旁边有个马克杯",
            userImageKind = "图片",
            userImageAttached = true
        )
        assertTrue("本地视觉看到的画面要进提示词", p.contains("一只橘猫趴在键盘上"))
        assertTrue(p.contains("你眼里的画面"))
        // 0.9.9：形状固定成 [图片内容描述：…]，日志里 grep 得到、用户也认得
        assertTrue(p.contains(PersonaEngine.IMAGE_NOTE_HEADER))
        assertTrue(p.contains("[图片内容描述：一只橘猫趴在键盘上，旁边有个马克杯]"))
        // 最关键的一条：不能让它回答「我看不到图片」
        assertTrue(p.contains("确实看到了"))
        assertFalse("不能出现「禁止描述画面」这种反指令", p.contains("没有截图就绝对不要描述画面"))
    }

    @Test
    fun `云端多模态自己看图时也要标记他发了图`() {
        setAffinity(60)
        val p = PersonaEngine.buildReplyPrompt(
            foregroundApp = null,
            toolResult = null,
            hasImage = true,
            presence = cold,
            userImageKind = "视频",
            userImageAttached = true
        )
        assertTrue(p.contains("视频"))
        assertTrue("图在请求里，也得让它知道自己看得到", p.contains("直接看着图说话"))
    }

    @Test
    fun `没发图时不留图片段`() {
        setAffinity(60)
        val p = prompt()
        assertFalse(p.contains("你眼里的画面"))
        assertFalse(p.contains("直接看着图说话"))
    }

    // ---------------------------------------------------------- 内心独白（0.9.6）

    @Test
    fun `内心独白会进提示词`() {
        setAffinity(60)
        PersonaEngine.setInnerThoughtForTest("也不知道他体检结果怎么样了，有点担心")
        val p = prompt()
        assertTrue(p.contains("也不知道他体检结果怎么样了"))
        assertTrue(p.contains("脑子里冒出来的"))
        // 关键约束：可以顺着提，不能原样念出来、也不能跟话题无关硬塞
        assertTrue(p.contains("无关就别硬提"))
    }

    @Test
    fun `没有独白时不留空段`() {
        setAffinity(60)
        PersonaEngine.setInnerThoughtForTest(null)
        val p = prompt()
        assertFalse(p.contains("脑子里冒出来的"))
    }

    @Test
    fun `三天前的独白不再当成刚刚在想`() {
        setAffinity(60)
        val threeDaysAgo = System.currentTimeMillis() - 3L * 24 * 3_600_000
        PersonaEngine.setInnerThoughtForTest("也不知道他体检结果怎么样了", at = threeDaysAgo)
        val p = prompt()
        // 措辞是「我刚刚还在想」，过期还注入就是让她撒谎 —— 宁可这一轮什么都不提
        assertFalse("过期独白不该进提示词", p.contains("也不知道他体检结果怎么样了"))
        assertFalse(p.contains("脑子里冒出来的"))
        assertNull(PersonaEngine.innerThoughtSnapshot())
    }

    @Test
    fun `两小时前的独白仍然算刚刚`() {
        setAffinity(60)
        val twoHoursAgo = System.currentTimeMillis() - 2L * 3_600_000
        PersonaEngine.setInnerThoughtForTest("也不知道他体检结果怎么样了", at = twoHoursAgo)
        val p = prompt()
        assertTrue(p.contains("也不知道他体检结果怎么样了"))
        assertEquals("也不知道他体检结果怎么样了", PersonaEngine.innerThoughtSnapshot())
    }

    @Test
    fun `提示词给了表情标签白名单`() {
        setAffinity(60)
        val p = prompt()
        assertTrue("要告诉它能用的标签，否则它不会用", p.contains("[大哭]"))
        assertTrue("一句最多一个，不然整条消息都是表情", p.contains("一句最多一个"))
    }

    // ------------------------------------------------------------ 连续情感（0.9.15）

    @Test
    fun `PAD 没起来时提示词里没有情感段落`() {
        // 未 init / 开关关着 —— 段落必须完全不出现，否则老断言会凭空多一堆东西
        setAffinity(60)
        val p = prompt()
        assertFalse(p.contains("【你此刻的感受】"))
    }

    @Test
    fun `PAD 有状态时情感段落插在语气之后`() {
        setAffinity(60)
        val t0 = 1_700_000_000_000L
        // 假时钟必须设：lastDecayAt 是 2023 年的时间戳，用真实时钟读会先衰减 20 个半衰期，
        // 快慢两个变量一起落回基线，落差消失 —— 断言就查不到「表里不一」了
        PadRuntime.clockForTest = { t0 }
        PadRuntime.useStateForTest(
            PadState(
                quickP = 0.55f, quickA = 0.10f, quickD = 0.10f,
                slowP = -0.18f, slowA = 0f, slowD = 0f,
                baseP = 0f, baseA = 0f, baseD = 0f,
                updatedAt = t0, lastDecayAt = t0
            )
        )
        val p = prompt()
        assertTrue("心情段落要在", p.contains("【你此刻的感受】"))
        assertTrue("表里不一的落差也要在", p.contains("说得出话"))
        // 顺序：先说关系关系（toneBlock），再说这会儿的心情，最后才是环境感知
        val toneAt = p.indexOf("【你们现在的关系还算正常】")
        val padAt = p.indexOf("【你此刻的感受】")
        val envAt = p.indexOf("【环境感知 · 你现在身处哪里】")
        assertTrue("toneBlock 要在 PAD 前面", toneAt in 0 until padAt)
        assertTrue("PAD 要在环境感知前面", envAt < 0 || padAt < envAt)
        PadRuntime.resetForTest()
    }

    // ---------------------------------------------------------------- 日记（0.9.15 阶段 3）

    private fun diary(text: String = "今天心里有点沉，还惦记着他体检的事。") = Diary(
        day = "2026-11-11",
        at = 1_700_000_000_000L,
        quick = PAD(-0.2f, -0.1f, 0f),
        slow = PAD(-0.05f, 0f, 0f),
        text = text
    )

    @Test
    fun `冷启动第一轮带上昨晚那句话`() {
        setAffinity(60)
        PersonaEngine.setDiaryForTest(diary())
        PersonaEngine.beginTurnForTest()
        val p = prompt()
        assertTrue("日记段要在", p.contains("【醒来时心里还留着的那点东西】"))
        assertTrue("正文要在", p.contains("还惦记着他体检的事"))
        assertTrue("必须明说不用复述", p.contains("不要照着念"))
    }

    @Test
    fun `第二轮不会再念一遍`() {
        setAffinity(60)
        PersonaEngine.setDiaryForTest(diary())
        PersonaEngine.beginTurnForTest()
        assertTrue(prompt().contains("还惦记着他体检的事"))
        // 下一轮：pendingDiary 已经在上一轮被消费掉了
        PersonaEngine.beginTurnForTest()
        assertFalse(
            "昨天的日记今天每轮都念，比不念更假",
            prompt().contains("还惦记着他体检的事")
        )
    }

    @Test
    fun `没有日记时提示词一个字都不多`() {
        setAffinity(60)
        PersonaEngine.setDiaryForTest(null)
        PersonaEngine.beginTurnForTest()
        assertFalse(prompt().contains("【醒来时心里还留着的那点东西】"))
    }

    @Test
    fun `日记排在语气之前、人设之后`() {
        setAffinity(60)
        PersonaEngine.setDiaryForTest(diary())
        PersonaEngine.beginTurnForTest()
        val p = prompt()
        val styleAt = p.indexOf("你是")
        val diaryAt = p.indexOf("【醒来时心里还留着的那点东西】")
        val toneAt = p.indexOf("【你们现在的关系还算正常】")
        assertTrue("人设必须还在最前面（小模型吃开头）", styleAt in 0 until diaryAt)
        assertTrue("日记要早于关系语气，否则会被当成寒暄", diaryAt < toneAt)
    }
}
