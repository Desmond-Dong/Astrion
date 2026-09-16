package com.example.ava.ui.screens.panel

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.toRoute
import com.example.ava.R
import com.example.ava.panel.CardController
import com.example.ava.panel.KeyRouter
import com.example.ava.panel.KeyPress
import com.example.ava.panel.PanelCard
import com.example.ava.panel.PanelCardTypes
import com.example.ava.services.HaEntityState
import com.example.ava.services.HomeAssistantStatesStore
import com.example.ava.panel.PanelConfigStore
import com.example.ava.ui.DeviceDetail
import com.example.ava.ui.theme.RemoteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    init {
        // The topmost device page repurposes the physical keys first
        // (§3.10.4 物理键语义); unhandled keys fall through to the HA bindings.
        keyRouter.setHandler(::handleKey)
    }

    override fun onCleared() {
        keyRouter.setHandler(null)
        super.onCleared()
    }

    private suspend fun handleKey(press: KeyPress): Boolean {
        if (press.cancel) return true
        val current = card.value ?: return false
        return cardController.handleDeviceKey(current, press.keyCode, press.longPress)
    }

    private fun action(block: suspend (PanelCard) -> Unit) {
        viewModelScope.launch { card.value?.let { block(it) } }
    }

    fun tvKey(key: String) = action { cardController.pressTvKey(it, key) }

    fun turnOn() = action { cardController.turnOn(it.primaryEntity?.entityId ?: return@action) }
    fun turnOff() = action { cardController.turnOff(it.primaryEntity?.entityId ?: return@action) }

    fun lightOn(brightnessPct: Int? = null, kelvin: Int? = null) = action {
        cardController.lightTurnOn(it.primaryEntity?.entityId ?: return@action, brightnessPct, kelvin)
    }

    fun lightOff() = action { cardController.lightTurnOff(it.primaryEntity?.entityId ?: return@action) }

    fun setTemperature(value: Double) = action {
        cardController.climateSetTemperature(it.primaryEntity?.entityId ?: return@action, value)
    }

    fun setHvacMode(mode: String) = action {
        cardController.climateSetHvacMode(it.primaryEntity?.entityId ?: return@action, mode)
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

    fun mediaCommand(command: String) = action {
        cardController.mediaCommand(it.primaryEntity?.entityId ?: return@action, command)
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
        title = current?.displayName() ?: "",
        onBack = { navController.popBackStack() }
    ) {
        when (current?.type) {
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

/** A circular remote key (original 60dp 圆形按钮). */
@Composable
private fun RemoteKey(
    label: String,
    modifier: Modifier = Modifier,
    size: Int = 60,
    tint: Color = RemoteColors.onSurface,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = RemoteColors.key,
        modifier = modifier
            .size(size.dp)
            .clickable(onClick = onClick)
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
private fun IconKey(
    iconRes: Int,
    modifier: Modifier = Modifier,
    size: Int = 60,
    tint: Color = RemoteColors.onSurface,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = RemoteColors.key,
        modifier = modifier
            .size(size.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size((size * 0.42).dp)
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
                RemoteKey(label = "▲", onClick = { viewModel.tvKey("UP") })
                Spacer(Modifier.height(8.dp))
                Row {
                    RemoteKey(label = "◀", onClick = { viewModel.tvKey("LEFT") })
                    Spacer(Modifier.width(8.dp))
                    RemoteKey(label = "OK", onClick = { viewModel.tvKey("CENTER") })
                    Spacer(Modifier.width(8.dp))
                    RemoteKey(label = "▶", onClick = { viewModel.tvKey("RIGHT") })
                }
                Spacer(Modifier.height(8.dp))
                RemoteKey(label = "▼", onClick = { viewModel.tvKey("DOWN") })
            }
            Spacer(Modifier.width(28.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RemoteKey(label = "音量+", onClick = { viewModel.tvKey("VOLUME_UP") })
                RemoteKey(label = "音量−", onClick = { viewModel.tvKey("VOLUME_DOWN") })
                RemoteKey(label = "静音", onClick = { viewModel.tvKey("MUTE") })
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RemoteKey(label = "频道−", onClick = { viewModel.tvKey("CHANNEL_DOWN") })
            RemoteKey(
                label = "▶ ⏸",
                onClick = {
                    // 原版 togglePlayPause：同一键在播放/暂停间切换
                    viewModel.tvKey("PLAY_PAUSE")
                }
            )
            RemoteKey(label = "频道+", onClick = { viewModel.tvKey("CHANNEL_UP") })
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
    androidx.compose.material3.AlertDialog(
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
    val target = stateOf(card, haStates, "temperature")?.toDoubleOrNull()
    val current = stateOf(card, haStates, "current_temperature")?.toDoubleOrNull()
    val fanMode = stateOf(card, haStates, "fan_mode")

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (target != null) "${formatTemp(target)}℃" else "--℃",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface
            )
            InfoLine(
                text = listOfNotNull(
                    translateOnOff(state),
                    current?.let { "当前 ${formatTemp(it)}℃" }
                ).joinToString(" · ")
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            RemoteKey(label = "−", size = 72, onClick = {
                viewModel.setTemperature((target ?: 24.0) - 0.5)
            })
            Spacer(Modifier.width(36.dp))
            RemoteKey(label = "+", size = 72, onClick = {
                viewModel.setTemperature((target ?: 24.0) + 0.5)
            })
        }

        val modes = listOf(
            "off" to "关机", "cool" to "制冷", "heat" to "制热",
            "dry" to "除湿", "fan_only" to "送风", "auto" to "自动"
        )
        ChipRow(
            options = modes,
            selected = state,
            onSelect = { viewModel.setHvacMode(it) }
        )

        if (fanMode != null) {
            val fanModes = listOf("auto" to "自动", "low" to "低速", "medium" to "中速", "high" to "高速")
            ChipRow(
                options = fanModes,
                selected = fanMode,
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
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) RemoteColors.accent else RemoteColors.key,
                modifier = Modifier.clickable { onSelect(value) }
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
    val brightness = stateOf(card, haStates, "brightness")?.toFloatOrNull()?.div(2.55f) ?: 0f
    var sliderValue by remember(brightness) { mutableStateOf(brightness) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("电源", color = RemoteColors.onSurface, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = state == "on",
                onCheckedChange = { on ->
                    if (on) viewModel.lightOn() else viewModel.lightOff()
                }
            )
        }

        Column {
            Text(
                text = "亮度 ${sliderValue.toInt()}%",
                color = RemoteColors.onSurface,
                fontSize = 15.sp
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = {
                    viewModel.lightOn(brightnessPct = sliderValue.toInt())
                },
                enabled = state == "on",
                valueRange = 0f..100f
            )
        }

        Column {
            Text(
                text = "色温",
                color = RemoteColors.onSurface,
                fontSize = 15.sp
            )
            var kelvin by remember { mutableStateOf(4000f) }
            Slider(
                value = kelvin,
                onValueChange = { kelvin = it },
                onValueChangeFinished = {
                    viewModel.lightOn(kelvin = kelvin.toInt())
                },
                enabled = state == "on",
                valueRange = 2000f..6500f
            )
            Row {
                Text("暖 2000K", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("冷 6500K", color = RemoteColors.onSurfaceVariant, fontSize = 12.sp)
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
    val percentage = stateOf(card, haStates, "percentage")?.toFloatOrNull() ?: 0f
    val step = if (card.percentageStep > 0) card.percentageStep else 20

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${percentage.toInt()}%",
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = RemoteColors.onSurface
        )

        val levels = (1..(100 / step).coerceAtMost(5)).map { it * step }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            levels.forEach { level ->
                val selected = percentage >= level - step / 2f
                RemoteKey(
                    label = "$level",
                    size = 52,
                    tint = if (selected) RemoteColors.accent else RemoteColors.onSurface,
                    onClick = { viewModel.fanPercentage(level) }
                )
            }
        }

        RemoteKey(
            label = if (state == "on") "关闭" else "打开",
            size = 84,
            onClick = { if (state == "on") viewModel.turnOff() else viewModel.turnOn() }
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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
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

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
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
                onClick = { viewModel.mediaCommand("media_play_pause") }
            )
            RemoteKey(label = "⏭", onClick = { viewModel.mediaCommand("media_next_track") })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteKey(label = "音量−", onClick = { viewModel.mediaCommand("volume_down") })
            RemoteKey(label = "静音", onClick = { viewModel.mediaCommand("volume_mute") })
            RemoteKey(label = "音量+", onClick = { viewModel.mediaCommand("volume_up") })
        }
    }
}

// ── Switch / scene (§4.1) ──────────────────────────────────────────────

@Composable
private fun SwitchSceneContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates)
    val isScene = card.type == PanelCardTypes.SCENE

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
