package com.nexus.agent.memory

import com.nexus.agent.data.db.RawTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 夜间批量巩固的单测（0.9.14）。
 *
 * 这一版把学习从「跑一次学一次」改成「攒一天学一次」，
 * 所以真正需要钉死的是**统计口径**：
 *  - 分组必须按「界面 + 意图」，不能只按意图（同一个意图在不同界面是两条路）；
 *  - 骨架必须能忽略离群样本（用户那天走了条更短的路，不该把收敛好的骨架换掉）；
 *  - 模型输出解析失败必须返回空表，让调用方退化为规则经验，**不能瞎挂**。
 */
class MemoryBatchTest {

    private fun trace(
        kind: String = RawTrace.KIND_AGENT_SUCCESS,
        fingerprint: String = "com.tencent.mm#abcd",
        intentKey: String = "打开微信搜订阅号",
        intent: String = "打开微信搜订阅号",
        pkg: String = "com.tencent.mm",
        outcome: String = RawTrace.OUTCOME_SUCCESS,
        steps: String = "click_text\ttext=搜索",
        failStep: Int = 0,
        detail: String = "",
        createdAt: Long = 1_700_000_000_000L
    ) = RawTrace(
        kind = kind,
        intent = intent,
        intentKey = intentKey,
        fingerprint = fingerprint,
        screenKeys = "t:搜索",
        pkg = pkg,
        steps = steps,
        outcome = outcome,
        failStep = failStep,
        detail = detail,
        createdAt = createdAt
    )

    private fun step(text: String) = ActionStep("click_text", mapOf("text" to text))

    private fun variant(vararg labels: String) = labels.map { step(it) }

    // ---- 分组 ----

    @Test
    fun `按界面加意图分组，同一意图不同界面不算一组`() {
        val traces = listOf(
            trace(fingerprint = "fpA"),
            trace(fingerprint = "fpA"),
            trace(fingerprint = "fpB"),
            trace(fingerprint = "fpA", intentKey = "打开微信搜公众号", intent = "打开微信搜公众号")
        )
        val groups = MemoryConsolidator.group(traces)
        assertEquals(3, groups.size)
        val a = groups.first { it.fingerprint == "fpA" && it.intentKey == "打开微信搜订阅号" }
        assertEquals(2, a.successes.size)
    }

    @Test
    fun `指纹为空的轨迹不进任何组`() {
        val groups = MemoryConsolidator.group(
            listOf(trace(fingerprint = ""), trace(fingerprint = "   "))
        )
        assertTrue("空指纹不能学出「在任何界面都能跑」的假记忆", groups.isEmpty())
    }

    @Test
    fun `成功的进成功桶，失败和回放失败分开装`() {
        val groups = MemoryConsolidator.group(
            listOf(
                trace(),
                trace(kind = RawTrace.KIND_AGENT_FAIL, outcome = RawTrace.OUTCOME_FAILED, failStep = 2),
                trace(kind = RawTrace.KIND_REPLAY_FAIL, outcome = RawTrace.OUTCOME_FAILED, failStep = 1),
                trace(kind = RawTrace.KIND_AGENT_ABORT, outcome = RawTrace.OUTCOME_ABORTED)
            )
        )
        assertEquals(1, groups.size)
        val g = groups[0]
        assertEquals(1, g.successes.size)
        assertEquals(1, g.failures.size)
        assertEquals(1, g.replayFails.size) // 中断那条谁都不进
    }

    // ---- 骨架 ----

    @Test
    fun `多条轨迹求骨架，偶尔才点的那步被剔掉`() {
        val merged = MemoryConsolidator.mergeMany(
            listOf(
                variant("搜索", "订阅号"),
                variant("搜索", "订阅号", "某一篇文章"),
                variant("搜索", "订阅号")
            )
        )
        assertEquals(listOf("搜索", "订阅号"), merged.map { it.args["text"] })
    }

    @Test
    fun `离群样本不会把已经收敛的骨架换掉`() {
        // 用户那天走了一条完全不同的短路径：与累积骨架的 LCS 只有 1 步
        val merged = MemoryConsolidator.mergeMany(
            listOf(variant("搜索", "订阅号"), variant("搜索", "购物车"))
        )
        assertEquals(listOf("搜索", "订阅号"), merged.map { it.args["text"] })
    }

    @Test
    fun `只有一个样本时原样返回`() {
        val merged = MemoryConsolidator.mergeMany(listOf(variant("搜索", "订阅号", "文章")))
        assertEquals(3, merged.size)
    }

