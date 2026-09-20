package com.example.astrion.ui.screens.panel

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.example.astrion.panel.KeyPress
import com.example.astrion.panel.KeyRouter
import com.example.astrion.panel.PanelCard
import com.example.astrion.panel.PanelCardTypes
import com.example.astrion.panel.PanelConfigStore
import com.example.astrion.panel.PanelLayout
import com.example.astrion.panel.PanelRoom
import com.example.astrion.panel.ShortcutBinding
import com.example.astrion.panel.ShortcutBindingStore
import com.example.astrion.ui.ShortcutBindRoute
import com.example.astrion.ui.theme.RemoteBackground
import com.example.astrion.ui.theme.RemoteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 快捷键绑定（原版 ShortcutKeyActivity / ShortCutKeyBindActivity 的移植）：
 * 列出可绑定的物理键（F4–F11）与当前绑定，点进去在 设备/场景/房间 里选一个
 * 保存；运行时长按可绑定键直接进入该键的绑定页。
 */
@HiltViewModel
class ShortcutKeysViewModel @Inject constructor(
    panelConfigStore: PanelConfigStore,
    shortcutBindingStore: ShortcutBindingStore,
    haStatesStore: com.example.astrion.services.HomeAssistantStatesStore,
    private val keyRouter: KeyRouter,
) : ViewModel() {
    val bindings = shortcutBindingStore.bindings

    val layout = panelConfigStore.effectiveLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PanelLayout())

    val haStates = haStatesStore.states

    /** 绑定页/列表页打开期间吞掉物理键，避免误触发（原版绑定页 swallow）。 */
    private val swallow: suspend (KeyPress) -> Boolean = { true }

    init {
        keyRouter.pushHandler(swallow)
    }

    override fun onCleared() {
        keyRouter.popHandler(swallow)
        super.onCleared()
    }

    fun bindingLabel(keycode: Int): String {
        val binding = bindings.value[keycode] ?: return "未绑定"
        return when (binding.type) {
            "DeviceRoom" -> "房间 · ${binding.uuid}"
            else -> {
                val cards = layout.value.rooms.flatMap { it.cards }
                // 优先 cardId 精确匹配；旧布局留下的过期 uuid 再按实体 ID 兜底
                val card = cards.firstOrNull { it.cardId == binding.uuid }
                    ?: cards.firstOrNull { c ->
                        val entityId = c.primaryEntity?.entityId
                        entityId != null && binding.uuid.contains(entityId)
                    }
                card?.displayName(haStates.value) ?: "已失效绑定"
            }
        }
    }
}

@Composable
fun ShortcutKeysScreen(
    navController: NavController,
    viewModel: ShortcutKeysViewModel = hiltViewModel(),
) {
    val bindings by viewModel.bindings.collectAsStateWithLifecycle()
    val layout by viewModel.layout.collectAsStateWithLifecycle()

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
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "返回",
                    tint = RemoteColors.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "快捷键绑定",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface
            )
        }
        Text(
            text = "每个键绑定对应类型的设备，按下即可一键直达；长按实体键随时进入该键的绑定页",
            color = RemoteColors.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ShortcutBindingStore.BINDABLE_KEYS.size) { index ->
                val keycode = ShortcutBindingStore.BINDABLE_KEYS[index]
                val bound = bindings.containsKey(keycode)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = RemoteColors.surfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(ShortcutBindRoute(keycode)) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = ShortcutBindingStore.keyDisplayName(keycode),
                            color = RemoteColors.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = viewModel.bindingLabel(keycode),
                            color = if (bound) RemoteColors.accent else RemoteColors.hintText,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.weight(1f))
                        Text("▾", color = RemoteColors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── 绑定页 ─────────────────────────────────────────────────────────────

@HiltViewModel
class ShortcutBindViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    panelConfigStore: PanelConfigStore,
    haStatesStore: com.example.astrion.services.HomeAssistantStatesStore,
    private val shortcutBindingStore: ShortcutBindingStore,
    private val keyRouter: KeyRouter,
) : ViewModel() {
    val keyCode = savedStateHandle.toRoute<ShortcutBindRoute>().keyCode

    val layout = panelConfigStore.effectiveLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PanelLayout())

    val haStates = haStatesStore.states

    private val swallow: suspend (KeyPress) -> Boolean = { true }

    init {
        keyRouter.pushHandler(swallow)
    }

    override fun onCleared() {
        keyRouter.popHandler(swallow)
        super.onCleared()
    }

    fun currentBinding(): ShortcutBinding? = shortcutBindingStore.get(keyCode)

    /** 保存；binding=null 即解除绑定（原版：不选中直接保存 = 解绑）。 */
    fun save(binding: ShortcutBinding?) = shortcutBindingStore.set(keyCode, binding)
}

