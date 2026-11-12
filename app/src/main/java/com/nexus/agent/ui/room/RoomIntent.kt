package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme

/**
 * 「这句话是不是在说装修」以及「说的是什么」（0.9.19 批次 C）。
 *
 * ## 为什么要先本地判，而不是直接丢给模型
 *
 * 装修指令**高度格式化**：「加个红色沙发」「把墙换成深蓝」「把床挪到窗边」。
 * 这类句子用关键词就能百分之百读懂，而叫一次模型要 1~8 秒，还可能：
 * 断网、没配 Key、本地模型没下、模型自由发挥吐一段散文。
 *
 * 所以顺序是 **本地解析 → 认不出才问模型**（见 [RoomDecorator.run]）。
 * 好处有三个：常见说法零延迟、离线可用、结果可复现（同一句话永远同一个结果）。
 *
 * ## 为什么输出的是一张 map 而不是自己的数据结构
 *
 * 本地解析的结果和模型返回的 JSON **走同一个校验器**（[RoomDecorator.validate]）。
 * 如果各走一套，就会出现「本地路线能加第 13 件家具、模型路线不能」这种分裂。
 * 这里刻意让两种来源长得一模一样。
 *
 * ## 一个刻意的收敛
 *
 * 只认「有动作」的句子。单说一个名词（「沙发」「那张床」）**不拦截** ——
 * 用户在讲故事、在抱怨、在问东西，都可能提到沙发。宁可漏，不可误吞。
 */
object RoomIntent {

    // ------------------------------------------------------------ 词表

    /** 家具词 → 种类。长的在前：「书桌」必须先于「桌」命中 */
    private val KIND_WORDS: List<Pair<String, String>> = listOf(
        "落地灯" to RoomItem.KIND_LAMP,
        "台灯" to RoomItem.KIND_LAMP,
        "书架" to RoomItem.KIND_SHELF,
        "书柜" to RoomItem.KIND_SHELF,
        "置物架" to RoomItem.KIND_SHELF,
        "柜子" to RoomItem.KIND_SHELF,
        "书桌" to RoomItem.KIND_DESK,
        "办公桌" to RoomItem.KIND_DESK,
        "电脑桌" to RoomItem.KIND_DESK,
        "桌子" to RoomItem.KIND_DESK,
        "桌" to RoomItem.KIND_DESK,
        "沙发" to RoomItem.KIND_SOFA,
        "地毯" to RoomItem.KIND_RUG,
        "垫子" to RoomItem.KIND_RUG,
        "挂画" to RoomItem.KIND_POSTER,
        "画框" to RoomItem.KIND_POSTER,
        "海报" to RoomItem.KIND_POSTER,
        "盆栽" to RoomItem.KIND_PLANT,
        "绿植" to RoomItem.KIND_PLANT,
        "绿萝" to RoomItem.KIND_PLANT,
        "植物" to RoomItem.KIND_PLANT,
        "花" to RoomItem.KIND_PLANT,
        "床" to RoomItem.KIND_BED,
        "灯" to RoomItem.KIND_LAMP,
        "画" to RoomItem.KIND_POSTER
    )

    /** 槽位词 → 语义槽位（交给 RoomLayout 归一） */
    private val SLOT_WORDS: List<Pair<String, String>> = listOf(
        "屋子中间" to RoomLayout.SLOT_CENTER_FLOOR,
        "房间中间" to RoomLayout.SLOT_CENTER_FLOOR,
        "靠窗" to RoomLayout.SLOT_BY_WINDOW,
        "窗边" to RoomLayout.SLOT_BY_WINDOW,
        "窗下" to RoomLayout.SLOT_BY_WINDOW,
        "窗台" to RoomLayout.SLOT_BY_WINDOW,
        // 刻意**没有**「桌子」：它是名词不是位置。「加个桌子」会被读成
        // 「加张桌子放到桌子上」，桌子的默认位是角落。
        "桌上" to RoomLayout.SLOT_ON_DESK,
        "桌面" to RoomLayout.SLOT_ON_DESK,
        "墙上" to RoomItem.SLOT_ON_WALL,
        "墙角" to RoomLayout.SLOT_CORNER,
        "角落" to RoomLayout.SLOT_CORNER,
        "拐角" to RoomLayout.SLOT_CORNER,
        "左墙" to RoomLayout.SLOT_LEFT_WALL,
        "靠左" to RoomLayout.SLOT_LEFT_WALL,
        "左边" to RoomLayout.SLOT_LEFT_WALL,
        "左侧" to RoomLayout.SLOT_LEFT_WALL,
        "右墙" to RoomLayout.SLOT_RIGHT_WALL,
        "靠右" to RoomLayout.SLOT_RIGHT_WALL,
        "右边" to RoomLayout.SLOT_RIGHT_WALL,
        "右侧" to RoomLayout.SLOT_RIGHT_WALL,
        "中间" to RoomLayout.SLOT_CENTER_FLOOR,
        "中央" to RoomLayout.SLOT_CENTER_FLOOR
    )

