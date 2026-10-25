package com.nexus.agent.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * LunaMacro 极简色板 —— 低饱和、几何切割、极简高级。
 *
 * 参考亚文化 PV 动画截图：浅灰底、纯白/浅蓝块、深炭灰文字、细灰线框、微光黄点缀。
 */

/** 页面底色（浅灰） */
val LunaBackground = Color(0xFFF2F2F2)

/** 卡片 / 面板底色（纯白） */
val LunaSurface = Color(0xFFFFFFFF)

/** 卡片 / 面板备选底色（浅蓝） */
val LunaSurfaceAlt = Color(0xFFE6F0FA)

/** 主行动色 / 高亮（深空黑） */
val LunaPrimary = Color(0xFF1A1A1A)

/** 主行动色文字（纯白） */
val LunaOnPrimary = Color(0xFFFFFFFF)

/** 微光黄点缀（选中态 / 高亮） */
val LunaAccent = Color(0xFFFFDE59)

/** 主文字（深炭灰） */
val LunaOnBackground = Color(0xFF2C2C2C)

/** 次要文字（浅灰） */
val LunaOnSurfaceVariant = Color(0xFF808080)

/** 边框线（1px 浅灰）—— 0.7.0 起仅用于分隔线，不再作为卡片描边 */
val LunaOutline = Color(0xFFCCCCCC)

/** 弥散阴影色：极淡的墨色投影，制造悬浮感而不显脏 */
val LunaShadow = Color(0x1A000000)

/** 弥散阴影色（强调态，稍重） */
val LunaShadowStrong = Color(0x26000000)

/** 按下态（比主色更深） */
val LunaPressed = Color(0xFF000000)

// ---- 状态色（低饱和，用于权限状态块） ----

/** 正常态背景（浅蓝） */
val LunaOkBg = Color(0xFFE6F0FA)

/** 正常态前景 */
val LunaOkFg = Color(0xFF2C2C2C)

/** 异常态背景（浅黄） */
val LunaWarnBg = Color(0xFFFFF3CD)

/** 异常态前景 */
val LunaWarnFg = Color(0xFF8A6D1A)

/** 错误态背景（浅红） */
val LunaErrBg = Color(0xFFFDE2E2)

/** 错误态前景 */
val LunaErrFg = Color(0xFFB3403A)
