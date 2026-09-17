package com.example.astrion.ui.screens.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.astrion.panel.ScreensaverController
import com.example.astrion.ui.theme.RemoteColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Battery snapshot shared by the screensaver and the charging hint
 * (original ScreenSaveTopView / TimeBatteryView, §3.10.7).
 */
data class PanelBatteryState(
    val level: Int = 0,
    val charging: Boolean = false,
)

/**
 * Overlays hosted above the whole panel UI (§3.10.7 屏保 / 充电动画):
 *
 * - the idle screensaver (clock / date / battery / HA link) and
 * - the short charging hint shown when a charger is plugged in.
 *
 * Dismissal works through [ScreensaverController.onUserActivity], which the
 * activity calls for every key and touch.
 */
@Composable
fun ScreensaverHost(
    controller: ScreensaverController,
    haConnected: Boolean,
) {
    val active by controller.active.collectAsState()
    val chargingFlash by controller.chargingFlash.collectAsState()
    val battery = rememberPanelBatteryState(controller)
    if (active) {
        ScreensaverOverlay(battery = battery.value, connected = haConnected)
    }
    if (chargingFlash) {
        ChargingFlashOverlay(battery = battery.value)
    }
}

/**
 * Tracks battery level / charging state and reports charger plug-in events to
 * the controller (original ChargingAnimationManager behaviour).
 */
@Composable
fun rememberPanelBatteryState(controller: ScreensaverController): State<PanelBatteryState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(PanelBatteryState()) }
    DisposableEffect(context, controller) {
        var previousCharging = false
        var firstDelivery = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL ||
                        plugged != 0
                state.value = PanelBatteryState(
                    level = if (level >= 0 && scale > 0) level * 100 / scale else state.value.level,
                    charging = charging
                )
                if (!firstDelivery && charging && !previousCharging) {
                    controller.showChargingFlash()
                }
                previousCharging = charging
                firstDelivery = false
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return state
}

/**
 * Full-screen idle overlay modelled on the original ScreenSaverDialog:
 * large HH:mm clock, localized date, battery with charge fill, and a
 * disconnected marker when the Home Assistant link is down.
 */
@Composable
private fun ScreensaverOverlay(
    battery: PanelBatteryState,
    connected: Boolean,
) {
    // Second tick so the clock stays current while visible.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val locale = Locale.getDefault()
    val timeText = remember(now, locale) {
        SimpleDateFormat("HH:mm", locale).format(Date(now))
    }
    val dateText = remember(now, locale) {
        val pattern = if (locale.language.equals("zh", ignoreCase = true)) {
            "M月d日 EEEE"
        } else {
            "EEEE, MMMM d"
        }
        SimpleDateFormat(pattern, locale).format(Date(now))
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070C))
            .pointerInput(Unit) {
                // Touches dismiss via MainActivity#dispatchTouchEvent ->
                // onUserActivity; consume them here so the remote UI below
                // never sees them while the screensaver is up.
                detectTapGestures { }
            }
    ) {
        // Status row (original ScreenSaveTopView: battery + HA no-connect).
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!connected) {
                Text(
                    text = "HA",
                    color = RemoteColors.error,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "⚠", color = RemoteColors.error, fontSize = 18.sp)
            }
            BatteryIcon(level = battery.level, charging = battery.charging)
            Text(
                text = "${battery.level}%",
                color = RemoteColors.onSurface,
                fontSize = 18.sp
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = timeText,
                color = RemoteColors.onSurface,
                fontSize = 96.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = dateText,
                color = RemoteColors.onSurfaceVariant,
                fontSize = 24.sp
            )
        }
    }
}

/**
 * Draws the battery outline + charge fill (original BatteryView: 2px stroke,
 * terminal nub, low level in red).
 */
@Composable
private fun BatteryIcon(
    level: Int,
    charging: Boolean,
    modifier: Modifier = Modifier,
) {
    val bodyColor = if (level <= LOW_BATTERY_LEVEL) RemoteColors.error else RemoteColors.onSurface
    Box(modifier = modifier.size(width = 34.dp, height = 18.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val gap = 2.dp.toPx()
            val nubWidth = 3.dp.toPx()
            val nubHeight = size.height * 0.4f
            val bodyWidth = size.width - nubWidth - gap
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset.Zero,
                size = Size(bodyWidth, size.height),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = stroke)
            )
            drawRoundRect(
                color = bodyColor,
                topLeft = Offset(bodyWidth + gap, (size.height - nubHeight) / 2f),
                size = Size(nubWidth, nubHeight),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
            val inset = stroke * 1.5f
            val fillWidth = (bodyWidth - inset * 2) * (level.coerceIn(0, 100) / 100f)
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = bodyColor,
                    topLeft = Offset(inset, inset),
                    size = Size(fillWidth, size.height - inset * 2),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }
        if (charging) {
            Text(
                text = "⚡",
                color = Color(0xFF05070C),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Short plug-in hint (original FullScreenChargingDialog / TopBatteryHintDialog). */
@Composable
private fun ChargingFlashOverlay(battery: PanelBatteryState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = RemoteColors.surface
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "⚡",
                    color = RemoteColors.secondary,
                    fontSize = 64.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "充电中 ${battery.level}%",
                    color = RemoteColors.onSurface,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

private const val LOW_BATTERY_LEVEL = 19
