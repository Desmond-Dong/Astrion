package com.example.astrion.services

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import com.example.astrion.settings.DisplaySettingsStore
import com.example.astrion.wakelocks.ScreenWakeLock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Raise to wake (§3.8, original `WakeupUtils`): while enabled, the
 * accelerometer detects the panel being picked up (vector distance from a
 * slowly tracked gravity baseline above the configured threshold) and lights
 * the screen up if it was off.
 *
 * The threshold (m/s²) comes from the HA number entity
 * `raise_to_wake_threshold`; 0 disables the feature.
 */
@Singleton
class RaiseToWakeController @Inject constructor(
    @ApplicationContext context: Context,
    private val displaySettingsStore: DisplaySettingsStore
) {
    private val sensorManager =
        context.getSystemService(SENSOR_SERVICE) as SensorManager
    private val powerManager =
        context.getSystemService(POWER_SERVICE) as PowerManager
    private val screenWakeLock = ScreenWakeLock(context)

    private var observeJob: Job? = null
    private var listener: SensorEventListener? = null

    // Baseline of the (gravity) acceleration, tracked slowly so gradual
    // reorientation or vibration does not trigger; only the sudden jerk of
    // picking the panel up exceeds the threshold.
    private val baseline = FloatArray(3)
    private var haveBaseline = false

    @Volatile
    private var threshold = 0f

    @Volatile
    private var lastWakeRealtimeMs = 0L

    /** Starts observing the configured threshold. Call from the service. */
    fun start(scope: CoroutineScope) {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            displaySettingsStore.raiseToWakeThreshold.collect { value ->
                threshold = value
                updateListener()
            }
        }
    }

    /** Stops sensing and releases the wake lock (service shutdown). */
    fun stop() {
        observeJob?.cancel()
        observeJob = null
        unregister()
    }

    private fun updateListener() {
        val enabled = threshold > 0f
        if (enabled && listener == null) {
            val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accelerometer == null) {
                Timber.w("Raise to wake enabled but no accelerometer, ignoring")
                return
            }
            val newListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                        onAcceleration(event.values)
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            listener = newListener
            haveBaseline = false
            // rate=3 == SENSOR_DELAY_NORMAL, same session rate as WakeupUtils.
            sensorManager.registerListener(
                newListener,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Timber.d("Raise to wake enabled, threshold=${threshold}")
        } else if (!enabled && listener != null) {
            Timber.d("Raise to wake disabled")
            unregister()
        }
    }

    private fun unregister() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        screenWakeLock.release()
    }

    private fun onAcceleration(values: FloatArray) {
        if (values.size < 3) return
        if (!haveBaseline) {
            baseline[0] = values[0]
            baseline[1] = values[1]
            baseline[2] = values[2]
            haveBaseline = true
            return
        }
        val dx = values[0] - baseline[0]
        val dy = values[1] - baseline[1]
        val dz = values[2] - baseline[2]
        val magnitude = sqrt(dx * dx + dy * dy + dz * dz)
        // Slowly track the baseline so reorientation/vibration never builds up.
        baseline[0] += dx * BASELINE_TRACKING
        baseline[1] += dy * BASELINE_TRACKING
        baseline[2] += dz * BASELINE_TRACKING
        if (magnitude <= threshold) return

        // A jerk above the threshold: reset the baseline so a sustained tilt
        // after picking the panel up does not re-trigger on every sample.
        haveBaseline = false
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeRealtimeMs < MIN_WAKEUP_INTERVAL_MS) return
        lastWakeRealtimeMs = now
        if (powerManager.isInteractive) return
        Timber.d("Raise to wake triggered (magnitude=$magnitude)")
        screenWakeLock.wakeUp(WAKE_HOLD_MS)
    }

    private companion object {
        /** Fraction of each sample merged into the moving baseline. */
        const val BASELINE_TRACKING = 0.1f

        /** Debounce between wake triggers (original MIN_WAKEUP_INTERVAL_MS). */
        const val MIN_WAKEUP_INTERVAL_MS = 3000L

        /** How long the screen stays bright after a raise-to-wake. */
        const val WAKE_HOLD_MS = 10000L
    }
}
