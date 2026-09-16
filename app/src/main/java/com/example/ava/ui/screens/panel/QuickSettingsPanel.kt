package com.example.ava.ui.screens.panel

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ava.services.SatelliteStateHolder
import com.example.ava.esphome.Connected
import com.example.ava.ui.theme.RemoteColors
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonColors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The quick settings panel (原版 DropDownView): swipe down from the top edge
 * to reveal it. Keeps the original semantics — WiFi/HA status, screen
 * brightness, media volume — without duplicating things Home Assistant
 * already configures.
 */
@Composable
fun QuickSettingsPanel(
    connected: Boolean,
    micMuted: Boolean,
    onMicMutedChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 34.dp)
                .background(RemoteColors.surface, RoundedCornerShape(18.dp))
                .clickable(enabled = false) {}
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (connected) RemoteColors.secondary else RemoteColors.error,
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (connected) "Home Assistant 已连接" else "Home Assistant 未连接",
                    color = RemoteColors.onSurface,
                    fontSize = 15.sp
                )
            }
            Spacer(Modifier.height(14.dp))

            // Media volume (外放音量), 0..15 like the original SystemVolumeHs
            val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
            var volume by remember {
                mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
            }
            Text("音量 $volume/$maxVol", color = RemoteColors.onSurface, fontSize = 14.sp)
            Slider(
                value = volume.toFloat(),
                onValueChange = {
                    volume = it.toInt()
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                },
                valueRange = 0f..maxVol.toFloat()
            )
            Spacer(Modifier.height(10.dp))

            // Screen brightness 0..255 (原版 ScreenBrightnessHs)
            var brightness by remember {
                mutableIntStateOf(
                    runCatching {
                        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                    }.getOrDefault(128)
                )
            }
            Text("亮度 $brightness", color = RemoteColors.onSurface, fontSize = 14.sp)
            Slider(
                value = brightness.toFloat(),
                onValueChange = {
                    brightness = it.toInt()
                    runCatching {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            brightness
                        )
                    }
                },
                valueRange = 20f..255f
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("麦克风静音", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = micMuted, onCheckedChange = onMicMutedChanged)
            }
        }
    }
}

/**
 * Opens the quick settings panel when the user swipes down from the top edge
 * of the screen (original drop-down entry gesture).
 */
fun Modifier.topEdgeSwipeToOpen(enabled: Boolean = true, onOpen: () -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val edgePx = 60.dp.toPx()
        val threshold = 110.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.y > edgePx) return@awaitEachGesture
            var total = 0f
            var opened = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                total += change.positionChange().y
                change.consume()
                if (total > threshold && !opened) {
                    opened = true
                    onOpen()
                }
            }
        }
    }
