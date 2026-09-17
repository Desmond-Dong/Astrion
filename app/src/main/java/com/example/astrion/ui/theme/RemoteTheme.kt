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
    /** layout_bg #0C0C0D — the native near-black home background. */
    val backgroundTop = Color(0xFF0C0C0D)
    val backgroundBottom = Color(0xFF0C0C0D)

    /** universal_item_background_selected #17171A — dark gray panel/card. */
    val surface = Color(0xFF17171A)

    /** view_default_bg #2B2B2E — elevated panel / key area. */
    val surfaceVariant = Color(0xFF2B2B2E)

    /** text_universal_gold #BDA67A — brand gold accent (buttons/save/OTA/voice). */
    val accent = Color(0xFFBDA67A)

    /** item_device_on_background #32281D — warm surface used while a device is ON. */
    val accentContainer = Color(0xFF32281D)

    /** text_default_white #F6F6F6 — primary text. */
    val onSurface = Color(0xFFF6F6F6)

    /** text_device_item_name #BFBDBD — secondary text / device row names. */
    val onSurfaceVariant = Color(0xFFBFBDBD)

    /** divider #303030 — hairline separators. */
    val outline = Color(0xFF303030)

    /** device_on_line #27D343 — online / ON indicator green. */
    val secondary = Color(0xFF27D343)

    /** text_red #F1453C — offline / errors. */
    val error = Color(0xFFF1453C)

    /** device_button_background_unselected #2B2B2B — idle remote key. */
    val key = Color(0xFF2B2B2B)

    /** device_button_background_selected #808080 — focused/selected remote key. */
    val keyPressed = Color(0xFF808080)

    /** device_button_focuses_onclick_border #FF9800 — focus ring orange. */
    val warning = Color(0xFFFF9800)

    /** Native accent palette extras. */
    val deviceOn = Color(0xFF32281D)
    val roomName = Color(0xE6CCCBCB)
    val hintText = Color(0x80B0B0B0)
    val wifiHint = Color(0xFF8C7B5B)
    val topBarText = Color(0xCCFFFFFF)
    val popupBackground = Color(0x9B040404)
    val popupLine = Color(0xFF2F2F36)
    val rowSeparator = Color(0xFF242425)
    val dot = Color(0xFF4A4A4C)
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