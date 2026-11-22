package com.nexus.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 幂等账本单测（0.9.31 · 协议 §7）。
 *
 * 这一层的错误都是"静默双执行"：用户没看到报错，只看到点两次发送。
 * 所以每种命中状态都单独钉一遍，尤其是「进程重启后读到 running」——
 * 那个分支写错，等于把 `UNKNOWN_RESULT` 变成了"再点一次"。
 */
class ActionLedgerTest {

    private var now = 1_700_000_000_000L
    private val store = ActionLedger.MemoryStore()

    private fun ledger(boot: String = "boot-a") =
        ActionLedger(store, boot, now = { now })

    @Test
    fun `第一次见到 id 就直接执行`() {
        assertSame(ActionLedger.Begin.Fresh, ledger().begin("id1"))
    }

    @Test
    fun `跑完之后同一个 id 直接回缓存不再执行`() {
        val l = ledger()
        l.begin("id1")
        l.done("id1", """{"ok":true}""")

        val second = l.begin("id1")
        assertTrue(second is ActionLedger.Begin.Replay)
        assertEquals("""{"ok":true}""", (second as ActionLedger.Begin.Replay).response)
    }

    @Test
    fun `同一个进程里重复进来是 in flight`() {
        val l = ledger()
        l.begin("id1")
        assertSame(ActionLedger.Begin.InFlight, l.begin("id1"))
    }

    @Test
    fun `进程重启后读到 running 一律 UNKNOWN 不重跑`() {
        ledger("boot-a").begin("id1")
        // 换一个进程标识，模拟被划掉后重新打开
        assertSame(ActionLedger.Begin.Unknown, ledger("boot-b").begin("id1"))
    }

    @Test
    fun `超过 10 分钟的记录视为过期重新执行`() {
        val l = ledger()
        l.begin("id1")
        l.done("id1", """{"ok":true}""")

        now += MplProtocol.LEDGER_TTL_MS + 1
        assertSame(ActionLedger.Begin.Fresh, ledger().begin("id1"))
    }

    @Test
    fun `等待中的请求在对方完成后拿到同一份结果`() {
        // awaitDone 会按真实时钟轮询，这里必须用系统时钟（固定时钟会死循环）
        val l = ActionLedger(store, "boot-a", now = { System.currentTimeMillis() })
        l.begin("id1")

        Thread {
            Thread.sleep(150)
            l.done("id1", """{"ok":true,"n":1}""")
        }.start()

        val waited = l.awaitDone("id1", 3_000)
        assertTrue(waited is ActionLedger.Begin.Replay)
        assertEquals("""{"ok":true,"n":1}""", (waited as ActionLedger.Begin.Replay).response)
    }

    @Test
    fun `等不到结果回 UNKNOWN 而不是 Fresh`() {
        val l = ActionLedger(store, "boot-a", now = { System.currentTimeMillis() })
        l.begin("id1")
        assertSame(ActionLedger.Begin.Unknown, l.awaitDone("id1", 100))
    }

    @Test
    fun `forget 之后可以重新执行`() {
        val l = ledger()
        l.begin("id1")
        l.done("id1", """{"ok":true}""")
        l.forget("id1")
        assertSame(ActionLedger.Begin.Fresh, l.begin("id1"))
    }

    @Test
    fun `账本不无限长`() {
        val l = ledger()
        repeat(80) { i ->
            now += 100
            l.begin("id-$i")
            l.done("id-$i", """{"ok":true}""")
        }
        assertTrue("账本应裁剪到 64 条以内，实际 ${l.size()}", l.size() <= 64)
    }

    @Test
    fun `保留的是最新的那批`() {
        val l = ledger()
        repeat(70) { i ->
            now += 100
            l.begin("id-$i")
            l.done("id-$i", """{"ok":true}""")
        }
        // 最新那条必须还在
        assertTrue(l.begin("id-69") is ActionLedger.Begin.Replay)
    }

    @Test
    fun `坏记录不会把账本搞崩`() {
        store.put("bad", "garbage")
        // 解析不出 ts → 当成没有记录，重新执行
        assertSame(ActionLedger.Begin.Fresh, ledger().begin("bad"))
    }
}
