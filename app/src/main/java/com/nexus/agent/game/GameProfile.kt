package com.nexus.agent.game

import java.util.UUID

/**
 * 游戏 Profile：一份完整的游戏自动化配置。
 *
 * 包含目标应用、屏幕区域定义、规则集合、感知帧率。
 */
data class GameProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageName: String? = null,               // 目标游戏包名
    val regions: Map<String, Region> = emptyMap(), // 区域名 → Region
    val rules: List<GameRule> = emptyList(),       // 规则集合
    val perceptionFps: Int = 5                     // 感知帧率
)
