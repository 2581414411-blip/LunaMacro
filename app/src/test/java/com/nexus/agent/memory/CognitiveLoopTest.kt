package com.nexus.agent.memory

import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.RawTrace
import com.nexus.agent.habit.HabitEvent
import com.nexus.agent.habit.HabitSkill
import com.nexus.agent.habit.HabitTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 认知闭环合并的单测（0.9.16）。
 *
 * 这一版把「自我学习」（文件里，用户点收下才转正）并进「外挂记忆」（Room 里，
 * 攒够次数自动转正）。真正需要钉死的是**两条路径的边界**：
 *
 *  - 手动路径没有意图 → 永远只能产 candidate，攒一万次也不许自己变 active；
 *  - 没有名字的候选 → 路由层不许回放（名字是它唯一的意图来源）；
 *  - 一次回放失败 → suspect（当场不拦），不是永久作废；
 *  - 关掉外挂记忆 → 画像一个字都不许进提示词（0.9.15 之前的真 bug）。
 */
class CognitiveLoopTest {

    private fun step(text: String) = ActionStep("click_text", mapOf("text" to text, "index" to "0"))

    // ==================== ① 适配器切分 ====================

    private fun ev(
        at: Long,
        kind: String,
        label: String = "",
        screen: String = "",
        pkg: String = "com.tencent.mm"
    ) = HabitEvent(at = at, pkg = pkg, app = "微信", kind = kind, screen = screen, label = label)

    @Test
    fun `十五秒断档切成两段`() {
        val t = 1_700_000_000_000L
        val events = listOf(
            ev(t, HabitEvent.KIND_OPEN, screen = "微信"),
            ev(t + 1_000, HabitEvent.KIND_TAP, "搜索"),
            ev(t + 2_000, HabitEvent.KIND_TAP, "订阅号"),
            // 断档 20 秒（> 15 秒）→ 新的一段
            ev(t + 22_000, HabitEvent.KIND_TAP, "通讯录"),
            ev(t + 23_000, HabitEvent.KIND_TAP, "新的朋友")
        )
        val eps = AccessibilityTraceAdapter.segment(events)
        assertEquals(2, eps.size)
        assertEquals(listOf("搜索", "订阅号"), eps[0].labels)
        assertEquals("微信", eps[0].screen)
        assertEquals(listOf("通讯录", "新的朋友"), eps[1].labels)
    }

    @Test
    fun `同一毫秒的事件保持传入顺序`() {
        val t = 1_700_000_000_000L
        val eps = AccessibilityTraceAdapter.segment(
            listOf(
                ev(t, HabitEvent.KIND_TAP, "搜索"),
                ev(t, HabitEvent.KIND_TAP, "订阅号"),
                ev(t + 500, HabitEvent.KIND_TAP, "搜索")
            )
        )
        assertEquals(1, eps.size)
        // 顺序反了整条记忆就废了：这不是「集合」，是「序列」
        assertEquals(listOf("搜索", "订阅号", "搜索"), eps[0].labels)
    }

    @Test
    fun `换应用一定断段`() {
        val t = 1_700_000_000_000L
        val eps = AccessibilityTraceAdapter.segment(
            listOf(
                ev(t, HabitEvent.KIND_TAP, "搜索"),
                ev(t + 500, HabitEvent.KIND_TAP, "订阅号"),
                ev(t + 800, HabitEvent.KIND_TAP, "播放", pkg = "com.netease.cloudmusic")
            )
        )
        // 后一段只有一步，不够 MIN_STEPS → 丢掉，只剩第一段
        assertEquals(1, eps.size)
        assertEquals("com.tencent.mm", eps[0].pkg)
    }

    @Test
    fun `手动轨迹落库时没有意图`() {
        val ep = AccessibilityTraceAdapter.Episode(
            pkg = "com.tencent.mm",
            app = "微信",
            startAt = 1L,
            endAt = 2L,
            screen = "微信",
            labels = listOf("搜索", "订阅号")
        )
        val row = AccessibilityTraceAdapter.toTrace(ep)
        assertEquals(RawTrace.SOURCE_USER, row.source)
        assertEquals(RawTrace.KIND_USER_PATH, row.kind)
        assertEquals("", row.intent)
        assertEquals("", row.intentKey)
        assertEquals("com.tencent.mm#user", row.fingerprint)
        assertEquals(2, ActionStep.decodeAll(row.steps).size)
    }

    // ==================== ② 无意图组绝不产生 active ====================

