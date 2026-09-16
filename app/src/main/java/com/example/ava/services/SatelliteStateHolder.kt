package com.example.ava.services

import com.example.ava.esphome.Connected
import com.example.ava.esphome.EspHomeState
import com.example.ava.esphome.Stopped
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

    fun set(state: EspHomeState) {
        _deviceState.value = state
    }
}
