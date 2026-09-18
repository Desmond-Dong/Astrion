package com.example.astrion.ui.screens.panel

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * 加速长按（原版 AcControlView TEMP_INTERVAL=300ms / tempStepCount）：
 * 按下立即触发一次 onStep(0)，保持期间每 [intervalMs] 再触发一次，
 * tick 逐次递增（原版每 300ms 步长累加一步，越按越快）。
 */
private fun Modifier.acceleratingPress(
    intervalMs: Long = 300,
    onStep: (tick: Int) -> Unit,
): Modifier = composed {
    val currentStep by rememberUpdatedState(onStep)
    pointerInput(intervalMs) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            currentStep(0)
            var tick = 0
            while (true) {
                val released = withTimeoutOrNull(intervalMs) { waitForUpOrCancellation() }
                if (released != null) break
                tick++
                currentStep(tick)
            }
        }
    }
}

/**
 * 原版 ImageSwitchView：60×60dp 圆形电源开关（marginTop 10dp，居中），
 * 图标取 ic_state_*（原版 icon_device_open / icon_device_closed）。
 */
@Composable
private fun DevicePowerSwitch(
    isOn: Boolean,
    onRes: Int,
    offRes: Int,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = RemoteColors.key,
        modifier = Modifier
            .padding(top = 10.dp)
            .size(60.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onToggle(!isOn) }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (isOn) onRes else offRes),
                contentDescription = if (isOn) "关闭" else "打开",
                tint = if (isOn) RemoteColors.accent else RemoteColors.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
        }
    }
}

/**
 * 原版 AcOptionSelectPopup（popup_ac_mode_select_view.xml）：底部弹出选项列表，
 * 背景popup_bg #2B2B2B，当前项高亮（原版金色 #BDA67A），点外部关闭。
 */
