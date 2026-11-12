package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem

/**
 * 语义槽位 → 画布坐标（0.9.19 批次 C）。
 *
 * ## 为什么要有这一层
 *
 * 库里存的是**几何槽位**（`back_left` / `mid_left` / `front_mid`…）。那是给渲染层
 * 用的：带深度前缀，好排前后遮挡，换设备也不穿帮。
 *
 * 但用户嘴里说的是**语义槽位**：「靠左墙」「窗边」「屋子中间」「桌上」「角落」。
 * 这两套词汇对不上：`by_window` 在库里的几何名是 `window_side`，
 * `corner` 对应的是 `front_left`。
 *
 * 所以让模型说人话、让代码存几何 —— 这一层就是翻译官，**翻译只发生一次**
 * （写入时），库里不混两套词汇，老数据也不需要迁移。
 *
 * ## 为什么坐标不在这一层重新定义
 *
 * 坐标的**唯一出处**是 [RoomGeometry]。这一层只做「语义 → 几何」和
 * 「给这种家具再加一点偏移/缩放」（挂画挂墙、台灯放桌面）。如果这里再写一份
 * 坐标表，改一次地板比例就要改两个地方，两处早晚不一致 —— 那正是
 * 「她说在窗边、画在墙角」这类 bug 的来源。
 */
object RoomLayout {

    // ------------------------------------------------------------ 语义槽位（模型看到的词）

    const val SLOT_LEFT_WALL = "left_wall"
    const val SLOT_RIGHT_WALL = "right_wall"
    const val SLOT_CENTER_FLOOR = "center_floor"
    const val SLOT_BY_WINDOW = "by_window"
    const val SLOT_CORNER = "corner"
    const val SLOT_ON_DESK = "on_desk"

    /** 给提示词用的完整列表，**顺序即提示词里的顺序** */
    val SLOTS = listOf(
        SLOT_LEFT_WALL, SLOT_RIGHT_WALL, SLOT_CENTER_FLOOR,
        SLOT_BY_WINDOW, SLOT_CORNER, SLOT_ON_DESK
    )

    /** 语义槽位 → 几何槽位。加新词只改这一张表，渲染层不用动。 */
    private val GEOMETRIC: Map<String, String> = mapOf(
        SLOT_LEFT_WALL to RoomItem.SLOT_BACK_LEFT,
        SLOT_RIGHT_WALL to RoomItem.SLOT_BACK_RIGHT,
        SLOT_CENTER_FLOOR to RoomItem.SLOT_MID_CENTER,
        SLOT_BY_WINDOW to RoomItem.SLOT_WINDOW_SIDE,
        SLOT_CORNER to RoomItem.SLOT_FRONT_LEFT,
        SLOT_ON_DESK to RoomItem.SLOT_ON_DESK
    )

    /**
     * 中文别名 → 语义槽位。
     *
     * 不是「以防万一」：本地小模型（和关键词解析）经常吐「左边」「桌上」
     * 这种词。把它当非法槽位丢掉，用户会看到「她说摆好了，屋子没变」。
     *
     * 长词在前：`窗边的角落` 应该先命中「窗边」，不能先命中「角落」。
     */
    private val ZH_ALIASES: List<Pair<String, String>> = listOf(
        "靠窗" to SLOT_BY_WINDOW,
        "窗边" to SLOT_BY_WINDOW,
        "窗下" to SLOT_BY_WINDOW,
        "窗台" to SLOT_BY_WINDOW,
        "左墙" to SLOT_LEFT_WALL,
        "靠左" to SLOT_LEFT_WALL,
        "左边" to SLOT_LEFT_WALL,
        "左侧" to SLOT_LEFT_WALL,
        "右墙" to SLOT_RIGHT_WALL,
        "靠右" to SLOT_RIGHT_WALL,
        "右边" to SLOT_RIGHT_WALL,
        "右侧" to SLOT_RIGHT_WALL,
        "屋子中间" to SLOT_CENTER_FLOOR,
        "房间中间" to SLOT_CENTER_FLOOR,
        "正中间" to SLOT_CENTER_FLOOR,
        "中央" to SLOT_CENTER_FLOOR,
        "中间" to SLOT_CENTER_FLOOR,
        "桌上" to SLOT_ON_DESK,
        "桌子上" to SLOT_ON_DESK,
        "桌面" to SLOT_ON_DESK,
        "墙角" to SLOT_CORNER,
        "角落" to SLOT_CORNER,
        "拐角" to SLOT_CORNER
    )

