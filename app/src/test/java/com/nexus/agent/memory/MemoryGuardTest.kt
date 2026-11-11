package com.nexus.agent.memory

import com.nexus.agent.data.db.ActionMemory
import com.nexus.agent.data.db.IntentTip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全闸与路由打分单测（0.9.13）。
 *
 * 这一组是「出事就会上新闻」的部分：
 *  - 含支付/账号/输入类的动作，连记忆都不该产；
 *  - 状态不是 active 的记忆，就算界面和意图都对上也不许回放；
 *  - 界面相似度不够时，光靠意图像不能动手。
 */
class MemoryGuardTest {

    private fun tap(text: String) = ActionStep("click_text", mapOf("text" to text))
    private fun input(text: String) = ActionStep("input_text", mapOf("text" to text))

    @Test
    fun `正常点击导航动作可以回放`() {
        assertEquals("", MemoryGuard.rejectReason(listOf(tap("搜索"), tap("订阅号"), tap("返回"))))
    }

    @Test
    fun `空轨迹不能变成记忆`() {
        assertEquals("轨迹为空", MemoryGuard.rejectReason(emptyList()))
    }

    @Test
    fun `支付类动作一票否决`() {
        val r = MemoryGuard.rejectReason(listOf(tap("搜索"), tap("立即购买")))
        assertTrue(r.contains("一票否决"))
        assertTrue(MemoryGuard.isBlocked(listOf(tap("确认支付"))))
    }

    @Test
    fun `输入类动作不可回放`() {
        val r = MemoryGuard.rejectReason(listOf(tap("搜索框"), input("订阅号")))
        assertTrue(r.contains("不可回放"))
        assertTrue(MemoryGuard.hasUnreplayableStep(listOf(input("随便什么"))))
    }

    @Test
    fun `步骤太多的不算习惯`() {
        val many = (1..ActionMemory.MAX_STEPS + 1).map { tap("按钮$it") }
        assertTrue(MemoryGuard.rejectReason(many).contains("上限"))
    }

    @Test
    fun `任务原话含敏感词时记忆不介入`() {
        assertTrue(MemoryGuard.taskBlocked("帮我把这个订单支付了"))
        assertTrue(MemoryGuard.taskBlocked("改一下登录密码"))
        assertFalse(MemoryGuard.taskBlocked("打开微信搜订阅号"))
    }

    @Test
    fun `会改动内容的动作被标为需要提醒`() {
        assertTrue(MemoryGuard.isCaution(listOf(tap("搜索"), tap("发送"))))
        assertEquals("发送", MemoryGuard.cautionHit(listOf(tap("搜索"), tap("发送"))))
        assertFalse(MemoryGuard.isCaution(listOf(tap("搜索"), tap("订阅号"))))
    }
}

/**
 * 路由打分单测（0.9.13）。
 *
 * 只测纯函数部分（打分 / 经验捞取），不碰数据库 ——
 * `route()` 本身的库操作要么在真机上验，要么由集成测试覆盖，
 * 但「什么条件下才允许拦截」这条判断必须能在单测里被钉死。
 */
class MemoryRouterTest {

    private fun snapshot(fp: String, keys: String) = MemoryScreen.Snapshot(
        pkg = fp.substringBefore('#'),
        uiJson = null,
        elementCount = 0,
        fingerprint = fp,
        keysCsv = keys
    )

    private fun memory(
        state: String = ActionMemory.STATE_ACTIVE,
        fp: String = "com.tencent.mm#abcd",
        keys: String = "t:搜索\u0001t:订阅号",
        intentKey: String = "打开微信搜订阅号",
        steps: String = "click_text\ttext=搜索"
    ) = ActionMemory(
        memoryKey = "$fp|$intentKey",
        intent = "打开微信搜订阅号",
        intentKey = intentKey,
        fingerprint = fp,
        screenKeys = keys,
        pkg = fp.substringBefore('#'),
        steps = steps,
        state = state
    )

    @Test
    fun `界面与意图都对上才允许回放`() {
        val s = MemoryRouter.score(
            memory(),
            MemoryRouter.Input("打开微信搜订阅号", snapshot("com.tencent.mm#abcd", "t:搜索\u0001t:订阅号")),
            "打开微信搜订阅号"
        )
        assertEquals(1f, s.screenSim, 0.001f)
        assertEquals(1f, s.intentSim, 0.001f)
        assertTrue(s.eligible)
    }

    @Test
    fun `候选态不许回放`() {
        val s = MemoryRouter.score(
            memory(state = ActionMemory.STATE_CANDIDATE),
            MemoryRouter.Input("打开微信搜订阅号", snapshot("com.tencent.mm#abcd", "t:搜索\u0001t:订阅号")),
            "打开微信搜订阅号"
        )
        assertFalse(s.eligible)
    }

    @Test
    fun `过期与作废都不许回放`() {
        listOf(ActionMemory.STATE_EXPIRED, ActionMemory.STATE_REVOKED).forEach { st ->
            val s = MemoryRouter.score(
                memory(state = st),
                MemoryRouter.Input("打开微信搜订阅号", snapshot("com.tencent.mm#abcd", "t:搜索\u0001t:订阅号")),
                "打开微信搜订阅号"
            )
            assertFalse("state=$st", s.eligible)
        }
    }

    @Test
    fun `界面差太多时意图再像也不许回放`() {
        val s = MemoryRouter.score(
            memory(),
            MemoryRouter.Input("打开微信搜订阅号", snapshot("com.tencent.mm#9f9f", "t:购物车\u0001t:我的")),
            "打开微信搜订阅号"
        )
        assertTrue(s.screenSim < 0.1f)
        assertFalse(s.eligible)
    }

    @Test
    fun `意图差太多时界面一样也不许回放`() {
        val s = MemoryRouter.score(
            memory(),
            MemoryRouter.Input("打开淘宝看看购物车", snapshot("com.tencent.mm#abcd", "t:搜索\u0001t:订阅号")),
            IntentKey.normalize("打开淘宝看看购物车")
        )
        assertFalse(s.eligible)
    }

    @Test
    fun `经验按界面捞取`() {
        MemoryRouter.useTipsForTest(
            listOf(
                IntentTip(
                    tipKey = "com.tencent.mm#abcd|搜索",
                    fingerprint = "com.tencent.mm#abcd",
                    intentKey = "搜索",
                    tip = "先点搜索框等键盘弹出"
                )
            )
        )
        assertEquals(listOf("先点搜索框等键盘弹出"), MemoryRouter.tipsFor("com.tencent.mm#abcd"))
        assertTrue(MemoryRouter.tipsFor("com.taobao#0000").isEmpty())
        MemoryRouter.resetForTest()
    }

    @Test
    fun `界面为空时不注入任何经验`() {
        MemoryRouter.useTipsForTest(
            listOf(IntentTip(tipKey = "k", fingerprint = "com.a#1", intentKey = "x", tip = "t"))
        )
        assertTrue(MemoryRouter.tipsFor("").isEmpty())
        assertTrue(MemoryRouter.tipsBlockForScreen("com.a", null).isEmpty())
        MemoryRouter.resetForTest()
    }
}