@Composable
private fun OptionPopup(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = RemoteColors.popupBackground,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clickable(onClick = {})
        ) {
            Column {
                Text(
                    text = title,
                    color = RemoteColors.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                )
                options.forEach { (value, label) ->
                    val isSelected = value == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) RemoteColors.accent else RemoteColors.onSurface,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Text(text = "✓", color = RemoteColors.accent, fontSize = 18.sp)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

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

/** 原版 item_round_bg 圆形按钮（60×60dp 圆底，禁用时 alpha 0.5，原版 setControlEnabled）。 */
@Composable
private fun RoundIconButton(
    size: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = RemoteColors.key,
        modifier = Modifier
            .size(size.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = RemoteColors.onSurface,
                fontSize = 14.sp,
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

// ── Climate (§3.10.4 ①) — 原版 activity_device_ac.xml + AcControlView ──
//
// 布局（原版 dp 值）：顶部 60×60dp 圆形电源开关（marginTop 10）；中部 200dp 面板：
// 降/升温按钮 85×175dp（水平边距 20dp，圆角底、40dp 符号居中），温度文本区
// 100×110dp 居中（70sp 粗体温度 + 度数符号，当前温度 13sp #A5A5A5）；
// heat_cool 模式改为当前温度（95dp 高）+ 双目标按钮 100×48dp（水平间距 45dp）；
// 底部 60dp 高一行三个 60×60dp 圆钮（模式/风速/预设，padding 3dp，marginTop 50）。
//
// 逻辑（原版 AcControlView）：温度步进 step（target_temp_step，默认 1.0），
// 按下立即下发一次，长按每 300ms 一次且步长按 tick 加速；每次变化立即发
// climate.set_temperature，3s 内不跟随 HA 状态回写（UserOverrideWindowManager）；
// 电源切换带模式记忆（关机前缓存 hvac 模式，开机恢复，无缓存回落 cool）；
// 模式/风速/预设为底部弹窗选择（AcOptionSelectPopup）；
// heat_cool 用范围温度弹窗（AcTemperatureRangePopup：low ∈ [min, high-step]，
// high ∈ [low+step, max]，每次调整立即下发）；
// 物理键：24/25=温度±（300ms 加速）、92/93=风速循环、132=电源、164=回主页。

@Composable
private fun ClimateContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "unavailable"
    val isRange = state == "heat_cool"
    val available = state != "unavailable"
    val isOn = available && state != "off"
    val minT = stateOf(card, haStates, "min_temp")?.toDoubleOrNull() ?: 16.0
    val maxT = stateOf(card, haStates, "max_temp")?.toDoubleOrNull() ?: 30.0
    val stepT = stateOf(card, haStates, "target_temp_step")?.toDoubleOrNull()
        ?.takeIf { it > 0.0 } ?: 1.0
    val attrTemp = stateOf(card, haStates, "temperature")?.toDoubleOrNull()
    val currentTemp = stateOf(card, haStates, "current_temperature")?.toDoubleOrNull()
    val rangeLow = stateOf(card, haStates, "target_temp_low")?.toDoubleOrNull()
    val rangeHigh = stateOf(card, haStates, "target_temp_high")?.toDoubleOrNull()
    val hvacModes = attrList(card, haStates, "hvac_modes").filter { it != "off" }
    val fanModes = attrList(card, haStates, "fan_modes")
    val fanMode = stateOf(card, haStates, "fan_mode")
    val presetModes = attrList(card, haStates, "preset_modes")
    val presetMode = stateOf(card, haStates, "preset_mode")

    // 用户覆盖窗口（原版 3s）：操作后 3s 内 UI 显示本地 pending 值
    var pendingTemp by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(pendingTemp) {
        if (pendingTemp != null) {
            delay(3000)
            pendingTemp = null
        }
    }

    // 原版电源记忆逻辑：非关机状态缓存模式，开机时恢复
    var cachedMode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state) { if (isOn) cachedMode = state }

    var showModePopup by remember { mutableStateOf(false) }
    var showFanPopup by remember { mutableStateOf(false) }
    var showPresetPopup by remember { mutableStateOf(false) }
    var rangeTarget by remember { mutableStateOf<Int?>(null) } // 0=低(制热) 1=高(制冷)

    val displayTemp = pendingTemp ?: attrTemp ?: ((minT + maxT) / 2.0)

    // 原版 addTempWithStep / reduceTempWithStep：round(±max(step, gestureStep))，
    // 加速期 gestureStep = (tick+1)*step，夹在 [min, max]，无变化不发送
    fun stepTemp(dir: Int, tick: Int) {
        if (!available || isRange) return
        val gesture = stepT * (tick + 1)
        val raw = displayTemp + dir * maxOf(stepT, gesture)
        val next = (Math.round(raw * 10) / 10.0).coerceIn(minT, maxT)
        if (next != displayTemp) {
            pendingTemp = next
            viewModel.setTemperature(next)
        }
    }

    fun togglePower() {
        if (!available) return
        if (isOn) {
            viewModel.setHvacMode("off")
        } else {
            val resume = cachedMode?.takeIf { it != "off" && it != "unavailable" } ?: "cool"
            viewModel.setHvacMode(resume)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 原版 imgSwitchView：60×60dp 电源开关，marginTop 10
        DevicePowerSwitch(
            isOn = isOn,
            onRes = R.drawable.ic_state_climate_on,
            offRes = R.drawable.ic_state_climate_off,
            enabled = available,
            onToggle = { togglePower() }
        )

        Spacer(Modifier.height(30.dp)) // 原版 rlPanel0 marginTop 30

        // rlPanel1：200dp 高的温度面板
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // rlAcTempAddReduce：85×175dp，marginLeft 20
            TemperatureSideButton(
                symbol = "−",
                enabled = available && !isRange,
                onStep = { tick -> stepTemp(-1, tick) },
                modifier = Modifier.padding(start = 20.dp)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (!isRange) {
                    // tvSetTemp：100×110dp，70sp 粗体 + 度符号（0.3 比例）+ 当前温度
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = formatTemp(displayTemp),
                                fontSize = 64.sp,
                                fontWeight = FontWeight.Bold,
                                color = RemoteColors.onSurface
                            )
                            Text(
                                text = "°",
                                fontSize = 19.sp, // 原版度符号比例 0.3 × 温度字号
                                fontWeight = FontWeight.Normal,
                                color = RemoteColors.onSurface
                            )
                        }
                        if (currentTemp != null) {
                            Text(
                                text = "当前温度  ${formatTemp(currentTemp)}°",
                                fontSize = 13.sp,
                                color = Color(0xFFA5A5A5) // 原版 text_off_white
                            )
                        }
                    }
                } else {
                    // 原版 heatCoolPanel：当前温度 95dp 高 + 温度不可用提示 + 双目标按钮
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = currentTemp?.let { formatTemp(it) } ?: "--",
                            fontSize = 56.sp,
                            fontWeight = FontWeight.Bold,
                            color = RemoteColors.onSurface,
                            modifier = Modifier.height(95.dp)
                        )
                        if (currentTemp == null) {
                            Text(
                                text = "温度不可用",
                                fontSize = 14.sp,
                                color = Color(0xFFA0A0A0), // 原版 text_disabled
                                modifier = Modifier.height(22.dp)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(45.dp),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            RangeTargetButton(
                                label = "制热",
                                value = rangeLow?.let { formatTemp(it) } ?: "--",
                                enabled = available,
                                onClick = { rangeTarget = 0 }
                            )
                            RangeTargetButton(
                                label = "制冷",
                                value = rangeHigh?.let { formatTemp(it) } ?: "--",
                                enabled = available,
                                onClick = { rangeTarget = 1 }
                            )
                        }
                    }
                }
            }
            // rlAcTempAdd：85×175dp，marginRight 20
            TemperatureSideButton(
                symbol = "+",
                enabled = available && !isRange,
                onStep = { tick -> stepTemp(1, tick) },
                modifier = Modifier.padding(end = 20.dp)
            )
        }

        Spacer(Modifier.height(50.dp)) // 原版模式行 marginTop 50

        // 原版底部 60dp 行：模式 / 风速 / 预设 三个 60×60dp 圆钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            RoundIconButton(
                size = 60,
                label = acModeLabel(state),
                enabled = available && hvacModes.isNotEmpty(),
                onClick = { showModePopup = true }
            )
            RoundIconButton(
                size = 60,
                label = "风速",
                enabled = available && fanModes.isNotEmpty() && fanMode != null,
                onClick = { showFanPopup = true }
            )
            RoundIconButton(
                size = 60,
                label = "预设",
                enabled = available && presetModes.isNotEmpty(),
                onClick = { showPresetPopup = true }
            )
        }
    }

    if (showModePopup) {
        OptionPopup(
            title = "模式",
            options = hvacModes.map { it to acModeLabel(it) },
            selected = state,
            onSelect = { mode ->
                cachedMode = mode // 原版：选择模式同时更新 cachedMode
                viewModel.setHvacMode(mode)
                showModePopup = false
            },
            onDismiss = { showModePopup = false }
        )
    }
    if (showFanPopup) {
        OptionPopup(
            title = "风速",
            options = fanModes.map { it to it },
            selected = fanMode,
            onSelect = { mode ->
                viewModel.setFanMode(mode)
                showFanPopup = false
            },
            onDismiss = { showFanPopup = false }
        )
    }
    if (showPresetPopup) {
        OptionPopup(
            title = "预设",
            options = presetModes.map { it to it },
            selected = presetMode,
            onSelect = { mode ->
                viewModel.setPresetMode(mode)
                showPresetPopup = false
            },
            onDismiss = { showPresetPopup = false }
        )
    }
    rangeTarget?.let { target ->
        RangeTemperatureDialog(
            initialLow = rangeLow ?: minT,
            initialHigh = rangeHigh ?: maxT,
            minTemp = minT,
            maxTemp = maxT,
            step = stepT,
            target = target,
            onApply = { low, high -> viewModel.setTemperatureRange(low, high) },
            onDismiss = { rangeTarget = null }
        )
    }
}