    /**
     * 把模型/用户给的槽位归一成**几何槽位**；认不出来返回 null。
     *
     * 三种输入都接受：
     *  1. 语义槽位（`left_wall`）→ 翻成几何；
     *  2. 几何槽位（`back_left`，老数据 / 老提示词的产物）→ 原样放行；
     *  3. 中文别名（`左边`）→ 先翻语义再翻几何。
     *
     * 归一放在这里而不是校验层，是为了让「白名单」只有一个：能归一的就是合法。
     */
    fun geometricOf(slot: String): String? {
        val s = slot.trim().lowercase()
        if (s.isEmpty()) return null
        GEOMETRIC[s]?.let { return it }
        if (s in RoomItem.SLOTS || s == RoomItem.SLOT_ON_WALL ||
            s == RoomItem.SLOT_MID_CENTER || s == RoomItem.SLOT_WINDOW_SIDE ||
            s == RoomItem.SLOT_ON_DESK
        ) {
            return s
        }
        val zh = ZH_ALIASES.firstOrNull { s.contains(it.first) }?.second ?: return null
        return GEOMETRIC[zh]
    }

    /** 语义槽位名（用于回话：「沙发摆在左墙边」）。认不出返回 null */
    fun semanticOf(geometric: String): String? =
        GEOMETRIC.entries.firstOrNull { it.value == geometric }?.key

    // ------------------------------------------------------------ 坐标

    /**
     * 挂载方式。
     *
     * [WALL] 的 `y` 含义和 [FLOOR] **完全不同**（前者是屏幕高度比例，后者是地板带比例）。
     * 用一个字段加一个枚举来表达，比拆成两个字段好：拆开以后
     * 「只 set 了 floorY 忘了 set wallY」这种 bug 编译器不会说话。
     */
    enum class Mount { FLOOR, WALL }

    /**
     * @param x     横向 0~1（屏幕宽度比例）
     * @param y     [Mount.FLOOR]：地板带 0~1；[Mount.WALL]：屏幕高度 0~1
     * @param scale 已经算上远近与家具自身缩放的最终倍数
     * @param depth 绘制顺序（−1 墙面 / 0 后 / 1 中 / 2 前）
     */
    data class Placement(
        val x: Float,
        val y: Float,
        val scale: Float,
        val depth: Int,
        val mount: Mount
    )

    /** 安全区：留一点边，家具压到屏幕边缘会被裁掉一半 */
    const val SAFE_X_MIN = 0.06f
    const val SAFE_X_MAX = 0.94f
    const val SAFE_Y_MIN = 0.00f
    const val SAFE_Y_MAX = 1.00f

    /**
     * 挂画的底边挂在墙的哪个高度（屏幕比例）。
     *
     * 0.40 是「挂在视平线上」那个位置：墙占到屏高的 0.60，所以画的中心落在
     * 墙面的中段。挂得太低（0.46 试过）会像贴在地板上的一张纸条。
     */
    const val WALL_BASE_Y = 0.40f

    /**
     * 桌面件的落点：**桌面**，不是地面。
     *
     * 0.365 是算出来的：书桌默认落在 `mid_left`（地板带 y=0.44），它的台面在
     * `baseY − 1.02u` 处；把这些代进地板换算，回推出来就是 0.365。
     * 之前随手写 0.30，台灯悬在桌面上方 60 多像素 —— 单测看不出来，画面一眼假。
     *
     * **已知代价**：这个点是相对「书桌的默认位置」算的。用户把书桌挪走了，
     * 台灯不会跟着走（`place()` 是 `(kind, slot)` 的纯函数，看不到别的家具）。
     * 宁可这样，也不要为了它去查一遍全屋 —— 那样渲染就变成有状态的了。
     */
    const val ON_DESK_Y = 0.365f

    /** 桌面件（台灯/小摆件）额外缩到 0.46，不然台灯比桌子还大 */
    const val ON_DESK_SCALE = 0.46f

    /**
     * 挂画的固定缩放：1.30。
     *
     * 试过 0.62（按「远处的家具都小」推的），在 1080 宽的屏上算出来只有 44×56 像素，
     * 挂在墙上像一块污渍。墙上的东西离得远，但**观众看它时是平视的** ——
     * 该按「一眼能认出这是幅画」定尺寸，而不是按透视缩放的数学。
     */
    const val POSTER_SCALE = 1.30f

