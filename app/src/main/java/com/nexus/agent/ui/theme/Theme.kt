package com.nexus.agent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Nexus Agent 主题。
 *
 * 深色主题为主，主色蓝色 #2563EB；浅色主题作为备选。
 */

/** 主色：蓝色 #2563EB */
val PrimaryBlue = Color(0xFF2563EB)
val PrimaryBlueDark = Color(0xFF1D4ED8)
val AccentBlue = Color(0xFF60A5FA)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = AccentBlue,
    onSecondary = Color.White,
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF23272E),
    onSurfaceVariant = Color(0xFFA8B0B8),
    tertiary = Color(0xFF8AB4F8),
    onTertiary = Color(0xFF0F1115)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = AccentBlue,
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF171A1F),
    surface = Color.White,
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFEEF1F5),
    onSurfaceVariant = Color(0xFF4B5563),
    tertiary = Color(0xFF2563EB),
    onTertiary = Color.White
)

/**
 * Nexus Agent 主题包装器。
 *
 * @param darkTheme 是否使用深色主题，默认跟随系统
 */
@Composable
fun NexusAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content
    )
}
