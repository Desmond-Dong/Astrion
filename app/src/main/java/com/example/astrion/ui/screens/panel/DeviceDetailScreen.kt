package com.example.astrion.ui.screens.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.example.astrion.R
import com.example.astrion.panel.CardController
import com.example.astrion.panel.KeyRouter
import com.example.astrion.panel.KeyPress
import com.example.astrion.panel.PanelCard
import com.example.astrion.panel.PanelCardTypes
import com.example.astrion.services.HaEntityState
import com.example.astrion.services.HomeAssistantStatesStore
import com.example.astrion.panel.PanelConfigStore
import com.example.astrion.ui.DeviceDetail
import com.example.astrion.ui.components.NativeSlider
import com.example.astrion.ui.components.holdRepeat
import com.example.astrion.ui.theme.RemoteBackground
import com.example.astrion.ui.theme.RemoteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 物理键长按自动重复（原版 200ms 间隔）。 */
private const val HOLD_REPEAT_MS = 200L

/**
 * 拖动节流（原版 ThrottlerUtil）：拖动中按 [intervalMs] 节流连续下发
 * [send]，松手必定精确发送一次。
 */
private class ValueThrottle<T>(
    val onDrag: (T) -> Unit,
    val onCommit: (T) -> Unit,
)

@Composable
private fun <T> rememberValueThrottle(
    send: (T) -> Unit,
    intervalMs: Long,
): ValueThrottle<T> {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<T?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val sendRef by rememberUpdatedState(send)
    val onDrag: (T) -> Unit = { v ->
        pending = v
        if (job == null || !job!!.isActive) {
            job = scope.launch {
                while (isActive) {
                    val next = pending
                    if (next == null) break
                    sendRef(next)
                    delay(intervalMs)
                }
            }
        }
    }
    val onCommit: (T) -> Unit = { v ->
        pending = v
        job?.cancel()
        job = null
        sendRef(v)
    }
    return remember(intervalMs) { ValueThrottle(onDrag, onCommit) }
}