    @Test
    fun `两种走法骨架太短就不学`() {
        val variants = listOf(
            variant("搜索", "订阅号"),
            variant("搜索", "订阅号", "某文章", "点赞", "返回", "评论")
        )
        val merged = MemoryConsolidator.mergeMany(variants)
        val cov = MemoryConsolidator.coverage(merged, variants)
        assertTrue("coverage=$cov", cov < MemoryConsolidator.MIN_COVERAGE)
    }

    @Test
    fun `主干一致时允许学下来`() {
        val variants = listOf(
            variant("搜索", "订阅号"),
            variant("搜索", "订阅号", "广告"),
            variant("搜索", "订阅号")
        )
        val merged = MemoryConsolidator.mergeMany(variants)
        val cov = MemoryConsolidator.coverage(merged, variants)
        assertTrue("coverage=$cov", cov >= MemoryConsolidator.MIN_COVERAGE)
    }

    // ---- 模型输出解析 ----

    @Test
    fun `经验按序号对回场景`() {
        val raw = "1|在搜索页先点搜索框等键盘弹出\n2| 经验：点发布前先往下翻确认加载完\n乱码没有竖线\n9|越界的序号"
        val map = MemoryConsolidator.parseTipLines(raw, 2)
        assertEquals("在搜索页先点搜索框等键盘弹出", map[1])
        assertEquals("点发布前先往下翻确认加载完", map[2])
        assertEquals(2, map.size)
    }

    @Test
    fun `解析不出东西时返回空表而不是瞎猜`() {
        assertTrue(MemoryConsolidator.parseTipLines("我给你写了一段散文，没有序号", 2).isEmpty())
        assertTrue(MemoryConsolidator.parseTipLines(null, 2).isEmpty())
        assertTrue(MemoryConsolidator.parseTipLines("1|有内容", 0).isEmpty())
    }

    @Test
    fun `没有模型时的兜底经验必须说明证据`() {
        val ask = MemoryConsolidator.Ask(
            key = "k",
            fingerprint = "fp",
            intentKey = "打开微信搜订阅号",
            pkg = "com.tencent.mm",
            intent = "打开微信搜订阅号",
            fails = 3,
            evidence = "",
            fromReplay = false
        )
        val tip = MemoryConsolidator.fallbackTip(ask)
        assertTrue(tip, tip.contains("3"))
        assertFalse(tip, tip.contains("注意检查"))
    }

    // ---- 偏好统计 ----

    @Test
    fun `偏好只数成功轨迹，失败的不算他会做的事`() {
        val traces = listOf(
            trace(),
            trace(),
            trace(kind = RawTrace.KIND_AGENT_FAIL, outcome = RawTrace.OUTCOME_FAILED),
            trace(pkg = "com.taobao", intent = "把购物车结算", intentKey = "把购物车结算",
                fingerprint = "tb#1")
        )
        val s = MemoryProfile.summarize(traces)
        assertEquals("com.tencent.mm" to 2, s.topApps.first())
        assertEquals(2, s.topApps.size)
        assertTrue(s.topApps.none { it.second == 3 })
    }

    @Test
    fun `偏好空数据时不产出任何块`() {
        assertTrue(MemoryProfile.render(MemoryProfile.summarize(emptyList())).isEmpty())
    }

    @Test
    fun `偏好块必须带「不是要求」的免责句`() {
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 30)
        }.timeInMillis
        val text = MemoryProfile.render(
            MemoryProfile.summarize(listOf(trace(createdAt = at)))
        )
        assertTrue(text, text.contains("常用应用"))
        assertTrue(text, text.contains("晚上"))
        assertTrue("少了这句她会在聊天里主动汇报「我注意到你常刷微信」", text.contains("不要主动提"))
    }

    // ---- 夜间排程 ----

    @Test
    fun `夜间任务落在凌晨三点到下次凌晨三点之间`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        val delay = MemoryNightShift.nextNightDelayMs(now)
        assertTrue("delay=$delay", delay in 1..(24L * 60 * 60 * 1000))

        val at = Calendar.getInstance().apply { timeInMillis = now + delay }
        assertEquals(MemoryNightShift.NIGHT_HOUR, at.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, at.get(Calendar.MINUTE))
    }

    @Test
    fun `正好三点时排到明天而不是现在`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, MemoryNightShift.NIGHT_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val delay = MemoryNightShift.nextNightDelayMs(now)
        assertEquals(24L * 60 * 60 * 1000, delay)
    }
}
