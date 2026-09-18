package com.example.astrion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The panel is an appliance with an always-dark "remote" theme that mirrors the
 * original HaRemote APK palette: 纯黑面板、深灰按键、米金描边、开启状态暖褐底 + 绿色指示灯.
 * It stays dark regardless of the system setting.
 */
object RemoteColors {
    // 原版官方配色（colors.xml 提取）：layout_bg #0C0C0D、
    // device_button #2B2B2B、text_device_item_name #BFBDBD、
    // text_universal_gold #BDA67A、device_on_line #27D343、text_red #F1453C
    val backgroundTop = Color(0xFF0C0C0D)
    val backgroundBottom = Color(0xFF0C0C0D)
    val surface = Color(0xFF161618)
    val surfaceVariant = Color(0xFF2B2B2B)
    val accent = Color(0xFFBDA67A)
    val accentContainer = Color(0xFF2B2B2B)
    val onSurface = Color(0xFFFFFFFF)
    val onSurfaceVariant = Color(0xFFBFBDBD)
    val outline = Color(0xFF3A3A3C)
    val secondary = Color(0xFF27D343)
    val error = Color(0xFFF1453C)
    val key = Color(0xFF2B2B2B)
    val keyPressed = Color(0xFF808080)
    val deviceOn = Color(0xFF282828)
    val hintText = Color(0x80B0B0B0)
    val topBarText = Color(0xFFFFFFFF)
    val roomName = Color(0xFFCCCCCC)
    val popupBackground = Color(0xFF2B2B2B)
    val wifiHint = Color(0xFF8C7B5B)
    val popupLine = Color(0xFF3A3A3C)
    val dot = Color(0xFFBFBDBD)
    val rowSeparator = Color(0xFF27D343)
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
        onPrimary = Color(0xFF1F1B12),
        primaryContainer = RemoteColors.accentContainer,
        onPrimaryContainer = RemoteColors.onSurface,
        secondary = RemoteColors.secondary,
        onSecondary = Color(0xFF04240D),
        secondaryContainer = RemoteColors.surfaceVariant,
        onSecondaryContainer = RemoteColors.onSurface,
        error = RemoteColors.error,
        onError = Color.White,
        errorContainer = Color(0xFF4A1712),
        onErrorContainer = Color(0xFFFFDAD6),
        background = RemoteColors.backgroundTop,
        onBackground = RemoteColors.onSurface,
        surface = RemoteColors.surface,
        onSurface = RemoteColors.onSurface,
        surfaceVariant = RemoteColors.surfaceVariant,
        onSurfaceVariant = RemoteColors.onSurfaceVariant,
        outline = RemoteColors.outline,
        outlineVariant = RemoteColors.rowSeparator,
        surfaceContainerLowest = RemoteColors.backgroundTop,
        surfaceContainerLow = Color(0xFF121214),
        surfaceContainer = Color(0xFF161617),
        surfaceContainerHigh = RemoteColors.surfaceVariant,
        surfaceContainerHighest = RemoteColors.surfaceVariant
    )
    MaterialTheme(colorScheme = scheme, content = content)
}