package com.example.astrion.panel

import android.content.Context
import com.example.astrion.panel.infrared.DecodedIR
import com.example.astrion.panel.infrared.InfraredManager
import com.example.astrion.panel.infrared.IrCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transmits IR codes from the HA-pushed codebook ([PanelConfigStore.irCodebook])
 * through the local IR emitter.
 *
 * Card screens use this to fire codes locally (no HA round trip) when the
 * pressed key exists in the codebook; anything else goes to Home Assistant as
 * a service call through [com.example.astrion.services.HaActionBus].
 */
@Singleton
class PanelIrController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val panelConfigStore: PanelConfigStore,
) {
    private val infraredManager by lazy { InfraredManager(context) }

    val available: Boolean get() = infraredManager.available

    /** Decodes the raw code string for [device]/[button] from the codebook. */
    suspend fun decode(device: String, button: String): DecodedIR? {
        val code = panelConfigStore.irCodebook.first().devices[device]?.get(button)
            ?: return null
        return IrCodec.decode(code)
    }

    /**
     * Transmits the codebook code for [device]/[button] locally.
     *
     * @return false when the device has no emitter or the button/code is
     *         unknown — callers should fall back to an HA service call.
     */
    suspend fun transmit(device: String, button: String): Boolean {
        if (!available) return false
        val decoded = decode(device, button) ?: return false
        infraredManager.transmit(decoded.carrierFrequencyHz, decoded.timings, decoded.repeatCount)
        return true
    }

    /** Transmits raw zigzag timings directly (used by the raw infrared entity). */
    suspend fun transmitTimings(carrierFrequencyHz: Int, timings: List<Int>, repeatCount: Int) {
        if (available) {
            infraredManager.transmit(carrierFrequencyHz, timings, repeatCount)
        }
    }

    /**
     * Transmits a raw code string in any of the supported formats
     * (comma timings / Broadlink base64 / AES base64) — used by the
     * astrion/control_command event path.
     */
    suspend fun transmitRaw(code: String): Boolean {
        if (!available) return false
        val decoded = IrCodec.decode(code) ?: return false
        infraredManager.transmit(decoded.carrierFrequencyHz, decoded.timings, decoded.repeatCount)
        return true
    }

    /**
     * Transmits a named button looked up across every device in the
     * codebook — used by astrion/control_command when only a button
     * name is provided.
     */
    suspend fun transmitByButton(button: String): Boolean {
        if (!available) return false
        val book = panelConfigStore.irCodebook.first()
        for (buttons in book.devices.values) {
            val code = buttons[button] ?: continue
            val decoded = IrCodec.decode(code) ?: continue
            infraredManager.transmit(decoded.carrierFrequencyHz, decoded.timings, decoded.repeatCount)
            return true
        }
        return false
    }
}
