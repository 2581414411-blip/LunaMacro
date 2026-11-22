package com.nexus.agent.bridge

/**
 * 幂等账本（协议 §7）。
 *
 * ## 为什么必须落盘
 *
 * 「同一个 id 只执行一次」听起来是内存里去重就够了 —— 直到用户手滑把宏月划掉。
 * 那一刻内存账本没了，MPL 那边 5 秒没收到响应、按规则重发同一个 id，
 * 宏月把它当成新请求 → **点两次「发送」**。
 *
 * 所以这里是**两段写**：
 *
 * ```
 * 收到 id → 落盘 {R, bootId, ts}
 * 执行完 → 落盘 {D, bootId, ts, 完整响应}
 * ```
 *
 * 重启后读到 `R` 且 `bootId` 不是本进程 → 返回 `UNKNOWN_RESULT`，**绝不重跑**。
 * 这不是"保守"，是因为"不确定成没成"必须让用户看一眼，
 * 而不是赌一把再点一次。
 *
 * ## 为什么抽 Store
 *
 * 真实实现是 SharedPreferences，但那样单测就得拖 Android 框架进来。
 * 抽一层之后，[MemoryStore] 让「kill -9 后重发」这种场景能在纯 JVM 里跑出来。
 */
class ActionLedger(
    private val store: Store,
    private val bootId: String,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val ttlMs: Long = MplProtocol.LEDGER_TTL_MS,
    private val maxEntries: Int = 64
) {

    interface Store {
        fun get(id: String): String?
        fun put(id: String, value: String)
        fun all(): Map<String, String>
        fun remove(id: String)
    }

    /** 内存实现：单测用；也是 SharedPreferences 不可用时的兜底 */
    class MemoryStore : Store {
        private val map = LinkedHashMap<String, String>()
        @Synchronized
        override fun get(id: String): String? = map[id]
        @Synchronized
        override fun put(id: String, value: String) {
            map[id] = value
        }
        @Synchronized
        override fun all(): Map<String, String> = LinkedHashMap(map)
        @Synchronized
        override fun remove(id: String) {
            map.remove(id)
        }
    }

    /** 一次请求该做什么 */
    sealed class Begin {
        /** 没记录，或者记录已过期 → 执行 */
        object Fresh : Begin()

        /** 已经跑完了 → 直接回缓存，不再执行 */
        data class Replay(val response: String) : Begin()

        /** 同一 id 正在跑 → 等它跑完 */
        object InFlight : Begin()

        /** 上次跑一半进程没了 → 不重跑，让用户看一眼 */
        object Unknown : Begin()
    }

    @Synchronized
    fun begin(id: String): Begin {
        val entry = read(id) ?: run {
            writeRunning(id)
            return Begin.Fresh
        }
        val age = now() - entry.ts
        if (age > ttlMs) {
            // 过期记录：清掉重来（10 分钟前的请求不该再影响现在）
            store.remove(id)
            writeRunning(id)
            return Begin.Fresh
        }
        return when (entry.state) {
            'D' -> Begin.Replay(entry.payload.orEmpty())
            'R' -> if (entry.bootId != bootId) Begin.Unknown else Begin.InFlight
            else -> Begin.Fresh
        }
    }

    /**
     * 等一个正在跑的请求结束。
     *
     * @return 拿到结果 → Replay；超时 → Unknown（不重跑）
     */
    fun awaitDone(id: String, waitMs: Long): Begin {
        val deadline = now() + waitMs.coerceAtLeast(0)
        while (now() < deadline) {
            val entry = read(id)
            if (entry != null && entry.state == 'D' && now() - entry.ts <= ttlMs) {
                return Begin.Replay(entry.payload.orEmpty())
            }
            Thread.sleep(50)
        }
        return Begin.Unknown
    }

    /** 执行完，写第二段 */
    @Synchronized
    fun done(id: String, response: String) {
        store.put(id, "D|$bootId|${now()}|$response")
        prune()
    }

    /** 只清一条（重试场景：明确知道没执行才允许重来） */
    @Synchronized
    fun forget(id: String) {
        store.remove(id)
    }

    fun size(): Int = store.all().size

    // ---------------------------------------------------------------- 内部

    private data class Entry(val state: Char, val bootId: String, val ts: Long, val payload: String?)

    private fun writeRunning(id: String) {
        store.put(id, "R|$bootId|${now()}|")
        prune()
    }

    private fun read(id: String): Entry? {
        val raw = store.get(id) ?: return null
        val parts = raw.split("|", limit = 4)
        if (parts.size < 3) return null
        val state = parts[0].firstOrNull() ?: return null
        val ts = parts[2].toLongOrNull() ?: return null
        return Entry(state, parts[1], ts, parts.getOrNull(3))
    }

    /** 只留最近 [maxEntries] 条 —— 账本是防重，不是日志，不能无限长 */
    private fun prune() {
        val all = store.all()
        if (all.size <= maxEntries) return
        val ordered = all.entries.sortedByDescending { (_, v) -> v.split("|", limit = 4).getOrNull(2)?.toLongOrNull() ?: 0L }
        ordered.drop(maxEntries).forEach { (id, _) -> store.remove(id) }
    }
}
