package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem

/**
 * 房间的空间换算（0.9.17 批次 B）—— 纯函数，不碰 Compose。
 *
 * ## 为什么要单独一层
 *
 * 「左后方」在手机上和在折叠屏上是两个坐标。如果家具里存的是像素，换台设备
 * 就会摆到墙外面去。所以 [RoomItem.slot] 存槽位，这里负责把槽位翻成
 * **归一化坐标**（0~1），渲染层再乘上当前画布尺寸。
 *
 * ## 三个轴
 *
 *  - `x`：横向位置，0 左 1 右；
 *  - `y`：在**地板带**里的位置，0 贴墙 1 贴屏幕（不是整屏比例）；
 *  - `depth`：−1 墙面 / 0 后 / 1 中 / 2 前。绘制顺序按它排 —— 后画的压在前面，
 *    这样沙发才能挡住地毯的一角，而不是反过来。
 *
 * 越远的东西越小（[itemScaleOf]）：这是唯一的透视暗示。不做梯形地板、
 * 不做真正的 3D，第一版只要「看着像一间屋子」。
 */
object RoomGeometry {

    /** 地平线在整屏的哪个高度：0.60 以上是墙，以下是地板 */
    const val HORIZON = 0.60f

    /** 远处的家具缩到 0.78 倍，近处 1.00 倍 */
    const val SCALE_BACK = 0.78f
    const val SCALE_FRONT = 1.00f

    /** 槽位 → 归一化坐标与深度 */
    data class Anchor(val x: Float, val y: Float, val depth: Int)

    private val ANCHORS: Map<String, Anchor> = mapOf(
        RoomItem.SLOT_BACK_LEFT to Anchor(0.20f, 0.04f, 0),
        RoomItem.SLOT_BACK_MID to Anchor(0.50f, 0.02f, 0),
        RoomItem.SLOT_BACK_RIGHT to Anchor(0.80f, 0.04f, 0),
        RoomItem.SLOT_MID_LEFT to Anchor(0.24f, 0.44f, 1),
        RoomItem.SLOT_MID_RIGHT to Anchor(0.76f, 0.44f, 1),
        RoomItem.SLOT_FRONT_LEFT to Anchor(0.16f, 0.90f, 2),
        RoomItem.SLOT_FRONT_MID to Anchor(0.50f, 0.86f, 2),
        RoomItem.SLOT_FRONT_RIGHT to Anchor(0.84f, 0.90f, 2),

        // ---- 0.9.19：语义槽位落到的几何位置 ----
        // 她站在 (0.50, 0.78)。中间这块因此不能放在 0.78，只能退到她身后 0.42 ——
        // 否则「屋子中间放个沙发」会把她整个遮住，看起来像她被塞进沙发里。
        RoomItem.SLOT_MID_CENTER to Anchor(0.50f, 0.42f, 1),
        // 窗在屏宽 0.62~0.88、屏高 0.10~0.30，所以「窗边」贴墙放、取窗的左下角。
        //
        // **不能取窗户中线 0.75**：默认布置里床就在 `back_right`（0.80），
        // 两者中间只差 54px，而一张沙发半宽 80、一张床半宽 83 —— 会整个叠在一起。
        // 0.63 离床 194px，间隙才够；离 back_mid 的落地灯 140px，也不撞。
        RoomItem.SLOT_WINDOW_SIDE to Anchor(0.63f, 0.06f, 0),
        // 桌面：比书桌锚点（mid_left，0.44）高一截，缩放在 RoomLayout 里另乘系数
        RoomItem.SLOT_ON_DESK to Anchor(0.24f, 0.30f, 1),
        // 墙面件：深度 −1 永远最先画。y 对挂画没意义（挂画按屏幕比例定高），
        // 但这里仍给个合法值，免得「读到锚点却在别处忘了走墙面分支」时炸成 NaN。
        RoomItem.SLOT_ON_WALL to Anchor(0.30f, 0.10f, -1)
    )

    /** 认不出来的槽位一律摆到正中偏后 —— 宁可挡住一点，也不要整间屋子空掉 */
    private val FALLBACK = Anchor(0.50f, 0.30f, 1)

    fun anchorOf(slot: String): Anchor = ANCHORS[slot] ?: FALLBACK

    /**
     * 她在屋里的站位：正中偏前，脚踩在地板带的 0.70 处。
     *
     * **这是「她站哪儿」的唯一出处**，角色层必须用 [heroFootFraction] 换算，
     * 不许自己写 `padding(bottom = 84.dp)` —— 那样换台屏幕她就挪位置，
     * 而且和家具不共用同一套地板换算，看着像站在地板上方。
     *
     * 0.70 落在中景（0.44）和前排（0.90）之间：再往后会被家具挤。
     * **再往前也不行**：0.9.19 起屏幕底部多了一条装修输入条（约 58dp），
     * 原来的 0.78（脚在 0.912）会让输入条压住她的脚背 —— 看起来像她陷进地板里。
     */
    const val HERO_X = 0.50f
    const val HERO_Y = 0.70f

    /**
     * 脚底在**屏幕高度**上的比例（0~1）。
     *
     * 家具用 `floorTop + (H − floorTop) × anchor.y` 定位，角色得跟它同一套换算，
     * 否则「脚踩在地板上」只是巧合，改一次地板比例就露馅。
     */
    fun heroFootFraction(): Float = HORIZON + (1f - HORIZON) * HERO_Y

