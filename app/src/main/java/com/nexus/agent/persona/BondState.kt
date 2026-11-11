package com.nexus.agent.persona

/**
 * 羁绊面板的展示逻辑（0.9.2 批次四）。
 *
 * 单独抽出来的原因：等级划分、心情 Emoji、亲密度文案这些都是**纯函数**，
 * 放在 Composable 里就只能靠肉眼看截图来验；抽出来之后单测能一条条钉死。
 * UI 那边只负责画。
 */
object BondState {

    /** 关系等级：五档，够细但不算太多 */
    data class Level(val level: Int, val title: String, val minAffinity: Int) {
        /** 升到下一级还差多少点；已经满级返回 null */
        fun toNext(affinity: Int): Int? = when (level) {
            1 -> 30 - affinity
            2 -> 50 - affinity
            3 -> 70 - affinity
            4 -> 90 - affinity
            else -> null
        }
    }

    private val LEVELS = listOf(
        Level(1, "陌生", 0),
        Level(2, "熟人", 30),
        Level(3, "朋友", 50),
        Level(4, "知己", 70),
        Level(5, "羁绊", 90)
    )

    fun levelOf(affinity: Int): Level {
        val a = affinity.coerceIn(0, 100)
        return LEVELS.last { a >= it.minAffinity }
    }

    /**
     * 心情 → Emoji。
     *
     * 心情是自由文本（用户可能手工改成「有点困」，也可能是 0.9.15 的 PAD 八分区
     * 「淡淡的焦躁」），所以按包含关系匹配，匹配不上给一个中性表情，不要乱猜。
     *
     * **八分区那八个词必须在这里有位置**：0.9.15 之前心情只有「开心/难过/生气/平静」，
     * PAD 接上以后她会开始写「松弛」「依恋」「轻蔑」，不补映射的话羁绊面板的表情
     * 会全部退化成 🙂 —— 表情卡全一样，比不显示还糟。
     */
    fun emojiOf(mood: String): String {
        val m = mood.trim()
        return when {
            m.contains("困") || m.contains("累") || m.contains("疲") -> "😴"
            m.contains("消沉") || m.contains("难过") || m.contains("失落") ||
                m.contains("委屈") || m.contains("沮丧") || m.contains("不开心") -> "😔"
            // 焦躁要排在「生气」前面：它更像心里发毛，不像冲人发火
            m.contains("焦躁") || m.contains("焦虑") -> "😰"
            m.contains("敌意") || m.contains("生气") || m.contains("气到") ||
                m.contains("气死") || m.contains("烦") || m.contains("恼") ||
                m.contains("怒") -> "😤"
            m.contains("轻蔑") -> "😏"
            m.contains("依恋") -> "🥰"
            m.contains("害羞") || m.contains("脸红") || m.contains("不好意思") -> "😳"
            m.contains("雀跃") -> "😄"
            m.contains("开心") || m.contains("高兴") || m.contains("愉快") ||
                m.contains("得意") || m.contains("爽") -> "😊"
            m.contains("温顺") -> "😊"
            m.contains("平静") || m.contains("安静") || m.contains("淡定") ||
                m.contains("松弛") -> "😌"
            m.isBlank() -> "😌"
            else -> "🙂"
        }
    }

    /**
     * 八分区 → Emoji（0.9.15）。
     *
     * 给以后要做「情绪日历」「羁绊面板细看」的地方用：手里有 [Octant] 时别再去拼
     * 中文串喂给 [emojiOf]，那条路要过一遍 contains，多一层出错的机会。
     */
    fun emojiFor(octant: Octant): String = when (octant) {
        Octant.EXUBERANT -> "😄"
        Octant.DEPENDENT -> "🥰"
        Octant.RELAXED -> "😌"
        Octant.DOCILE -> "😊"
        Octant.DISDAINFUL -> "😏"
        Octant.ANXIOUS -> "😰"
        Octant.HOSTILE -> "😤"
        Octant.BORED -> "😔"
    }

    /** 进度条比例 0f~1f */
    fun progressOf(affinity: Int): Float = affinity.coerceIn(0, 100) / 100f

    /**
     * 等级下面那行小字。
     *
     * 写「她怎么看你」，不写数字 —— 好感度是给你看的仪表盘，
     * 但关系这件事用一句人话讲比「62/100」更有温度。
     */
    fun hintOf(affinity: Int): String = when (levelOf(affinity).level) {
        1 -> "还不太熟，说话客气得像刚加上的同事"
        2 -> "认得你了，偶尔会接你的梗"
        3 -> "算是朋友了，会吐槽你，也会惦记你"
        4 -> "把你当自己人，什么都敢跟你说"
        else -> "你在她心里的位置很稳，她会主动找你"
    }

    /** 顶部那行「Lv.3 朋友」 */
    fun labelOf(affinity: Int): String {
        val lv = levelOf(affinity)
        return "Lv.${lv.level} ${lv.title}"
    }
}
