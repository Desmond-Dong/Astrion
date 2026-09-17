package com.example.astrion.esphome.voiceassistant

import kotlin.math.abs

/**
 * Device-side end-of-speech (VAD) configuration.
 *
 * @param silenceThreshold Average absolute sample amplitude, as a fraction of
 *   full scale, below which a frame counts as silence. `0` disables the local
 *   detector (the pipeline then waits for the server-side VAD as before).
 * @param silenceDurationMs How long the audio must stay silent after speech
 *   before the end of the utterance is declared.
 * @param minSpeechDurationMs Minimum duration the speech itself must have
 *   lasted before an end may be declared (filters short blips).
 */
data class VadConfig(
    val silenceThreshold: Float,
    val silenceDurationMs: Long,
    val minSpeechDurationMs: Long = DEFAULT_MIN_SPEECH_DURATION_MS
) {
    val enabled: Boolean
        get() = silenceThreshold > 0f && silenceDurationMs > 0L

    companion object {
        const val DEFAULT_MIN_SPEECH_DURATION_MS = 500L

        /** Disables the local end-of-speech detection. */
        val DISABLED = VadConfig(silenceThreshold = 0f, silenceDurationMs = 0L)
    }
}

/**
 * Device-side end-of-speech detector (ported from the Ava-Pro panel): tracks
 * speech and trailing silence in the streamed microphone audio and returns
 * `true` from [processAudio] once the user has finished speaking (enough
 * speech followed by enough silence).
 *
 * The input must be 16-bit little-endian PCM, mono, as produced by the
 * microphone ([com.example.astrion.esphome.android.microphone.AudioRecordMicrophone]).
 *
 * @param clock Wall clock source, injectable for tests.
 */
class SilenceDetector(
    private val silenceThreshold: Float = 0.008f,
    private val silenceDurationMs: Long = 1200,
    private val minSpeechDurationMs: Long = VadConfig.DEFAULT_MIN_SPEECH_DURATION_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var lastSoundTime = 0L
    private var speechStartTime = 0L
    private var isSpeaking = false

    fun reset() {
        lastSoundTime = 0
        speechStartTime = 0
        isSpeaking = false
    }

    /**
     * Feeds one chunk of PCM audio into the detector, returning `true` when
     * the end of speech has been detected (once per utterance).
     */
    fun processAudio(audioBytes: ByteArray): Boolean {
        val currentTime = clock()
        val silence = checkSilence(audioBytes)

        if (silence) {
            if (isSpeaking && lastSoundTime > 0) {
                val silenceDuration = currentTime - lastSoundTime
                if (silenceDuration >= silenceDurationMs) {
                    val speechDuration = lastSoundTime - speechStartTime
                    isSpeaking = false
                    if (speechDuration >= minSpeechDurationMs) {
                        return true
                    }
                }
            }
        } else {
            lastSoundTime = currentTime
            if (!isSpeaking) {
                isSpeaking = true
                speechStartTime = currentTime
            }
        }

        return false
    }

    private fun checkSilence(audioBytes: ByteArray): Boolean {
        if (audioBytes.size < 2) return true

        var sum = 0L
        var count = 0

        var i = 0
        while (i < audioBytes.size - 1) {
            val lo = audioBytes[i].toInt() and 0xFF
            val hi = audioBytes[i + 1].toInt()
            val sample = lo or (hi shl 8)
            val signed = if (sample > 32767) sample - 65536 else sample
            sum += abs(signed)
            count++
            i += 2
        }

        if (count == 0) return true

        val volume = sum.toFloat() / count
        return volume < (silenceThreshold * 32768)
    }
}
