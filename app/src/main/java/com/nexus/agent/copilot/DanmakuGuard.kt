package com.nexus.agent.copilot

/**
 * 弹幕守卫（0.9.4 批次四）—— 把「复读机」和「屏幕解说员」挡在灵动岛之外。
 *
 * ## 为什么需要它
 *
 * 画面差分（[CopilotEngine] 里的 MAD 阈值）只能挡住「画面完全没动」，
 * 挡不住这两种真实的骚扰：
 *
 *  1. 静态桌面 / 不动的网页里，时钟、光标、通知栏图标会带来零点几的像素变化，
 *     差分过得去 → 每轮都推理 → 模型每次都说「这是一个手机桌面」；
 *  2. 游戏里同一场战斗持续十几秒，每 5 秒触发一次，模型会给出
 *     「玩家正在与敌人战斗」这类几乎一样的废话，连着刷四遍。
 *
 * 提示词能让它少说，但模型不会永远听话，所以必须在代码里兜一层硬拦截。
 *
 * ## 三层判定
 *
 * | 层 | 判据 | 结果 |
 * |---|---|---|
 * | 模型主动沉默 | 输出含 `<SILENT>` | 直接丢弃，不弹 |
 * | 套话 | 含「我看到 / 当前界面 / 屏幕上」等客观描述 | 丢弃并静默 |
 * | 撞车 | 与最近 [CAPACITY] 条之一相似度 ≥ [SIMILARITY_LIMIT] | 丢弃并静默 |
 *
 * 全部是纯 Kotlin，没有任何 Android 依赖 —— 这些规则必须能在纯 JVM 单测里跑，
 * 否则「阈值写错」这种问题只能靠真机盯着屏幕看，那是必然漏的。
 */
internal class DanmakuGuard(
    private val capacity: Int = CAPACITY
) {

    companion object {

        /** 记住最近几条。用户要的是 5 条，够覆盖一次连续战斗的重复 */
        const val CAPACITY = 5

        /**
         * 相似度阈值：≥ 这个值就当「同一句话」。
         *
         * 0.7 是用户指定的口径。低于它会让「666 这波无敌」和「666 这波太秀了」
         * 都被判重复（两句其实不一样，判重太狠会让她显得只会说一次话）；
         * 高于它则拦不住「哈哈被炸飞了吧」→「哈哈被炸飞了」这种换词复读。
         */
        const val SIMILARITY_LIMIT = 0.7

        /** 模型约定的沉默指令 */
        const val SILENT = "<SILENT>"

        /**
         * 客观描述套话。
         *
         * 只收「一眼能看出是在解说屏幕」的短语，别把「这」「画面」这类常用字
         * 单独收进来 —— 那会把「这波走位」也误杀。
         */
        private val BOILERPLATE = listOf(
            "我看到", "我看到了", "我能看到", "可以看到", "可以看出",
            "当前应用", "当前界面", "当前页面", "应用的界面",
            "屏幕上", "屏幕中", "画面中", "画面里", "画面显示",
            "这张截图", "该截图", "图中", "图片中", "图像中",
            "显示的是", "显示着一个", "这是一个手机", "这是一个应用",
            "界面是", "上面显示"
        )

        /** 判「主动沉默」：容忍大小写、方括号变体与尾部解释 */
        fun isSilent(raw: String?): Boolean {
            if (raw.isNullOrBlank()) return true
            val s = raw.trim()
            if (s.isEmpty()) return true
            val upper = s.uppercase()
            if (upper.contains(SILENT)) return true
            // 有的模型会写成 [SILENT] / （SILENT） / SILENT
            val bare = s.trim(' ', '[', ']', '(', ')', '（', '）', '【', '】', '。', '.', '"', '\'')
            return bare.equals("SILENT", ignoreCase = true) ||
                bare == "沉默" || bare == "静默"
        }

        /** 判「套话」：出现客观描述短语即命中 */
        fun isBoilerplate(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            return BOILERPLATE.any { t.contains(it) }
        }

        /**
         * 相似度（0~1，1 = 完全相同）。
         *
         * 用编辑距离而不是「包含」：包含只能抓「A 是 B 的子串」，
         * 而模型复读时通常是换一两个字（「吧」→「了」），那是距离不是包含。
         * 字符串长度上限 [CopilotEngine] 已截到 30 字，O(n·m) 完全无所谓。
         */
        fun similarity(a: String, b: String): Double {
            val x = normalize(a)
            val y = normalize(b)
            if (x.isEmpty() && y.isEmpty()) return 1.0
            if (x.isEmpty() || y.isEmpty()) return 0.0
            if (x == y) return 1.0
            // 一边是另一边的子串（且都不算太短）→ 直接判高度相似，
            // 「被苦力怕炸飞了」vs「哈哈，被苦力怕炸飞了吧」编辑距离可能到不了 0.7
            if (minOf(x.length, y.length) >= 4) {
                if (x.contains(y) || y.contains(x)) return 1.0
            }
            val dist = levenshtein(x, y)
            return 1.0 - dist.toDouble() / maxOf(x.length, y.length)
        }

        /** 去掉空白与标点、统一小写 —— 复读往往只差一个逗号 */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                if (ch.isWhitespace()) continue
                if (ch in "，。！？、；：,.!?;:…~～\"'“”‘’「」『』《》【】()（）[]{}·-—_*#@") continue
                sb.append(ch.lowercaseChar())
            }
            return sb.toString()
        }

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(
                        cur[j - 1] + 1,        // 插入
                        prev[j] + 1,           // 删除
                        prev[j - 1] + cost     // 替换
                    )
                }
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return prev[b.length]
        }
    }

    /** 最近说过的几句（新的在尾部） */
    private val history = ArrayDeque<String>(capacity)

    /**
     * 判定一句新弹幕。**不记账** —— 记账要等它真的显示出去了再调 [record]。
     *
     * @return null = 可以弹；非 null = 拦截原因（同时写日志用）
     */
    fun rejectReason(candidate: String): String? {
        val text = candidate.trim()
        if (text.isEmpty()) return "空内容"
        if (isSilent(text)) return "模型选择沉默"
        if (isBoilerplate(text)) return "套话（客观描述屏幕）"
        for (old in history) {
            if (similarity(text, old) >= SIMILARITY_LIMIT) return "和最近说过的「$old」太像"
        }
        return null
    }

    /** 记一条真正说出去的话 */
    fun record(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        history.addLast(t)
        while (history.size > capacity) history.removeFirst()
    }

    /** 换应用 / 重新开陪玩时清空：换了个场景，同一句吐槽不算复读 */
    fun reset() = history.clear()

    fun recent(): List<String> = history.toList()

    fun size(): Int = history.size
}
