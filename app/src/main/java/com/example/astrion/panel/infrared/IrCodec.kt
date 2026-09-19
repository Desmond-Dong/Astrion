package com.example.astrion.panel.infrared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/**
 * Decoded infrared code in zigzag timing format
 * (positive = mark, negative = space).
 */
data class DecodedIR(
    val carrierFrequencyHz: Int,
    val timings: List<Int>,
    val repeatCount: Int = 1,
)

/**
 * Decodes infrared codes in three formats supported by the HaRemote/Astrion
 * system (§3.4 of the requirements doc):
 *
 * 1. **Comma format**: `"freq,p1,p2,p3,..."` — frequency in Hz followed by
 *    alternating mark/space durations in µs. All values are positive.
 *
 * 2. **AES binary**: Base64-encoded AES/ECB/PKCS5 ciphertext (key
 *    `"AiksSecretKey026"`). After decryption, the binary payload contains a
 *    header (version, protocol, reserved, frequency) followed by protocol-
 *    specific data (NEC / NECExt / RAW).
 *
 * 3. **Broadlink**: Base64-encoded byte array with type, repeat, LE length,
 *    and pulse ticks at 30.4535 µs per count. Only IR (type 38) is accepted;
 *    RF (type 178) is rejected.
 *
 * All three formats are decoded into zigzag timings compatible with
 * [InfraredManager.transmit]. The codec is pure Kotlin/JVM with no Android
 * dependencies, so it can be unit-tested in a standard JVM test runner.
 */
object IrCodec {

    private const val AES_KEY_STRING = "AiksSecretKey026"
    private const val BROADLINK_TICK_MICROS = 30.45353159851301
    private const val BROADLINK_IR_PACKET_TYPE = 38
    private const val MAX_BROADLINK_PATTERN_LENGTH = 2048
    private const val MAX_BROADLINK_PULSE_MICROS = 1_000_000
    private const val MAX_BROADLINK_REPEATS = 10

