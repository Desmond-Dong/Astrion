package com.example.astrion.ui.screens.panel

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.example.astrion.ha.HaConnectionState
import com.example.astrion.ha.HaPanelBridge
import com.example.astrion.panel.PanelCard
import com.example.astrion.panel.PanelCardTypes
import com.example.astrion.panel.PanelLayout
import com.example.astrion.services.ActivityNavigator
import com.example.astrion.services.HomeAssistantStatesStore
import com.example.astrion.ui.DeviceDetail
import com.example.astrion.ui.HaSettingsRoute
import com.example.astrion.ui.ShortcutKeysRoute
import com.example.astrion.ui.weatherEmoji
import com.example.astrion.ui.weatherLabel
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
    private val panelBridge: HaPanelBridge,
    private val displaySettingsStore: com.example.astrion.settings.DisplaySettingsStore,
    private val screenOffController: com.example.astrion.services.ScreenOffController,
    private val cardController: com.example.astrion.panel.CardController,
) : ViewModel() {
    val layout = panelConfigStore.layout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PanelLayout())
    val haStates = haStatesStore.states
    val currentPage = activityNavigator.currentPage
    val connectionState = panelBridge.state
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

    fun refreshDevices() = panelBridge.reconnect()

    fun screenOff() = screenOffController.turnScreenOffNow()

    /** 原版"点卡即控"范式：风扇/开关点卡直接切换电源，场景点卡执行（SceneItem/
     *  FanItem/SwitchItem 的 onClick 均 sendCommand，详情是次要入口）。 */
    fun toggleCardPower(card: PanelCard) {
        viewModelScope.launch {
            cardController.handleDeviceKey(card, 132, longPress = false)
        }
    }

    /** 场景点卡执行（原版 executeByMode 缺省 immediate）。成功后回调 UI 反馈。 */
    fun executeScene(card: PanelCard, onExecuted: () -> Unit) {
        viewModelScope.launch {
            cardController.turnOn(card.primaryEntity?.entityId ?: return@launch)
            onExecuted()
        }
    }

    /** 原版监控开关卡全关（keyOpenALLCloseSelectList）：对一类设备逐个发关闭。
     *  范围按集成 device_types 限定（域名字符串）。 */
    fun allOff(monitor: PanelCard, type: String) {
        val wantedDomains = type.substringBefore('.') // 全关目标域（light/switch/climate）
        val entities = layout.value.rooms.flatMap { it.cards }
            .filter { c ->
                val domain = c.primaryEntity?.entityId?.substringBefore('.')
                domain == wantedDomains &&
                    (monitor.deviceTypes.isEmpty() || domain in monitor.deviceTypes)
            }
            .mapNotNull { it.primaryEntity?.entityId }
        viewModelScope.launch {
            entities.forEach { entityId ->
                when (type) {
                    PanelCardTypes.CLIMATE ->
                        cardController.climateSetHvacMode(entityId, "off")
                    else ->
                        cardController.turnOff(entityId)
                }
                delay(100) // 原版 100ms/台 间隔发送
            }
        }
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

        PanelCardTypes.WEATHER -> {
            if (state == "unavailable") return "不可用"
            val unit = states["${primary.entityId}.temperature_unit"]?.state ?: "°C"
            val temp = states["${primary.entityId}.temperature"]?.state
                ?.toDoubleOrNull()?.let { "%.0f".format(it) }
            return listOfNotNull(temp?.let { "$it$unit" }, weatherLabel(state))
                .joinToString(" · ")
        }

        PanelCardTypes.MEDIA_PLAYER -> {
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
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val raiseToWake by viewModel.raiseToWake.collectAsStateWithLifecycle()
    val screenSaverTimeout by viewModel.screenSaverTimeout.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val rooms = remember(layout) { layout.rooms }
    // 官方原版行为：所有设备同页显示（不分房间分页），一页纵向滚动网格
    val allCards = remember(rooms) { rooms.flatMap { it.cards } }

    val connected = connectionState == HaConnectionState.Connected
    var quickSettingsOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .topEdgeSwipeToOpen(enabled = !quickSettingsOpen) { quickSettingsOpen = true }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 原版 home_activity：IndexTopView(状态/时间条) + 下拉把手 + WiFi 提示 + 房间名条
            TimeTopBar(connected = connected)
            SwipeHandle()
            if (!connected) WifiHintRow()
            HomeTitleBar()

            if (rooms.isEmpty()) {
                val pairingUrl = if (!connected) {
                    com.example.astrion.utils.getLocalIpAddress()
                        ?.let { "http://$it:${com.example.astrion.ha.HaEnrollServer.PORT}" }
                } else {
                    null
                }
                EmptyLayoutHint(pairingUrl = pairingUrl, onRefresh = { viewModel.refreshDevices() })
            } else {
                RoomDeviceList(
                    cards = allCards,
                    haStates = haStates,
                    onOpenCard = { card -> navController.navigate(DeviceDetail(card.cardId)) },
                    onTogglePower = { card -> viewModel.toggleCardPower(card) },
                    onExecuteScene = { card, done -> viewModel.executeScene(card, done) },
                    onAllOff = { card, type -> viewModel.allOff(card, type) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 快捷面板画在内容之上，触摸不会被首页拦截
        if (quickSettingsOpen) {
            QuickSettingsPanel(
                connected = connected,
                raiseToWake = raiseToWake,
                onRaiseToWakeChanged = { viewModel.setRaiseToWake(it) },
                screenSaverTimeout = screenSaverTimeout,
                onScreenSaverTimeoutChanged = { viewModel.setScreenSaverTimeout(it) },
                onRefreshDevices = { viewModel.refreshDevices() },
                onScreenOff = { viewModel.screenOff() },
                onOpenShortcutKeys = { navController.navigate(ShortcutKeysRoute) },
                onOpenConnectionSettings = { navController.navigate(HaSettingsRoute) },
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

/** 原生 IndexTopView(view_top_view) 等价：左 WiFi 指示、中间 14sp 时间、右侧电量。
 *  时间 HH:mm 24 小时制；电量非 null 时显示电池盒 + 百分比。 */
@Composable
private fun TimeTopBar(connected: Boolean) {
    val time = remember { mutableStateOf("") }
    val battery = rememberBatteryLevel()
    LaunchedEffect(Unit) {
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            time.value = timeFmt.format(Date())
            delay(30_000)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 15.dp) // 原版 IndexTopView marginTop 15
            .height(25.dp), // 原版高度 25dp
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        color = if (connected) RemoteColors.secondary else RemoteColors.error,
                        shape = CircleShape
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (connected) "WiFi" else "无网",
                color = RemoteColors.onSurfaceVariant,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = time.value,
                color = RemoteColors.topBarText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (battery != null) {
                // 原版 BatteryView 27×13（描边 2px + 电极）
                Box(
                    modifier = Modifier
                        .size(width = 27.dp, height = 13.dp)
                        .border(2.dp, RemoteColors.topBarText, RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((battery.toFloat() / 100f).coerceIn(0f, 1f))
                            .padding(horizontal = 1.dp)
                            .background(RemoteColors.topBarText)
                            .height(7.dp)
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(text = "$battery%", color = RemoteColors.topBarText, fontSize = 12.sp)
            }
        }
    }
}

/** 原版下拉把手 v1：45×3dp 圆角条，marginTop 3，居中。 */
@Composable
private fun SwipeHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 45.dp, height = 3.dp)
                .background(RemoteColors.outline, RoundedCornerShape(2.dp))
        )
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

/** 原生居中标题条 rlViewLayout(30dp) + 原版字号 23sp #E6CCCBCB。
 *  官方原版为房间选择器；本项目所有设备同页显示，固定标题"所有设备"。 */
@Composable
private fun HomeTitleBar() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "所有设备",
            color = RemoteColors.roomName,
            fontSize = 23.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 原版设备列表：单列纵向（LinearLayoutManager 15dp 间距/边距）。 */
@Composable
private fun RoomDeviceList(
    cards: List<PanelCard>,
    haStates: Map<String, com.example.astrion.services.HaEntityState>,
    onOpenCard: (PanelCard) -> Unit,
    onTogglePower: (PanelCard) -> Unit,
    onExecuteScene: (PanelCard, () -> Unit) -> Unit,
    onAllOff: (PanelCard, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // 容错: duplicate keys would crash the grid - keep the first of any dupes
    val uniqueCards = remember(cards) { cards.distinctBy { it.cardId } }
    // 原版监控开关卡：空调/灯/开关 三分区计数（StatisticsDevice isShow* 分区）
    var monitorTarget by remember { mutableStateOf<PanelCard?>(null) }
    if (monitorTarget != null) {
        AllOffDialog(
            monitor = monitorTarget!!,
            cards = uniqueCards,
            haStates = haStates,
            onAllOff = { category ->
                onAllOff(monitorTarget!!, category)
                monitorTarget = null
            },
            onDismiss = { monitorTarget = null }
        )
    }
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
                Text(text = "暂无设备", color = RemoteColors.hintText, fontSize = 15.sp)
            }
        }
        return
    }
    // 原版设备列表：单列纵向（LinearLayoutManager）、item 间距 15dp、
    // 左右 padding 15dp（layout_device_list + SpaceItemDecoration(15)）
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 15.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)
    ) {
        uniqueCards.forEach { card ->
            val entityId = card.primaryEntity?.entityId
            // 原版各卡片底部信息行：AC=温度+模式、灯光=亮度%、音乐=音量%
            val bottomInfo = when (card.resolvedType) {
                PanelCardTypes.CLIMATE -> {
                    val temp = entityId
                        ?.let { haStates["$it.temperature"]?.state }
                        ?.toDoubleOrNull()?.let { "%.0f".format(it) }
                    val mode = entityId
                        ?.let { haStates[it]?.state }
                        ?.let { acModeLabel(it) }
                    listOfNotNull(temp?.let { "$it℃" }, mode).joinToString(" · ")
                        .ifBlank { null }
                }

                PanelCardTypes.LIGHT -> entityId
                    ?.let { haStates["$it.brightness"]?.state?.toFloatOrNull() }
                    ?.let { "亮度 ${((it / 2.55f).toInt().coerceIn(0, 100))}%" }

                PanelCardTypes.MEDIA_PLAYER -> entityId
                    ?.let { haStates["$it.volume_level"]?.state?.toFloatOrNull() }
                    ?.let { "音量 ${((it * 100).toInt().coerceIn(0, 100))}%" }

                // 原版监控开关卡三分区计数（StatisticsDevice isShowAc/Light/Switch，
                // 范围按集成 device_types 过滤）
                PanelCardTypes.SWITCH_MONITOR -> {
                    val shown = card.deviceTypes.ifEmpty {
                        listOf("climate", "light", "switch")
                    }
                    fun onCount(domain: String) = uniqueCards.count {
                        it.primaryEntity?.entityId?.substringBefore('.') == domain &&
                            it.primaryEntity?.entityId?.let { id -> haStates[id]?.state } == "on"
                    }
                    shown.mapNotNull { domain ->
                        val label = when (domain) {
                            "climate" -> "空调"
                            "light" -> "灯"
                            "switch" -> "开关"
                            else -> return@mapNotNull null
                        }
                        "${label} ${onCount(domain)}"
                    }.joinToString("  ").ifBlank { null }
                }

                else -> null
            }
            // 原版"点卡即控"：风扇/开关点卡切换电源、场景点卡执行（均无详情页
            // 或详情为次要入口，⋮/右侧热区才是进详情）；其它类型点卡进详情
            val directControl = card.resolvedType in setOf(
                PanelCardTypes.FAN, PanelCardTypes.SWITCH, PanelCardTypes.SCENE
            )
            var sceneExecuted by remember(card.cardId) { mutableStateOf(false) }
            // 原版 cSExecute 成功动画 2s 后隐藏
            LaunchedEffect(sceneExecuted) {
                if (sceneExecuted) {
                    delay(2000)
                    sceneExecuted = false
                }
            }
            // 原版 delayed 模式：3s 倒计时（触摸/按键取消），走完才执行
            var sceneCountdown by remember(card.cardId) { mutableStateOf<Int?>(null) }
            LaunchedEffect(sceneCountdown) {
                var left = sceneCountdown ?: return@LaunchedEffect
                while (left > 0) {
                    delay(1000)
                    left -= 1
                    sceneCountdown = left
                }
                if (left == 0 && sceneCountdown != null) {
                    sceneCountdown = null
                    onExecuteScene(card) { sceneExecuted = true }
                }
            }
            // 原版 popup 模式：确认弹窗
            var sceneConfirm by remember(card.cardId) { mutableStateOf(false) }
            if (sceneConfirm) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { sceneConfirm = false },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            sceneConfirm = false
                            onExecuteScene(card) { sceneExecuted = true }
                        }) { Text("执行", color = RemoteColors.accent) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { sceneConfirm = false }) {
                            Text("取消", color = RemoteColors.onSurfaceVariant)
                        }
                    },
                    containerColor = RemoteColors.popupBackground,
                    title = {
                        Text(
                            text = "是否执行\"${card.displayName(haStates)}\"场景？",
                            color = RemoteColors.onSurface,
                            fontSize = 16.sp
                        )
                    }
                )
            }
            // 原版 delayed 模式的 3s 进度反馈（卡片中央倒计时）
            val countdownText = sceneCountdown?.takeIf { it > 0 }
            DeviceCard(
                card = card,
                name = card.displayName(haStates),
                stateText = cardStateText(card, haStates),
                primaryState = entityId?.let { haStates[it]?.state } ?: "",
                bottomInfo = bottomInfo,
                isBlind = card.curtainType == "blind" ||
                    // 集成未配置时按 tilt 能力兜底
                    (card.curtainType.isBlank() && card.primaryEntity?.entityId
                        ?.let { haStates["$it.current_tilt_position"] != null } == true),
                countdownText = countdownText,
                sceneExecuted = sceneExecuted,
                emoji = if (card.resolvedType == PanelCardTypes.WEATHER) {
                    card.primaryEntity?.entityId
                        ?.let { haStates[it]?.state }
                        ?.let { weatherEmoji(it) }
                } else {
                    null
                },
                onClick = {
                    when {
                        // 原版 MonitorSwitchItem：点击弹全关选择（灯/开关/空调）
                        card.resolvedType == PanelCardTypes.SWITCH_MONITOR ->
                            monitorTarget = card

                        card.resolvedType == PanelCardTypes.FAN ||
                            card.resolvedType == PanelCardTypes.SWITCH ->
                            onTogglePower(card)

                        // 原版 executeByMode：immediate（缺省）立即执行 /
                        // delayed 3s 倒计时（触摸取消）/ popup 确认弹窗
                        card.resolvedType == PanelCardTypes.SCENE -> when (card.mode) {
                            "delayed" -> {
                                if (sceneCountdown == null) sceneCountdown = 3 else {
                                    sceneCountdown = null // 再点=取消（原版触摸取消）
                                }
                            }

                            "popup" -> sceneConfirm = true

                            else -> onExecuteScene(card) {
                                sceneExecuted = true // 原版 cSExecute 成功动画 2s
                            }
                        }

                        else -> onOpenCard(card)
                    }
                },
                onDotsClick = if (directControl) {
                    { onOpenCard(card) } // 原版 viewEnterDetail/⋮ 进详情
                } else {
                    null
                }
            )
        }
    }
}