@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    panelConfigStore: PanelConfigStore,
    haStatesStore: HomeAssistantStatesStore,
    private val cardController: CardController,
    private val keyRouter: KeyRouter,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<DeviceDetail>()

    val card = panelConfigStore.layout
        .map { layout -> layout.rooms.flatMap { it.cards }.firstOrNull { it.cardId == route.cardId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val haStates = haStatesStore.states

    /** 物理键长按自动重复（原版 200ms 间隔），松手 cancel 停止。 */
    private var holdJob: Job? = null

    // 稳定引用：入栈/出栈必须是同一个对象
    private val keyHandler: suspend (KeyPress) -> Boolean = { press -> handleKey(press) }

    init {
        // The topmost device page repurposes the physical keys first
        // (§3.10.4 物理键语义); unhandled keys fall through to the HA bindings.
        keyRouter.pushHandler(keyHandler)
    }

    override fun onCleared() {
        keyRouter.popHandler(keyHandler)
        stopHold()
        super.onCleared()
    }

    private fun stopHold() {
        holdJob?.cancel()
        holdJob = null
    }

    private fun startHold(card: PanelCard, keyCode: Int) {
        holdJob?.cancel()
        holdJob = viewModelScope.launch {
            while (isActive) {
                cardController.holdDeviceStep(card, keyCode)
                delay(HOLD_REPEAT_MS)
            }
        }
    }

    private suspend fun handleKey(press: KeyPress): Boolean {
        if (press.cancel) {
            // 长按松开：停止自动重复步进
            stopHold()
            return true
        }
        val current = card.value ?: return false
        return if (press.longPress) {
            // 长按触发一次大步，随后保持循环
            val consumed = cardController.handleDeviceKey(current, press.keyCode, longPress = true)
            if (consumed) startHold(current, press.keyCode) else stopHold()
            consumed
        } else {
            stopHold()
            cardController.handleDeviceKey(current, press.keyCode, longPress = false)
        }
    }

    private fun action(block: suspend (PanelCard) -> Unit) {
        viewModelScope.launch { card.value?.let { block(it) } }
    }

    fun tvKey(key: String) = action { cardController.pressTvKey(it, key) }

    fun turnOn() = action { cardController.turnOn(it.primaryEntity?.entityId ?: return@action) }
    fun turnOff() = action { cardController.turnOff(it.primaryEntity?.entityId ?: return@action) }

    fun lightOn(brightnessPct: Int? = null, kelvin: Int? = null, rgb: List<Int>? = null) = action {
        cardController.lightTurnOn(
            it.primaryEntity?.entityId ?: return@action,
            brightnessPct,
            kelvin,
            rgb
        )
    }

    fun lightOff() = action { cardController.lightTurnOff(it.primaryEntity?.entityId ?: return@action) }

    fun lightEffect(effect: String) = action {
        cardController.lightEffect(it.primaryEntity?.entityId ?: return@action, effect)
    }

    fun setTemperature(value: Double) = action {
        cardController.climateSetTemperature(it.primaryEntity?.entityId ?: return@action, value)
    }

    fun setTemperatureRange(low: Double, high: Double) = action {
        cardController.climateSetTemperatureRange(it.primaryEntity?.entityId ?: return@action, low, high)
    }

    fun setHvacMode(mode: String) = action {
        cardController.climateSetHvacMode(it.primaryEntity?.entityId ?: return@action, mode)
    }

    fun setPresetMode(mode: String) = action {
        cardController.climateSetPresetMode(it.primaryEntity?.entityId ?: return@action, mode)
    }

    fun setFanMode(mode: String) = action {
        cardController.climateSetFanMode(it.primaryEntity?.entityId ?: return@action, mode)
    }

    fun fanPercentage(percentage: Int) = action {
        cardController.fanSetPercentage(it.primaryEntity?.entityId ?: return@action, percentage)
    }

    fun coverOpen() = action { cardController.coverOpen(it.primaryEntity?.entityId ?: return@action) }
    fun coverStop() = action { cardController.coverStop(it.primaryEntity?.entityId ?: return@action) }
    fun coverClose() = action { cardController.coverClose(it.primaryEntity?.entityId ?: return@action) }
    fun coverPosition(position: Int) = action {
        cardController.coverSetPosition(it.primaryEntity?.entityId ?: return@action, position)
    }

    fun coverTilt(tilt: Int) = action {
        cardController.coverSetTiltPosition(it.primaryEntity?.entityId ?: return@action, tilt)
    }

    fun mediaCommand(command: String) = action {
        cardController.mediaCommand(it.primaryEntity?.entityId ?: return@action, command)
    }

    fun mediaTogglePlayback() = action {
        cardController.mediaTogglePlayback(it.primaryEntity?.entityId ?: return@action)
    }

    fun mediaVolume(level: Float) = action {
        cardController.mediaVolumeSet(it.primaryEntity?.entityId ?: return@action, level)
    }

    fun mediaSeek(positionSec: Int) = action {
        cardController.mediaSeek(it.primaryEntity?.entityId ?: return@action, positionSec)
    }

    fun mediaSelectSource(source: String) = action {
        cardController.mediaSelectSource(it.primaryEntity?.entityId ?: return@action, source)
    }

    fun mediaSelectSoundMode(soundMode: String) = action {
        cardController.mediaSelectSoundMode(it.primaryEntity?.entityId ?: return@action, soundMode)
    }
}

@Composable
fun DeviceDetailScreen(
    navController: NavController,
    cardId: String,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    val haStates by viewModel.haStates.collectAsStateWithLifecycle()

    val current = card
    DetailScaffold(
        title = current?.displayName(haStates) ?: "",
        onBack = { navController.popBackStack() }
    ) {
        when (current?.resolvedType) {
            PanelCardTypes.TV -> TvRemoteContent(current, haStates, viewModel)
            PanelCardTypes.CLIMATE -> ClimateContent(current, haStates, viewModel)
            PanelCardTypes.LIGHT -> LightContent(current, haStates, viewModel)
            PanelCardTypes.FAN -> FanContent(current, haStates, viewModel)
            PanelCardTypes.COVER -> CoverContent(current, haStates, viewModel)
            PanelCardTypes.MEDIA_PLAYER -> MediaContent(current, haStates, viewModel)
            PanelCardTypes.SWITCH, PanelCardTypes.SCENE -> SwitchSceneContent(current, haStates, viewModel)
            PanelCardTypes.WEATHER -> WeatherContent(current, haStates)
            else -> GenericContent(current, haStates)
        }
    }
}

// ── Shared scaffolding ─────────────────────────────────────────────────

@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RemoteBackground)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "返回",
                    tint = RemoteColors.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        content()
    }
}