    private fun cluster(occurrences: Int, days: Int) = MemoryConsolidator.UserCluster(
        pkg = "com.tencent.mm",
        app = "微信",
        key = "com.tencent.mm#user|微信搜索订阅号",
        name = "微信 · 搜索 → 订阅号",
        steps = listOf(step("搜索"), step("订阅号")),
        variants = listOf(listOf(step("搜索"), step("订阅号"))),
        coverage = 1f,
        occurrences = occurrences,
        days = days,
        lastAt = 1_700_000_000_000L,
        ids = listOf(1L, 2L, 3L)
    )

    @Test
    fun `手动路径攒再多也只能是候选`() {
        val row = MemoryConsolidator.userRowOf(cluster(occurrences = 99, days = 30), 1_700_000_000_000L)
        assertEquals(ActionMemory.STATE_CANDIDATE, row.state)
        assertEquals(ActionMemory.ORIGIN_USER, row.origin)
        assertEquals("", row.label)
        assertEquals(99, row.successCount)
        // 没名字 → 连回放资格都没有
        assertFalse(row.isReplayable)
    }

    @Test
    fun `命名闸把 active 压回候选`() {
        assertEquals(
            ActionMemory.STATE_CANDIDATE,
            MemoryConsolidator.gatedState(ActionMemory.ORIGIN_USER, "", ActionMemory.STATE_ACTIVE)
        )
        // agent 路径不受影响：它的意图来自用户原话，不需要再起名
        assertEquals(
            ActionMemory.STATE_ACTIVE,
            MemoryConsolidator.gatedState(ActionMemory.ORIGIN_AGENT, "", ActionMemory.STATE_ACTIVE)
        )
        // 有名字就放行
        assertEquals(
            ActionMemory.STATE_ACTIVE,
            MemoryConsolidator.gatedState(ActionMemory.ORIGIN_USER, "早上签到", ActionMemory.STATE_ACTIVE)
        )
    }

    // ==================== ③ 命名门槛（路由层） ====================

    private fun mem(
        state: String = ActionMemory.STATE_ACTIVE,
        origin: String = ActionMemory.ORIGIN_AGENT,
        label: String = "",
        consecutiveFail: Int = 0
    ) = ActionMemory(
        memoryKey = "com.tencent.mm#user|早上签到",
        intent = "微信 · 搜索 → 订阅号",
        intentKey = "早上签到",
        fingerprint = "com.tencent.mm#user",
        pkg = "com.tencent.mm",
        origin = origin,
        label = label,
        steps = "click_text\ttext=搜索\nclick_text\ttext=订阅号",
        state = state,
        consecutiveFail = consecutiveFail
    )

    @Test
    fun `手动记忆没名字不许回放`() {
        val nameless = mem(origin = ActionMemory.ORIGIN_USER, label = "")
        assertFalse(MemoryRouter.Scored(nameless, 0.1f, 1f, pkgMatch = true).eligible)

        val named = mem(origin = ActionMemory.ORIGIN_USER, label = "早上签到")
        assertTrue(MemoryRouter.Scored(named, 0.1f, 1f, pkgMatch = true).eligible)
    }

    @Test
    fun `手动记忆必须站在同一个应用里`() {
        val named = mem(origin = ActionMemory.ORIGIN_USER, label = "早上签到")
        // 包名对不上（用户现在在别的 App 里说同一句话）→ 不回放
        assertFalse(MemoryRouter.Scored(named, 0.1f, 1f, pkgMatch = false).eligible)
    }

    @Test
    fun `agent记忆仍然要过界面指纹闸`() {
        val agent = mem(origin = ActionMemory.ORIGIN_AGENT)
        assertFalse(MemoryRouter.Scored(agent, 0.3f, 1f, pkgMatch = true).eligible)
        assertTrue(MemoryRouter.Scored(agent, 0.9f, 1f, pkgMatch = true).eligible)
    }

    // ==================== ④⑤ suspect 恢复与报废 ====================

    @Test
    fun `suspect 当晚又走通就恢复 active`() {
        val suspect = mem(state = ActionMemory.STATE_SUSPECT, consecutiveFail = 1)
        assertEquals(
            ActionMemory.STATE_ACTIVE,
            MemoryConsolidator.nextStateOnSuccess(suspect, suspect.successCount + 3)
        )
    }

    @Test
    fun `失败一次转 suspect 第二次才作废`() {
        assertEquals(
            ActionMemory.STATE_SUSPECT,
            MemoryConsolidator.stateOnFail(ActionMemory.STATE_ACTIVE, 1)
        )
        assertEquals(
            ActionMemory.STATE_REVOKED,
            MemoryConsolidator.stateOnFail(ActionMemory.STATE_SUSPECT, 2)
        )
        // 本来就不可回放的，失败不该把它变成 suspect（那是「曾经能跑」才有的状态）
        assertEquals(
            ActionMemory.STATE_CANDIDATE,
            MemoryConsolidator.stateOnFail(ActionMemory.STATE_CANDIDATE, 1)
        )
    }

