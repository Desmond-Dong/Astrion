package com.example.ava.esphome.infrared

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Wraps the platform infrared emitter reflectively so ESPHome
 * entities can transmit raw timings without depending on the
 * public SDK surface of [android.hardware.consumerir.ConsumerIrManager],
 * which is hidden / @SystemApi in recent compileSdk levels.
 *
 * On devices without IR hardware the manager will be null and
 * all transmit calls become no-ops.
 */
class InfraredManager(context: Context) {

    private val irManager: Any? = run {
        try {
            val clazz = Class.forName(CLASS_NAME)
            val getSystemService = context.javaClass.getMethod(
                "getSystemService", String::class.java
            )
            getSystemService.invoke(context, Context.CONSUMER_IR_SERVICE)
        } catch (e: Exception) {
            Timber.d(e, "ConsumerIrManager not available")
            null
        }
    }

    /** Whether the device actually has an IR emitter that can be used. */
    val available: Boolean
        get() {
            val ir = irManager ?: return false
            return try {
                val method = ir.javaClass.getMethod(METHOD_HAS_IR_EMITTER)
                method.invoke(ir) as Boolean
            } catch (e: Exception) {
                false
            }
        }

    private val transmitLock = Mutex()

    /**
     * Whether the emitter can transmit on the given carrier frequency.
     * Returns true when the emitter reports no frequency constraints
     * or the range cannot be determined.
     */
    fun supportsCarrierFrequency(carrierFrequencyHz: Int): Boolean {
        val ir = irManager ?: return false
        return try {
            val method = ir.javaClass.getMethod(METHOD_GET_CARRIER_FREQUENCIES)
            @Suppress("UNCHECKED_CAST")
            val ranges = method.invoke(ir) as? IntArray ?: return true
            if (ranges.isEmpty()) return true
            // Ranges are reported as consecutive min,max pairs.
            for (i in ranges.indices step 2) {
                val min = ranges[i]
                val max = ranges[i + 1]
                if (carrierFrequencyHz in min..max) return true
            }
            return false
        } catch (e: Exception) {
            true // assume supported when reflection fails
        }
    }

    /**
     * Transmits [timings] on the given carrier frequency, repeated
     * [repeatCount] times.
     *
     * The protocol timings are zigzag encoded: positive values are marks (IR
     * LED on) and negative values are spaces (IR LED off).
     * The platform API expects alternating durations as positive values.
     */
    suspend fun transmit(carrierFrequencyHz: Int, timings: List<Int>, repeatCount: Int) {
        val ir = irManager
        if (ir == null || !available) {
            Timber.w("IR transmit requested but the device has no IR emitter")
            return
        }
        if (carrierFrequencyHz <= 0) {
            Timber.w("IR transmit requested with invalid carrier frequency: $carrierFrequencyHz")
            return
        }
        if (timings.isEmpty()) {
            Timber.w("IR transmit requested with empty timings")
            return
        }
        // Convert marks and spaces to positive durations for the platform API.
        val pattern = timings.map { if (it < 0) -it else it }.toIntArray()
        val repeats = if (repeatCount <= 0) 1 else repeatCount
        try {
            val transmitMethod = ir.javaClass.getMethod(
                METHOD_TRANSMIT,
                Int::class.javaPrimitiveType,
                IntArray::class.java
            )
            withContext(Dispatchers.IO) {
                transmitLock.withLock {
                    repeat(repeats) { index ->
                        transmitMethod.invoke(ir, carrierFrequencyHz, pattern)
                        if (index < repeats - 1) {
                            // Give the emitter time to finish the previous burst.
                            Thread.sleep(REPEAT_DELAY_MS)
                        }
                    }
                }
            }
        } catch (exception: SecurityException) {
            Timber.e(exception, "IR transmit failed, missing transmit permission")
        } catch (exception: IllegalArgumentException) {
            Timber.e(exception, "IR transmit failed, invalid carrier frequency or pattern")
        } catch (exception: Exception) {
            Timber.e(exception, "IR transmit failed unexpectedly")
        }
    }

    private companion object {
        const val CLASS_NAME = "android.hardware.consumerir.ConsumerIrManager"
        const val METHOD_HAS_IR_EMITTER = "hasIrEmitter"
        const val METHOD_GET_CARRIER_FREQUENCIES = "getCarrierFrequencies"
        const val METHOD_TRANSMIT = "transmit"
        const val REPEAT_DELAY_MS = 100L
    }
}