    /**
     * Attempt to decode [code] in comma, Broadlink, or AES format.
     *
     * @return [DecodedIR] with carrier frequency, zigzag timings, and repeat
     *         count, or `null` if the input is empty or unrecognisable.
     */
    fun decode(code: String): DecodedIR? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return null
        return tryCommaFormat(trimmed)
            ?: tryBroadlinkFormat(trimmed)
            ?: tryAesFormat(trimmed)
    }

    // ── Comma format ────────────────────────────────────────────────────

    /**
     * Comma format: `"freq,dur1,dur2,..."` where `freq` is carrier Hz and the
     * remaining elements are alternating positive mark/space durations in µs.
     *
     * If the first element is a plausible carrier frequency (≥ 10000 Hz), it
     * is used as the carrier and the rest are timings. Otherwise all elements
     * are treated as timings with the default 38 000 Hz carrier.
     */
    private fun tryCommaFormat(code: String): DecodedIR? {
        val parts = code.split(",").map { it.trim() }
        if (parts.size < 2) return null

        val numbers = parts.mapNotNull { it.toIntOrNull() }
        if (numbers.size < 2) return null
        if (numbers.any { it <= 0 }) return null

        val (carrier, durations) = if (numbers[0] in MIN_CARRIER_HZ..MAX_CARRIER_HZ) {
            numbers[0] to numbers.drop(1)
        } else {
            DEFAULT_CARRIER_HZ to numbers
        }
        if (durations.size < 2) return null

        // Convert alternating mark/space to zigzag
        return DecodedIR(
            carrierFrequencyHz = carrier,
            timings = durations.mapIndexed { index, value ->
                if (index % 2 == 0) value else -value
            },
        )
    }

    // ── Broadlink format ────────────────────────────────────────────────

    /**
     * Broadlink format (≥ 6 bytes):
     * - `[0]` = type (38 = IR, 178 = RF)
     * - `[1]` = repeat count (≤ 10)
     * - `[2..3]` = LE 16-bit length of pulse data
     * - `[4..]` = pulse ticks; byte 0 → next 2 bytes as LE extended value
     * - µs = `round(count × 30.4535)`; emit when `0 < µs ≤ 1 000 000`
     * - End marker: `0x0D 0x05`
     */
    private fun tryBroadlinkFormat(code: String): DecodedIR? {
        val decoded = try {
            Base64.getDecoder().decode(code)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size < 6) return null

        val type = decoded[0].toInt() and 0xFF
        if (type != BROADLINK_IR_PACKET_TYPE) return null // RF (178) rejected

        val repeatCount =
            ((decoded[1].toInt() and 0xFF).coerceAtMost(MAX_BROADLINK_REPEATS)) + 1

        val dataLength = (decoded[2].toInt() and 0xFF) or
            ((decoded[3].toInt() and 0xFF) shl 8)
        if (dataLength <= 0 || dataLength > decoded.size - 4) return null

        val rawCounts = mutableListOf<Int>()
        var i = 4
        val end = 4 + dataLength
        while (i < end && i < decoded.size) {
            val b = decoded[i].toInt() and 0xFF
            // End marker 0x0D 0x05
            if (b == 0x0D && i + 1 < end &&
                (decoded[i + 1].toInt() and 0xFF) == 0x05
            ) break
            if (b == 0x00 && i + 2 < end) {
                val ext =
                    (decoded[i + 1].toInt() and 0xFF) or
                        ((decoded[i + 2].toInt() and 0xFF) shl 8)
                rawCounts.add(ext)
                i += 3
            } else {
                rawCounts.add(b)
                i++
            }
        }

        val timings = rawCounts
            .map { (it * BROADLINK_TICK_MICROS).roundToInt() }
            .filter { us -> us > 0 && us <= MAX_BROADLINK_PULSE_MICROS }
        if (timings.size > MAX_BROADLINK_PATTERN_LENGTH) return null
        if (timings.isEmpty()) return null

        // Convert alternating mark/space positive durations to zigzag
        return DecodedIR(
            carrierFrequencyHz = DEFAULT_CARRIER_HZ,
            timings = timings.mapIndexed { index, value ->
                if (index % 2 == 0) value else -value
            },
            repeatCount = repeatCount,
        )
    }

    // ── AES binary format ───────────────────────────────────────────────

    /**
     * AES binary: Base64 → AES/ECB/PKCS5Padding with key
     * `"AiksSecretKey026"` (16 bytes). Decrypted payload:
     *
     * | Offset | Field       | Size    |
     * |--------|-------------|---------|
     * | 0      | version     | 1 byte  |
     * | 1      | protocol    | 1 byte  |
     * | 2      | reserved    | 2 bytes |
     * | 4      | frequency   | 4 bytes |
     * | 8…     | data        | varies  |
     *
     * Protocols:
     * - `1` = NEC: two shorts (address, command), 32-bit encoding
     * - `2` = NECExt: two shorts (address, command), `(cmd<<16)|addr`
     * - `10` = RAW: short count + N int pulses
     */
    private fun tryAesFormat(code: String): DecodedIR? {
        val encrypted = try {
            Base64.getDecoder().decode(code)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (encrypted.size < 8) return null

        val decrypted = try {
            val keySpec = SecretKeySpec(AES_KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            cipher.doFinal(encrypted)
        } catch (_: Exception) {
            return null
        }
        if (decrypted.size < 8) return null

        val buf = ByteBuffer.wrap(decrypted).order(ByteOrder.BIG_ENDIAN)
        val version = buf.get().toInt() and 0xFF
        if (version != 1) return null

        val protocol = buf.get().toInt() and 0xFF
        buf.short // reserved, skip
        val frequency = buf.int
        if (frequency <= 0) return null

        val timings = when (protocol) {
            1 -> decodeNec(buf, complemented = true)
            2 -> decodeNec(buf, complemented = false)
            10 -> decodeRaw(buf)
            else -> return null
        } ?: return null

        return DecodedIR(
            carrierFrequencyHz = frequency,
            timings = timings,
        )
    }

    /**
     * Decode NEC / NECExt payload. After protocol header, two shorts
     * (address, command) are read and expanded into a 32-bit zigzag
     * timing pattern.
     *
     * - Header: 9000 µs mark, 4500 µs space
     * - Bits 0–31 (LSB first):
     *   - bit 0: 560 mark, 560 space
     *   - bit 1: 560 mark, 1690 space
     * - Tail: 560 µs mark
     */
    private fun decodeNec(buf: ByteBuffer, complemented: Boolean): List<Int>? {
        if (buf.remaining() < 4) return null
        val address = buf.short.toInt() and 0xFFFF
        val command = buf.short.toInt() and 0xFFFF

        val addr = address and 0xFF
        val addrInv = (address shr 8) and 0xFF
        val cmd = command and 0xFF
        val cmdInv = (command shr 8) and 0xFF

        val data = if (complemented) {
            addr or (addrInv shl 8) or (cmd shl 16) or (cmdInv shl 24)
        } else {
            (cmd shl 16) or (addr and 0xFFFF)
        }

        return buildNecTimings(data)
    }

    private fun buildNecTimings(data: Int): List<Int> {
        val timings = mutableListOf<Int>()
        timings.add(9000)      // mark
        timings.add(-4500)     // space
        for (bit in 0 until 32) {
            timings.add(560)   // mark
            timings.add(
                if ((data shr bit) and 1 == 0) -560 else -1690
            ) // space
        }
        timings.add(560)       // tail mark
        return timings
    }

    /**
     * Decode RAW payload: short (16 bits) pulse count, then N int (32-bit)
     * pulse durations.
     */
    private fun decodeRaw(buf: ByteBuffer): List<Int>? {
        if (buf.remaining() < 2) return null
        val count = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < count * 4) return null
        return buildList {
            for (i in 0 until count) {
                add(buf.int)
            }
        }
    }

    private const val MIN_CARRIER_HZ = 10_000
    private const val MAX_CARRIER_HZ = 100_000
    private const val DEFAULT_CARRIER_HZ = 38_000
}