private fun acModeLabel(mode: String): String = when (mode) {
    "off" -> "关"
    "auto" -> "自动"
    "cool" -> "制冷"
    "heat" -> "制热"
    "dry" -> "除湿"
    "fan_only" -> "仅送风"
    "heat_cool" -> "制热制冷"
    else -> mode
}

/** 原版 rlAcTempAdd / rlAcTempAddReduce：85×175dp 圆角按钮，长按 300ms 加速步进。 */
@Composable
private fun TemperatureSideButton(
    symbol: String,
    enabled: Boolean,
    onStep: (tick: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = RemoteColors.key,
        modifier = modifier
            .size(width = 85.dp, height = 175.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(
                if (enabled) Modifier.acceleratingPress(intervalMs = 300) { onStep(it) }
                else Modifier
            )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                fontSize = 40.sp, // 原版 40dp 图标
                color = RemoteColors.onSurface
            )
        }
    }
}

/** 原版 rlHeatTarget / rlCoolTarget：100×48dp 圆角按钮（24dp 图标 + 22sp 数值）。 */
@Composable
private fun RangeTargetButton(
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = RemoteColors.key,
        modifier = Modifier
            .size(width = 100.dp, height = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = RemoteColors.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp) // 原版图标 marginEnd 6
            )
            Text(text = value, fontSize = 22.sp, color = RemoteColors.onSurface)
        }
    }
}

/**
 * 原版 AcTemperatureRangePopup：显示当前选中目标（低/高），± 步进，
 * low ∈ [minTemp, high-step]、high ∈ [low+step, maxTemp]，每次调整立即下发。
 */
@Composable
private fun RangeTemperatureDialog(
    initialLow: Double,
    initialHigh: Double,
    minTemp: Double,
    maxTemp: Double,
    step: Double,
    target: Int,
    onApply: (Double, Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var low by remember { mutableStateOf(initialLow) }
    var high by remember { mutableStateOf(initialHigh) }

    fun adjust(up: Boolean) {
        if (target == 0) {
            val raw = low + if (up) step else -step
            val next = (Math.round(raw * 10) / 10.0).let {
                if (up) it.coerceAtMost(high - step) else it.coerceAtLeast(minTemp)
            }
            if (next < high && next != low) {
                low = next
                onApply(next, high)
            }
        } else {
            val raw = high + if (up) step else -step
            val next = (Math.round(raw * 10) / 10.0).let {
                if (up) it.coerceAtMost(maxTemp) else it.coerceAtLeast(low + step)
            }
            if (next > low && next != high) {
                high = next
                onApply(low, next)
            }
        }
    }

    val value = if (target == 0) low else high
    val canUp = if (target == 0) low + step < high else high + step <= maxTemp
    val canDown = if (target == 0) low - step >= minTemp else high - step > low

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = RemoteColors.surface,
        title = {
            Text(
                text = if (target == 0) "制热目标温度" else "制冷目标温度",
                color = RemoteColors.onSurface
            )
        },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RoundIconButton(
                    size = 60,
                    label = "−",
                    enabled = canDown,
                    onClick = { adjust(false) }
                )
                Text(
                    text = formatTemp(value),
                    fontSize = 55.sp,
                    fontWeight = FontWeight.Bold,
                    color = RemoteColors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                RoundIconButton(
                    size = 60,
                    label = "+",
                    enabled = canUp,
                    onClick = { adjust(true) }
                )
            }
        }
    )
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

