package com.nexus.agent.macro

import java.util.UUID

/**
 * 宏（Macro）：一段预设的动作序列，供游戏等重复性场景回放。
 *
 * 核心思路：不让 LLM 逐步推理每一步操作，而是让用户/录制定义一次操作序列，
 * Agent 或用户直接回放。
 */
data class Macro(
    val id: String = UUID.randomUUID().toString(), // UUID
    val name: String,                              // 用户可见名称
    val description: String = "",                  // 描述
    val targetPackage: String? = null,             // 目标游戏包名（可空）
    val steps: List<MacroStep> = emptyList(),      // 步骤序列
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 单个动作步骤。
 *
 * @param type          动作类型
 * @param params        动作参数（坐标/文本/时长等，值均为 JSON 兼容基本类型）
 * @param delayAfterMs  执行后等待时间（毫秒）
 */
data class MacroStep(
    val type: StepType,
    val params: Map<String, Any> = emptyMap(),
    val delayAfterMs: Long = 0
)

/**
 * 步骤类型。
 */
enum class StepType {
    TAP,           // 点击坐标
    LONG_PRESS,    // 长按
    SWIPE,         // 滑动
    MULTI_TOUCH,   // 多指
    WAIT,          // 等待
    SCREENSHOT,    // 截图（用于条件判断）
    CONDITION      // 条件分支（后续批次实现）
}
