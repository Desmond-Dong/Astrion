package com.example.astrion.panel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
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
 * Drives the appliance screensaver (§3.10.7). Faithful to the original
 * ScreenSaverService: the true 息屏 is performed by the SYSTEM — we write
 * `Settings.System.SCREEN_OFF_TIMEOUT` and the OS powers the backlight down
 * completely (zero glow). Charging always-on mode writes the max timeout so
 * the panel stays lit on the dock (original `setSystemScreenTimeout(2147483647)`).
 *
 * When the platform refuses the settings write, the controller degrades to
 * the in-app path: instant power-sleep reflection, otherwise a dimmed black
 * overlay (brightness 1). The clock overlay itself only appears in the
 * user-selected 充电屏保 mode.
 *
 * The controller outlives the activity so the idle clock keeps running across
 * recreation; the idle loop is started from [com.example.astrion.MainActivity].
 */
@Singleton
class ScreensaverController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val displaySettingsStore: DisplaySettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _active = MutableStateFlow(false)

    /** True while the idle screensaver overlay should cover the UI. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _screenOff = MutableStateFlow(false)

    /** True while the fallback black overlay should cover the UI. */
    val screenOff: StateFlow<Boolean> = _screenOff.asStateFlow()

    private val _charging = MutableStateFlow(false)

    /** True while the panel sits on the charger/dock. */
    val charging: StateFlow<Boolean> = _charging.asStateFlow()

    private val _chargingFlash = MutableStateFlow(false)

    /** True while the short "charger plugged in" hint overlay should show. */
    val chargingFlash: StateFlow<Boolean> = _chargingFlash.asStateFlow()

    @Volatile
    private var timeoutSeconds = 0

    @Volatile
    private var chargingScreenOff = false

    @Volatile
    private var chargingNow = false

    /** True when the OS accepted our SCREEN_OFF_TIMEOUT write (true息屏 path). */
    @Volatile
    private var systemTimeoutWriteOk = false

    /** System brightness before the screen-off dim, restored on wake. */
    private var previousBrightness = 0

    @Volatile
    private var lastInteractionRealtimeMs = SystemClock.elapsedRealtime()

    private var loopStarted = false
    private var flashJob: Job? = null

    /**
     * Starts the idle poll loop, mirrors the configured timeout into the
     * system screen-off setting and tracks charging state from the OS
     * battery broadcasts. Idempotent.
     */
    fun start() {
        if (loopStarted) return
        loopStarted = true
        displaySettingsStore.screenSaverTimeout
            .onEach { timeoutSeconds = it; applySystemTimeout() }
            .launchIn(scope)
        displaySettingsStore.chargingDisplay
            .onEach { chargingScreenOff = it == DisplaySettings.CHARGING_SCREEN_OFF; applySystemTimeout() }
            .launchIn(scope)
        scope.launch {
            while (isActive) {
                delay(IDLE_POLL_MS)
                tick()
            }
        }
        registerBatteryReceiver()
        applySystemTimeout()
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
            endScreenOff()
        }
    }

    /** Reports the current charging state (drives the 充电熄屏 behaviour). */
    fun setCharging(value: Boolean) {
        if (chargingNow == value) return
        _charging.value = value
        Timber.d("Charging state changed: $value (mode=${if (chargingScreenOff) "熄屏" else "屏保"})")
        chargingNow = value
        // 原版：充电常亮写 MAX 熄屏时间，拔出恢复 DisplaySettings/Screen_Off_Timeout
        applySystemTimeout()
        if (!value) endScreenOff()
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
        val idleMs = SystemClock.elapsedRealtime() - lastInteractionRealtimeMs
        val idleReached = timeout > 0 && idleMs >= timeout * 1000L

        // 系统息屏路径：写好 SCREEN_OFF_TIMEOUT 后由系统关背光（真灭屏，无余光），
        // 这里只负责充电屏保模式的时钟 overlay 以及旧路径兜底。
        if (systemTimeoutWriteOk) {
            if (!idleReached) {
                if (_active.value) _active.value = false
                endScreenOff()
                return
            }
            if (chargingNow && !chargingScreenOff) {
                // 充电 + 屏保：常亮，显示时钟
                if (!_screenOff.value && !_active.value) {
                    Timber.d("Screensaver shown after ${idleMs}ms idle")
                    _active.value = true
                }
            } else {
                // 其余情况交给系统熄屏（已写入对应超时），应用内不再显示任何覆盖
                if (_active.value) _active.value = false
            }
            return
        }

        // 充电熄屏模式：空闲即熄（屏保已显示时也允许切到熄屏）
        if (chargingNow && chargingScreenOff) {
            if (!idleReached) {
                endScreenOff()
                return
            }
            if (_screenOff.value) return
            Timber.d("Charging idle: screen off requested (idle=${idleMs}ms, mode=熄屏)")
            if (!tryGoToSleep()) {
                startScreenOffFallback()
            }
            return
        }

        // 屏保模式（或未充电）
        if (!idleReached) {
            if (_active.value) _active.value = false
            endScreenOff()
            return
        }
        if (_screenOff.value) return
        if (_active.value) return
        Timber.d("Screensaver shown after ${idleMs}ms idle")
        _active.value = true
    }

    /**
     * Mirrors the settings into `Settings.System.SCREEN_OFF_TIMEOUT` — the
     * original mechanism for the true display-off (zero backlight). 息屏常亮
     * (charging+屏保) and user's never both write the max value.
     */
    private fun applySystemTimeout() {
        val millis = when {
            chargingNow && !chargingScreenOff -> NEVER_TIMEOUT_MS
            timeoutSeconds <= 0 -> NEVER_TIMEOUT_MS
            else -> timeoutSeconds * 1000L
        }
        val ok = runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
            Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT
            ) >= 0
        }.getOrDefault(false)
        if (ok != systemTimeoutWriteOk) {
            Timber.d("System screen-off timeout write: ${if (ok) "OK" else "denied"}")
        }
        systemTimeoutWriteOk = ok
    }

    /** Battery monitor so charging state also updates while no UI is composed. */
    private fun registerBatteryReceiver() {
        runCatching {
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(receiveContext: Context?, intent: Intent?) {
                        intent ?: return
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                        setCharging(
                            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                    status == BatteryManager.BATTERY_STATUS_FULL ||
                                    plugged != 0
                        )
                    }
                },
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.onFailure { Timber.w(it, "Battery receiver registration failed") }
    }

    /** Blacks the display out and dims the backlight so it reads as 熄屏. */
    private fun startScreenOffFallback() {
        _screenOff.value = true
        runCatching {
            previousBrightness = Settings.System.getInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS
            )
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, SCREEN_OFF_BRIGHTNESS
            )
        }.onFailure { Timber.w(it, "Failed to dim for screen-off") }
    }

    private fun endScreenOff() {
        if (!_screenOff.value) return
        _screenOff.value = false
        runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                if (previousBrightness > 0) previousBrightness else 128
            )
        }
        Timber.d("Charging screen-off ended, brightness restored")
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

        /** Backlight floor while the charging screen-off overlay is up. */
        const val SCREEN_OFF_BRIGHTNESS = 1

        /** Original 息屏常亮/不熄屏 value: system timeout = Int.MAX ms. */
        const val NEVER_TIMEOUT_MS = Int.MAX_VALUE - 1L
    }
}