    /**
     * 唯一入口：给（种类，槽位）算出画在哪儿。
     *
     * **全函数，永不抛**：模型可能给出任何字符串，渲染层每帧都会调它，
     * 抛一次异常就是整间屋子黑屏。认不出的槽位按种类的默认槽位落位。
     */
    fun place(kind: String, slot: String): Placement {
        val geometric = geometricOf(slot) ?: defaultSlotGeometricOf(kind)
        val anchor = RoomGeometry.anchorOf(geometric)
        val wall = isWallMounted(kind)

        val scaleBias = when {
            wall -> POSTER_SCALE
            geometric == RoomItem.SLOT_ON_DESK -> ON_DESK_SCALE
            else -> 1f
        }
        val scale = RoomGeometry.depthScaleOf(geometric) * scaleBias

        return if (wall) {
            Placement(
                x = clampX(anchor.x),
                y = WALL_BASE_Y,
                scale = scale,
                depth = -1,
                mount = Mount.WALL
            )
        } else {
            Placement(
                x = clampX(anchor.x),
                // 桌面件按「台面高度」重算 y，不用地板带里那个粗糙锚点
                y = if (geometric == RoomItem.SLOT_ON_DESK) {
                    ON_DESK_Y
                } else {
                    anchor.y.coerceIn(SAFE_Y_MIN, SAFE_Y_MAX)
                },
                scale = scale,
                depth = anchor.depth,
                mount = Mount.FLOOR
            )
        }
    }

    /** 挂画只能上墙；其余一律落地 */
    fun isWallMounted(kind: String): Boolean = kind.trim().lowercase() == RoomItem.KIND_POSTER

    /**
     * 一件家具的最终缩放 = 落点缩放 × 它自己记着的 scale。
     *
     * `place()` 只看 `(kind, slot)`，看不到「这件地毯是 1.15 倍」这种**实例**属性。
     * 批次 B 里地毯靠这个字段比默认大一点；0.9.19 改成走 `place()` 之后
     * 这个乘数一度被漏掉（地毯悄悄缩回 1.0）—— 所以这里把它补成一个可测的纯函数，
     * 而不是散在 `DrawScope` 里乘一下。
     */
    fun finalScaleOf(item: RoomItem): Float =
        place(item.kind, item.slot).scale * RoomGeometry.clampScale(item.scale)

    fun clampX(x: Float): Float = if (x.isNaN()) 0.5f else x.coerceIn(SAFE_X_MIN, SAFE_X_MAX)

    /** 只允许落在安全区里 —— 单测用来验证「任何槽位都不会把家具推出屏幕」 */
    fun isWithinSafeArea(p: Placement): Boolean =
        p.mount == Mount.WALL ||
            (p.x in SAFE_X_MIN..SAFE_X_MAX && p.y in SAFE_Y_MIN..SAFE_Y_MAX)

    // ------------------------------------------------------------ 默认落位

    /**
     * 没说位置时，这种家具该去哪儿。
     *
     * 不求「最好看」，求「不打架」：床靠右墙、沙发靠左墙、地毯铺中间、
     * 台灯上桌、挂画上墙。用户说了「加个沙发」就一定要看到变化，
     * 落到一个已经有东西的槽位也比分不清有没有生效好。
     */
    fun defaultSlotOf(kind: String): String = when (kind.trim().lowercase()) {
        RoomItem.KIND_SOFA -> SLOT_LEFT_WALL
        RoomItem.KIND_BED -> SLOT_RIGHT_WALL
        RoomItem.KIND_DESK -> SLOT_CORNER
        RoomItem.KIND_LAMP -> SLOT_ON_DESK
        RoomItem.KIND_SHELF -> SLOT_LEFT_WALL
        RoomItem.KIND_PLANT -> SLOT_CORNER
        RoomItem.KIND_RUG -> SLOT_CENTER_FLOOR
        RoomItem.KIND_POSTER -> RoomItem.SLOT_ON_WALL
        else -> SLOT_CENTER_FLOOR
    }

    /** 默认落位的几何形式（写入库要用这个） */
    fun defaultSlotGeometricOf(kind: String): String =
        geometricOf(defaultSlotOf(kind)) ?: RoomItem.SLOT_MID_CENTER

    /**
     * 挑一个还空着的槽位。
     *
     * `usedSlots` 传**几何槽位**。先试默认槽位，被占了就按
     * 「左墙 → 右墙 → 角落 → 窗边 → 桌面 → 中间」的顺序找下一个。
     *
     * **中间放最后**：那是她站的地方，新家具摆过去会被她挡掉一半。
     * 只有别的都满了才退到中间 —— 总比跟别人叠在一起强。
     * 全满就回默认槽位（宁可与别人重叠，也不要丢掉这件家具）。
     */
    fun freeSlotGeometricOf(kind: String, usedSlots: Collection<String>): String {
        val preferred = defaultSlotGeometricOf(kind)
        if (preferred !in usedSlots) return preferred
        val order = if (isWallMounted(kind)) {
            listOf(RoomItem.SLOT_ON_WALL)
        } else {
            listOf(
                SLOT_LEFT_WALL, SLOT_RIGHT_WALL, SLOT_CORNER,
                SLOT_BY_WINDOW, SLOT_ON_DESK, SLOT_CENTER_FLOOR
            ).mapNotNull { geometricOf(it) }
        }
        return order.firstOrNull { it !in usedSlots } ?: preferred
    }
}
