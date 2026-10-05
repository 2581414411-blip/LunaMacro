package com.nexus.agent.game

/**
 * 屏幕分析结果（SFT 数据采集/标注的统一数据结构）。
 */
data class ScreenAnalysis(
    val screenType: String = "其他",
    val buttons: List<ButtonInfo> = emptyList(),
    val description: String = ""
)

/**
 * 按钮信息（归一化坐标 0.0-1.0，相对截图宽高）。
 */
data class ButtonInfo(
    val text: String,
    val x: Float,
    val y: Float
)
