package com.example.astrion.ui.screens.panel

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.example.astrion.services.SatelliteStateHolder
import com.example.astrion.utils.getLocalIpAddress
import com.example.astrion.esphome.Connected
import com.example.astrion.ui.theme.RemoteColors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    raiseToWake: Boolean,
    onRaiseToWakeChanged: (Boolean) -> Unit,
    screenSaverTimeout: Int,
    onScreenSaverTimeoutChanged: (Int) -> Unit,
    onRefreshDevices: () -> Unit,
    onOpenShortcutKeys: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
            // 上滑关闭（原版下拉面板的手势）；轻点空白处同样关闭
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var total = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        total += change.positionChange().y
                        if (total < -140f) {
                            onDismiss()
                            break
                        }
                    }
                }
            }
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 34.dp)
                .background(RemoteColors.surface, RoundedCornerShape(18.dp))
                // 面板本体消费点击，避免点空白处误触背景关闭；内容超高时滚动
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { }
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
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

            // 网络（原版 llWifiButton：WiFi 状态 + IP）
            val ip = remember { getLocalIpAddress() ?: "" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网络", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (ip.isNotBlank()) "WiFi · $ip" else "未连接",
                    color = RemoteColors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(10.dp))

            // 刷新设备（原版 rlRefreshDevice：重连 HA 重拉实体）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRefreshDevices)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("刷新设备", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("重连 ↻", color = RemoteColors.accent, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))

            // 息屏（原版 ScreenOffSettings：按键/无操作后背光全灭）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onScreenOff)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("息屏", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("立即 →", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))

            // 抬手唤醒（原版 rlWake）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("抬手唤醒", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Switch(checked = raiseToWake, onCheckedChange = onRaiseToWakeChanged)
            }
            Spacer(Modifier.height(6.dp))

            // 屏保时长：0=关闭，30 秒步进到 10 分钟（与 HA number 实体双向同步）
            val saverLabel = when {
                screenSaverTimeout <= 0 -> "关闭"
                screenSaverTimeout < 60 -> "${screenSaverTimeout}秒"
                else -> "${(screenSaverTimeout + 30) / 60}分钟"
            }
            Text("屏保 $saverLabel", color = RemoteColors.onSurface, fontSize = 14.sp)
            Slider(
                value = screenSaverTimeout.toFloat(),
                onValueChange = { onScreenSaverTimeoutChanged((it / 30f).roundToInt() * 30) },
                valueRange = 0f..600f,
                steps = 19
            )
            Spacer(Modifier.height(6.dp))

            // 快捷键绑定（原版 rlShortcutKey → ShortcutKeyActivity）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenShortcutKeys)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("快捷键绑定", color = RemoteColors.onSurface, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("F4–F11 一键直达", color = RemoteColors.accent, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))

            // 系统设置（原版 rlSetting）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSettings)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("系统设置", color = RemoteColors.onSurface, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))

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
 * of the screen (original drop-down entry gesture). The gesture is only
 * claimed after it clearly becomes a downward drag from the edge, so normal
 * content scrolling is never hijacked; as soon as the panel opens the rest of
 * the touch is handed over to it (no dead first frame).
 */
fun Modifier.topEdgeSwipeToOpen(enabled: Boolean = true, onOpen: () -> Unit): Modifier =
    pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val edgePx = 80.dp.toPx()
        val threshold = 90.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.y > edgePx) return@awaitEachGesture
            var total = 0f
            var claimed = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull() ?: break
                if (!change.pressed) break
                total += change.positionChange().y
                if (!claimed && total > slop) {
                    claimed = true
                }
                if (claimed) {
                    change.consume()
                    if (total > threshold) {
                        onOpen()
                        break
                    }
                }
            }
        }
    }