    @Test
    fun `用户拒绝过的动作不会自己长回来`() {
        val rejected = mem(
            state = ActionMemory.STATE_REJECTED,
            origin = ActionMemory.ORIGIN_USER,
            label = "早上签到"
        )
        assertEquals(
            ActionMemory.STATE_REJECTED,
            MemoryConsolidator.nextStateOnSuccess(rejected, 100)
        )
        assertFalse(rejected.isReplayable)
    }

    // ==================== ⑥ 画像合并 ====================

    @Test
    fun `一份画像里只有一段习惯`() {
        val block = MemoryProfile.render(
            MemoryProfile.Summary(
                topApps = listOf("com.tencent.mm" to 3),
                topIntents = listOf("搜订阅号" to 2),
                busiestSlot = 3
            )
        )
        assertTrue(block.isNotBlank())
        // 只允许一个抬头：以前人格提示词里同时有 HabitMemory 和 MemoryProfile 两段
        assertEquals(1, Regex("常做的事").findAll(block).count())
    }

    @Test
    fun `人格提示词不再注入第二份习惯画像`() {
        val candidates = listOf(
            File("src/main/java/com/nexus/agent/copilot/PersonaEngine.kt"),
            File("app/src/main/java/com/nexus/agent/copilot/PersonaEngine.kt")
        )
        val src = candidates.firstOrNull { it.isFile }
        assumeTrue("找不到 PersonaEngine 源码，跳过源码守卫", src != null)
        val text = src!!.readText(Charsets.UTF_8)
        // 这条守卫是给「以后有人顺手把 HabitMemory.block 加回来」准备的
        assertFalse(
            "PersonaEngine 又把 HabitMemory.block 注回提示词了（画像只能有一份）",
            text.contains("HabitMemory.block")
        )
        assertTrue(text.contains("MemoryProfile.block"))
    }

    // ==================== ⑦ 关闭语义（钉住 0.9.15 的 bug） ====================

    @Test
    fun `关掉外挂记忆画像一个字都不注入`() {
        assertEquals("", MemoryProfile.gatedBlock(false) { "【他最近常做的事】…" })
        assertEquals("正文", MemoryProfile.gatedBlock(true) { "正文" })
    }

    // ==================== ⑧ 老数据导入 ====================

    private fun skill(
        name: String,
        state: String,
        pkg: String = "com.tencent.mm",
        steps: List<String> = listOf("搜索", "订阅号"),
        occurrences: Int = 5
    ) = HabitSkill(
        id = name,
        state = state,
        pkg = pkg,
        app = "微信",
        name = name,
        steps = steps.map { HabitTrace.TraceStep("微信", it) },
        occurrences = occurrences,
        days = 3,
        lastAt = 1_700_000_000_000L,
        createdAt = 1_699_000_000_000L
    )

    @Test
    fun `老技能十条导成十行且状态映射正确`() {
        val skills = buildList {
            repeat(4) { add(skill("已收下$it", HabitSkill.STATE_ACTIVE)) }
            repeat(4) { add(skill("候选$it", HabitSkill.STATE_CANDIDATE)) }
            repeat(2) { add(skill("不要$it", HabitSkill.STATE_REJECTED)) }
        }
        val rows = LegacyHabitImporter.plan(skills, emptySet(), 1_700_000_000_000L)
        assertEquals(10, rows.size)
        assertEquals(4, rows.count { it.state == ActionMemory.STATE_ACTIVE })
        assertEquals(4, rows.count { it.state == ActionMemory.STATE_CANDIDATE })
        assertEquals(2, rows.count { it.state == ActionMemory.STATE_REJECTED })
        // 收下过的必须带名字，否则导入完也回放不了
        val active = rows.first { it.state == ActionMemory.STATE_ACTIVE }
        assertTrue(active.label.isNotBlank())
        assertTrue(active.isReplayable)
        // 候选不带名字
        assertEquals("", rows.first { it.state == ActionMemory.STATE_CANDIDATE }.label)
        // rejected 不能映射成 revoked（revoked 会被下一晚的成功轨迹救回来）
        assertEquals(0, rows.count { it.state == ActionMemory.STATE_REVOKED })
    }

