package com.example.astrion.panel

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import com.example.astrion.settings.DisplaySettings
import com.example.astrion.settings.DisplaySettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * While charging the user chooses the idle behaviour (§3.10.7 充电时常亮):
 * keep the screensaver (default) or black the display out — true sleep when
 * the platform permits, a pure-black overlay otherwise.
 *
 * The controller outlives the activity so the idle clock keeps running across
 * recreation; the idle loop is started from [com.example.astrion.MainActivity].
 */
@Singleton
class ScreensaverController @Inject constructor(
    @ApplicationContext context: Context,
    private val displaySettingsStore: DisplaySettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _active = MutableStateFlow(false)

    /** True while the idle screensaver overlay should cover the UI. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _screenOff = MutableStateFlow(false)

    /** True while the display should be blacked out (charging + 熄屏). */
    val screenOff: StateFlow<Boolean> = _screenOff.asStateFlow()

    private val _chargingFlash = MutableStateFlow(false)

    /** True while the short "charger plugged in" hint overlay should show. */
    val chargingFlash: StateFlow<Boolean> = _chargingFlash.asStateFlow()

    @Volatile
    private var timeoutSeconds = 0

    @Volatile
    private var chargingScreenOff = false

    @Volatile
    private var charging = false

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
        displaySettingsStore.chargingDisplay
            .onEach { chargingScreenOff = it == DisplaySettings.CHARGING_SCREEN_OFF }
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
        if (_screenOff.value) {
            Timber.d("Charging screen-off dismissed by user activity")
            _screenOff.value = false
        }
    }

    /** Reports the current charging state (drives the 充电熄屏 behaviour). */
    fun setCharging(value: Boolean) {
        charging = value
        if (!value && _screenOff.value) _screenOff.value = false
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
        if (_active.value || _screenOff.value) return
        val idleMs = SystemClock.elapsedRealtime() - lastInteractionRealtimeMs
        if (idleMs >= timeout * 1000L) {
            if (charging && chargingScreenOff) {
                Timber.d("Charging idle: turning screen off after ${idleMs}ms")
                if (!tryGoToSleep()) _screenOff.value = true
            } else {
                Timber.d("Screensaver shown after ${idleMs}ms idle")
                _active.value = true
            }
        }
    }

    /**
     * True platform sleep when the device has the (signature) DEVICE_POWER
     * right; otherwise the caller falls back to the black overlay.
     */
    private fun tryGoToSleep(): Boolean = runCatching {
        powerManager.javaClass
            .getMethod("goToSleep", Long::class.javaPrimitiveType)
            .invoke(powerManager, SystemClock.uptimeMillis())
        true
    }.getOrElse {
        Timber.d("goToSleep unavailable, using black overlay")
        false
    }

    private companion object {
        /** How often the idle clock is checked. */
        const val IDLE_POLL_MS = 1000L

        /** How long the plug-in charging hint stays up. */
        const val CHARGING_FLASH_MS = 3500L
    }
}