/** A circular remote key (original 60dp 圆形按钮). 长按可连发（原版 500ms）。 */
@Composable
private fun RemoteKey(
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 60,
    tint: Color = RemoteColors.onSurface,
    longRepeat: Boolean = false,
    onClick: () -> Unit,
) {
    val surfaceModifier = if (longRepeat) {
        modifier.holdRepeat(onTap = onClick, onRepeat = onClick)
    } else {
        modifier.clickable(onClick = onClick)
    }
    Surface(
        shape = CircleShape,
        color = RemoteColors.key,
        modifier = surfaceModifier
            .size(size.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = tint,
                fontSize = if (label.length <= 2) 18.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun InfoLine(text: String) {
    Text(
        text = text,
        color = RemoteColors.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium
    )
}

private fun stateOf(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    attribute: String = "",
): String? {
    val entityId = card.primaryEntity?.entityId ?: return null
    val key = if (attribute.isEmpty()) entityId else "$entityId.$attribute"
    return haStates[key]?.state
}

private fun attrList(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    attribute: String,
): List<String> =
    (stateOf(card, haStates, attribute) ?: "")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

// ── TV remote (§3.10.4 ⑰) ──────────────────────────────────────────────

@Composable
private fun TvRemoteContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    var numberPadOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteKey(label = "电源", onClick = { viewModel.tvKey("POWER") })
            RemoteKey(label = "返回", onClick = { viewModel.tvKey("BACK") })
            RemoteKey(label = "主页", onClick = { viewModel.tvKey("HOME") })
            RemoteKey(label = "菜单", onClick = { viewModel.tvKey("MENU") })
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // D-pad ring (原版 LLTvPlaySelect 方向环)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RemoteKey(label = "▲", longRepeat = true, onClick = { viewModel.tvKey("UP") })
                Spacer(Modifier.height(8.dp))
                Row {
                    RemoteKey(label = "◀", longRepeat = true, onClick = { viewModel.tvKey("LEFT") })
                    Spacer(Modifier.width(8.dp))
                    RemoteKey(label = "OK", onClick = { viewModel.tvKey("CENTER") })
                    Spacer(Modifier.width(8.dp))
                    RemoteKey(label = "▶", longRepeat = true, onClick = { viewModel.tvKey("RIGHT") })
                }
                Spacer(Modifier.height(8.dp))
                RemoteKey(label = "▼", longRepeat = true, onClick = { viewModel.tvKey("DOWN") })
            }
            Spacer(Modifier.width(28.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RemoteKey(label = "音量+", longRepeat = true, onClick = { viewModel.tvKey("VOLUME_UP") })
                RemoteKey(label = "音量−", longRepeat = true, onClick = { viewModel.tvKey("VOLUME_DOWN") })
                RemoteKey(label = "静音", onClick = { viewModel.tvKey("MUTE") })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteKey(label = "频道−", longRepeat = true, onClick = { viewModel.tvKey("CHANNEL_DOWN") })
            RemoteKey(
                label = "▶ ⏸",
                onClick = {
                    // 原版 togglePlayPause：同一键在播放/暂停间切换
                    viewModel.tvKey("PLAY_PAUSE")
                }
            )
            RemoteKey(label = "频道+", longRepeat = true, onClick = { viewModel.tvKey("CHANNEL_UP") })
            RemoteKey(label = "数字", onClick = { numberPadOpen = true })
        }
    }

    if (numberPadOpen) {
        NumberPadDialog(
            onKey = { key ->
                viewModel.tvKey(key)
            },
            onDismiss = { numberPadOpen = false }
        )
    }
}

@Composable
private fun NumberPadDialog(
    onKey: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = RemoteColors.surface,
        title = { Text("数字键盘", color = RemoteColors.onSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("DEL", "0", "OK")
                )
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { digit ->
                            RemoteKey(
                                label = digit,
                                size = 56,
                                onClick = {
                                    when (digit) {
                                        "DEL" -> onKey("DELETE")
                                        "OK" -> onDismiss()
                                        else -> onKey("NUM_$digit")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

// ── Climate (§3.10.4 ①) ────────────────────────────────────────────────

@Composable
private fun ClimateContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "unavailable"
    val isOn = state != "off"
    val target = stateOf(card, haStates, "temperature")?.toDoubleOrNull()
    val minT = stateOf(card, haStates, "min_temp")?.toDoubleOrNull() ?: 16.0
    val maxT = stateOf(card, haStates, "max_temp")?.toDoubleOrNull() ?: 30.0
    val fanMode = stateOf(card, haStates, "fan_mode")
    val presetMode = stateOf(card, haStates, "preset_mode")
    val presetModes = attrList(card, haStates, "preset_modes")
    val fanModes = attrList(card, haStates, "fan_modes")
    val isRange = state == "heat_cool"
    val low = stateOf(card, haStates, "target_temp_low")?.toDoubleOrNull()
    val high = stateOf(card, haStates, "target_temp_high")?.toDoubleOrNull()

    val current = target ?: if (isRange) ((low ?: minT) + (high ?: maxT)) / 2 else 24.0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        val alpha = if (isOn) 1f else 0.5f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${formatTemp(current)}℃",
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface,
                modifier = Modifier.alpha(alpha)
            )
            InfoLine(
                text = listOfNotNull(
                    translateOnOff(state),
                    stateOf(card, haStates, "current_temperature")
                        ?.toDoubleOrNull()?.let { "当前 ${formatTemp(it)}℃" }
                ).joinToString(" · ")
            )
        }

        // 温度滑条（原版 ViewAcTemperatureSlider：拖动节流、松手精确发送）
        val tempDisplay = remember(current) { mutableStateOf(current.toFloat()) }
        val tempThrottle = rememberValueThrottle<Float>(
            send = { v -> viewModel.setTemperature(v.toDouble()) },
            intervalMs = 300
        )
        Text("目标温度", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
        NativeSlider(
            value = tempDisplay.value,
            onDrag = { v ->
                tempDisplay.value = v
                tempThrottle.onDrag(v)
            },
            onCommit = { v ->
                tempDisplay.value = v
                tempThrottle.onCommit(v)
            },
            valueRange = minT.toFloat()..maxT.toFloat(),
            enabled = isOn
        )

        if (isRange) {
            // 双温区（heat_cool → target_temp_low/high，原版 low<high 护栏）
            val lowDisplay = remember(low, high) { mutableStateOf((low ?: minT).toFloat()) }
            val highDisplay = remember(high, low) { mutableStateOf((high ?: maxT).toFloat()) }
            val rangeThrottle = rememberValueThrottle<Pair<Float, Float>>(
                send = { (l, h) -> viewModel.setTemperatureRange(l.toDouble(), h.toDouble()) },
                intervalMs = 300
            )
            Text("冷却温度下限", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            NativeSlider(
                value = lowDisplay.value,
                onDrag = { v ->
                    lowDisplay.value = v
                    rangeThrottle.onDrag(v.coerceAtMost(highDisplay.value - 1f) to highDisplay.value)
                },
                onCommit = { v ->
                    val l = v.coerceAtMost(highDisplay.value - 1f)
                    lowDisplay.value = l
                    rangeThrottle.onCommit(l to highDisplay.value)
                },
                valueRange = minT.toFloat()..maxT.toFloat(),
                enabled = isOn
            )
            Text("加热温度上限", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            NativeSlider(
                value = highDisplay.value,
                onDrag = { v ->
                    highDisplay.value = v
                    rangeThrottle.onDrag(lowDisplay.value to v.coerceAtLeast(lowDisplay.value + 1f))
                },
                onCommit = { v ->
                    val h = v.coerceAtLeast(lowDisplay.value + 1f)
                    highDisplay.value = h
                    rangeThrottle.onCommit(lowDisplay.value to h)
                },
                valueRange = minT.toFloat()..maxT.toFloat(),
                enabled = isOn
            )
        }

        val modes = listOf(
            "off" to "关机", "cool" to "制冷", "heat" to "制热",
            "dry" to "除湿", "fan_only" to "送风", "auto" to "自动"
        )
        Text("模式", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
        ChipRow(
            options = modes,
            selected = state,
            enabled = true,
            onSelect = { viewModel.setHvacMode(it) }
        )

        if (presetModes.isNotEmpty()) {
            Text("预设", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            ChipRow(
                options = presetModes.map { it to it },
                selected = presetMode,
                enabled = isOn,
                onSelect = { viewModel.setPresetMode(it) }
            )
        }

        if (fanModes.isNotEmpty() && fanMode != null) {
            Text("风速", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            ChipRow(
                options = fanModes.map { it to it },
                selected = fanMode,
                enabled = isOn,
                onSelect = { viewModel.setFanMode(it) }
            )
        }
    }
}

private fun formatTemp(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) RemoteColors.accent else RemoteColors.key,
                modifier = Modifier.clickable(enabled = enabled) { onSelect(value) }
            ) {
                Text(
                    text = label,
                    color = if (isSelected) Color.White else RemoteColors.onSurface,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
    }
}

// ── Light (§3.10.4 ⑧) ──────────────────────────────────────────────────

@Composable
private fun LightContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "off"
    val isOn = state == "on"

    // 能力联动 (§3.10.4 ⑧⑨⑩): what the entity supports decides what shows —
    // color temp / color controls appear only when the light supports them.
    val supportedModes = stateOf(card, haStates, "supported_color_modes") ?: ""
    val supportsColorTemp = supportedModes.contains("color_temp")
    val supportsColor = listOf("hs", "rgb", "rgbw", "rgbww", "xy").any { supportedModes.contains(it) }
    val kelvinNow = stateOf(card, haStates, "color_temp_kelvin")?.toFloatOrNull()
    val minKelvin = stateOf(card, haStates, "min_color_temp_kelvin")?.toFloatOrNull() ?: 2000f
    val maxKelvin = stateOf(card, haStates, "max_color_temp_kelvin")?.toFloatOrNull() ?: 6500f
    val effects = attrList(card, haStates, "effect_list")
    val currentEffect = stateOf(card, haStates, "effect")

    val brightness = stateOf(card, haStates, "brightness")?.toFloatOrNull()?.div(2.55f) ?: 0f
    val brightnessDisplay = remember(brightness) { mutableStateOf(brightness) }
    val brightnessThrottle = rememberValueThrottle<Float>(
        send = { v -> viewModel.lightOn(brightnessPct = v.toInt()) },
        intervalMs = 100
    )

    val kelvinDisplay = remember(minKelvin, maxKelvin, kelvinNow) {
        mutableStateOf(kelvinNow ?: ((minKelvin + maxKelvin) / 2f))
    }
    val kelvinThrottle = rememberValueThrottle<Float>(
        send = { v -> viewModel.lightOn(kelvin = v.toInt()) },
        intervalMs = 300
    )

    // RGB 2D 取色器：垂直色相渐变，拖动 300ms 节流（原版 LightRgbActivity）
    val hueNow = remember { mutableStateOf(0f) }
    val rgbThrottle = rememberValueThrottle<Float>(
        send = { h ->
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            viewModel.lightOn(
                rgb = listOf(
                    android.graphics.Color.red(rgb),
                    android.graphics.Color.green(rgb),
                    android.graphics.Color.blue(rgb)
                )
            )
        },
        intervalMs = 300
    )

    // 电源 300ms 防抖（原版 mSwitchDebounceRunnable）
    val scope = rememberCoroutineScope()
    var powerPending by remember { mutableStateOf<Boolean?>(null) }
    var powerJob by remember { mutableStateOf<Job?>(null) }
    fun requestPower(on: Boolean) {
        powerPending = on
        powerJob?.cancel()
        powerJob = scope.launch {
            delay(300)
            val v = powerPending ?: return@launch
            if (v) viewModel.lightOn() else viewModel.lightOff()
        }
    }

    val alpha = if (isOn) 1f else 0.5f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("电源", color = RemoteColors.onSurface, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = isOn,
                enabled = powerJob == null,
                onCheckedChange = { requestPower(it) }
            )
        }

        Column(modifier = Modifier.alpha(alpha)) {
            Text(
                text = "亮度 ${brightnessDisplay.value.toInt()}%",
                color = RemoteColors.onSurface,
                fontSize = 15.sp
            )
            NativeSlider(
                value = brightnessDisplay.value,
                onDrag = { v ->
                    brightnessDisplay.value = v
                    brightnessThrottle.onDrag(v)
                },
                onCommit = { v ->
                    brightnessDisplay.value = v
                    brightnessThrottle.onCommit(v)
                },
                valueRange = 0f..100f,
                enabled = isOn
            )
        }

        if (supportsColorTemp) {
            Column(modifier = Modifier.alpha(alpha)) {
                Text(
                    text = "色温 ${kelvinDisplay.value.toInt()}K",
                    color = RemoteColors.onSurface,
                    fontSize = 15.sp
                )
                NativeSlider(
                    value = kelvinDisplay.value,
                    onDrag = { v ->
                        kelvinDisplay.value = v
                        kelvinThrottle.onDrag(v)
                    },
                    onCommit = { v ->
                        kelvinDisplay.value = v
                        kelvinThrottle.onCommit(v)
                    },
                    valueRange = minKelvin..maxKelvin,
                    enabled = isOn
                )
                Row {
                    Text("暖 ${minKelvin.toInt()}K", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text("冷 ${maxKelvin.toInt()}K", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        if (supportsColor) {
            val hueStops = listOf(0, 60, 120, 180, 240, 300, 360)
                .map { h -> Color.hsv(h.toFloat(), 1f, 1f) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(alpha)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "颜色 (色相 ${hueNow.value.toInt()}°)",
                        color = RemoteColors.onSurface,
                        fontSize = 15.sp
                    )
                    Row {
                        Text("红", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        Text("紫", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                NativeSlider(
                    value = hueNow.value / 3.6f,
                    onDrag = { v ->
                        val h = v * 3.6f
                        hueNow.value = h
                        rgbThrottle.onDrag(h)
                    },
                    onCommit = { v ->
                        val h = v * 3.6f
                        hueNow.value = h
                        rgbThrottle.onCommit(h)
                    },
                    vertical = true,
                    axisLength = 170.dp,
                    thickness = 18.dp,
                    trackBrush = Brush.horizontalGradient(hueStops),
                    activeColor = Color.Transparent,
                    thumbColor = Color.White,
                    thumbSize = 24.dp,
                    enabled = isOn
                )
            }
        }

        if (effects.isNotEmpty()) {
            Column(modifier = Modifier.alpha(alpha)) {
                Text("特效", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
                ChipRow(
                    options = effects.map { it to it },
                    selected = currentEffect,
                    enabled = isOn,
                    onSelect = { viewModel.lightEffect(it) }
                )
            }
        }
    }
}

// ── Fan (§3.10.4 ④) ────────────────────────────────────────────────────

@Composable
private fun FanContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "off"
    val isOn = state == "on"
    val percentage = stateOf(card, haStates, "percentage")?.toFloatOrNull() ?: 0f
    val display = remember(percentage) { mutableStateOf(percentage) }
    val throttle = rememberValueThrottle<Float>(
        send = { v -> viewModel.fanPercentage(v.toInt()) },
        intervalMs = 100
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${display.value.toInt()}%",
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = RemoteColors.onSurface,
            modifier = Modifier.alpha(if (isOn) 1f else 0.5f)
        )

        NativeSlider(
            value = display.value,
            onDrag = { v ->
                display.value = v
                throttle.onDrag(v)
            },
            onCommit = { v ->
                display.value = v
                throttle.onCommit(v)
            },
            valueRange = 0f..100f,
            enabled = isOn
        )

        RemoteKey(
            label = if (isOn) "关闭" else "打开",
            size = 84,
            onClick = { if (isOn) viewModel.turnOff() else viewModel.turnOn() }
        )
    }
}

// ── Cover (§3.10.4 ⑤⑥⑦) ───────────────────────────────────────────────

@Composable
private fun CoverContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "closed"
    val position = stateOf(card, haStates, "current_position")?.toFloatOrNull()
    val tilt = stateOf(card, haStates, "current_tilt_position")?.toFloatOrNull()
    val supportsTilt = tilt != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = if (position != null) "打开 $position%" else when (state) {
                "open" -> "打开"
                "closed" -> "关闭"
                "opening" -> "打开中…"
                "closing" -> "关闭中…"
                else -> state
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            color = RemoteColors.onSurface
        )

        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            RemoteKey(label = "打开", size = 76, onClick = { viewModel.coverOpen() })
            RemoteKey(label = "停止", size = 76, onClick = { viewModel.coverStop() })
            RemoteKey(label = "关闭", size = 76, onClick = { viewModel.coverClose() })
        }

        if (position != null) {
            val positionDisplay = remember(position) { mutableStateOf(position) }
            val positionThrottle = rememberValueThrottle<Float>(
                send = { v -> viewModel.coverPosition(v.toInt()) },
                intervalMs = 100
            )
            Text("位置", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            NativeSlider(
                value = positionDisplay.value,
                onDrag = { v ->
                    positionDisplay.value = v
                    positionThrottle.onDrag(v)
                },
                onCommit = { v ->
                    positionDisplay.value = v
                    positionThrottle.onCommit(v)
                },
                valueRange = 0f..100f
            )
        }

        if (supportsTilt) {
            val tiltDisplay = remember(tilt) { mutableStateOf(tilt ?: 50f) }
            val tiltThrottle = rememberValueThrottle<Float>(
                send = { v -> viewModel.coverTilt(v.toInt()) },
                intervalMs = 100
            )
            Text("翻转", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            NativeSlider(
                value = tiltDisplay.value,
                onDrag = { v ->
                    tiltDisplay.value = v
                    tiltThrottle.onDrag(v)
                },
                onCommit = { v ->
                    tiltDisplay.value = v
                    tiltThrottle.onCommit(v)
                },
                valueRange = 0f..100f
            )
        }
    }
}

// ── Media player (§3.10.4 ⑫) ───────────────────────────────────────────

@Composable
private fun MediaContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val entityId = card.primaryEntity?.entityId
    val state = stateOf(card, haStates) ?: "off"
    val title = entityId?.let { haStates["$it.media_title"]?.state } ?: ""
    val artist = entityId?.let { haStates["$it.media_artist"]?.state } ?: ""
    val volume = entityId?.let { haStates["$it.volume_level"]?.state }?.toFloatOrNull() ?: 0f
    val position = entityId?.let { haStates["$it.media_position"]?.state }?.toFloatOrNull() ?: 0f
    val duration = entityId?.let { haStates["$it.media_duration"]?.state }?.toFloatOrNull() ?: 0f
    val sources = attrList(card, haStates, "source_list")
    val source = stateOf(card, haStates, "source")
    val soundModes = attrList(card, haStates, "sound_mode_list")
    val soundMode = stateOf(card, haStates, "sound_mode")

    val volumeDisplay = remember(volume) { mutableStateOf(volume * 100f) }
    val volumeThrottle = rememberValueThrottle<Float>(
        send = { v -> viewModel.mediaVolume(v / 100f) },
        intervalMs = 100
    )

    val seekDisplay = remember(position) { mutableStateOf(position) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title.ifBlank { translateOnOff(state) },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = RemoteColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (artist.isNotBlank()) {
                Text(text = artist, fontSize = 14.sp, color = RemoteColors.onSurfaceVariant)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteKey(label = "⏮", onClick = { viewModel.mediaCommand("media_previous_track") })
            RemoteKey(
                label = if (state == "playing") "⏸" else "▶",
                size = 84,
                // 原版状态机：OFF/STANDBY → turn_on + play
                onClick = { viewModel.mediaTogglePlayback() }
            )
            RemoteKey(label = "⏭", onClick = { viewModel.mediaCommand("media_next_track") })
        }

        if (duration > 0f) {
            val progressText =
                "${formatDuration(seekDisplay.value.toInt().coerceAtLeast(0))} / " +
                    formatDuration(duration.toInt())
            Text(progressText, color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
            NativeSlider(
                value = seekDisplay.value,
                onDrag = { seekDisplay.value = it },
                onCommit = { v ->
                    seekDisplay.value = v
                    // 原版 1000ms 防抖：进度条拖动松手才 seek
                    viewModel.mediaSeek(v.toInt())
                },
                valueRange = 0f..duration,
                enabled = state == "playing"
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音量", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("${volumeDisplay.value.toInt()}%", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
        }
        NativeSlider(
            value = volumeDisplay.value,
            onDrag = { v ->
                volumeDisplay.value = v
                volumeThrottle.onDrag(v)
            },
            onCommit = { v ->
                volumeDisplay.value = v
                volumeThrottle.onCommit(v)
            },
            valueRange = 0f..100f
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteKey(
                label = "静音",
                onClick = { viewModel.mediaCommand("volume_mute") }
            )
        }

        if (sources.isNotEmpty()) {
            Text("信号源", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            ChipRow(
                options = sources.map { it to it },
                selected = source,
                enabled = true,
                onSelect = { viewModel.mediaSelectSource(it) }
            )
        }

        if (soundModes.isNotEmpty()) {
            Text("音效", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
            ChipRow(
                options = soundModes.map { it to it },
                selected = soundMode,
                enabled = true,
                onSelect = { viewModel.mediaSelectSoundMode(it) }
            )
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}

// ── Switch / scene (§4.1) ──────────────────────────────────────────────

@Composable
private fun SwitchSceneContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates)
    val isScene = card.resolvedType == PanelCardTypes.SCENE

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isScene) {
            RemoteKey(
                label = "执行",
                size = 120,
                onClick = { viewModel.turnOn() }
            )
        } else {
            RemoteKey(
                label = if (state == "on") "关闭" else "打开",
                size = 120,
                onClick = { if (state == "on") viewModel.turnOff() else viewModel.turnOn() }
            )
        }
        Spacer(Modifier.height(16.dp))
        InfoLine(translateOnOff(state ?: "unavailable"))
    }
}

// ── Weather (§3.10.4 ⑳) ────────────────────────────────────────────────

@Composable
private fun WeatherContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
) {
    val entityId = card.primaryEntity?.entityId
    val condition = stateOf(card, haStates) ?: "--"
    val temperature = entityId?.let { haStates["$it.temperature"]?.state }
    val humidity = entityId?.let { haStates["$it.humidity"]?.state }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (temperature != null) "$temperature℃" else "--",
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = RemoteColors.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(text = condition, fontSize = 20.sp, color = RemoteColors.onSurfaceVariant)
        if (humidity != null) {
            Spacer(Modifier.height(4.dp))
            InfoLine("湿度 $humidity%")
        }
    }
}

// ── Generic fallback ───────────────────────────────────────────────────

@Composable
private fun GenericContent(
    card: PanelCard?,
    haStates: Map<String, HaEntityState>,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (card == null) {
            InfoLine("未找到该卡片，请检查 Panel Layout 中的 card_id")
            return
        }
        card.entities.forEach { ref ->
            Surface(shape = RoundedCornerShape(14.dp), color = RemoteColors.surfaceVariant) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = ref.alias.ifBlank { ref.entityId },
                        color = RemoteColors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = haStates[ref.entityId]?.state ?: "--",
                        color = RemoteColors.onSurfaceVariant
                    )
                }
            }
        }
    }
}