package com.nexus.agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * LunaMacro 主题 —— 低饱和配色、微圆角、弥散阴影。
 *
 * 统一浅色底；字重对比强烈（标题 ExtraBold、正文 Medium）；字间距归零让文字紧凑；
 * 0.7.0 起由「几何切割」转向「轻盈悬浮」：圆角 10~16dp、无硬线框、靠极淡阴影分层。
 */

/** 极简色板（浅色） */
private val LunaColorScheme = lightColorScheme(
    primary = LunaPrimary,
    onPrimary = LunaOnPrimary,
    primaryContainer = LunaPrimary,
    onPrimaryContainer = LunaOnPrimary,
    secondary = LunaAccent,
    onSecondary = LunaOnBackground,
    secondaryContainer = LunaAccent,
    onSecondaryContainer = LunaOnBackground,
    tertiary = LunaAccent,
    onTertiary = LunaOnBackground,
    background = LunaBackground,
    onBackground = LunaOnBackground,
    surface = LunaSurface,
    onSurface = LunaOnBackground,
    surfaceVariant = LunaSurfaceAlt,
    onSurfaceVariant = LunaOnSurfaceVariant,
    outline = LunaOutline,
    outlineVariant = LunaOutline,
    error = LunaErrFg,
    onError = Color.White,
    errorContainer = LunaErrBg,
    onErrorContainer = LunaErrFg
)

/** 排版：无衬线、字重对比强烈、字间距 0 */
private val LunaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.sp
    )
)

/**
 * 形状：微圆角（10~16dp）。
 *
 * 卡片走 medium（14dp）、对话框走 extraLarge（16dp），与 0.7.0「轻盈悬浮」的视觉基调一致。
 */
private val LunaShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/**
 * LunaMacro 主题包装器。
 *
 * @param darkTheme 保留参数以兼容调用方，但本主题统一走浅色极简风。
 */
@Composable
fun NexusAgentTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LunaColorScheme,
        typography = LunaTypography,
        shapes = LunaShapes,
        content = content
    )
}
