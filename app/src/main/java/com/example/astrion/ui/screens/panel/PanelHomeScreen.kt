package com.example.astrion.ui.screens.panel

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.astrion.R
import com.example.astrion.esphome.Connected
import com.example.astrion.panel.PanelCard
import com.example.astrion.panel.PanelCardTypes
import com.example.astrion.panel.PanelLayout
import com.example.astrion.services.ActivityNavigator
import com.example.astrion.services.HomeAssistantStatesStore
import com.example.astrion.services.SatelliteStateHolder
import com.example.astrion.ui.DeviceDetail
import com.example.astrion.ui.ShortcutKeysRoute
import com.example.astrion.ui.theme.RemoteBackground
import com.example.astrion.ui.theme.RemoteColors
import com.example.astrion.utils.getLocalIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class PanelViewModel @Inject constructor(
    panelConfigStore: com.example.astrion.panel.PanelConfigStore,
    haStatesStore: HomeAssistantStatesStore,
    private val activityNavigator: ActivityNavigator,
    private val satelliteStateHolder: SatelliteStateHolder,
    private val microphoneSettingsStore: com.example.astrion.settings.MicrophoneSettingsStore,
    private val displaySettingsStore: com.example.astrion.settings.DisplaySettingsStore,
) : ViewModel() {
    val layout = panelConfigStore.layout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PanelLayout())
    val haStates = haStatesStore.states
    val currentPage = activityNavigator.currentPage
    val deviceState = satelliteStateHolder.deviceState
    val micMuted = microphoneSettingsStore.muted
    val raiseToWake = displaySettingsStore.raiseToWakeThreshold
        .map { it > 0f }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val screenSaverTimeout = displaySettingsStore.screenSaverTimeout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun selectRoom(title: String) = activityNavigator.setPage(title)

    fun setRaiseToWake(enabled: Boolean) {
        viewModelScope.launch {
            displaySettingsStore.raiseToWakeThreshold.set(if (enabled) 4f else 0f)
        }
    }

    fun setScreenSaverTimeout(seconds: Int) {
        viewModelScope.launch {
            displaySettingsStore.screenSaverTimeout.set(seconds.coerceIn(0, 600))
        }
    }

    fun refreshDevices() = satelliteStateHolder.reconnect()

    fun setMicMuted(muted: Boolean) {
        viewModelScope.launch { micMuted.set(muted) }
    }
}

/** Per-type accent pair for the icon gradient (reserved for editor previews). */
fun cardAccent(type: String): List<Color> = when (type) {
    PanelCardTypes.TV -> listOf(Color(0xFF2F6BFF), Color(0xFF69B6FF))
    PanelCardTypes.LIGHT -> listOf(Color(0xFFFF9F2E), Color(0xFFFFD75E))
    PanelCardTypes.CLIMATE -> listOf(Color(0xFF1FB6C9), Color(0xFF63E5E0))
    PanelCardTypes.FAN -> listOf(Color(0xFF2ECC71), Color(0xFF8BE9A8))
    PanelCardTypes.COVER -> listOf(Color(0xFF8E6BFF), Color(0xFFC9A6FF))
    PanelCardTypes.MEDIA_PLAYER -> listOf(Color(0xFFFF5E7A), Color(0xFFFFA26B))
    PanelCardTypes.SCENE -> listOf(Color(0xFFF2B01E), Color(0xFFFFE08A))
    PanelCardTypes.WEATHER -> listOf(Color(0xFF39A0FF), Color(0xFF9BD1FF))
    PanelCardTypes.HOST -> listOf(Color(0xFF5B7CFA), Color(0xFF8FA6FF))
    PanelCardTypes.SWITCH_MONITOR -> listOf(Color(0xFF2FBF9B), Color(0xFF7BE3C3))
    else -> listOf(Color(0xFF4C6A92), Color(0xFF7E9CC4))
}

/** Maps a card type to its icon resource (§4 aiks-* card icons). */
fun cardIconRes(type: String): Int = when (type) {
    PanelCardTypes.TV -> R.drawable.ic_panel_tv
    PanelCardTypes.LIGHT -> R.drawable.ic_panel_light
    PanelCardTypes.FAN -> R.drawable.ic_panel_fan
    PanelCardTypes.CLIMATE -> R.drawable.ic_panel_ac
    PanelCardTypes.COVER -> R.drawable.ic_panel_curtain
    PanelCardTypes.SWITCH -> R.drawable.ic_panel_switch
    PanelCardTypes.SCENE -> R.drawable.ic_panel_scene
    PanelCardTypes.MEDIA_PLAYER -> R.drawable.ic_panel_media
    PanelCardTypes.WEATHER -> R.drawable.ic_panel_weather
    PanelCardTypes.HOST -> R.drawable.ic_panel_host
    PanelCardTypes.SWITCH_MONITOR -> R.drawable.ic_panel_monitor
    else -> R.drawable.ic_panel_switch
}

