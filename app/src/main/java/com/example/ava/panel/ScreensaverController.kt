package com.example.ava.panel

import android.os.SystemClock
import com.example.ava.settings.DisplaySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the appliance screensaver (§3.10.7): after `screen_saver_timeout`
 * seconds without a key or touch, the full-screen idle overlay (clock, date,
 * battery, HA link) takes over, mirroring the original ScreenSaverDialog.
 * Any key or touch dismisses it.
 *
 * The controller outlives the activity so the idle clock keeps running across
 * recreation; the idle loop is started from [com.example.ava.MainActivity].
 */
@Singleton
class ScreensaverController @Inject constructor(
    private val displaySettingsStore: DisplaySettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow(false)

    /** True while the idle screensaver overlay should cover the UI. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _chargingFlash = MutableStateFlow(false)

    /** True while the short "charger plugged in" hint overlay should show. */
    val chargingFlash: StateFlow<Boolean> = _chargingFlash.asStateFlow()

    @Volatile
    private var timeoutSeconds = 0

    @Volatile
    private var lastInteractionRealtimeMs = SystemClock.elapsedRealtime()

    private var loopStarted = false
    private var flashJob: Job? = null

    /**
     * Starts the idle poll loop and keeps the configured timeout in sync with
     * the HA number entity. Idempotent.
     */
    fun start() {
        if (loopStarted) return
        loopStarted = true
        displaySettingsStore.screenSaverTimeout
            .onEach { timeoutSeconds = it }
            .launchIn(scope)
        scope.launch {
            while (isActive) {
                delay(IDLE_POLL_MS)
                tick()
            }
        }
    }

    /** Records user interaction; dismisses the screensaver when showing. */
    fun onUserActivity() {
        lastInteractionRealtimeMs = SystemClock.elapsedRealtime()
        if (_active.value) {
            Timber.d("Screensaver dismissed by user activity")
            _active.value = false
        }
    }

    /** Shows the short charging hint (plugged in while running, §3.10.7). */
    fun showChargingFlash() {
        Timber.d("Charging flash shown")
        flashJob?.cancel()
        _chargingFlash.value = true
        flashJob = scope.launch {
            delay(CHARGING_FLASH_MS)
            _chargingFlash.value = false
        }
    }

    private fun tick() {
        val timeout = timeoutSeconds
        if (timeout <= 0) {
            if (_active.value) _active.value = false
            return
        }
        if (_active.value) return
        val idleMs = SystemClock.elapsedRealtime() - lastInteractionRealtimeMs
        if (idleMs >= timeout * 1000L) {
            Timber.d("Screensaver shown after ${idleMs}ms idle")
            _active.value = true
        }
    }

    private companion object {
        /** How often the idle clock is checked. */
        const val IDLE_POLL_MS = 1000L

        /** How long the plug-in charging hint stays up. */
        const val CHARGING_FLASH_MS = 3500L
    }
}
