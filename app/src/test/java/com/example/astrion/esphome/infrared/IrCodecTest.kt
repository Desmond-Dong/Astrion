package com.example.astrion.esphome.infrared

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IrCodecTest {

    @Test
    fun `decode comma format with frequency`() {
        val code = "38000,9000,4500,560,560,560,1690"

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(
            listOf(9000, -4500, 560, -560, 560, -1690),
            decoded.timings
        )
        assertEquals(1, decoded.repeatCount)
    }

    @Test
    fun `decode comma format without leading frequency uses default carrier`() {
        val decoded = assertNotNull(IrCodec.decode("9000,4500,560,560"))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(listOf(9000, -4500, 560, -560), decoded.timings)
    }

    @Test
    fun `empty and single element input is rejected`() {
        assertNull(IrCodec.decode(""))
        assertNull(IrCodec.decode("   "))
        assertNull(IrCodec.decode("38000"))
        assertNull(IrCodec.decode("not_a_code"))
    }

    @Test
    fun `broadlink format decodes pulse ticks to microseconds`() {
        // Type 38 (IR), repeat 0, LE length, then ticks [10, 20, 30] + end marker
        val payload = byteArrayOf(
            38,                 // type
            0,                  // repeat
            5, 0,               // LE length = 5 (3 ticks + 2 end marker)
            10, 20, 30,         // ticks
            0x0D, 0x05,         // end marker
        )
        val code = Base64.getEncoder().encodeToString(payload)

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(1, decoded.repeatCount)
        // 10*30.4535 -> 305, 20*30.4535 -> 609, 30*30.4535 -> 914 (roundToInt)
        assertEquals(listOf(305, -609, 914), decoded.timings)
    }

    @Test
    fun `broadlink extended values and repeat are handled`() {
        // A 400-count pulse needs the 2-byte LE extended encoding (0x00 marker).
        val payload = byteArrayOf(
            38,                 // type
            2,                  // repeat -> repeat+1 = 3
            9, 0,               // LE length = 9 (3 pulses + 2 extended bytes + 2 end marker)
            0x00, 0x90.toByte(), 0x01,   // extended value 0x0190 = 400
            10,                 // single byte
            0x00, 0x07, 0x00,   // extended value 7 (LE 0x0007)
            0x0D, 0x05,         // end marker
        )
        val dataLength = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        assertEquals(9, dataLength)
        val code = Base64.getEncoder().encodeToString(payload)

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(3, decoded.repeatCount)
        // 400*30.4535 = 12181.4 -> 12181, 10 -> 305, 7 -> 213
        assertEquals(listOf(12_181, -305, 213), decoded.timings)
    }

    @Test
    fun `broadlink rf type is rejected`() {
        val payload = byteArrayOf(
            178.toByte(),        // RF type
            0,
            3, 0,
            10, 20,
            0x0D, 0x05,
        )
        val code = Base64.getEncoder().encodeToString(payload)

        assertNull(IrCodec.decode(code))
    }

    @Test
    fun `broadlink too small payload is rejected`() {
        val payload = byteArrayOf(38, 0, 2, 0, 10)
        val code = Base64.getEncoder().encodeToString(payload)

        assertNull(IrCodec.decode(code))
    }

    @Test
    fun `aes nec code decodes to header bits and tail`() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(1)                       // version
            put(1)                       // protocol = NEC
            putShort(0)                  // reserved
            putInt(38_000)               // frequency
            putShort(0x00FF.toShort())   // address
            putShort(0x45BA.toShort())   // command
        }.array()
        val code = aesEncode(payload)

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(1, decoded.repeatCount)
        // Header + 32 bits (2 each) + tail mark
        assertEquals(67, decoded.timings.size)
        assertEquals(9000, decoded.timings[0])
        assertEquals(-4500, decoded.timings[1])
        assertEquals(560, decoded.timings.last())
    }

    @Test
    fun `aes necext protocol uses non-complemented encoding`() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(1)                       // version
            put(2)                       // protocol = NECExt
            putShort(0)                  // reserved
            putInt(56_000)               // frequency
            putShort(0x00FF.toShort())   // address
            putShort(0x45BA.toShort())   // command
        }.array()
        val code = aesEncode(payload)

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(56_000, decoded.carrierFrequencyHz)
        assertEquals(67, decoded.timings.size)
    }

    @Test
    fun `aes raw protocol exposes pulses verbatim`() {
        val payload = ByteBuffer.allocate(8 + 2 + 3 * 4).order(ByteOrder.BIG_ENDIAN).apply {
            put(1)                       // version
            put(10)                      // protocol = RAW
            putShort(0)                  // reserved
            putInt(38_000)               // frequency
            putShort(3)                  // 3 pulses
            putInt(8000)
            putInt(-4000)
            putInt(500)
        }.array()
        val code = aesEncode(payload)

        val decoded = assertNotNull(IrCodec.decode(code))

        assertEquals(38_000, decoded.carrierFrequencyHz)
        assertEquals(listOf(8000, -4000, 500), decoded.timings)
    }

    @Test
    fun `aes wrong version is rejected`() {
        val payload = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(2)                       // wrong version
            put(1)
            putShort(0)
            putInt(38_000)
            putShort(0x00FF.toShort())
            putShort(0x45BA.toShort())
        }.array()
        val code = aesEncode(payload)

        assertNull(IrCodec.decode(code))
    }

    @Test
    fun `plain arbitrary base64 is not decoded as aes`() {
        val code = Base64.getEncoder().encodeToString("definitely not an IR code".toByteArray())

        assertNull(IrCodec.decode(code))
    }

    private fun aesEncode(payload: ByteArray): String {
        val keySpec = SecretKeySpec("AiksSecretKey026".toByteArray(Charsets.UTF_8), "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        return Base64.getEncoder().encodeToString(cipher.doFinal(payload))
    }
}