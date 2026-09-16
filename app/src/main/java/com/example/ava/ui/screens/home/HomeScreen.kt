package com.example.ava.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.example.ava.R
import com.example.ava.settings.IrDeviceSettings
import com.example.ava.ui.Settings
import com.example.ava.ui.services.StartStopVoiceSatellite
import com.example.ava.ui.services.components.timerListSection
import com.example.ava.ui.services.components.timerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val devices by viewModel.irDevices.collectAsStateWithLifecycle(emptyList())
    val enabledDevices = remember(devices) { devices.filter { it.enabled } }
    val pages by viewModel.pages.collectAsStateWithLifecycle(emptyList())
    val volume by viewModel.volume.collectAsStateWithLifecycle(1f)
    val muted by viewModel.muted.collectAsStateWithLifecycle(false)
    val wakeSound by viewModel.enableWakeSound.collectAsStateWithLifecycle(true)
    val repeatSound by viewModel.repeatTimerFinishedSound.collectAsStateWithLifecycle(true)
    val micMuted by viewModel.micMuted.collectAsStateWithLifecycle(false)
    val satelliteName by viewModel.satelliteName.collectAsStateWithLifecycle("")
    val timerState = timerState()
    val hasTimers = timerState.timers.isNotEmpty()

    val activityPages = remember(enabledDevices, pages) {
        (enabledDevices.map { it.name } + pages).distinct()
    }
    var selectedPage by rememberSaveable(activityPages) {
        mutableStateOf(activityPages.firstOrNull() ?: "")
    }
    val totalButtons = remember(enabledDevices) {
        enabledDevices.sumOf { it.buttons.size }
    }

    RemoteTheme {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = satelliteName.ifBlank { stringResource(R.string.app_name) },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(
                                    R.string.home_subtitle_devices_buttons_pages,
                                    enabledDevices.size,
                                    totalButtons,
                                    activityPages.size
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Settings) }) {
                            Icon(
                                painter = painterResource(R.drawable.settings_24px),
                                contentDescription = stringResource(R.string.label_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                RemoteColors.backgroundTop,
                                RemoteColors.backgroundBottom
                            )
                        )
                    )
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        StartStopVoiceSatellite()
                    }
                    if (hasTimers) {
                        timerListSection(state = timerState)
                    }
                    if (activityPages.isNotEmpty()) {
                        item {
                            ActivityPagesRow(
                                pages = activityPages,
                                selected = selectedPage,
                                onSelect = { selectedPage = it }
                            )
                        }
                    }
                    item {
                        MediaPlayerCard(
                            volume = volume,
                            muted = muted,
                            wakeSound = wakeSound,
                            repeatSound = repeatSound,
                            onVolumeChanged = { viewModel.setVolume(it) },
                            onMutedChanged = { viewModel.setMuted(it) },
                            onWakeSoundChanged = { viewModel.setEnableWakeSound(it) },
                            onRepeatSoundChanged = { viewModel.setRepeatTimerSound(it) }
                        )
                    }
                    item {
                        MicrophoneCard(
                            micMuted = micMuted,
                            onMicMutedChanged = { viewModel.setMicMuted(it) }
                        )
                    }
                    items(enabledDevices, key = { it.objectId ?: it.name }) { device ->
                        DeviceCard(
                            device = device,
                            onPress = { timings -> viewModel.transmitTimings(timings) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Local dark "remote" color scheme that gives the home screen a consistent
 * premium look regardless of the system theme.
 */
@Composable
private fun RemoteTheme(content: @Composable () -> Unit) {
    val remoteScheme = androidx.compose.material3.darkColorScheme(
        primary = RemoteColors.accent,
        onPrimary = Color.White,
        primaryContainer = RemoteColors.accentContainer,
        onPrimaryContainer = RemoteColors.onSurface,
        secondary = RemoteColors.secondary,
        onSecondary = Color(0xFF06281B),
        secondaryContainer = RemoteColors.surfaceVariant,
        onSecondaryContainer = RemoteColors.onSurface,
        error = RemoteColors.error,
        onError = Color.White,
        errorContainer = Color(0xFF7A1F1F),
        onErrorContainer = Color(0xFFFFDAD6),
        background = RemoteColors.backgroundTop,
        onBackground = RemoteColors.onSurface,
        surface = RemoteColors.surface,
        onSurface = RemoteColors.onSurface,
        surfaceVariant = RemoteColors.surfaceVariant,
        onSurfaceVariant = RemoteColors.onSurfaceVariant,
        outline = RemoteColors.outline,
        outlineVariant = RemoteColors.outline,
        surfaceContainerLowest = RemoteColors.backgroundTop,
        surfaceContainerLow = RemoteColors.surface,
        surfaceContainer = RemoteColors.surface,
        surfaceContainerHigh = RemoteColors.surfaceVariant,
        surfaceContainerHighest = RemoteColors.surfaceVariant
    )
    MaterialTheme(colorScheme = remoteScheme, content = content)
}

private object RemoteColors {
    val backgroundTop = Color(0xFF0B1220)
    val backgroundBottom = Color(0xFF152238)
    val surface = Color(0xFF1E2A42)
    val surfaceVariant = Color(0xFF27364F)
    val accent = Color(0xFF3D8BFF)
    val accentContainer = Color(0xFF16325A)
    val onSurface = Color(0xFFE6ECF7)
    val onSurfaceVariant = Color(0xFFA9B8D0)
    val outline = Color(0xFF3A4A68)
    val secondary = Color(0xFF37E0A0)
    val error = Color(0xFFFF6B6B)
}

@Composable
private fun ActivityPagesRow(
    pages: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(pages) { page ->
            FilterChip(
                selected = page == selected,
                onClick = { onSelect(page) },
                label = {
                    Text(
                        text = page,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    containerColor = MaterialTheme.colorScheme.surface,
                    labelColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun MediaPlayerCard(
    volume: Float,
    muted: Boolean,
    wakeSound: Boolean,
    repeatSound: Boolean,
    onVolumeChanged: (Float) -> Unit,
    onMutedChanged: (Boolean) -> Unit,
    onWakeSoundChanged: (Boolean) -> Unit,
    onRepeatSoundChanged: (Boolean) -> Unit
) {
    HomeCard {
        Text(
            text = stringResource(R.string.home_section_media_player),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Slider(
            value = volume,
            onValueChange = onVolumeChanged,
            valueRange = 0f..1f
        )
        ToggleRow(
            label = stringResource(R.string.home_toggle_muted),
            checked = muted,
            onCheckedChange = onMutedChanged
        )
        ToggleRow(
            label = stringResource(R.string.home_toggle_wake_sound),
            checked = wakeSound,
            onCheckedChange = onWakeSoundChanged
        )
        ToggleRow(
            label = stringResource(R.string.home_toggle_repeat_timer_sound),
            checked = repeatSound,
            onCheckedChange = onRepeatSoundChanged
        )
    }
}

@Composable
private fun MicrophoneCard(
    micMuted: Boolean,
    onMicMutedChanged: (Boolean) -> Unit
) {
    HomeCard {
        ToggleRow(
            label = stringResource(R.string.home_toggle_mic_muted),
            checked = micMuted,
            onCheckedChange = onMicMutedChanged
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(
    device: IrDeviceSettings,
    onPress: (List<Int>) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = device.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (device.buttons.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_device_no_buttons),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    device.buttons.forEach { (buttonName, timings) ->
                        RemoteButton(
                            label = buttonName,
                            onClick = { onPress(timings) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(2.dp)
        )
    }
}

@Composable
private fun HomeCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}