// ── Light (§3.10.4 ⑧) — 原版 activity_device_light.xml + LightControlView ──
//
// 布局（原版 dp 值）：顶部 60×60dp 电源开关（marginTop 10，居中）；面板左右
// 边距 16dp：亮度区 marginTop 20（百分比 55sp 白色大字 + 80dp 高亮度条，
// marginTop 20）；三入口行 marginTop 40，等权重三列（60×60dp 圆角图标块 +
// 20sp 白色标签：白光 / 彩光 / 效果）。
//
// 子页（原版 LightColorTempActivity / LightRgbActivity / LightEffectActivity）：
// 竖直取色区 500dp 高（左右边距 20、上 40、下 50），底部 50dp 返回栏（#A0000000）。
//
// 逻辑（原版 LightControlView）：亮度拖动 100ms 节流、松手精确下发
// （ThrottlerUtil(100)，pct→255 换算）；电源切换 300ms 防抖、挂起期间开关禁用；
// 关机时面板 alpha 0.5 且三入口禁用（refreshPowerStatusToUI）；色温/彩光子页
// 拖动 300ms 节流 + 松手精确发送（ThrottlerUtil(300)）；效果点击即发送。

@Composable
private fun LightContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "off"
    val isOn = state == "on"

    // 能力联动：色温 / 彩色 / 效果入口按实体支持情况显示（原版 updateUIVisibility）
    val supportedModes = stateOf(card, haStates, "supported_color_modes") ?: ""
    val supportsColorTemp = supportedModes.contains("color_temp")
    val supportsColor = listOf("hs", "rgb", "rgbw", "rgbww", "xy").any { supportedModes.contains(it) }
    val minKelvin = stateOf(card, haStates, "min_color_temp_kelvin")?.toFloatOrNull() ?: 2000f
    val maxKelvin = stateOf(card, haStates, "max_color_temp_kelvin")?.toFloatOrNull() ?: 6500f
    val kelvinNow = stateOf(card, haStates, "color_temp_kelvin")?.toFloatOrNull()
    val effects = attrList(card, haStates, "effect_list")
    val currentEffect = stateOf(card, haStates, "effect")
    val brightnessAttr = stateOf(card, haStates, "brightness")?.toFloatOrNull()?.div(2.55f) ?: 0f

    val scope = rememberCoroutineScope()

    // 亮度：拖动 100ms 节流，松手精确下发（原版 ThrottlerUtil(100)）
    val brightnessDisplay = remember(brightnessAttr) { mutableStateOf(brightnessAttr) }
    val brightnessThrottle = rememberValueThrottle<Float>(
        send = { v -> viewModel.lightOn(brightnessPct = v.toInt()) },
        intervalMs = 100
    )

    // 电源 300ms 防抖（原版 mSwitchDebounceRunnable），挂起期间开关禁用
    var powerJob by remember { mutableStateOf<Job?>(null) }
    fun requestPower(on: Boolean) {
        if (powerJob?.isActive == true) return
        powerJob = scope.launch {
            delay(300)
            powerJob = null
            if (on) viewModel.lightOn() else viewModel.lightOff()
        }
    }

    // 子页状态：null=主面板，"colorTemp"/"rgb"/"effect"=对应子页（原版跳转 Activity）
    var subPage by remember { mutableStateOf<String?>(null) }

    val panelAlpha = if (isOn) 1f else 0.5f // 原版 refreshPowerStatusToUI

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 原版 imgSwitchView：60×60dp 电源开关，marginTop 10
            DevicePowerSwitch(
                isOn = isOn,
                onRes = R.drawable.ic_state_light_on,
                offRes = R.drawable.ic_state_light_off,
                onToggle = { requestPower(it) }
            )

            Spacer(Modifier.height(20.dp)) // 原版亮度区 marginTop 20

            // 白光亮度：百分比大字（数字 55sp + % 小字）+ 80dp 高亮度条
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp) // 原版 rlPanel0 marginLeft/Right 16
                    .alpha(panelAlpha)
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Spacer(Modifier.width(24.dp))
                    Text(
                        text = "${brightnessDisplay.value.toInt()}",
                        fontSize = 55.sp,
                        fontWeight = FontWeight.Bold,
                        color = RemoteColors.onSurface
                    )
                    Text(
                        text = "%",
                        fontSize = 17.sp, // 原版 Spannable 100:30 比例
                        color = RemoteColors.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
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
                    enabled = isOn,
                    thickness = 28.dp,
                    thumbSize = 80.dp, // 原版亮度条高度 80dp（触控区同高）
                    thumbColor = Color.Transparent,
                    activeColor = Color(0xFFC8A96E) // 原版 slider_accent
                )
            }

            Spacer(Modifier.height(40.dp)) // 原版三入口行 marginTop 40

            // 三入口：白光（色温）/ 彩光（RGB）/ 效果，等权重三列
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(panelAlpha)
            ) {
                if (supportsColorTemp) {
                    LightModeEntry(
                        label = "白光",
                        glyph = "◎",
                        enabled = isOn,
                        onClick = { subPage = "colorTemp" },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (supportsColor) {
                    LightModeEntry(
                        label = "彩光",
                        glyph = "❋",
                        enabled = isOn,
                        onClick = { subPage = "rgb" },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (effects.isNotEmpty()) {
                    LightModeEntry(
                        label = "效果",
                        glyph = "✦",
                        enabled = isOn,
                        onClick = { subPage = "effect" },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 子页覆盖层（原版跳转 Activity，此处就地覆盖展示）
        when (subPage) {
            "colorTemp" -> {
                val kelvinDisplay = remember(minKelvin, maxKelvin, kelvinNow) {
                    mutableStateOf(kelvinNow ?: ((minKelvin + maxKelvin) / 2f))
                }
                val kelvinThrottle = rememberValueThrottle<Float>(
                    send = { v -> viewModel.lightOn(kelvin = v.toInt()) },
                    intervalMs = 300
                )
                LightSubPageOverlay(onBack = { subPage = null }) {
                    VerticalValuePicker(
                        value = kelvinDisplay.value,
                        valueRange = minKelvin..maxKelvin,
                        topLabel = "${maxKelvin.toInt()}K",
                        bottomLabel = "${minKelvin.toInt()}K",
                        brush = Brush.verticalGradient(
                            listOf(Color(0xFFD9F2FF), Color(0xFFFFE3B8))
                        ),
                        onDrag = { v ->
                            kelvinDisplay.value = v
                            kelvinThrottle.onDrag(v)
                        },
                        onCommit = { v ->
                            kelvinDisplay.value = v
                            kelvinThrottle.onCommit(v)
                        }
                    )
                }
            }

            "rgb" -> {
                val hueDisplay = remember { mutableStateOf(0f) }
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
                LightSubPageOverlay(onBack = { subPage = null }) {
                    VerticalValuePicker(
                        value = hueDisplay.value,
                        valueRange = 0f..360f,
                        topLabel = null,
                        bottomLabel = null,
                        brush = Brush.verticalGradient(
                            listOf(0, 60, 120, 180, 240, 300, 360).map {
                                Color.hsv(it.toFloat(), 1f, 1f)
                            }
                        ),
                        onDrag = { v ->
                            hueDisplay.value = v
                            rgbThrottle.onDrag(v)
                        },
                        onCommit = { v ->
                            hueDisplay.value = v
                            rgbThrottle.onCommit(v)
                        }
                    )
                }
            }

            "effect" -> {
                LightSubPageOverlay(onBack = { subPage = null }) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(effects) { effect ->
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (effect == currentEffect) {
                                    RemoteColors.accent
                                } else {
                                    RemoteColors.key
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.lightEffect(effect) }
                            ) {
                                Text(
                                    text = effect,
                                    fontSize = 18.sp,
                                    color = RemoteColors.onSurface,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 原版三入口项：60×60dp 圆角图标块 + 20sp 白色标签（light_color_item_bg）。 */
@Composable
private fun LightModeEntry(
    label: String,
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = RemoteColors.key,
            modifier = Modifier
                .size(60.dp)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = glyph,
                    fontSize = 28.sp,
                    color = RemoteColors.onSurface
                )
            }
        }
        Text(
            text = label,
            fontSize = 20.sp,
            color = RemoteColors.onSurface,
            modifier = Modifier.padding(bottom = 2.dp) // 原版 paddingBottom 2
        )
    }
}

/**
 * 原版子页框架（activity_light_color_temp / _rgb / _effect）：取色区 500dp 高、
 * 左右边距 20、上 40；底部 50dp 返回栏（#A0000000）。
 */
@Composable
private fun LightSubPageOverlay(
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RemoteBackground)
    ) {
        Spacer(Modifier.height(40.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(500.dp)
        ) {
            content()
        }
        Spacer(Modifier.weight(1f))
        Surface(
            color = Color(0xA0000000), // 原版 BackView 背景
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp) // 原版返回栏高度 50dp
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = "返回",
                        tint = RemoteColors.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 竖直取色器（原版 ViewColorTempPickerVertical / ViewColorPickerVertical）：
 * 顶部为 range 上界、底部为下界，拖动连续回调，松手精确回调一次。
 */
@Composable
private fun VerticalValuePicker(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    topLabel: String?,
    bottomLabel: String?,
    brush: Brush,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var heightPx by remember { mutableStateOf(0) }
    val fraction = ((value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(500.dp)
            .onSizeChanged { heightPx = it.height }
            .clip(RoundedCornerShape(24.dp))
            .background(brush)
            .pointerInput(valueRange) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun valueAt(y: Float): Float {
                        val h = if (heightPx > 0) heightPx.toFloat() else 1f
                        val f = (y / h).coerceIn(0f, 1f)
                        return valueRange.endInclusive - f *
                            (valueRange.endInclusive - valueRange.start)
                    }
                    var last = valueAt(down.position.y)
                    onDrag(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            onCommit(last)
                            change.consume()
                            break
                        }
                        if (change.position != change.previousPosition) {
                            last = valueAt(change.position.y)
                            onDrag(last)
                        }
                        change.consume()
                    }
                }
            }
    ) {
        // 当前值指示圈（原版滑块）
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    translationY = ((heightPx - 44.dp.toPx()) * (1f - fraction))
                        .coerceIn(0f, (heightPx - 44.dp.toPx()).coerceAtLeast(0f))
                }
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
                .border(3.dp, Color(0xFF2B2B2B), CircleShape)
        )
        if (topLabel != null) {
            Text(
                text = topLabel,
                fontSize = 14.sp,
                color = Color(0xFF2B2B2B),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
            )
        }
        if (bottomLabel != null) {
            Text(
                text = bottomLabel,
                fontSize = 14.sp,
                color = Color(0xFF2B2B2B),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
            )
        }
    }
}


// ── Fan (§3.10.4 ④) — 原版 activity_device_fan.xml + FanActivity ────────
//
// 布局（原版 dp 值）：顶部 60×60dp 电源开关（marginTop 10，居中）；档位列表
// 340dp 高（左右边距 20），五档在上、一档在下，行间 10dp 间距；每行圆角底、
// padding 15，内容为 40dp 风叶图标 + 15sp 白色档位文字（一档..五档）。
// 不支持百分比时隐藏列表，显示 16sp #666666 提示“不支持风速调节”。
//
// 逻辑（原版 FanActivity）：percentage_step（默认 20）→ 档位数 = 100/step
// 夹在 1..5，档位值 = min(i*step, 100)；点击档位立即高亮、300ms 防抖后下发
// fan.set_percentage，1500ms 内不跟随 HA 回写；电源切换 300ms 防抖，列表
// alpha 以 500ms 动画切换 1.0/0.5（首次 0ms）；物理键 24/25 按最近档位上下
// 移动、132 切换电源。

@Composable
private fun FanContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "off"
    val isOn = state == "on"
    val percentage = stateOf(card, haStates, "percentage")?.toFloatOrNull()
    val stepAttr = stateOf(card, haStates, "percentage_step")?.toFloatOrNull()
    val supportsPercentage = percentage != null || stepAttr != null

    val scope = rememberCoroutineScope()

    // 原版 buildSpeedLevels：步进默认 20，档位数 = 100/step 夹在 1..5
    val step = stepAttr?.takeIf { it > 0f } ?: 20f
    val levelCount = (100f / step).toInt().coerceIn(1, 5)
    val levels = (1..levelCount).map { minOf((it * step).toInt(), 100) }
    val levelLabels = listOf("一档", "二档", "三档", "四档", "五档")

    // 选中档位 + 1500ms 用户覆盖窗口（原版 UserOverrideWindowManager "fan"）
    var pendingLevel by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingLevel) {
        if (pendingLevel != null) {
            delay(1500)
            pendingLevel = null
        }
    }

    // 档位点击：立即高亮，300ms 防抖后下发（原版 mSpeedDebounceRunnable）
    var speedJob by remember { mutableStateOf<Job?>(null) }
    fun setFanSpeed(value: Int) {
        pendingLevel = value
        speedJob?.cancel()
        speedJob = scope.launch {
            delay(300)
            speedJob = null
            viewModel.fanPercentage(value)
        }
    }

    // 电源 300ms 防抖（原版 mSwitchDebounceRunnable）
    var powerJob by remember { mutableStateOf<Job?>(null) }
    fun requestPower(on: Boolean) {
        if (powerJob?.isActive == true) return
        powerJob = scope.launch {
            delay(300)
            powerJob = null
            if (on) viewModel.turnOn() else viewModel.turnOff()
        }
    }

    // 原版 updateBtnPowerUI：alpha 500ms 动画 1.0/0.5（首次 0ms 由初始值承担）
    val listAlpha by animateFloatAsState(
        targetValue = if (isOn) 1f else 0.5f,
        animationSpec = tween(durationMillis = 500),
        label = "fanListAlpha"
    )

    // 原版 updateSpeedIconUI：百分比与档位值完全相等才高亮
    val selectedLevel = pendingLevel
        ?: percentage?.toInt()?.let { cur -> levels.firstOrNull { it == cur } }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 原版 imgSwitchView：60×60dp 电源开关，marginTop 10
        DevicePowerSwitch(
            isOn = isOn,
            onRes = R.drawable.ic_state_fan_on,
            offRes = R.drawable.ic_state_fan_off,
            onToggle = { requestPower(it) }
        )

        if (!supportsPercentage) {
            // 原版 tvSpeedDeviceControl：16sp #666666 居中提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "不支持风速调节",
                    fontSize = 16.sp,
                    color = Color(0xFF666666)
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
            // 原版 llSpeed：340dp 高、左右边距 20，行间 10dp
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(340.dp)
                    .alpha(listAlpha),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 原版布局 rlSpeed5 在最上（五档 → 一档）
                levels.asReversed().forEach { value ->
                    val index = levels.indexOf(value)
                    val isSelected = selectedLevel == value
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) RemoteColors.surfaceVariant else RemoteColors.key,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clickable(enabled = isOn) { setFanSpeed(value) }
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(15.dp) // 原版行 padding 15
                        ) {
                            Text(
                                text = "✳",
                                fontSize = (24 + index * 4).sp, // 原版风叶图标随档位增强
                                color = if (isSelected) RemoteColors.accent else RemoteColors.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = levelLabels[index],
                                fontSize = 15.sp,
                                color = if (isSelected) RemoteColors.accent else RemoteColors.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}



// ── Cover / 窗帘 (§3.10.4 ⑤⑥⑦) — 原版 activity_device_curtain.xml ──────
//
// 布局（原版 dp 值）：CurtainView 300×250dp 居中（marginTop 100→此处 40）；
// 百分比文字 30sp 白色（marginTop 20，"X%" 格式）；底部按钮行（marginTop 25、
// 下边距 20）：打开 70×70dp（左右边距 16）/ 停止 70×70dp 居中 / 关闭 70×70dp，
// 50dp 图标居中；到位时对应按钮盖遮罩并禁用。
//
// 逻辑（原版 CurtainActivity + CurtainView）：拖动实时更新百分比文字，松手
// 300ms 防抖后 cover.set_cover_position 并阻塞状态回写 30s（preventUiUpdates）；
// 开/停/关按钮 300ms 防抖下发；位置 100% 禁开、0% 禁关；
// 物理键 132=开/关切换、23=停止（原版 keyControl0penOrClose / keyControlStop）。

@Composable
private fun CoverContent(
    card: PanelCard,
    haStates: Map<String, HaEntityState>,
    viewModel: DeviceDetailViewModel,
) {
    val state = stateOf(card, haStates) ?: "closed"
    val position = stateOf(card, haStates, "current_position")?.toFloatOrNull()
    val supportsTilt = stateOf(card, haStates, "current_tilt_position") != null

    val scope = rememberCoroutineScope()

    // 拖动中的实时值；松手后 300ms 防抖下发并阻塞回写 30s
    var dragOpen by remember { mutableStateOf<Int?>(null) }
    var pendingSend by remember { mutableStateOf<Int?>(null) }
    var blockUntil by remember { mutableStateOf(0L) }

    LaunchedEffect(pendingSend) {
        val target = pendingSend
        if (target != null) {
            delay(300) // 原版 mPositionDebounceRunnable 300ms
            pendingSend = null
            blockUntil = System.currentTimeMillis() + 30000L // 原版 preventUiUpdates 30s
            viewModel.coverPosition(target)
        }
    }

    val attrOpen = position?.toInt() ?: when (state) {
        "open" -> 100
        "closed" -> 0
        else -> null
    }
    val displayOpen = dragOpen
        ?: pendingSend?.takeIf { System.currentTimeMillis() < blockUntil }
        ?: attrOpen

    // 原版 sendCurtainControl：控制命令 300ms 防抖
    var controlJob by remember { mutableStateOf<Job?>(null) }
    fun sendControl(command: suspend () -> Unit) {
        controlJob?.cancel()
        controlJob = scope.launch {
            delay(300)
            blockUntil = 0
            command()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))

        // 原版 CurtainView：300×250dp 可拖动窗帘轨道
        CurtainDragView(
            open = displayOpen,
            enabled = position != null,
            onDrag = { open -> dragOpen = open }, // 原版 onPercentageChanged：仅更新显示
            onRelease = { open -> // 原版 onStopTrackingTouch
                dragOpen = null
                if (open != attrOpen) pendingSend = open
            }
        )

        // 原版 tvCurtainTrackPercentage：30sp 白色，marginTop 20，"%s%%"
        Text(
            text = "${displayOpen ?: 0}%",
            fontSize = 30.sp,
            color = RemoteColors.onSurface,
            modifier = Modifier.padding(top = 20.dp)
        )

        Spacer(Modifier.height(25.dp))

        // 原版底部按钮行：打开 / 停止 / 关闭，各 70×70dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            CurtainButton(
                label = "打开",
                enabled = (displayOpen ?: 0) < 100, // 原版 100% 禁开 + 遮罩
                onClick = { sendControl { viewModel.coverOpen() } }
            )
            Spacer(Modifier.weight(1f))
            CurtainButton(
                label = "停止",
                enabled = true,
                onClick = { sendControl { viewModel.coverStop() } }
            )
            Spacer(Modifier.weight(1f))
            CurtainButton(
                label = "关闭",
                enabled = (displayOpen ?: 0) > 0, // 原版 0% 禁关 + 遮罩
                onClick = { sendControl { viewModel.coverClose() } }
            )
        }

        Spacer(Modifier.height(20.dp)) // 原版 marginBottom 20

        InfoLine(
            when (state) {
                "opening" -> "打开中…"
                "closing" -> "关闭中…"
                else -> ""
            }
        )

        // 翻转叶片（原版为独立百叶窗页 CurtainBlindsActivity，此处按需追加）
        if (supportsTilt) {
            val tilt = stateOf(card, haStates, "current_tilt_position")?.toFloatOrNull() ?: 50f
            val tiltDisplay = remember(tilt) { mutableStateOf(tilt) }
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

/**
 * 原版 CurtainView 复刻：300×250dp，浅灰轨道线（0xFFDBDADA），帘布左锚定、
 * 拖动手柄改变帘布覆盖 → 开度 = 100 - 覆盖比例。拖动中只回调显示值，
 * 松手回调一次提交值（原版 onPercentageChanged / onStopTrackingTouch）。
 */
@Composable
private fun CurtainDragView(
    open: Int?,
    enabled: Boolean,
    onDrag: (Int) -> Unit,
    onRelease: (Int) -> Unit,
) {
    var widthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val handleSize = with(density) { 44.dp.toPx() }
    val sliderHalf = handleSize / 2f

    // 手柄中心 x ↔ 开度换算（原版 setPercentage / drawCanvas 的映射）
    fun handleXFor(openPct: Int): Float {
        val track = (widthPx - sliderHalf).coerceAtLeast(1f)
        return sliderHalf + (1f - openPct.coerceIn(0, 100) / 100f) * track
    }

    fun openFor(handleX: Float): Int {
        val track = (widthPx - sliderHalf).coerceAtLeast(1f)
        val f = ((handleX - sliderHalf) / track).coerceIn(0f, 1f)
        return (100 - Math.round(f * 100)).coerceIn(0, 100)
    }

    var handleX by remember { mutableStateOf<Float?>(null) }
    val currentHandle = handleX ?: open?.let { handleXFor(it) } ?: sliderHalf

    Box(
        modifier = Modifier
            .size(width = 300.dp, height = 250.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var last = openFor(down.position.x.coerceIn(0f, size.width.toFloat()))
                    onDrag(last)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            onRelease(last)
                            handleX = null // 松手后跟随状态值（阻塞期由 pendingSend 承接）
                            change.consume()
                            break
                        }
                        if (change.position != change.previousPosition) {
                            val x = change.position.x.coerceIn(0f, size.width.toFloat())
                            handleX = x
                            last = openFor(x)
                            onDrag(last)
                        }
                        change.consume()
                    }
                }
            }
    ) {
        // 轨道线（原版 mTopPaint：浅灰粗线，圆帽）
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawLine(
                color = Color(0xFFDBDADA),
                start = Offset(sliderHalf / 2f, 4.dp.toPx()),
                end = Offset(size.width - sliderHalf / 2f, 4.dp.toPx()),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        // 帘布：左锚定到当前手柄处（原版 mScreening 帘布位图）
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (currentHandle - handleSize).toInt().coerceAtLeast(0),
                        3.dp.roundToPx()
                    )
                }
                .width(with(density) { currentHandle.coerceAtLeast(0f).toDp() })
                .height(with(density) { (250.dp.toPx() - 10.dp.toPx()).toDp() })
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF3A3A3C), Color(0xFF232325))
                    )
                )
        )
        // 拖动手柄（原版 mSlider 滑块图标，位于帘布右缘）
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (currentHandle - sliderHalf).toInt().coerceAtLeast(0),
                        (125).dp.roundToPx() - (handleSize / 2f).toInt()
                    )
                }
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xFFDBDADA))
                .border(3.dp, Color(0xFF2B2B2B), CircleShape)
        )
    }
}

/** 原版窗帘按钮：70×70dp 圆角底 + 50dp 图标；禁用时压暗（到位遮罩语义）。 */
@Composable
private fun CurtainButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = RemoteColors.key,
        modifier = Modifier
            .size(70.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 18.sp, color = RemoteColors.onSurface)
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