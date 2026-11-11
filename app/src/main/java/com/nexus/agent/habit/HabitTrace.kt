package com.nexus.agent.habit

import java.util.Calendar

/**
 * 从「点过的按钮流水」里挖出「他反复走的那条路」（0.9.12）。
 *
 * 0.9.11 只做了画像：统计哪个应用用得多、哪个时段活跃。那是**印象**，
 * 只能让她在聊天里多说一句「你又刷上了」，并不能让她替你做任何事。
 * 这一层要的是**能执行的东西**：一串稳定的、可重放的步骤。
 *
 * 学术上的对应关系（这几条决定了实现方式，不是装饰）：
 *  - Agent Workflow Memory（Wang et al., arXiv:2409.07429）：从历史轨迹里
 *    归纳可复用 routine，再作为上下文引导后续决策。它证明「归纳」这一步
 *    必须发生在**轨迹**上，而不是在汇总统计上。
 *  - EchoTrail-GUI（arXiv:2512.19396）：自己的结论 —— bad memory is worse
 *    than no memory。所以这里宁可少产候选，也要卡「出现次数 + 跨天」双闸。
 *  - Executable Agentic Memory（arXiv:2605.12294）：把频繁的多步子序列压成
 *    可复用的高层动作（action-group mining），而不是每次让模型自由生成。
 *  - Process mining 的 variant analysis：A→B 这种 directly-follows graph
 *    会把不同次执行的边拼成一条**从未真正走过**的路；要重放必须用
 *    end-to-end 的完整变体（variant）。所以这里按 episode 切，不按相邻事件对。
 *
 * 全程纯函数：不碰磁盘、不发网络、不调模型。可被纯 JVM 单测逐条钉死。
 */
object HabitTrace {

    /** 同一应用内两次点击隔这么久，就不算「连续一次操作」了 */
    const val SEGMENT_GAP_MS = 15_000L

    /** 少于两步不算「动作」，只能算随手一点 */
    const val MIN_STEPS = 2

    /**
     * 一个技能最多留这么多步。
     *
     * 超过十步的「习惯」几乎不可靠 —— 中间任何一步界面变了整条就废，
     * 而且用户审阅时也看不懂。宁可只学前半段，也不要产出一坨 30 步的东西。
     */
    const val MAX_STEPS = 8

    /** 至少重复这么多次才认 */
    const val MIN_OCCURRENCES = 3

    /** 并且至少分布在这么多天（同一天连点三次不算习惯） */
    const val MIN_DAYS = 2

    /** 两条轨迹的 Dice 相似度到 0.7 才认为是同一个动作的不同变体 */
    const val SIMILARITY = 0.7

    // ---- 风险分级 ----
    const val RISK_SAFE = "safe"
    const val RISK_CAUTION = "caution"
    const val RISK_BLOCKED = "blocked"

    /**
     * 一票否决：只要任何一步的标签命中这些词，这个「习惯」永远不变成可执行动作。
     *
     * 为什么要在这一层再拦一次 —— 金融/支付类**界面**已经在采集闸被整屏跳过了，
     * 但「立即购买」这种按钮可能出现在淘宝、京东、美团里，那些应用不在免采名单。
     * 一个会自己点「立即购买」的助手不是助手，是事故。
     */
    private val BLOCK_WORDS = listOf(
        "支付", "付款", "转账", "汇款", "红包", "充值", "提现", "打赏",
        "购买", "下单", "结算", "提交订单", "确认订单", "立即抢购",
        "卸载", "注销", "解绑", "恢复出厂", "格式化", "清空数据", "删除账号",
        "退出登录", "退出帐号", "退出账号", "删除聊天", "撤回"
    )

    /** 需要人工盯着：这些动作能撤销/能撤销得掉，但仍然不该悄悄发生 */
    private val CAUTION_WORDS = listOf(
        "发送", "发布", "分享", "提交", "确认", "删除", "移除", "退订",
        "拉黑", "举报", "拨号", "呼叫", "群发", "评论", "点赞", "关注"
    )

    /** 轨迹里的一步：在哪个界面点了什么 */
    data class TraceStep(val screen: String, val label: String) {
        fun key(): String = screen + "\u0001" + label
    }

    /**
     * 一次连续操作：同一应用、没有长时间断档的一串点击。
     *
     * 这是「variant」，不是「DFG 的一条边」—— 重放的最小单位。
     */
    data class Episode(
        val pkg: String,
        val app: String,
        val startAt: Long,
        val steps: List<TraceStep>
    )

