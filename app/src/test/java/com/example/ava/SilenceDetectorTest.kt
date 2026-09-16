package com.example.ava

import com.example.ava.esphome.voiceassistant.SilenceDetector
import com.example.ava.esphome.voiceassistant.VadConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceDetectorTest {
    private var now = 0L

    private fun detector(
        threshold: Float = 0.008f,
        silenceDurationMs: Long = 1200,
        minSpeechDurationMs: Long = VadConfig.DEFAULT_MIN_SPEECH_DURATION_MS
    ) = SilenceDetector(
        silenceThreshold = threshold,
        silenceDurationMs = silenceDurationMs,
        minSpeechDurationMs = minSpeechDurationMs,
        clock = { now }
    )

    private fun advanceSilently(detector: SilenceDetector, steps: Int): Boolean {
        for (i in 0 until steps) {
            now += 100
            if (detector.processAudio(silentChunk()))
                return true
        }
        return false
    }

    @Test
    fun silence_only_should_neither_speak_nor_end() {
        val detector = detector()
        assertFalse(advanceSilently(detector, 50))
    }

    @Test
    fun speech_followed_by_silence_should_end_once() {
        val detector = detector()
        // 800ms of speech
        repeat(8) {
            now += 100
            assertFalse(detector.processAudio(speechChunk()))
        }
        // ~1200ms of trailing silence ends the utterance
        assertTrue(advanceSilently(detector, 12))
        // Reported only once
        assertFalse(advanceSilently(detector, 5))
    }

    @Test
    fun short_blip_should_not_end() {
        val detector = detector()
        now += 100
        detector.processAudio(speechChunk())
        assertFalse(advanceSilently(detector, 30))
    }

    @Test
    fun reset_should_allow_detecting_a_new_utterance() {
        val detector = detector()
        repeat(8) {
            now += 100
            detector.processAudio(speechChunk())
        }
        assertTrue(advanceSilently(detector, 12))

        detector.reset()
        repeat(8) {
            now += 100
            detector.processAudio(speechChunk())
        }
        assertTrue(advanceSilently(detector, 12))
    }

    @Test
    fun disabled_config_should_not_be_enabled() {
        assertEquals(false, VadConfig.DISABLED.enabled)
        assertEquals(
            true,
            VadConfig(silenceThreshold = 0.008f, silenceDurationMs = 1200).enabled
        )
    }

    private fun speechChunk(): ByteArray {
        // Little-endian 16-bit samples of amplitude ~15872 (well above the
        // default threshold of 0.008 * 32768 = 262).
        val bytes = ByteArray(64)
        var i = 0
        while (i < bytes.size) {
            bytes[i] = 0x00
            bytes[i + 1] = 0x3E
            i += 2
        }
        return bytes
    }

    private fun silentChunk(): ByteArray = ByteArray(64)
}
