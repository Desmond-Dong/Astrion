package com.example.ava.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ava.esphome.infrared.InfraredManager
import com.example.ava.services.ActivityNavigator
import com.example.ava.services.HomeAssistantStatesStore
import com.example.ava.settings.ActivitySettingsStore
import com.example.ava.settings.IrSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the remote-style home screen. Everything shown on the home screen is
 * driven by the same settings stores that feed the ESPHome entities, so the
 * content is fully configurable.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val irSettingsStore: IrSettingsStore,
    val activitySettingsStore: ActivitySettingsStore,
    val playerSettingsStore: PlayerSettingsStore,
    val microphoneSettingsStore: MicrophoneSettingsStore,
    val voiceSatelliteSettingsStore: VoiceSatelliteSettingsStore,
    private val activityNavigator: ActivityNavigator,
    private val haStatesStore: HomeAssistantStatesStore
) : ViewModel() {

    val irDevices = irSettingsStore.irDevices
    val pages = activitySettingsStore.pages
    val volume = playerSettingsStore.volume
    val muted = playerSettingsStore.muted
    val enableWakeSound = playerSettingsStore.enableWakeSound
    val repeatTimerFinishedSound = playerSettingsStore.repeatTimerFinishedSound
    val micMuted = microphoneSettingsStore.muted
    val satelliteName = voiceSatelliteSettingsStore.name
    val currentPage = activityNavigator.currentPage
    val haEntityStates = haStatesStore.states

    private val infraredManager by lazy { InfraredManager(context) }

    /**
     * Transmits a stored IR code packet directly from the home screen, acting
     * like a remote while the ESPHome entities stay in sync in the background.
     */
    fun transmitTimings(carrierFrequencyHz: Int, timings: List<Int>) {
        if (timings.isEmpty()) return
        viewModelScope.launch {
            infraredManager.transmit(carrierFrequencyHz, timings, 1)
        }
    }

    fun setVolume(value: Float) = viewModelScope.launch { volume.set(value) }

    fun setMuted(value: Boolean) = viewModelScope.launch { muted.set(value) }

    fun setEnableWakeSound(value: Boolean) = viewModelScope.launch { enableWakeSound.set(value) }

    fun setRepeatTimerSound(value: Boolean) =
        viewModelScope.launch { repeatTimerFinishedSound.set(value) }

    fun setMicMuted(value: Boolean) = viewModelScope.launch { micMuted.set(value) }

    fun navigateTo(page: String) = activityNavigator.setPage(page)
}