    // ------------------------------------------------------------ 舞台（0.9.20 批次 D）

    /**
     * 设计基准宽高比（宽 ÷ 高）。
     *
     * ## 为什么要有这个东西
     *
     * 0.9.19 之前，所有横向坐标都按**整屏宽度**归一：沙发在 0.63，就是屏宽 63% 处。
     * 在 1080×2400 的手机上没问题，但把同一份代码放到折叠屏（2200×2400）上，
     * 屋子横向被拉成 0.92 —— 家具还是「各占 7.5% 屏宽」地铺开，于是整间屋子
     * 横向散架：床和沙发之间隔着一米，地毯变成一块横条。
     *
     * 不是家具画错了，是**摄像机被换成了广角**。真实世界里房间没变宽，
     * 是视野两侧多看到了墙。所以横向坐标不能按屏宽归一，要按「一个固定比例的
     * 舞台」归一，舞台以外的地方就是墙。
     *
     * 0.62 ≈ 9:14.5，比常见手机（9:19.5 ≈ 0.46）宽一点：手机上台宽度 = 屏宽
     * （和以前一模一样，零回归），只有比这更宽的设备才会出现两侧的墙。
     */
    const val STAGE_ASPECT = 0.62f

    /**
     * 舞台：横向坐标要映射到的那一段。纵向永远满高。
     *
     * @property left  舞台左边缘（px）。比屏幕窄时居中，两侧留出墙
     * @property width 舞台宽度（px）
     * @property height 舞台高度（px）＝ 屏幕高度
     */
    data class Stage(val left: Float, val width: Float, val height: Float) {
        /** 归一化 x（0~1）→ 屏幕像素 */
        fun px(fx: Float): Float = left + width * fx

        /** 舞台单位：家具尺寸的基准。跟舞台宽走，跟屏幕宽度无关 */
        val unit: Float get() = width * 0.075f
    }

    /**
     * 屏幕尺寸 → 舞台。
     *
     * 宽度取 `min(屏宽, 屏高 × STAGE_ASPECT)`：
     *  - 普通手机（0.45）：结果是屏宽 —— 和 0.9.19 完全一致；
     *  - 折叠屏展开（0.92）：结果是 `屏高 × 0.62`，居中，两边是墙；
     *  - 平板/横屏：同上，房间始终是竖着的一条，不会被摊成一片。
     */
    fun stageOf(width: Float, height: Float): Stage {
        val w = if (width.isFinite() && width > 0f) width else 1f
        val h = if (height.isFinite() && height > 0f) height else 1f
        val stageW = minOf(w, h * STAGE_ASPECT)
        return Stage(left = (w - stageW) / 2f, width = stageW, height = h)
    }

    /**
     * 绘制顺序：先远后近，同深度按 x 从左到右。
     *
     * 同深度按 x 排是为了**稳定**：否则每次 Room 重新发一次 List，家具可能互相
     * 交换前后关系，看起来像画面在抖。
     */
    fun ordered(items: List<RoomItem>): List<RoomItem> =
        items.sortedWith(compareBy({ anchorOf(it.slot).depth }, { anchorOf(it.slot).x }, { it.id }))

    /** 远近缩放：地板带上越靠下（越近）越大 */
    fun depthScaleOf(slot: String): Float {
        val y = anchorOf(slot).y.coerceIn(0f, 1f)
        return SCALE_BACK + (SCALE_FRONT - SCALE_BACK) * y
    }

    /**
     * 家具写入时可能带越界的 scale（自然语言装修很容易给出 3 倍这种数），
     * 这里统一钳制。**不在写入时拦**：数据保留用户原意，渲染层保证不炸。
     */
    fun clampScale(scale: Float): Float =
        if (scale.isNaN()) 1f else scale.coerceIn(RoomItem.SCALE_MIN, RoomItem.SCALE_MAX)

    // ================================================================ 赛博电视（0.9.26）

    /**
     * 电视在墙上的位置（**整屏比例**，不是地板带）。
     *
     * 为什么挂在右墙 0.78 而不是正中：中间是她的站位，墙上正中挂台电视会
     * 让她永远背对着屏幕。0.78 在窗户（0.62~0.88）下方，避开了床
     * （back_right = 0.80，但那是地板槽位，深度 0，不会打架）。
     */
    const val TV_CENTER_X = 0.76f

    /** 电视屏幕中心的高度（整屏比例）。0.44 在墙的中部偏下，坐着平视刚好 */
    const val TV_CENTER_Y = 0.44f

    /** 电视宽度占**舞台宽**的比例 */
    const val TV_WIDTH_FRAC = 0.30f

    /** 电视机身宽高比（屏幕 16:9 + 边框底座） */
    const val TV_ASPECT = 0.62f

    /**
     * 她看电视时坐的位置（地板带坐标）。
     *
     * 在电视正前方偏左下：正对着电视会把自己完全挡在屏幕前面（她的画框有
     * 40% 屏高），偏左 0.06 能露出屏幕右半边 —— 观众既看得到她，也看得到内容。
     */
    fun cyberSeat(): RoomBehaviorEngine.Spot = RoomBehaviorEngine.Spot(
        x = clampFeet(TV_CENTER_X - 0.06f),
        y = 0.66f
    )

    private fun clampFeet(x: Float): Float =
        if (!x.isFinite()) HERO_X
        else x.coerceIn(RoomBehaviorEngine.SAFE_FEET_X_MIN, RoomBehaviorEngine.SAFE_FEET_X_MAX)
}