/** Human readable card name: explicit name, then the HA friendly name, then the entity id. */
fun PanelCard.displayName(states: Map<String, com.example.astrion.services.HaEntityState> = emptyMap()): String {
    name.ifBlank { primaryEntity?.alias?.ifBlank { null } }?.let { return it }
    val entityId = primaryEntity?.entityId
    if (entityId != null) {
        states["$entityId.friendly_name"]?.state?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return entityId?.substringAfter('.')?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
        ?: "设备"
}

/** One-line state summary for a card, from the imported HA entity states. */
fun cardStateText(card: PanelCard, states: Map<String, com.example.astrion.services.HaEntityState>): String {
    val primary = card.primaryEntity ?: return ""
    val state = states[primary.entityId]?.state ?: return ""
    return when (card.resolvedType) {
        PanelCardTypes.CLIMATE -> {
            val temp = states["${primary.entityId}.temperature"]?.state
            buildString {
                append(translateOnOff(state))
                if (temp != null) append(" · ${temp}℃")
            }
        }

        PanelCardTypes.LIGHT, PanelCardTypes.FAN, PanelCardTypes.SWITCH ->
            translateOnOff(state)

        PanelCardTypes.COVER ->
            when (state) {
                "open" -> "打开"
                "closed" -> "关闭"
                "opening" -> "打开中…"
                "closing" -> "关闭中…"
                else -> state
            }

        PanelCardTypes.WEATHER, PanelCardTypes.MEDIA_PLAYER -> {
            val extra = states["${primary.entityId}.temperature"]?.state
                ?: states["${primary.entityId}.media_title"]?.state
            if (extra != null) "$state · $extra" else state
        }

        else -> state
    }
}

fun translateOnOff(state: String): String = when (state) {
    "on" -> "开启"
    "off" -> "关闭"
    "unavailable" -> "不可用"
    else -> state
}

// ── Home ────────────────────────────────────────────────────────────────

@Composable
fun PanelHomeScreen(
    navController: NavController,
    viewModel: PanelViewModel = hiltViewModel()
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val haStates by viewModel.haStates.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle(initialValue = false)
    val raiseToWake by viewModel.raiseToWake.collectAsStateWithLifecycle()
    val screenSaverTimeout by viewModel.screenSaverTimeout.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val rooms = remember(layout) { layout.rooms }

    val initialIndex = rooms.indexOfFirst { it.title == currentPage }.takeIf { it >= 0 } ?: 0
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (rooms.size - 1).coerceAtLeast(0)),
        pageCount = { rooms.size }
    )
    // HA navigate select drives the pager (§3.7 navigate_to).
    LaunchedEffect(currentPage, rooms) {
        val index = rooms.indexOfFirst { it.title == currentPage }
        if (index >= 0 && pagerState.currentPage != index) {
            pagerState.animateScrollToPage(index)
        }
    }

    val connected = deviceState == Connected
    var quickSettingsOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .topEdgeSwipeToOpen(enabled = !quickSettingsOpen) { quickSettingsOpen = true }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar(connected = connected)
            if (!connected) WifiHintRow()
            RoomSelectorBar(
                roomTitle = rooms.getOrNull(pagerState.currentPage)?.title,
                roomTitles = rooms.map { it.title },
                onSelectRoom = { viewModel.selectRoom(it) }
            )

            if (rooms.isEmpty()) {
                EmptyLayoutHint(onRefresh = { viewModel.refreshDevices() })
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    val room = rooms.getOrNull(page)
                    RoomDeviceList(
                        cards = room?.cards ?: emptyList(),
                        haStates = haStates,
                        onOpenCard = { card -> navController.navigate(DeviceDetail(card.cardId)) }
                    )
                }
                PageDotsIndicator(
                    pageCount = rooms.size,
                    currentPage = pagerState.currentPage,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 60.dp)
                )
            }
        }

        if (rooms.isNotEmpty()) {
            BottomActionBar(
                connected = connected,
                onRefresh = { viewModel.refreshDevices() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )
        }

        // 快捷面板画在内容之上，触摸不会被首页拦截
        if (quickSettingsOpen) {
            QuickSettingsPanel(
                connected = connected,
                micMuted = micMuted,
                onMicMutedChanged = { viewModel.setMicMuted(it) },
                raiseToWake = raiseToWake,
                onRaiseToWakeChanged = { viewModel.setRaiseToWake(it) },
                screenSaverTimeout = screenSaverTimeout,
                onScreenSaverTimeoutChanged = { viewModel.setScreenSaverTimeout(it) },
                onRefreshDevices = { viewModel.refreshDevices() },
                onOpenShortcutKeys = { navController.navigate(ShortcutKeysRoute) },
                onOpenSettings = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                    )
                },
                onDismiss = { quickSettingsOpen = false }
            )
        }
    }
}

