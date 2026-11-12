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
 *  - `depth`：0 后 / 1 中 / 2 前。绘制顺序按它排 —— 后画的压在前面，
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
        RoomItem.SLOT_FRONT_RIGHT to Anchor(0.84f, 0.90f, 2)
    )

    /** 认不出来的槽位一律摆到正中偏后 —— 宁可挡住一点，也不要整间屋子空掉 */
    private val FALLBACK = Anchor(0.50f, 0.30f, 1)

    fun anchorOf(slot: String): Anchor = ANCHORS[slot] ?: FALLBACK

    /**
     * 她在屋里的站位：正中偏前，脚踩在地板带的 0.78 处。
     *
     * **这是「她站哪儿」的唯一出处**，角色层必须用 [heroFootFraction] 换算，
     * 不许自己写 `padding(bottom = 84.dp)` —— 那样换台屏幕她就挪位置，
     * 而且和家具不共用同一套地板换算，看着像站在地板上方。
     *
     * 0.78 落在中景（0.44）和前排（0.90）之间：再往后会被家具挤，再往前会顶到抬头显示。
     */
    const val HERO_X = 0.50f
    const val HERO_Y = 0.78f

    /**
     * 脚底在**屏幕高度**上的比例（0~1）。
     *
     * 家具用 `floorTop + (H − floorTop) × anchor.y` 定位，角色得跟它同一套换算，
     * 否则「脚踩在地板上」只是巧合，改一次地板比例就露馅。
     */
    fun heroFootFraction(): Float = HORIZON + (1f - HORIZON) * HERO_Y

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

    /** 最终缩放 = 远近 × 家具自身 scale */
    fun finalScaleOf(item: RoomItem): Float = depthScaleOf(item.slot) * clampScale(item.scale)
}