/**
 * 原版 MonitorSwitchItem 的全关选择弹窗（keyOpenALLCloseSelectList +
 * CustomListDialog）：选灯/开关/空调 全关，对该类所有设备逐个发关闭命令。
 */
@Composable
private fun AllOffDialog(
    monitor: PanelCard,
    cards: List<PanelCard>,
    haStates: Map<String, com.example.astrion.services.HaEntityState>,
    onAllOff: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 各类别在当前布局中的设备（点击发全关）；范围按集成 device_types 限定
    //（域名字符串：light/switch/climate…），未配置时默认三类
    val wanted = monitor.deviceTypes.ifEmpty { listOf("light", "switch", "climate") }
    fun entitiesOf(domain: String) = cards.filter {
        it.primaryEntity?.entityId?.substringBefore('.') == domain
    }.mapNotNull { it.primaryEntity?.entityId }
    val options = buildList {
        wanted.forEach { domain ->
            if (entitiesOf(domain).isEmpty()) return@forEach
            when (domain) {
                "light" -> add("灯全部关闭" to "light")
                "switch" -> add("开关全部关闭" to "switch")
                "climate" -> add("空调全部关闭" to "climate")
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        containerColor = RemoteColors.popupBackground,
        title = { Text(monitor.displayName(haStates), color = RemoteColors.onSurface, fontSize = 18.sp) },
        text = {
            Column {
                options.forEachIndexed { index, (label, type) ->
                    Text(
                        text = label,
                        color = RemoteColors.onSurface,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAllOff(type) }
                            .padding(vertical = 14.dp)
                    )
                    if (index < options.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(RemoteColors.popupLine)
                        )
                    }
                }
            }
        }
    )
}

