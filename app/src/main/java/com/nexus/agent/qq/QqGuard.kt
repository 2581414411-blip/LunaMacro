package com.nexus.agent.qq

import java.security.MessageDigest

/**
 * 防死循环的闸门（0.9.25 批次 E）。
 *
 * ## 为什么必须有这一层
 *
 * 「监听通知 → 自动回复」这个链路本身就是个闭环：我们回复之后，QQ 会推一条**新通知**，
 * 里面正是我们自己刚发的那句话。如果不过滤，就会自己跟自己聊到天荒地老，
 * 还会把对方刷屏、把 API 额度烧光。
 *
 * 四道闸按「便宜的在前」排列，任意一道拦下就返回，不继续解析也不调模型：
 *
 * 1. [Gate.SELF]     —— title 就是主人的昵称（自己发的）
 * 2. [Gate.LOOPBACK] —— 内容指纹在 60 秒内出现过（我们刚发过一模一样的话）
 * 3. [Gate.SESSION]  —— 同一个会话 15 秒内已经回过（对方连发三条只回一次）
 * 4. [Gate.HOURLY]   —— 全局每小时最多 10 次（失控时兜底，也是「别把人得罪光」）
 *
 * ## 为什么指纹只看内容不看发送者
 *
 * 群聊里两个人可能说同一句「哈哈哈」。只按内容做指纹会误伤 —— 但误伤的代价是
 * **少回一条**，而漏判的代价是**自己跟自己聊 20 分钟**。这种不对称下，
 * 宁可靠「内容 + 会话」做指纹，并且窗口只留 60 秒。
 */
object QqGuard {

    /** 指纹有效期：60 秒。太短挡不住回环（模型可能慢），太长会误伤正常的重复寒暄 */
    private const val FINGERPRINT_WINDOW_MS = 60_000L

    /** 同一会话冷却：15 秒。对方连发三条，只回最后一条 */
    private const val SESSION_COOLDOWN_MS = 15_000L

    /** 全局限流窗口：1 小时 */
    private const val HOURLY_WINDOW_MS = 3_600_000L

    /** 窗口内最多回几条 */
    private const val HOURLY_MAX = 10

    /** 指纹缓存上限。不设上限的话，长时间运行会一直涨 */
    private const val FINGERPRINT_MAX = 64

    enum class Gate(val zh: String) {
        SELF("自己发的消息"),
        LOOPBACK("60 秒内刚发过一模一样的内容"),
        SESSION("这个聊天 15 秒内刚回过"),
        HOURLY("这一小时已经回了 $HOURLY_MAX 条"),
        PASS("通过")
    }

    /** sha1 指纹 → 时间戳（插入顺序即时间顺序，满了就丢最旧的） */
    private val sent = LinkedHashMap<String, Long>()

    /** 会话 key → 上次回复时间 */
    private val lastBySession = HashMap<String, Long>()

    /** 最近一小时内每次回复的时间戳 */
    private val replyTimes = ArrayDeque<Long>()

    /**
     * 判断这条消息该不该回。
     *
     * @param title     通知标题（QQ 里是发言人昵称）
     * @param content   通知正文
     * @param session   会话标识（一般用「标题 + 通知 key 前缀」，群聊时能区分不同群）
     * @param selfNick  主人自己的昵称，空串表示没配置
     */
    @Synchronized
    fun check(
        title: String,
        content: String,
        session: String,
        selfNick: String,
        now: Long = System.currentTimeMillis()
    ): Gate {
        // ---- 闸 1：自己发的 ----
        if (selfNick.isNotBlank() && title.trim() == selfNick.trim()) {
            return Gate.SELF
        }

        // ---- 闸 2：指纹回环 ----
        val fp = fingerprintOf("$session|$content")
        val lastAt = sent[fp]
        if (lastAt != null && now - lastAt < FINGERPRINT_WINDOW_MS) {
            return Gate.LOOPBACK
        }

        // ---- 闸 3：会话冷却 ----
        val lastReply = lastBySession[session]
        if (lastReply != null && now - lastReply < SESSION_COOLDOWN_MS) {
            return Gate.SESSION
        }

        // ---- 闸 4：全局限流 ----
        pruneHourly(now)
        if (replyTimes.size >= HOURLY_MAX) {
            return Gate.HOURLY
        }

        return Gate.PASS
    }

    /**
     * 记录「我们发出去了一句话」。
     *
     * 必须在真正调用 `action.actionIntent.send()` **之前**记录：
     * 发送是异步的，等它回来再记，中间那几毫秒足以来一条新通知并绕开指纹闸。
     *
     * 同时做两件事：写指纹（防回环）+ 记会话时间（防连发）+ 记全局时间（限流）。
     */
    @Synchronized
    fun markSent(session: String, content: String, now: Long = System.currentTimeMillis()) {
        val fp = fingerprintOf("$session|$content")
        sent[fp] = now
        while (sent.size > FINGERPRINT_MAX) {
            val oldest = sent.keys.firstOrNull() ?: break
            sent.remove(oldest)
        }
        lastBySession[session] = now
        replyTimes.addLast(now)
        pruneHourly(now)
    }

    /** 清空（测试用；也用于「用户手动重置限流」） */
    @Synchronized
    fun reset() {
        sent.clear()
        lastBySession.clear()
        replyTimes.clear()
    }

    /** 当前这一小时已经回了几条，给设置页显示 */
    @Synchronized
    fun hourlyCount(now: Long = System.currentTimeMillis()): Int {
        pruneHourly(now)
        return replyTimes.size
    }

    private fun pruneHourly(now: Long) {
        while (replyTimes.isNotEmpty() && now - replyTimes.first() > HOURLY_WINDOW_MS) {
            replyTimes.removeFirst()
        }
        // 顺手清掉过期的会话记录，避免长期运行无限增长
        val stale = lastBySession.entries.filter { now - it.value > HOURLY_WINDOW_MS }
        stale.forEach { lastBySession.remove(it.key) }
    }

    /** SHA-1 十六进制；只用于去重，不涉及任何安全用途 */
    private fun fingerprintOf(raw: String): String = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        md.digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault(raw.hashCode().toString())
}
