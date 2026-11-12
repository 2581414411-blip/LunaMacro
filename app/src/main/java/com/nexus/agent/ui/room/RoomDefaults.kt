package com.nexus.agent.ui.room

import com.nexus.agent.data.db.RoomItem
import com.nexus.agent.data.db.RoomTheme

/**
 * 第一版「她的屋子」长什么样（0.9.17 批次 B）—— 纯数据，可单测。
 *
 * ## 为什么要有默认家具
 *
 * 空屋子在画面上就是一块墙皮加一块地板，用户点门进来会以为功能坏了。
 * 但这套家具**不是**装修系统的起点，而是「看她情绪的那个舞台布景」：
 * 七件东西各占一个槽位，正好把七种绘制例程都跑一遍，也顺便给下一批的
 * 自然语言装修留一个「都能摆开」的参考布局。
 *
 * ## 为什么种子写入用主题行当开关
 *
 * [RoomStore.ensureSeeded] 只在 `room_theme` 为空时铺一次。用「有没有主题行」
 * 而不是「家具是不是 0 件」判断，是因为后者会让用户**清空房间之后又被塞满**——
 * 那比不种子还糟。
 */
object RoomDefaults {

    /** 暖木小屋的默认家具：7 件，7 个种类，7 个不同槽位 */
    fun furniture(now: Long = System.currentTimeMillis()): List<RoomItem> = listOf(
        RoomItem(
            kind = RoomItem.KIND_SHELF,
            slot = RoomItem.SLOT_BACK_LEFT,
            tint = 0xFF9A8571.toInt(),
            note = "旧书架",
            createdAt = now
        ),
        RoomItem(
            kind = RoomItem.KIND_LAMP,
            slot = RoomItem.SLOT_BACK_MID,
            tint = 0xFFD8B26A.toInt(),
            note = "落地灯",
            createdAt = now + 1
        ),
        RoomItem(
            kind = RoomItem.KIND_BED,
            slot = RoomItem.SLOT_BACK_RIGHT,
            tint = 0xFF9FB2C6.toInt(),
            note = "她的床",
            createdAt = now + 2
        ),
        RoomItem(
            kind = RoomItem.KIND_DESK,
            slot = RoomItem.SLOT_MID_LEFT,
            tint = 0xFFB08A62.toInt(),
            note = "书桌",
            createdAt = now + 3
        ),
        RoomItem(
            kind = RoomItem.KIND_PLANT,
            slot = RoomItem.SLOT_MID_RIGHT,
            tint = 0xFF7FA37F.toInt(),
            note = "绿萝",
            createdAt = now + 4
        ),
        RoomItem(
            kind = RoomItem.KIND_SOFA,
            slot = RoomItem.SLOT_FRONT_LEFT,
            tint = 0xFFB98F86.toInt(),
            note = "沙发",
            createdAt = now + 5
        ),
        RoomItem(
            kind = RoomItem.KIND_RUG,
            slot = RoomItem.SLOT_FRONT_MID,
            tint = 0xFFD9C4A8.toInt(),
            scale = 1.15f,
            note = "地毯",
            createdAt = now + 6
        )
    )

    fun theme(now: Long = System.currentTimeMillis()): RoomTheme = RoomTheme(
        wall = RoomTheme.DEFAULT_WALL,
        floor = RoomTheme.DEFAULT_FLOOR,
        window = true,
        preset = RoomTheme.PRESET_DEFAULT,
        updatedAt = now
    )

    /**
     * 主题预设 → (墙色, 地板色, 有没有窗)。
     *
     * 现在只有三套，写在代码里就够。等下一批接自然语言装修（「把屋子弄得像雨天下午」）
     * 时，这里会变成「预设 + 微调」：模型选一套最接近的预设，再给两个颜色偏移。
     * 那样就算它编了一个不存在的预设名，也能落到 [RoomTheme.PRESET_DEFAULT] 上。
     */
    data class Preset(val name: String, val wall: Int, val floor: Int, val window: Boolean)

    val PRESETS: List<Preset> = listOf(
        Preset(RoomTheme.PRESET_DEFAULT, RoomTheme.DEFAULT_WALL, RoomTheme.DEFAULT_FLOOR, true),
        Preset(RoomTheme.PRESET_NIGHT, RoomTheme.NIGHT_WALL, RoomTheme.NIGHT_FLOOR, true),
        Preset(RoomTheme.PRESET_STUDIO, RoomTheme.STUDIO_WALL, RoomTheme.STUDIO_FLOOR, false)
    )

    /** 认不出的预设名 → default。模型编词不该让房间整间变黑 */
    fun presetOf(name: String): Preset =
        PRESETS.firstOrNull { it.name == name } ?: PRESETS[0]
}