    /** 动作词：有它才认为是在下指令 */
    private val ADD_VERBS = listOf(
        "加", "放", "摆", "添", "弄", "来一个", "来张", "来张", "整一个",
        "挂", "铺", "搁", "买", "换个", "换成"
    )

    /** 移动动词：决定「新增」还是「挪已有的那一件」 */
    private val MOVE_VERBS = listOf(
        "移到", "挪到", "搬到", "放到", "摆到", "挂到", "铺到", "换到",
        "移", "挪", "搬"
    )

    private val REMOVE_VERBS = listOf("删掉", "删除", "删", "撤走", "撤掉", "撤", "拿走", "去掉", "移除", "不要了", "扔掉")

    /** 「墙/地板」类主题词 */
    private val WALL_WORDS = listOf("墙面", "墙壁", "墙")
    private val FLOOR_WORDS = listOf("地板", "地面", "地砖")

    private val ROOM_WORDS = listOf("装修", "布置", "装扮", "房间", "屋子", "屋里", "家具", "陈设", "主题")

    private val NIGHT_WORDS = listOf("夜里", "晚上", "夜间", "夜晚", "深夜")
    private val STUDIO_WORDS = listOf("工作室", "简约", "纯白")

    /**
     * 这个界面相关的词一旦出现，就**不当装修指令**。
     *
     * 「把桌面上的东西删掉」「屏幕里的沙发」—— 同名不同义。房间装修永远不涉及
     * 任何 App，所以只要句子里有界面/应用，就交给原来的链路（读屏、工具）。
     * 误拦的代价是「她想改手机设置，结果沙发被删了」，比漏拦严重得多。
     */
    private val SCREEN_WORDS = listOf("屏幕", "界面", "应用", "页面", "微信", "手机", "锁屏", "图标")

    // ------------------------------------------------------------ 对外

