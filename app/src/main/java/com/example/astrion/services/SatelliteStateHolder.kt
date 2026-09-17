package com.example.astrion.services

import com.example.astrion.esphome.Connected
import com.example.astrion.esphome.EspHomeState
import com.example.astrion.esphome.Stopped
import com.example.astrion.esphome.voiceassistant.VoiceAssistant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes the satellite device state to the UI without needing a service
 * binding: the appliance UI is always running while the service runs.
 */
@Singleton
class SatelliteStateHolder @Inject constructor() {
    private val _deviceState = MutableStateFlow<EspHomeState>(Stopped)
    val deviceState: StateFlow<EspHomeState> = _deviceState.asStateFlow()

    /** True once the device has handshaked with Home Assistant. */
    val isConnected: Boolean get() = _deviceState.value == Connected

    /**
     * The running voice assistant, used for the physical mic key push-to-talk
     * (免唤醒对话): pressing it starts an Assist pipeline without a wake word.
     */
    var voiceAssistant: VoiceAssistant? = null

    /** The running device, used by the quick settings 刷新 entry. */
    var device: com.example.astrion.esphome.EspHomeDevice? = null

    /** Re-connects the Home Assistant client, re-listing all entities. */
    fun reconnect() {
        device?.disconnectClient()
    }

    fun set(state: EspHomeState) {
        _deviceState.value = state
    }
}
