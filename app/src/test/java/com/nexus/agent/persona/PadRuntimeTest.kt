package com.nexus.agent.persona

import com.nexus.agent.copilot.PersonaEngine
import com.nexus.agent.data.db.PadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PAD 运行时单测（0.9.15 阶段 1）。
 *
 * 这一层测的不是数学（`PadStateTest` 已经把衰减/EMA/八分区钉死了），
 * 而是**接线之后的行为**：
 *
 *  - 一句话进来，快变量真的动了、慢变量只挪一小步；
 *  - 时间过去，它真的自己衰减回去（「睡一觉气就消了」）；
 *  - 心情标签真的写回了档案（羁绊面板的表情靠它）；
 *  - 没初始化时**一句话都不往提示词里塞**（否则老单测会凭空多一段）。
 *
 * 时钟和深夜都做了注入：衰减曲线全靠时间差，靠 `Thread.sleep` 测 20 分钟不现实，
 * 而「晚上十一点跑测试」和「下午跑测试」拿到两个结果是最难查的那种红。
 */
class PadRuntimeTest {

    /** 假时钟的起点（2023-11-14 的某个整点，随便挑的） */
    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        // 注意顺序：resetForTest 会把 clockForTest 清掉，所以时钟必须在它之后设
        PersonaEngine.resetForTest()
        PadRuntime.lateNightForTest = false
        PadRuntime.clockForTest = { t0 }
    }

    // ------------------------------------------------------------ 开关与初始化

    @Test
    fun `没初始化就一个字都不往提示词里塞`() {
        assertEquals("", PadRuntime.promptBlock())
    }

    @Test
    fun `没状态时当前坐标等于基线`() {
        val s = PadRuntime.current()
        assertEquals(s.base.p, s.quick.p, 1e-4f)
        assertEquals(s.base.a, s.quick.a, 1e-4f)
        assertEquals(s.base.d, s.quick.d, 1e-4f)
    }

    // ------------------------------------------------------------ 一轮对话

    @Test
    fun `被夸之后快变量上扬`() {
        PadRuntime.advanceForTest(null, "谢谢你，真棒")
        val s = PadRuntime.current()
        assertTrue("愉悦度要明显上去", s.quick.p > s.base.p + 0.2f)
        // +P +A +D = 雀跃
        assertEquals("雀跃", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `被凶之后心情落到焦躁`() {
        PadRuntime.advanceForTest(null, "滚，烦死了")
        val s = PadRuntime.current()
        assertTrue("愉悦度要下去", s.quick.p < s.base.p)
        assertTrue("唤醒度反而上来（被凶是紧绷不是发困）", s.quick.a > s.base.a)
        // −P +A −D = 焦躁。注意不是「敌意」：支配感掉了，她更像是被压着而不是压着人
        assertEquals("焦躁", PersonaEngine.profileSnapshot().mood)
    }

    @Test
    fun `全新安装的第一句话不会被算成晾了她很久`() {
        // lastDecayAt = 0 是「第一次」，绝不能当成「距上次过去了 1970 年」，
        // 否则第一句「嗯」就会先吃两次「被晾着」的负分
        PadRuntime.advanceForTest(null, "嗯")
        val s = PadRuntime.current()
        assertEquals(s.base.a, s.quick.a, 1e-3f)
        assertEquals(1, PadRuntime.advances)
    }

    @Test
    fun `慢变量一轮只挪一小步`() {
        PadRuntime.advanceForTest(null, "谢谢你，真棒")
        val s = PadRuntime.current()
        assertTrue("方向要对", s.slow.p > s.base.p)
        assertTrue("一轮最多挪一小步，改的是这周的心情不是这句的", s.slow.p < s.base.p + 0.05f)
    }

    @Test
    fun `闲聊不动长期基调`() {
        repeat(5) { i ->
            val at = t0 + i * 5 * 60_000L
            PadRuntime.clockForTest = { at }
            PadRuntime.advanceForTest(null, "嗯，今天在写代码")
        }
        val s = PadRuntime.current()
        assertEquals("纯闲聊不该把底色带跑", s.base.p, s.slow.p, 0.01f)
    }

    // ------------------------------------------------------------ 时间

    @Test
    fun `快变量二十分钟衰减一半`() {
        PadRuntime.advanceForTest(null, "谢谢你，真棒")
        val peak = PadRuntime.current().quick.p
        val base = PadRuntime.current().base.p

        PadRuntime.clockForTest = { t0 + PAD.QUICK_HALF_LIFE_MS }
        val half = PadRuntime.current().quick.p
        assertEquals("20 分钟后应该正好回到（峰值+基线)/2", (peak + base) / 2f, half, 0.01f)
    }

    @Test
    fun `隔一夜气就消了`() {
        PadRuntime.advanceForTest(null, "滚，烦死了")
        val base = PadRuntime.current().base.p
        PadRuntime.clockForTest = { t0 + 6 * 60 * 60 * 1000L }
        val s = PadRuntime.current()
        assertEquals("快变量落回基线", base, s.quick.p, 0.01f)
        assertEquals("慢变量也淡掉大半", base, s.slow.p, 0.06f)
    }

    @Test
    fun `反复读不会把状态读跑偏`() {
        PadRuntime.advanceForTest(null, "谢谢你，真棒")
        val first = PadRuntime.current().quick.p
        repeat(10) { i ->
            val at = t0 + (i + 1) * 60_000L
            PadRuntime.clockForTest = { at }
            PadRuntime.current()
        }
        // 时钟拨回去再读一次：如果 current() 偷着把衰减后的值写回内存，
        // 这里就会读到被前面十次读「消耗」掉的旧值
        PadRuntime.clockForTest = { t0 }
        assertEquals(first, PadRuntime.current().quick.p, 1e-4f)
    }

    // ------------------------------------------------------------ 提示词

    @Test
    fun `强颜欢笑会被说出来但不许说破`() {
        PadRuntime.useStateForTest(
            PadState(
                quickP = 0.60f, quickA = 0.10f, quickD = 0.10f,
                slowP = -0.20f, slowA = 0f, slowD = 0f,
                baseP = 0f, baseA = 0f, baseD = 0f,
                updatedAt = t0, lastDecayAt = t0
            )
        )
        val block = PadRuntime.promptBlock()
        assertTrue("要有段落标题", block.contains("【你此刻的感受】"))
        assertTrue("要说破落差", block.contains("说得出话"))
        assertTrue("但不能让她自己说破", block.contains("别把这份情绪说破"))
        assertTrue("必须禁止复述这段", block.contains("禁止复述这段文字"))
    }

    @Test
    fun `快慢一致时只给一句顺着说`() {
        PadRuntime.useStateForTest(
            PadState(
                quickP = 0.45f, quickA = 0.05f, quickD = 0.10f,
                slowP = 0.20f, slowA = 0f, slowD = 0f,
                baseP = 0f, baseA = 0f, baseD = 0f,
                updatedAt = t0, lastDecayAt = t0
            )
        )
        val block = PadRuntime.promptBlock()
        assertTrue(block.contains("顺着这个状态说话"))
        assertFalse("没落差就别提表里不一", block.contains("说破"))
    }

    @Test
    fun `心情标签是八分区里的词`() {
        PadRuntime.useStateForTest(
            PadState(
                quickP = 0.50f, quickA = -0.20f, quickD = 0.20f,
                slowP = 0f, slowA = 0f, slowD = 0f,
                baseP = 0f, baseA = 0f, baseD = 0f,
                updatedAt = t0, lastDecayAt = t0
            )
        )
        // +P −A +D = 松弛；这个词必须能被 BondState 认出来，否则表情会退化成 🙂
        val mood = PadRuntime.moodLabel()
        assertEquals("松弛", mood)
        assertEquals("😌", BondState.emojiOf(mood))
    }

    // ------------------------------------------------------------ 基线

    @Test
    fun `改了性格底色立刻跟着变`() {
        val before = PadRuntime.current().base
        PersonaEngine.setProfileForTest(
            PersonaEngine.profileSnapshot().copy(rebelliousness = 100)
        )
        val after = PadRuntime.current().base
        assertTrue("叛逆拉满，支配感该更高", after.d > before.d)
    }

    // ------------------------------------------------------------ 接线

    @Test
    fun `异步推进真的会落地`() {
        // advanceForTest 走的是同步路径，测不到 onTurn 里的协程有没有起得来。
        // 这一条盯的就是那个 scope.launch：scope 没起来的话生产环境永远不推进。
        PadRuntime.onTurn(null, "谢谢你，真棒")
        assertTrue("异步推进没跑起来", waitUntil { PadRuntime.advances > 0 })
        val s = PadRuntime.current()
        assertTrue("推进之后快变量应该在基线上方", s.quick.p > s.base.p)
    }

    @Test
    fun `记账的唯一入口会把 PAD 一起带起来`() {
        // LocalDigitalBeing.propagateTurn 是「每一轮推进且只推一次」的汇点（0.9.10 的教训）。
        // PAD 必须挂在它下面，不能另开一个入口 —— 否则又会出现双倍推进。
        LocalDigitalBeing.resetForTest()
        LocalDigitalBeing.propagateTurn(null, "谢谢你，真棒")
        assertTrue("propagateTurn 没把 PAD 带起来", waitUntil { PadRuntime.advances > 0 })
    }

    @Test
    fun `调一次只推一次`() {
        // 5 秒内的重复记账在 LocalDigitalBeing 里就被去重了；这一条确认 PAD 不会因为
        // 协程调度慢半拍而重复推进（那会让「骂一句」变成「骂两句」）。
        LocalDigitalBeing.resetForTest()
        LocalDigitalBeing.propagateTurn(null, "谢谢你，真棒")
        assertTrue(waitUntil { PadRuntime.advances == 1 })
        Thread.sleep(120)
        assertEquals("一次记账只该推一次", 1, PadRuntime.advances)
    }

    private fun waitUntil(timeoutMs: Long = 3_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(10)
        }
        return cond()
    }
}