    @Test
    fun `重复导入不翻倍`() {
        val skills = List(10) { skill("动作$it", HabitSkill.STATE_ACTIVE) }
        val first = LegacyHabitImporter.plan(skills, emptySet(), 1_700_000_000_000L)
        assertEquals(10, first.size)
        // 第二次：库里已经有这十个键 → 一条都不该再产生
        val second = LegacyHabitImporter.plan(skills, first.map { it.memoryKey }.toSet(), 1_700_000_000_000L)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `点不着的技能不导入`() {
        val bad = skill("空步骤", HabitSkill.STATE_ACTIVE, steps = listOf("", " "))
        assertNull(LegacyHabitImporter.rowOf(bad, 1L))
        val one = skill("只有一步", HabitSkill.STATE_ACTIVE, steps = listOf("搜索"))
        assertNull(LegacyHabitImporter.rowOf(one, 1L))
    }

    // ==================== 收下并命名 ====================

    @Test
    fun `收下时必须给名字`() {
        val cand = mem(state = ActionMemory.STATE_CANDIDATE, origin = ActionMemory.ORIGIN_USER)
        assertNull(MemoryAcceptance.acceptRow(cand, "   ", 100L))
        val ok = MemoryAcceptance.acceptRow(cand, " 早上 签到 ", 100L)
        assertNotNull(ok)
        assertEquals("早上 签到", ok!!.label)
        assertEquals(ActionMemory.STATE_ACTIVE, ok.state)
        assertEquals("早上签到", ok.intentKey)
        assertTrue(ok.isReplayable)
        // 身份证不变：改了它，下一夜会再长出一个同名候选
        assertEquals(cand.memoryKey, ok.memoryKey)
    }

    @Test
    fun `agent 记忆不能走收下流程`() {
        val agent = mem(state = ActionMemory.STATE_CANDIDATE, origin = ActionMemory.ORIGIN_AGENT)
        assertNull(MemoryAcceptance.acceptRow(agent, "随便起个名", 100L))
    }

    // ==================== 手动轨迹聚类 ====================

    private fun userTrace(id: Long, at: Long, labels: List<String>) = RawTrace(
        id = id,
        kind = RawTrace.KIND_USER_PATH,
        intent = "",
        intentKey = "",
        fingerprint = AccessibilityTraceAdapter.fingerprintOf("com.tencent.mm"),
        screenKeys = "t:微信",
        pkg = "com.tencent.mm",
        steps = ActionStep.encodeAll(labels.map { AccessibilityTraceAdapter.clickOf(it) }),
        outcome = RawTrace.OUTCOME_SUCCESS,
        detail = "微信",
        source = RawTrace.SOURCE_USER,
        createdAt = at
    )

    @Test
    fun `同一条路跨夜聚成一簇`() {
        val day1 = 1_700_000_000_000L
        val day2 = day1 + 86_400_000L
        val traces = listOf(
            userTrace(1, day1, listOf("搜索", "订阅号")),
            userTrace(2, day1 + 60_000, listOf("搜索", "订阅号", "某篇文章")),
            userTrace(3, day2, listOf("搜索", "订阅号"))
        )
        val clusters = MemoryConsolidator.clusterUserPaths(traces)
        assertEquals(1, clusters.size)
        val c = clusters[0]
        assertEquals(3, c.occurrences)
        assertEquals(2, c.days)
        assertEquals(listOf("搜索", "订阅号"), c.steps.map { it.args["text"] })
        assertTrue(c.name.startsWith("微信"))
        // 键跨夜稳定，才能靠同一行累加次数
        assertTrue(c.key.contains("#user|"))
    }

    @Test
    fun `末尾是文章标题也能聚成一簇`() {
        val day1 = 1_700_000_000_000L
        val day2 = day1 + 86_400_000L
        // 同一件事（搜订阅号进去看文章），最后一步每篇文章都不一样。
        // 严格按种子比相似度只有 0.67，会被切成两簇 → 永远攒不到 3 次、永远学不会。
        val traces = listOf(
            userTrace(1, day1, listOf("搜索", "订阅号", "某篇文章")),
            userTrace(2, day2, listOf("搜索", "订阅号", "今天这篇")),
            userTrace(3, day2 + 1_000, listOf("搜索", "订阅号"))
        )
        val clusters = MemoryConsolidator.clusterUserPaths(traces)
        assertEquals(1, clusters.size)
        val c = clusters[0]
        assertEquals(3, c.occurrences)
        assertEquals(2, c.days)
        assertEquals(listOf("搜索", "订阅号"), c.steps.map { it.args["text"] })
        assertTrue("覆盖率应 ≥ 0.6，实际 ${c.coverage}", c.coverage >= 0.6f)
    }

    @Test
    fun `两种走法不会硬压成一条`() {
        val day1 = 1_700_000_000_000L
        val traces = listOf(
            userTrace(1, day1, listOf("搜索", "订阅号")),
            userTrace(2, day1 + 1000, listOf("购物车", "结算", "确认"))
        )
        val clusters = MemoryConsolidator.clusterUserPaths(traces)
        assertEquals(2, clusters.size)
        clusters.forEach { assertTrue(it.coverage >= 0.99f) }
    }
}