    /** 挖出来的候选技能 */
    data class SkillCandidate(
        val pkg: String,
        val app: String,
        val steps: List<TraceStep>,
        val occurrences: Int,
        val days: Int,
        val lastAt: Long,
        val risk: String
    ) {
        /** 用于去重的稳定签名 */
        val signature: String
            get() = pkg + "\u0000" + steps.joinToString(">") { it.key() }

        /** 「微信 · 搜索 → 订阅号」这类给人看的名 */
        val displayName: String
            get() {
                val tail = steps.take(3).joinToString(" → ") { it.label.ifBlank { it.screen } }
                return if (tail.isBlank()) app else "$app · $tail"
            }
    }

    /**
     * 把流水切成 episode。
     *
     * 切分判据只有两条，都刻意保守：
     *  - 换了应用（包名变了）→ 断；
     *  - 相邻两步间隔超过 [SEGMENT_GAP_MS] → 断。
     *
     * 不做「返回键」判断：我们根本没采返回键，凭空推断只会把两条轨迹粘成一条错的。
     */
    fun segment(events: List<HabitEvent>): List<Episode> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.filter { it.at > 0 && it.pkg.isNotBlank() }.sortedBy { it.at }
        if (sorted.isEmpty()) return emptyList()

        val out = ArrayList<Episode>(16)
        var pkg = ""
        var app = ""
        var startAt = 0L
        var lastAt = 0L
        var steps = ArrayList<TraceStep>(8)

        fun flush() {
            if (steps.size >= MIN_STEPS) {
                out.add(Episode(pkg, app, startAt, steps.take(MAX_STEPS).toList()))
            }
        }