/** 原生顶部状态条：左侧 HA 连接状态、中间大号时间、右侧电量。 */
@Composable
private fun StatusBar(connected: Boolean) {
    val time = remember { mutableStateOf("") }
    val date = remember { mutableStateOf("") }
    val battery = rememberBatteryLevel()
    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFmt = SimpleDateFormat("M月d日", Locale.getDefault())
        while (true) {
            val now = Date()
            time.value = timeFmt.format(now)
            date.value = dateFmt.format(now)
            delay(30_000)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .background(
                    color = if (connected) RemoteColors.secondary else RemoteColors.error,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "HA", color = RemoteColors.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = time.value,
                color = RemoteColors.topBarText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(text = date.value, color = RemoteColors.hintText, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        if (battery != null) {
            Text(text = "$battery%", color = RemoteColors.topBarText, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(width = 15.dp, height = 8.dp)
                    .border(1.dp, RemoteColors.topBarText, RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((battery.toFloat() / 100f).coerceIn(0f, 1f))
                        .padding(horizontal = 1.dp)
                        .background(RemoteColors.topBarText)
                        .height(4.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberBatteryLevel(): Int? {
    val context = LocalContext.current
    val level = remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                if (l >= 0) level.value = l
            }
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return level.value
}

/** 原生顶部下拉提示：未连接时的红色提醒 + 金色补充说明。 */
@Composable
private fun WifiHintRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "请连接 WiFi", color = RemoteColors.error, fontSize = 17.sp)
        Spacer(Modifier.width(10.dp))
        Text(text = "当前未连接", color = RemoteColors.wifiHint, fontSize = 16.sp)
    }
}

/** 原生居中房间选择条 + 深色下拉。 */
@Composable
private fun RoomSelectorBar(
    roomTitle: String?,
    roomTitles: List<String>,
    onSelectRoom: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.clickable(enabled = roomTitles.size > 1) { menuOpen = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = roomTitle ?: "Astrion",
                color = RemoteColors.roomName,
                fontSize = 23.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (roomTitles.size > 1) {
                Spacer(Modifier.width(8.dp))
                Text(text = "▾", color = RemoteColors.onSurfaceVariant, fontSize = 16.sp)
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = RemoteColors.popupBackground
        ) {
            roomTitles.forEachIndexed { index, title ->
                DropdownMenuItem(
                    text = { Text(title, color = RemoteColors.roomName, fontSize = 16.sp) },
                    onClick = {
                        menuOpen = false
                        onSelectRoom(title)
                    }
                )
                if (index < roomTitles.size - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                            .height(1.dp)
                            .background(RemoteColors.popupLine)
                    )
                }
            }
        }
    }
}

/** 原版双列设备卡片网格：类型专属彩色渐变图标 + 名称 + 状态点。 */
@Composable
private fun RoomDeviceList(
    cards: List<PanelCard>,
    haStates: Map<String, com.example.astrion.services.HaEntityState>,
    onOpenCard: (PanelCard) -> Unit,
) {
    // 容错: duplicate keys would crash the grid - keep the first of any dupes
    val uniqueCards = remember(cards) { cards.distinctBy { it.cardId } }
    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_panel_host),
                    contentDescription = null,
                    tint = Color(0xFF3A3A3C),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(text = "此房间暂无设备", color = RemoteColors.hintText, fontSize = 15.sp)
            }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(uniqueCards, key = { it.cardId }) { card ->
            DeviceCard(
                card = card,
                name = card.displayName(haStates),
                stateText = cardStateText(card, haStates),
                onClick = { onOpenCard(card) }
            )
        }
    }
}

