package com.example.ava.esphome.android.microphone

import android.Manifest
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresPermission
import com.example.ava.esphome.microphone.Microphone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import timber.log.Timber
import java.nio.ByteBuffer

const val DEFAULT_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
const val DEFAULT_AUDIO_MODE = AudioManager.MODE_NORMAL
const val DEFAULT_SAMPLE_RATE_IN_HZ = 16000
const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
const val DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

fun audioRecordMicrophoneFlow(
    audioManager: AudioManager,
    audioSource: Flow<Int> = flowOf(DEFAULT_AUDIO_SOURCE),
    audioMode: Flow<Int> = flowOf(DEFAULT_AUDIO_MODE),
    useSpeakerphone: Flow<Boolean> = flowOf(false),
    noiseSuppression: Flow<Boolean> = flowOf(true),
    echoCancellation: Flow<Boolean> = flowOf(true),
    autoGain: Flow<Boolean> = flowOf(true)
): Flow<Microphone> = combine(
    combine(audioSource, audioMode, useSpeakerphone) { source, mode, speaker ->
        Triple(source, mode, speaker)
    },
    combine(noiseSuppression, echoCancellation, autoGain) { ns, aec, agc ->
        Triple(ns, aec, agc)
    }
) { (source, mode, speaker), (ns, aec, agc) ->
    AudioRecordMicrophone(
        audioManager = audioManager,
        audioSource = source,
        audioMode = mode,
        useSpeakerphone = speaker,
        enableNoiseSuppression = ns,
        enableEchoCancellation = aec,
        enableAutoGain = agc
    )
}

class AudioRecordMicrophone(
    val audioManager: AudioManager? = null,
    val audioSource: Int = DEFAULT_AUDIO_SOURCE,
    val audioMode: Int = DEFAULT_AUDIO_MODE,
    val useSpeakerphone: Boolean = false,
    val enableNoiseSuppression: Boolean = true,
    val enableEchoCancellation: Boolean = true,
    val enableAutoGain: Boolean = true,
    val sampleRateInHz: Int = DEFAULT_SAMPLE_RATE_IN_HZ,
    val channelConfig: Int = DEFAULT_CHANNEL_CONFIG,
    val audioFormat: Int = DEFAULT_AUDIO_FORMAT
) : Microphone {
    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
    private val buffer = ByteBuffer.allocateDirect(bufferSize)
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        audioManager?.apply {
            mode = audioMode
            setSpeakerphoneCompat(useSpeakerphone)
        }
        // Never let an unsupported audio source (e.g. an API 29+ source on an
        // older device) crash the panel: fall back to the default source.
        val safeSource = if (isAudioSourceSupported(audioSource)) {
            audioSource
        } else {
            Timber.w("Unsupported audio source $audioSource, falling back to default")
            DEFAULT_AUDIO_SOURCE
        }
        audioRecord = runCatching {
            AudioRecord(
                safeSource,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize * 2
            ).apply {
                check(state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize AudioRecord" }
            }
        }.getOrElse {
            Timber.e(it, "AudioRecord init failed for source $safeSource, retrying with default")
            AudioRecord(
                DEFAULT_AUDIO_SOURCE,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                bufferSize * 2
            ).apply {
                check(state == AudioRecord.STATE_INITIALIZED) { "Failed to initialize AudioRecord" }
            }
        }.apply {
            setupAudioEffects()
            Timber.d("Starting microphone")
            startRecording()
        }
    }

    private fun isAudioSourceSupported(source: Int): Boolean = when (source) {
        MediaRecorder.AudioSource.VOICE_PERFORMANCE -> Build.VERSION.SDK_INT >= 29
        MediaRecorder.AudioSource.UNPROCESSED -> Build.VERSION.SDK_INT >= 30
        else -> source >= 0
    }

    /**
     * Attaches the device's hardware voice effects to the capture session
     * (noise suppression / echo cancellation / auto gain). Support varies by
     * device; each effect is best-effort and silently skipped when
     * unavailable.
     */
    private fun AudioRecord.setupAudioEffects() {
        val sessionId = audioSessionId
        if (enableNoiseSuppression && NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.onFailure { Timber.w(it, "Failed to create NoiseSuppressor") }
        }
        if (enableEchoCancellation && AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.onFailure { Timber.w(it, "Failed to create AcousticEchoCanceler") }
        }
        if (enableAutoGain && AutomaticGainControl.isAvailable()) {
            runCatching {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }.onFailure { Timber.w(it, "Failed to create AutomaticGainControl") }
        }
        Timber.d(
            "Mic effects: NS=${noiseSuppressor != null}, AEC=${echoCanceler != null}, " +
                "AGC=${gainControl != null}"
        )
    }

    private fun releaseAudioEffects() {
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { gainControl?.release() }
        noiseSuppressor = null
        echoCanceler = null
        gainControl = null
    }

    override fun read(): ByteBuffer {
        audioRecord?.let {
            val read = it.read(buffer, bufferSize)
            check(read >= 0) { "error reading audio, read: $read" }
            // AudioRecord.read ignores the position and limit
            // of the buffer so manually update them.
            buffer.position(0)
            buffer.limit(read)
        } ?: error("Microphone not started")
        return buffer
    }

    override fun stop() {
        releaseAudioEffects()
        audioRecord?.let {
            it.release()
            audioRecord = null
            Timber.d("Microphone stopped")
        }
        audioManager?.apply {
            mode = AudioManager.MODE_NORMAL
            setSpeakerphoneCompat(false)
        }
    }

    override fun close() {
        stop()
    }

    @Suppress("DEPRECATION")
    private fun AudioManager.setSpeakerphoneCompat(enable: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            isSpeakerphoneOn = enable
        } else if (enable) {
            availableCommunicationDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { setCommunicationDevice(it) }
        } else {
            clearCommunicationDevice()
        }
    }
}
