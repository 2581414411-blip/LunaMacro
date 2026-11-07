package com.nexus.agent.system

import com.nexus.agent.copilot.CopilotVision
import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.copilot.ProbeLLM
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统解耦（0.9.0 批次三）的纯逻辑测试。
 *
 * 验三件事，全部是「关掉自动化之后必须成立」的硬要求：
 *  1. ROM 认得出 MIMU / 和顺，也不误伤别家；
 *  2. 关掉之后**提示词里一个字都不提工具** —— 只删 JSON 契约还不够，
 *     模型会凭训练记忆猜出 click_text，所以连「你可调用的工具」这句话都不能有；
 *  3. 人设提示词里要出现「不能操作手机」的行为约束。
 */
class SystemDecouplingTest {

    // ------------------------------------------------------------ ROM 识别

    @Test
    fun `DISPLAY 里带 MIMU 就认成定制系统`() {
        assertTrue(RomProfile.matches("MIMU_20261107_V3"))
        assertTrue("大小写不敏感", RomProfile.matches("mimu-1.2.3"))
    }

    @Test
    fun `品牌写和顺也认`() {
        assertTrue(RomProfile.matches("和顺"))
        assertTrue(RomProfile.matches("和顺OS 2.0"))
    }

    @Test
    fun `常见大厂 ROM 不误判`() {
        assertFalse(RomProfile.matches("Xiaomi HyperOS 1.0"))
        assertFalse(RomProfile.matches("vivo PD2301"))
        assertFalse(RomProfile.matches(""))
        assertFalse(RomProfile.matches(null))
        // 「MIMU」作为子串必须整词命中，别把 MIMUSIC 之类算进来
        assertTrue("MIMUSIC 也含 MIMU，认它没问题（宁可多提示一次）", RomProfile.matches("MIMUSIC"))
    }

    // ------------------------------------------------------------ 提示词

    @Test
    fun `关掉自动化后云端提示词不含任何工具 Schema`() {
        val p = CopilotVision.buildFullSystemPrompt("", null, "宏月")
        assertFalse("不该出现工具 Schema 段", p.contains("你可调用的工具"))
        assertFalse("不该出现工具名", p.contains("click_text"))
        assertFalse("不该给 action 契约", p.contains("\"type\":\"action\""))
        assertFalse("不该给 task 契约", p.contains("\"type\":\"task\""))
        assertTrue("要有纯对话约束", p.contains("不能操作手机"))
        assertTrue("仍然要能给 chat", p.contains("\"type\":\"chat\""))
    }

    @Test
    fun `开着自动化时提示词照常带工具`() {
        val p = CopilotVision.buildFullSystemPrompt(
            """[{"name":"click_text","description":"按文字点击","parameters":{}}]""",
            null,
            "宏月"
        )
        assertTrue(p.contains("click_text"))
        assertTrue(p.contains("\"type\":\"action\""))
        assertFalse(p.contains("不能操作手机"))
    }

    @Test
    fun `人设提示词在关闭时会加上不动手约束`() {
        PersonaEngine.resetForTest()
        val off = PersonaEngine.buildReplyPrompt(null, null, false, automationEnabled = false)
        assertTrue("要有行为约束", off.contains(AutomationPolicy.PURE_CHAT_RULE))
        assertTrue(off.contains("不能操作手机"))

        val on = PersonaEngine.buildReplyPrompt(null, null, false, automationEnabled = true)
        assertFalse("开着时不该出现这条约束", on.contains(AutomationPolicy.PURE_CHAT_RULE))
    }

    @Test
    fun `探针在无工具模式下只允许 chat`() {
        val p = ProbeLLM.buildPrompt(null, "（无：现在只能聊天）")
        assertFalse("不该给出 action 输出格式", p.contains("\"type\":\"action\""))
        assertFalse("不该给出 task 输出格式", p.contains("\"type\":\"task\""))
        assertTrue(p.contains("\"type\":\"chat\""))
        assertTrue(p.contains("不允许操作手机"))
    }

    @Test
    fun `探针在有工具时保持原样`() {
        val p = ProbeLLM.buildPrompt(null, "open_app：打开应用")
        assertTrue(p.contains("\"type\":\"action\""))
        assertTrue(p.contains("open_app"))
    }

    @Test
    fun `兜底文案说的是人话`() {
        assertTrue(AutomationPolicy.ADVISE_FALLBACK.contains("只能动嘴"))
        assertTrue(AutomationPolicy.PURE_CHAT_RULE.contains("不能操作手机"))
    }
}
