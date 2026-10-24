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
 * LunaMacro 主题 —— 低饱和抽象色块、极简高级、几何切割。
 *
 * 统一浅色底；字重对比强烈（标题 ExtraBold、正文 Medium）；字间距归零让文字紧凑；
 * 全局圆角 0dp（直角）强调几何切割感。
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

/** 形状：全局直角（0dp），强调几何切割感 */
private val LunaShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
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