        for (e in sorted) {
            val broken = e.pkg != pkg || e.at - lastAt > SEGMENT_GAP_MS
            if (broken) {
                flush()
                pkg = e.pkg
                app = e.app
                startAt = e.at
                steps = ArrayList(8)
            }
            lastAt = e.at

            when (e.kind) {
                HabitEvent.KIND_OPEN -> {
                    // 进界面本身不是「动作」，只用来打断/延续一次连续操作
                    if (e.app.isNotBlank()) app = e.app
                }

                HabitEvent.KIND_TAP -> {
                    val label = normalizeLabel(e.label)
                    if (label.isBlank()) continue
                    // 连点同一个控件只算一步（手抖 / 加载慢被点两下）
                    val prev = steps.lastOrNull()
                    if (prev != null && prev.label == label) continue
                    // screen 直接用事件自己的（点击事件不带界面标题）。
                    // 刻意**不**用最近一次「进界面」的标题兜底：那会把
                    // 「在谁的朋友圈/谁的聊天窗口里点的」这类信息关联到每一次点击上，
                    // 属于扩大采集面，而我们并不需要它 —— 同一个应用内的按钮文字
                    // 序列已经足够唯一地描述一条路径。
                    steps.add(TraceStep(normalizeLabel(e.screen), label))
                }
            }
        }
        flush()
        return out
    }

    /**
     * 主入口：流水 → 候选技能。
     *
     * @param events  已脱敏的原始记录
     * @param resumeFrom 已存在的技能签名，命中的不再重复产出（由调用方保证只含 candidate/active）
     */
    fun mine(events: List<HabitEvent>, resumeFrom: Set<String> = emptySet()): List<SkillCandidate> {
        val episodes = segment(events).filter { it.steps.size >= MIN_STEPS }
        if (episodes.isEmpty()) return emptyList()

        // 1. 先按「应用」分组：跨应用的动作序列不是习惯，是巧合
        val byPkg = episodes.groupBy { it.pkg }

        val result = ArrayList<SkillCandidate>(8)
        for ((pkg, list) in byPkg) {
            // 2. 同一应用内按相似度聚成「同一个动作的不同变体」
            val clusters = cluster(list)
            for (cluster in clusters) {
                val occ = cluster.size
                if (occ < MIN_OCCURRENCES) continue
                val days = distinctDays(cluster.map { it.startAt })
                if (days < MIN_DAYS) continue

                // 3. 取这一簇的公共骨架（LCS）：只有「每次都会做」的步骤才进技能
                val skeleton = skeletonOf(cluster)
                if (skeleton.size < MIN_STEPS) continue

                val risk = riskOf(skeleton)
                // 一票否决的技能连候选都不产 —— 让它出现在列表里，就迟早有人手滑点了执行
                if (risk == RISK_BLOCKED) continue

                val cand = SkillCandidate(
                    pkg = pkg,
                    app = cluster.first().app,
                    steps = skeleton,
                    occurrences = occ,
                    days = days,
                    lastAt = cluster.maxOf { it.startAt },
                    risk = risk
                )
                if (cand.signature in resumeFrom) continue
                result.add(cand)
            }
        }
        return result.sortedWith(
            compareByDescending<SkillCandidate> { it.occurrences }.thenByDescending { it.lastAt }
        ).take(6)
    }

    /**
     * 相似度聚类。
     *
     * 贪心即可：候选技能一次最多几个，没必要上层次聚类。
     * 顺序按「步数降序」，让最完整的那条当种子 —— 骨架会从更全的那条里选。
     */
    private fun cluster(episodes: List<Episode>): List<List<Episode>> {
        val sorted = episodes.sortedWith(
            compareByDescending<Episode> { it.steps.size }.thenBy { it.startAt }
        )
        val clusters = ArrayList<MutableList<Episode>>(8)
        for (ep in sorted) {
            var placed = false
            for (c in clusters) {
                if (similarity(c.first().steps, ep.steps) >= SIMILARITY) {
                    c.add(ep)
                    placed = true
                    break
                }
            }
            if (!placed) clusters.add(mutableListOf(ep))
        }
        return clusters
    }

    /** Dice 系数：2|LCS| / (|a| + |b|) */
    fun similarity(a: List<TraceStep>, b: List<TraceStep>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val l = lcs(a, b)
        return 2.0 * l.size / (a.size + b.size)
    }

    /** 最长公共子序列（按 [TraceStep.key] 比较，screen 不同但 label 相同的算不同步） */
    fun lcs(a: List<TraceStep>, b: List<TraceStep>): List<TraceStep> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].key() == b[j].key()) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val out = ArrayList<TraceStep>(dp[0][0])
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i].key() == b[j].key() -> {
                    out.add(a[i])
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    /** 一簇轨迹的公共骨架：反复与成员求 LCS，最后剩下的就是「每次都会做」的部分 */
    private fun skeletonOf(cluster: List<Episode>): List<TraceStep> {
        var sk = cluster.first().steps
        for (ep in cluster.drop(1)) {
            sk = lcs(sk, ep.steps)
            if (sk.size < MIN_STEPS) return emptyList()
        }
        return sk.take(MAX_STEPS)
    }

    /**
     * 计数类数字（「3 条未读」「12 个赞」）单独拎出来归一。
     *
     * 一开始写的是「所有数字都换成 #」，被单测当场打回来：那样「按钮1 / 按钮2 / 按钮3」
     * 会变成同一步，连点去重再一叠，整列列表项被吃掉只剩一格 ——
     * 学出来的路径是错的，而错的路径会真的去点。
     *
     * 所以只归一「数字 + 量词」这一种形态，其余数字原样保留。
     */
    private val COUNT_RE = Regex("\\d+\\s*(?:条|个|项|人|次|封|张|位)")

    /**
     * 标签归一化。
     *
     * 只做两件必须做的事：压空白、归一计数。不做同义词归一 ——
     * 那需要词表，词表会引入误判，而误判的代价是点错按钮。
     */
    fun normalizeLabel(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return ""
        return s.replace(COUNT_RE, "#").take(32)
    }

    /** 风险分级：任何一步命中就整条生效（取最严的那一档） */
    fun riskOf(steps: List<TraceStep>): String {
        var level = 0 // 0 safe / 1 caution / 2 blocked
        for (s in steps) {
            val hay = s.label
            if (hay.isEmpty()) continue
            if (BLOCK_WORDS.any { hay.contains(it) }) return RISK_BLOCKED
            if (CAUTION_WORDS.any { hay.contains(it) }) level = 1
        }
        return if (level == 1) RISK_CAUTION else RISK_SAFE
    }

    private fun distinctDays(ts: List<Long>): Int {
        val set = HashSet<Long>(8)
        val cal = Calendar.getInstance()
        for (t in ts) {
            cal.timeInMillis = t
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            set.add(cal.timeInMillis)
        }
        return set.size
    }
}