/** 空调模式中文标签（原版 HvacAndPresetMode.getLanguageString 简表）。 */
private fun acModeLabel(state: String): String = when (state) {
    "off" -> "关机"
    "auto" -> "自动"
    "cool" -> "制冷"
    "heat" -> "制热"
    "dry" -> "除湿"
    "fan_only" -> "送风"
    "heat_cool" -> "智能"
    else -> state
}

@Composable
private fun DeviceCard(
    card: PanelCard,
    name: String,
    stateText: String,
    primaryState: String,
    bottomInfo: String? = null,
    isBlind: Boolean = false,
    sceneExecuted: Boolean = false,
    countdownText: Int? = null,
    emoji: String? = null,
    onClick: () -> Unit,
    onDotsClick: (() -> Unit)? = null,
) {
    // 原版 index_device_*_item：无卡片盒子，居中 50dp 原版彩色状态图标
    // (alpha 0.8) + 下方 #BFBDBD 名称 + 左侧 4dp 白点表示开机 + 右上竖三点
    // 信息入口 + 底部信息行（AC 温度/模式、灯光亮度%、音乐音量%）。
    // 白点仅开机状态可见的类型有（开关/场景/天气卡原版恒不显示）；
    // ⋮ 仅可进详情的类型显示（SwitchItem 显式 GONE、场景无 viewEnterDetail）。
    // 天气卡片用 emoji 实况图标替代图标位，状态行显示 温度·天气。
    // 百叶窗（cover 带 current_tilt_position）用原版 blind 专属图标；
    // 卡片可配 hide_name/hide_icon 隐藏名称/图标（原版 list_elements）。
    val isOn = primaryState == "on" || primaryState == "open" || primaryState == "playing" ||
        primaryState == "heat" || primaryState == "cool" || primaryState == "auto" ||
        primaryState == "dry" || primaryState == "fan_only" || primaryState == "heat_cool"
    val isOffline = stateText.contains("不可用") || stateText.contains("unavailable")
    val iconRes = stateIconRes(card.resolvedType, isOn, isBlind)
    // 原版有开机白点的卡片类型
    val showDot = isOn && card.resolvedType in setOf(
        PanelCardTypes.CLIMATE, PanelCardTypes.LIGHT, PanelCardTypes.COVER,
        PanelCardTypes.FAN, PanelCardTypes.TV, PanelCardTypes.MEDIA_PLAYER
    )

    // 原版 TV 焦点导航：物理键上下在设备卡间移动焦点，焦点卡画 2px 描边
    // （device_button_focuses_onclick_border，CardAppearanceHelper）
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(155.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .let {
                if (isFocused) it.border(
                    width = 2.dp,
                    color = Color.White.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(12.dp)
                ) else it
            }
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!card.hideIcon) {
                if (emoji != null) {
                    Text(
                        text = emoji,
                        fontSize = 44.sp,
                        modifier = Modifier.height(56.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                } else {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = card.resolvedType,
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(50.dp)
                            .alpha(0.8f)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (!card.hideName) {
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
            // 原版 tvDeviceOffLine：位于图标+名称块下方（非覆盖中心）
            if (isOffline) {
                Text(
                    text = "设备已离线",
                    color = Color(0xFFBFBDBD),
                    fontSize = 15.sp
                )
            }
            if (card.resolvedType == PanelCardTypes.WEATHER && !isOffline) {
                Text(
                    text = stateText,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (showDot) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 13.dp, bottom = 5.dp)
                    .background(Color.White, CircleShape)
            )
        }
        // 原版右上角竖三点信息入口（incDeviceDetail：marginTop 5 / marginEnd 8）；
        // 开关/场景卡原版无此入口（SwitchItem GONE、场景无详情），点卡即控的
        // 风扇/开关 ⋮ 是进详情的热区
        if (onDotsClick != null) {
            Text(
                text = "⋮",
                color = Color(0xFFBFBDBD),
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 8.dp)
                    .clickable(onClick = onDotsClick)
            )
        }
        // 原版场景卡 cSExecute：右上 35dp 成功态动画 2s（此处简化为对勾角标）
        if (sceneExecuted) {
            Text(
                text = "✓",
                color = Color(0xFF27D343),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 10.dp)
            )
        }
        // 原版 delayed 模式：卡片中央倒计时（进度条语义简化为数字）
        if (countdownText != null) {
            Text(
                text = "$countdownText",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (bottomInfo != null && !isOffline) {
            // 原版底部信息行：30dp 高、marginBottom 8 / marginEnd 15、10sp
            Text(
                text = bottomInfo,
                color = Color(0xFFBFBDBD),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 8.dp, end = 15.dp)
            )
        }
    }
}

/** 原版两态图标：开=彩色 on 图标，关=灰色 off 图标。
 *  百叶窗（原版 curtain_interface_type=blind）有专属图标，这里以
 *  cover 实体带 current_tilt_position 属性识别。 */
private fun stateIconRes(type: String, isOn: Boolean, isBlind: Boolean = false): Int {
    val pair = when (type) {
        PanelCardTypes.LIGHT -> R.drawable.ic_state_light_on to R.drawable.ic_state_light_off
        PanelCardTypes.CLIMATE -> R.drawable.ic_state_climate_on to R.drawable.ic_state_climate_off
        PanelCardTypes.COVER ->
            if (isBlind) R.drawable.ic_state_blind_on to R.drawable.ic_state_blind_off
            else R.drawable.ic_state_cover_on to R.drawable.ic_state_cover_off
        PanelCardTypes.FAN -> R.drawable.ic_state_fan_on to R.drawable.ic_state_fan_off
        PanelCardTypes.MEDIA_PLAYER -> R.drawable.ic_state_media_on to R.drawable.ic_state_media_off
        PanelCardTypes.SWITCH -> R.drawable.ic_state_switch_on to R.drawable.ic_state_switch_off
        PanelCardTypes.TV -> R.drawable.ic_state_tv_on to R.drawable.ic_state_tv_off
        else -> R.drawable.ic_state_default to R.drawable.ic_state_default
    }
    return if (isOn) pair.first else pair.second
}

/** 原版空态 rlDeviceEmpty：110dp 图标 + "暂无设备"(22sp 白) + 指引(18sp 白) +
 *  150×50 刷新钮(18sp 白字) + 底部金色扫码提示。 */
@Composable
private fun EmptyLayoutHint(pairingUrl: String?, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(100.dp)) // 原版 rlDeviceEmpty 内容 marginTop 100
        Icon(
            painter = painterResource(R.drawable.ic_panel_host),
            contentDescription = null,
            tint = Color(0xFF2A2A2C),
            modifier = Modifier.size(110.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(text = "暂无设备", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = buildString {
                if (pairingUrl != null) {
                    append("① 手机/电脑浏览器打开\n")
                    append("$pairingUrl\n")
                    append("② 在网页里粘贴 HA 地址与访问令牌，提交后面板自动连接\n")
                    append("③ 也可下拉 → 连接设置 手动填写")
                } else {
                    append("已连接 Home Assistant，但集成里还没有分类卡片。\n")
                    append("在 HA「Astrion Remote」集成页面添加子条目并勾选设备，\n")
                    append("面板几秒内自动刷新。")
                }
            },
            textAlign = TextAlign.Center,
            color = Color.White,
            fontSize = 18.sp,
            lineHeight = 26.sp
        )
        Spacer(Modifier.height(40.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Transparent,
            border = BorderStroke(1.5.dp, RemoteColors.accent),
            modifier = Modifier
                .size(width = 150.dp, height = 50.dp)
                .clickable(onClick = onRefresh)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = "刷新", color = Color.White, fontSize = 18.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = "扫码看说明书[可扫码查看说明书]",
            color = RemoteColors.accent,
            fontSize = 15.sp,
            modifier = Modifier.padding(bottom = 30.dp)
        )
        Spacer(Modifier.height(10.dp))
    }
}