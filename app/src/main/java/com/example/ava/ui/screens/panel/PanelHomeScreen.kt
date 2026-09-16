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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PanelViewModel @Inject constructor(
    panelConfigStore: com.example.ava.panel.PanelConfigStore,
    haStatesStore: HomeAssistantStatesStore,
    private val activityNavigator: ActivityNavigator,
    satelliteStateHolder: SatelliteStateHolder,
) : ViewModel() {
    val layout = panelConfigStore.layout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PanelLayout())
    val haStates = haStatesStore.states
    val currentPage = activityNavigator.currentPage
    val deviceState = satelliteStateHolder.deviceState

    fun selectRoom(title: String) = activityNavigator.setPage(title)
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

/** Human readable card name: alias/name falls back to the entity id. */
fun PanelCard.displayName(): String =
    name.ifBlank {
        primaryEntity?.alias?.ifBlank { null }
            ?: primaryEntity?.entityId?.substringAfter('.')?.replace('_', ' ')
            ?: "设备"
    }

/** One-line state summary for a card, from the imported HA entity states. */
fun cardStateText(card: PanelCard, states: Map<String, com.example.ava.services.HaEntityState>): String {
    val primary = card.primaryEntity ?: return ""
    val state = states[primary.entityId]?.state ?: return ""
    return when (card.type) {
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

    Box(modifier = Modifier.fillMaxSize().background(RemoteBackground)) {
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
                style = MaterialTheme.typography.titleLarge,
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
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(cards, key = { it.cardId }) { card ->
            DeviceCard(
                card = card,
                stateText = cardStateText(card, haStates),
                onClick = { onOpenCard(card) }
            )
        }
    }
}

@Composable
private fun DeviceCard(
    card: PanelCard,
    stateText: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = RemoteColors.surface,
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(RemoteColors.accentContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(cardIconRes(card.type)),
                    contentDescription = card.type,
                    tint = RemoteColors.accent,
                    modifier = Modifier.size(26.dp)
                )
            }
            Column {
                Text(
                    text = card.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RemoteColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (stateText.isNotBlank()) {
                    Text(
                        text = stateText,
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
