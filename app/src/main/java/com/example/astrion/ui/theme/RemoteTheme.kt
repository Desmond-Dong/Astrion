package com.example.astrion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The panel is an appliance with an always-dark "remote" theme modelled on the
 * original HaRemote UI (深色面板、蓝灰卡片、高亮按键), regardless of the
 * system setting.
 */
object RemoteColors {
    val backgroundTop = Color(0xFF0B1220)
    val backgroundBottom = Color(0xFF152238)
    val surface = Color(0xFF1E2A42)
    val surfaceVariant = Color(0xFF27364F)
    val accent = Color(0xFF3D8BFF)
    val accentContainer = Color(0xFF16325A)
    val onSurface = Color(0xFFE6ECF7)
    val onSurfaceVariant = Color(0xFFA9B8D0)
    val outline = Color(0xFF3A4A68)
    val secondary = Color(0xFF37E0A0)
    val error = Color(0xFFFF6B6B)
    val key = Color(0xFF223252)
    val keyPressed = Color(0xFF3D8BFF)
    val warning = Color(0xFFFFB020)
}

val RemoteBackground = Brush.verticalGradient(
    colors = listOf(RemoteColors.backgroundTop, RemoteColors.backgroundBottom)
)

@Composable
fun AstrionPanelTheme(content: @Composable () -> Unit) {
    // Reserved for future light theme support; the panel is always dark.
    isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = RemoteColors.accent,
        onPrimary = Color.White,
        primaryContainer = RemoteColors.accentContainer,
        onPrimaryContainer = RemoteColors.onSurface,
        secondary = RemoteColors.secondary,
        onSecondary = Color(0xFF06281B),
        secondaryContainer = RemoteColors.surfaceVariant,
        onSecondaryContainer = RemoteColors.onSurface,
        error = RemoteColors.error,
        onError = Color.White,
        errorContainer = Color(0xFF7A1F1F),
        onErrorContainer = Color(0xFFFFDAD6),
        background = RemoteColors.backgroundTop,
        onBackground = RemoteColors.onSurface,
        surface = RemoteColors.surface,
        onSurface = RemoteColors.onSurface,
        surfaceVariant = RemoteColors.surfaceVariant,
        onSurfaceVariant = RemoteColors.onSurfaceVariant,
        outline = RemoteColors.outline,
        outlineVariant = RemoteColors.outline,
        surfaceContainerLowest = RemoteColors.backgroundTop,
        surfaceContainerLow = RemoteColors.surface,
        surfaceContainer = RemoteColors.surface,
        surfaceContainerHigh = RemoteColors.surfaceVariant,
        surfaceContainerHighest = RemoteColors.surfaceVariant
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