    /**
     * 要不要把这句话从聊天里截走。
     *
     * 三道闸：
     *  1. 太长的不截（超过 80 字多半是在说事，不是在装修）；
     *  2. 「带界面词的不截」在 [parse] 里 —— 那是**唯一**的闸，[RoomDecorator]
     *     也走同一条路。曾经把它只写在 `shouldIntercept` 里，结果房间输入框
     *     收到「把屏幕上的沙发图标删掉」照样把沙发删了；
     *  3. 能量出动作的截；量不出但「房间 + 家具」都在的，留给模型去听懂。
     */
    fun shouldIntercept(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 80) return false
        if (parse(t) != null) return true
        return ROOM_WORDS.any { t.contains(it) } && findKinds(t).isNotEmpty()
    }

    /**
     * 本地解析。认不出返回 null（调用方接着去问模型）。
     *
     * 返回结构与模型 JSON 完全同形，方便共用校验器。
     *
     * **界面词闸在这里**：房间装修永远不涉及任何 App。句子里出现「屏幕 / 应用 /
     * 微信」就整个放弃本地解析，交给原来的链路（读屏、工具）。误拦的代价是
     * 「她想改手机设置，结果沙发被删了」，比漏拦严重得多。
     */
    fun parse(text: String): Map<String, Any?>? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (SCREEN_WORDS.any { t.contains(it) }) return null

        removeOf(t)?.let { return it }
        themeOf(t)?.let { return it }
        return addOf(t)
    }

    /** 是不是「把已有的挪走」，而不是「再加一个」 */
    fun isMove(text: String): Boolean {
        val t = text.trim()
        return MOVE_VERBS.any { t.contains(it) }
    }

    // ------------------------------------------------------------ 三段解析

    private fun removeOf(t: String): Map<String, Any?>? {
        if (REMOVE_VERBS.none { t.contains(it) }) return null
        val kinds = findKinds(t)
        if (kinds.isEmpty()) return null
        return mapOf("action" to "remove", "kind" to kinds.first())
    }

    private fun themeOf(t: String): Map<String, Any?>? {
        val wall = findAny(t, WALL_WORDS)
        val floor = findAny(t, FLOOR_WORDS)
        // 「墙上挂幅画」也含「墙」——没有颜色就不是在换墙色
        if (wall == null && floor == null) {
            // 只要主题词 + 夜间词，也认（「弄成夜里那种感觉」）
            val preset = presetOf(t) ?: return null
            if (ROOM_WORDS.none { t.contains(it) }) return null
            return mapOf("action" to "theme", "preset" to preset)
        }

        val out = LinkedHashMap<String, Any?>()
        out["action"] = "theme"
        wall?.let { (i, len) -> colorNear(t, i, len)?.let { out["wall"] = it } }
        floor?.let { (i, len) -> colorNear(t, i, len)?.let { out["floor"] = it } }
        // 一句「把屋子换了个颜色」但没有色名 —— 交给模型，别在这里瞎猜
        if (out.size == 1) return null
        presetOf(t)?.let { out["preset"] = it }
        return out
    }

    private fun addOf(t: String): Map<String, Any?>? {
        val kinds = findKinds(t)
        if (kinds.isEmpty()) return null
        val color = colorAnywhere(t)
        val slot = slotOf(t)
        val hasVerb = ADD_VERBS.any { t.contains(it) } || MOVE_VERBS.any { t.contains(it) }
        // 只有一个名词、没有动作也没有位置/颜色 → 不像指令，不拦
        if (!hasVerb && color == null && slot == null) return null

        val items = kinds.take(MAX_ITEMS_PER_SENTENCE).map { kind ->
            val m = LinkedHashMap<String, Any?>()
            m["kind"] = kind
            // 一句话说两件东西时不指定槽位，让校验器各自挑空位
            if (kinds.size == 1 && slot != null) m["slot"] = slot
            if (color != null) m["color"] = color
            m
        }
        return mapOf("action" to "add", "items" to items)
    }

    /** 一句话最多认几件（「加个沙发、床、台灯、书架」已经到顶了） */
    const val MAX_ITEMS_PER_SENTENCE = 4

    // ------------------------------------------------------------ 小工具

    /**
     * 找出句子里提到的所有种类，**按出现位置排序**。
     *
     * 长词优先是为了「书桌」不被「桌」抢先、「落地灯」不被「灯」抢先；
     * 命中后把那段挖掉（替换成等长占位符），避免「书桌」再被「桌」命中一次。
     */
    fun findKinds(text: String): List<String> {
        val t = text.lowercase()
        val hits = ArrayList<Pair<Int, String>>()
        val masked = StringBuilder(t)
        for ((word, kind) in KIND_WORDS) {
            var from = 0
            while (true) {
                val i = masked.indexOf(word, from)
                if (i < 0) break
                hits.add(i to kind)
                for (k in word.indices) masked.setCharAt(i + k, '\u0000')
                from = i + word.length
            }
        }
        return hits.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun slotOf(t: String): String? {
        var best = -1
        var bestWord: String? = null
        for ((word, slot) in SLOT_WORDS) {
            val i = t.indexOf(word)
            if (i < 0) continue
            // 位置靠前的优先；同一位置取更长的词（「屋子中间」胜过「中间」）
            if (best < 0 || i < best || (i == best && word.length > (bestWord?.length ?: 0))) {
                best = i
                bestWord = slot
            }
        }
        return bestWord
    }

    private fun colorAnywhere(t: String): String? =
        RoomPalette.hits(t).firstOrNull()?.name

    /**
     * 关键词附近（前 12 字 / 后 12 字）的色名。
     *
     * 「深蓝色的墙」「墙换成深蓝」「把蓝色墙刷一下」都认。
     * 往后找优先：「墙换成深蓝」里那个颜色一定在后头；找不到才回头往前找。
     */
    private fun colorNear(t: String, kwIdx: Int, kwLen: Int): String? {
        val hits = RoomPalette.hits(t)
        hits.firstOrNull { it.index >= kwIdx + kwLen && it.index <= kwIdx + kwLen + 12 }
            ?.let { return it.name }
        return hits.lastOrNull { it.index + it.length <= kwIdx && it.index >= kwIdx - 12 }?.name
    }

    /** 找第一个出现的关键词，返回（位置, 长度）—— 长度用来算颜色词该从哪儿开始找 */
    private fun findAny(t: String, words: List<String>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        for (w in words) {
            val i = t.indexOf(w)
            if (i < 0) continue
            // 位置靠前的优先；同一位置取更长的词（「墙壁」胜过「墙」）
            if (best == null || i < best.first || (i == best.first && w.length > best.second)) {
                best = i to w.length
            }
        }
        return best
    }

    private fun presetOf(t: String): String? = when {
        NIGHT_WORDS.any { t.contains(it) } -> RoomTheme.PRESET_NIGHT
        STUDIO_WORDS.any { t.contains(it) } -> RoomTheme.PRESET_STUDIO
        else -> null
    }
}
