package com.example.ava.ui.screens.panel

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ava.R
import com.example.ava.esphome.Connected
import com.example.ava.panel.PanelCard
import com.example.ava.panel.PanelCardTypes
import com.example.ava.panel.PanelLayout
import com.example.ava.services.ActivityNavigator
import com.example.ava.services.HomeAssistantStatesStore
import com.example.ava.services.SatelliteStateHolder
import com.example.ava.ui.DeviceDetail
import com.example.ava.ui.theme.RemoteBackground
import com.example.ava.ui.theme.RemoteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PanelViewModel @Inject constructor(
    panelConfigStore: com.example.ava.panel.PanelConfigStore,
    haStatesStore: HomeAssistantStatesStore,
    private val activityNavigator: ActivityNavigator,
    private val satelliteStateHolder: SatelliteStateHolder,
    private val microphoneSettingsStore: com.example.ava.settings.MicrophoneSettingsStore,
    private val displaySettingsStore: com.example.ava.settings.DisplaySettingsStore,
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

    fun selectRoom(title: String) = activityNavigator.setPage(title)

    fun setRaiseToWake(enabled: Boolean) {
        viewModelScope.launch {
            displaySettingsStore.raiseToWakeThreshold.set(if (enabled) 4f else 0f)
        }
    }

    fun refreshDevices() = satelliteStateHolder.reconnect()

    fun setMicMuted(muted: Boolean) {
        viewModelScope.launch { micMuted.set(muted) }
    }
}

/** Per-type accent pair for the icon gradient (原版彩色设备图标风格). */
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
fun PanelCard.displayName(states: Map<String, com.example.ava.services.HaEntityState> = emptyMap()): String {
    name.ifBlank { primaryEntity?.alias?.ifBlank { null } }?.let { return it }
    val entityId = primaryEntity?.entityId
    if (entityId != null) {
        states["$entityId.friendly_name"]?.state?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return entityId?.substringAfter('.')?.replace('_', ' ')?.replaceFirstChar { it.uppercase() }
        ?: "设备"
}

/** One-line state summary for a card, from the imported HA entity states. */
fun cardStateText(card: PanelCard, states: Map<String, com.example.ava.services.HaEntityState>): String {
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

    var quickSettingsOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RemoteBackground)
            .topEdgeSwipeToOpen(enabled = !quickSettingsOpen) { quickSettingsOpen = true }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RoomTopBar(
                roomTitle = rooms.getOrNull(pagerState.currentPage)?.title,
                roomTitles = rooms.map { it.title },
                connected = deviceState == Connected,
                onSelectRoom = { title ->
                    viewModel.selectRoom(title)
                }
            )

            if (rooms.isEmpty()) {
                EmptyLayoutHint()
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f)
                ) { page ->
                    val room = rooms.getOrNull(page)
                    RoomCardsGrid(
                        cards = room?.cards ?: emptyList(),
                        haStates = haStates,
                        onOpenCard = { card ->
                            navController.navigate(DeviceDetail(card.cardId))
                        }
                    )
                }
                PageDotsIndicator(
                    pageCount = rooms.size,
                    currentPage = pagerState.currentPage,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

        // 快捷面板画在内容之上，触摸不会被首页拦截
        if (quickSettingsOpen) {
            QuickSettingsPanel(
                connected = deviceState == Connected,
                micMuted = micMuted,
                onMicMutedChanged = { viewModel.setMicMuted(it) },
                raiseToWake = raiseToWake,
                onRaiseToWakeChanged = { viewModel.setRaiseToWake(it) },
                onRefreshDevices = { viewModel.refreshDevices() },
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

@Composable
private fun RoomTopBar(
    roomTitle: String?,
    roomTitles: List<String>,
    connected: Boolean,
    onSelectRoom: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = roomTitles.size > 1) { menuOpen = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = roomTitle ?: "Astrion",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface
            )
            if (roomTitles.size > 1) {
                Spacer(Modifier.width(6.dp))
                Text(text = "▾", color = RemoteColors.onSurfaceVariant, fontSize = 16.sp)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                roomTitles.forEach { title ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = {
                            menuOpen = false
                            onSelectRoom(title)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        // HA connection indicator (original icon_ha_no_connect)
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (connected) RemoteColors.secondary else RemoteColors.error,
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun RoomCardsGrid(
    cards: List<PanelCard>,
    haStates: Map<String, com.example.ava.services.HaEntityState>,
    onOpenCard: (PanelCard) -> Unit,
) {
    // 容错: duplicate keys would crash the grid - keep the first of any dupes
    val uniqueCards = remember(cards) { cards.distinctBy { it.cardId } }
    if (cards.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "此房间还没有设备\n\n请在 Home Assistant 的\n\"Panel Layout\" 实体中配置",
                textAlign = TextAlign.Center,
                color = RemoteColors.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
    val accent = cardAccent(card.resolvedType)
    val isOn = stateText.contains("开启") || stateText.contains("打开") ||
        stateText.contains("播放") || card.resolvedType == PanelCardTypes.SCENE

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = RemoteColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(
                        Brush.linearGradient(accent),
                        RoundedCornerShape(16.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(cardIconRes(card.resolvedType)),
                    contentDescription = card.resolvedType,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = RemoteColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (stateText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (isOn) RemoteColors.secondary else RemoteColors.outline,
                                    shape = CircleShape
                                )
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = stateText.ifBlank { " " },
                        style = MaterialTheme.typography.bodySmall,
                        color = RemoteColors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PageDotsIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    if (pageCount <= 1) return
    Row(
        modifier = modifier.padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 8.dp else 6.dp)
                    .background(
                        color = if (index == currentPage) RemoteColors.accent
                        else RemoteColors.outline,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun EmptyLayoutHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(R.drawable.ic_panel_host),
                contentDescription = null,
                tint = RemoteColors.outline,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "等待 Home Assistant 配置",
                style = MaterialTheme.typography.titleMedium,
                color = RemoteColors.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "在 Home Assistant 中采纳此设备后，\n把布局 JSON 写入 \"Panel Layout\" 实体；\nIR 码库与按键绑定同样通过实体下发。",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = RemoteColors.onSurfaceVariant
            )
        }
    }
}
