package com.nexus.agent.qq

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 四道闸的单测（0.9.25 批次 E）。
 *
 * 这个类是整个 QQ 功能里**唯一能纯 JVM 测**的部分（不碰 Notification / Context），
 * 而它恰好也是最容易出事的部分 —— 漏一道闸，用户就会看到自己跟自己聊 20 分钟。
 */
class QqGuardTest {

    private val t0 = 1_700_000_000_000L

    @Before
    fun reset() {
        QqGuard.reset()
    }

    // ---------------------------------------------------------------- 闸 1：自己发的

    @Test
    fun `标题等于自己的昵称时直接丢弃`() {
        val gate = QqGuard.check(
            title = "大肥鱼",
            content = "在吗",
            session = "s1",
            selfNick = "大肥鱼",
            now = t0
        )
        assertEquals(QqGuard.Gate.SELF, gate)
    }

    @Test
    fun `没配昵称时不会误判自发`() {
        val gate = QqGuard.check("大肥鱼", "在吗", "s1", selfNick = "", now = t0)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    @Test
    fun `昵称带空格也能认出来`() {
        val gate = QqGuard.check("  大肥鱼 ", "在吗", "s1", selfNick = "大肥鱼", now = t0)
        assertEquals(QqGuard.Gate.SELF, gate)
    }

    // ---------------------------------------------------------------- 闸 2：指纹回环

    @Test
    fun `刚发过的内容 60 秒内会被拦下`() {
        QqGuard.markSent("s1", "在的", now = t0)
        val gate = QqGuard.check("小明", "在的", "s1", "", now = t0 + 5_000)
        assertEquals(QqGuard.Gate.LOOPBACK, gate)
    }

    @Test
    fun `超过 60 秒后同样的内容可以回`() {
        QqGuard.markSent("s1", "在的", now = t0)
        // 先过掉会话冷却（15 秒），再等到指纹过期
        val gate = QqGuard.check("小明", "在的", "s1", "", now = t0 + 61_000)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    @Test
    fun `不同会话的相同内容不会被指纹误伤`() {
        QqGuard.markSent("s1", "哈哈哈", now = t0)
        val gate = QqGuard.check("小红", "哈哈哈", "s2", "", now = t0 + 20_000)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    // ---------------------------------------------------------------- 闸 3：会话冷却

    @Test
    fun `同一会话 15 秒内只回一次`() {
        QqGuard.markSent("s1", "第一条回复", now = t0)
        val gate = QqGuard.check("小明", "接着又发一条", "s1", "", now = t0 + 5_000)
        assertEquals(QqGuard.Gate.SESSION, gate)
    }

    @Test
    fun `过了 15 秒同会话可以再回`() {
        QqGuard.markSent("s1", "第一条回复", now = t0)
        val gate = QqGuard.check("小明", "过了会儿又说", "s1", "", now = t0 + 16_000)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    @Test
    fun `不同会话互不影响冷却`() {
        QqGuard.markSent("s1", "回给甲的", now = t0)
        val gate = QqGuard.check("小红", "在吗", "s2", "", now = t0 + 1_000)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    // ---------------------------------------------------------------- 闸 4：全局限流

    @Test
    fun `一小时内最多回 10 条`() {
        repeat(10) { i ->
            QqGuard.markSent("s$i", "第 $i 条", now = t0 + i * 1_000L)
        }
        // 每一条都换了会话、内容也不同，前两道闸拦不住，只能靠限流
        val gate = QqGuard.check("新的", "新的消息", "s-new", "", now = t0 + 20_000L)
        assertEquals(QqGuard.Gate.HOURLY, gate)
    }

    @Test
    fun `过了窗口期计数会清零`() {
        repeat(10) { i ->
            QqGuard.markSent("s$i", "第 $i 条", now = t0 + i * 1_000L)
        }
        val gate = QqGuard.check("新的", "新的消息", "s-new", "", now = t0 + 3_700_000L)
        assertEquals(QqGuard.Gate.PASS, gate)
    }

    @Test
    fun `hourlyCount 反映当前窗口内的条数`() {
        QqGuard.markSent("s1", "a", now = t0)
        QqGuard.markSent("s2", "b", now = t0 + 1_000)
        assertEquals(2, QqGuard.hourlyCount(now = t0 + 2_000))
        assertEquals(0, QqGuard.hourlyCount(now = t0 + 3_700_000))
    }

    // ---------------------------------------------------------------- 排序

    @Test
    fun `四道闸按代价从低到高排列`() {
        // 自发 + 指纹同时成立时，应该先报「自己发的」——它最便宜也最确定
        QqGuard.markSent("s1", "在的", now = t0)
        val gate = QqGuard.check("我", "在的", "s1", selfNick = "我", now = t0 + 1_000)
        assertEquals(QqGuard.Gate.SELF, gate)
    }

    @Test
    fun `全过的时候返回 PASS`() {
        val gate = QqGuard.check("小明", "晚上一起吃饭吗", "s1", "我", now = t0)
        assertEquals(QqGuard.Gate.PASS, gate)
    }
}
