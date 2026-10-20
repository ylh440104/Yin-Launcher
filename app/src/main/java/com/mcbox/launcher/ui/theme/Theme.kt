package com.mcbox.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF04150A),
    primaryContainer = Color(0xFF166534),
    onPrimaryContainer = Color(0xFFDCFCE7),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF041226),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE8EDF2),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE8EDF2),
    surfaceVariant = Color(0xFF1F2630),
    onSurfaceVariant = Color(0xFF9BA7B4),
    outline = Color(0xFF39424E),
    error = Color(0xFFF87171)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF15803D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF052E16),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF11161C),
    surface = Color.White,
    onSurface = Color(0xFF11161C),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF55606C),
    outline = Color(0xFFC5CDD6),
    error = Color(0xFFB91C1C)
)

@Composable
fun McBoxTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}