@Composable
private fun DeviceCard(
    card: PanelCard,
    name: String,
    stateText: String,
    onClick: () -> Unit,
) {
    // 原版 index_device_*_item：纯黑底、居中 50dp 原版彩色状态图标
    // (alpha 0.8)、下方灰名、左侧小白点表示开启。
    val isOn = stateText.contains("开启") || stateText.contains("打开") ||
        stateText.contains("播放") || card.resolvedType == PanelCardTypes.SCENE
    val isOffline = stateText.contains("不可用") || stateText.contains("unavailable")
    val iconRes = stateIconRes(card.resolvedType, isOn)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(155.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = card.resolvedType,
                modifier = Modifier
                    .size(50.dp)
                    .alpha(0.8f)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = name,
                color = Color(0xFFBFBDBD),
                fontSize = 16.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp)
            )
        }
        if (isOn) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 13.dp, bottom = 5.dp)
                    .background(Color.White, CircleShape)
            )
        }
        if (isOffline) {
            Text(
                text = "设备已离线",
                color = Color(0xFFBFBDBD),
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (card.resolvedType == PanelCardTypes.LIGHT) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 8.dp, end = 15.dp)
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "亮度", color = Color(0xFFBFBDBD), fontSize = 10.sp)
                Spacer(Modifier.width(4.dp))
                Text(text = "色温", color = Color(0xFFBFBDBD), fontSize = 10.sp)
            }
        }
    }
}

/** 原版两态图标：开=彩色 on 图标，关=灰色 off 图标。 */
private fun stateIconRes(type: String, isOn: Boolean): Int {
    val pair = when (type) {
        PanelCardTypes.LIGHT -> R.drawable.ic_state_light_on to R.drawable.ic_state_light_off
        PanelCardTypes.CLIMATE -> R.drawable.ic_state_climate_on to R.drawable.ic_state_climate_off
        PanelCardTypes.COVER -> R.drawable.ic_state_cover_on to R.drawable.ic_state_cover_off
        PanelCardTypes.FAN -> R.drawable.ic_state_fan_on to R.drawable.ic_state_fan_off
        PanelCardTypes.MEDIA_PLAYER -> R.drawable.ic_state_media_on to R.drawable.ic_state_media_off
        PanelCardTypes.SWITCH -> R.drawable.ic_state_switch_on to R.drawable.ic_state_switch_off
        PanelCardTypes.TV -> R.drawable.ic_state_tv_on to R.drawable.ic_state_tv_off
        else -> R.drawable.ic_state_default to R.drawable.ic_state_default
    }
    return if (isOn) pair.first else pair.second
}

@Composable
private fun PageDotsIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 9.dp else 6.dp)
                    .background(
                        color = if (index == currentPage) RemoteColors.onSurface
                        else RemoteColors.dot,
                        shape = CircleShape
                    )
            )
        }
    }
}

/** 原生底部操作条：刷新/重连（设备的增删由 HA 推送 astrion_layout 管理）。 */
@Composable
private fun BottomActionBar(
    connected: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = if (connected) "↻" else "↻"
    val label = if (connected) "刷新设备" else "重新连接"
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = RemoteColors.surfaceVariant,
        border = BorderStroke(1.dp, RemoteColors.rowSeparator),
        modifier = modifier.clickable(onClick = onRefresh)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, color = RemoteColors.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(text = label, color = RemoteColors.accent, fontSize = 14.sp)
        }
    }
}

/** 原生空状态：大图标 + 三步接入指引 + 本面板地址 + 刷新按钮。 */
@Composable
private fun EmptyLayoutHint(onRefresh: () -> Unit) {
    val panelIp = remember { getLocalIpAddress().orEmpty() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_panel_host),
            contentDescription = null,
            tint = Color(0xFF2A2A2C),
            modifier = Modifier.size(110.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text(text = "三步开始使用", color = RemoteColors.accent, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "① 让面板和手机/电脑连同一个路由器\n" +
                "② 打开 Home Assistant → 设置 → 设备与服务 → ESPHome\n" +
                "③ 点本面板旁边的\"采纳\"即可（列表里没有就选\"其他\"，填下面的地址）",
            textAlign = TextAlign.Center,
            color = RemoteColors.onSurface,
            fontSize = 15.sp,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = if (panelIp.isBlank()) "本面板地址：见 下拉面板 → 网络"
            else "本面板地址：$panelIp  端口 6053",
            color = RemoteColors.accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(26.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = RemoteColors.accent,
            modifier = Modifier.clickable(onClick = onRefresh)
        ) {
            Text(
                text = "我已采纳，刷新",
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.padding(horizontal = 36.dp, vertical = 12.dp)
            )
        }
    }
}