private enum class BindTab(val label: String) { DEVICE("设备"), SCENE("场景"), ROOM("房间") }

@Composable
fun ShortcutBindScreen(
    navController: NavController,
    keyCode: Int,
    viewModel: ShortcutBindViewModel = hiltViewModel(),
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val haStates by viewModel.haStates.collectAsStateWithLifecycle()
    val current = remember { viewModel.currentBinding() }
    // 任意键都能绑：设备（全部同步设备）/ 场景 / 房间
    var tab by remember {
        mutableStateOf(if (current?.type == "DeviceRoom") BindTab.ROOM else BindTab.DEVICE)
    }
    // 选中的绑定；null = 未选（保存即解绑，原版行为）
    var selected by remember { mutableStateOf(current) }

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
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "返回",
                    tint = RemoteColors.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "${ShortcutBindingStore.keyDisplayName(keyCode)} 键绑定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = RemoteColors.onSurface
                )
                Text(
                    text = "绑定一台同步到面板的设备、场景或房间",
                    color = RemoteColors.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BindTab.entries.forEach { entry ->
                val isSel = entry == tab
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSel) RemoteColors.accent else RemoteColors.key,
                    modifier = Modifier.clickable { tab = entry }
                ) {
                    Text(
                        text = entry.label,
                        color = if (isSel) Color.White else RemoteColors.onSurface,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (tab) {
                BindTab.ROOM -> items(layout.rooms.size) { index ->
                    val room = layout.rooms[index]
                    BindRow(
                        title = room.title,
                        subtitle = "房间",
                        selected = selected?.type == "DeviceRoom" && selected?.uuid == room.title,
                        onClick = {
                            selected = toggleSelection(selected, "DeviceRoom", room.title)
                        }
                    )
                }

                BindTab.SCENE -> layout.rooms.forEach { room ->
                    val cards = room.cards.filter { it.resolvedType == PanelCardTypes.SCENE }
                    if (cards.isNotEmpty()) {
                        item(key = "${room.title}-scene-header") { RoomHeader(room) }
                        items(cards.size, key = { cards[it].cardId }) { index ->
                            val card = cards[index]
                            BindRow(
                                title = card.displayName(haStates),
                                subtitle = "场景",
                                selected = selected?.type == "Device" && selected?.uuid == card.cardId,
                                onClick = {
                                    selected = toggleSelection(selected, "Device", card.cardId)
                                }
                            )
                        }
                    }
                }

                BindTab.DEVICE -> layout.rooms.forEach { room ->
                    // 任意键都能绑同步到面板的任意设备（场景在"场景"页签）
                    val cards = room.cards.filter { it.resolvedType != PanelCardTypes.SCENE }
                    if (cards.isNotEmpty()) {
                        item(key = "${room.title}-device-header") { RoomHeader(room) }
                        items(cards.size, key = { cards[it].cardId }) { index ->
                            val card = cards[index]
                            BindRow(
                                title = card.displayName(haStates),
                                subtitle = deviceTypeLabel(card),
                                selected = selected?.type == "Device" && selected?.uuid == card.cardId,
                                onClick = {
                                    selected = toggleSelection(selected, "Device", card.cardId)
                                }
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = "不选中直接保存 = 解除绑定",
            color = RemoteColors.hintText,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = RemoteColors.accent,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    viewModel.save(selected)
                    navController.popBackStack()
                }
        ) {
            Text(
                text = "保存",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 12.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** 原版 ShortcutKeySelectionManager：再点一次取消选中。 */
private fun toggleSelection(
    current: ShortcutBinding?,
    type: String,
    uuid: String,
): ShortcutBinding? =
    if (current?.type == type && current.uuid == uuid) null
    else ShortcutBinding(type = type, uuid = uuid)

@Composable
private fun RoomHeader(room: PanelRoom) {
    Text(
        text = room.title,
        color = RemoteColors.onSurfaceVariant,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun BindRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) RemoteColors.accentContainer else RemoteColors.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, RemoteColors.accent) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = RemoteColors.onSurface,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    color = RemoteColors.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (selected) {
                Text(
                    text = "✓",
                    color = RemoteColors.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(RemoteColors.key, RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

private fun deviceTypeLabel(card: PanelCard): String = when (card.resolvedType) {
    PanelCardTypes.LIGHT -> "灯"
    PanelCardTypes.CLIMATE -> "空调"
    PanelCardTypes.FAN -> "风扇"
    PanelCardTypes.COVER -> "窗帘"
    PanelCardTypes.TV -> "电视"
    PanelCardTypes.MEDIA_PLAYER -> "媒体"
    PanelCardTypes.SWITCH -> "开关"
    PanelCardTypes.SCENE -> "场景"
    else -> "设备"
}
