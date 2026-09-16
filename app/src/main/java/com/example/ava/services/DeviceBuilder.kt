package com.example.ava.services

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
import androidx.media3.common.C.USAGE_ASSISTANT
import androidx.media3.common.C.USAGE_MEDIA
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.android.logger.TimberLogger
import com.example.ava.esphome.android.mediaplayer.media3MediaPlayer
import com.example.ava.esphome.android.microphone.audioRecordMicrophoneFlow
import com.example.ava.esphome.android.wakeword.MicroWakeWord
import com.example.ava.esphome.entities.ButtonEntity
import com.example.ava.esphome.entities.Entity
import com.example.ava.esphome.entities.InfraredEntity
import com.example.ava.esphome.entities.MediaPlayerEntity
import com.example.ava.esphome.entities.SelectEntity
import com.example.ava.esphome.entities.SwitchEntity
import com.example.ava.esphome.infrared.InfraredManager
import com.example.ava.esphome.voiceassistant.VoiceAssistant
import com.example.ava.esphome.voiceassistant.VoiceInputImpl
import com.example.ava.esphome.voiceassistant.VoiceOutputImpl
import com.example.ava.server.ServerImpl
import com.example.ava.settings.ActivitySettingsStore
import com.example.ava.settings.AudioProcessingSettingsStore
import com.example.ava.settings.IrDeviceSettings
import com.example.ava.settings.IrSettingsStore
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.availableStopWords
import com.example.ava.settings.availableWakeWords
import com.example.ava.settings.irObjectId
import com.example.esphomeproto.api.VoiceAssistantFeature
import com.example.esphomeproto.api.deviceInfoResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class DeviceBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val satelliteSettingsStore: VoiceSatelliteSettingsStore,
    private val microphoneSettingsStore: MicrophoneSettingsStore,
    private val audioProcessingSettingsStore: AudioProcessingSettingsStore,
    private val playerSettingsStore: PlayerSettingsStore,
    private val irSettingsStore: IrSettingsStore,
    private val activitySettingsStore: ActivitySettingsStore
) {
    suspend fun buildVoiceSatellite(coroutineContext: CoroutineContext): EspHomeDevice {
        val satelliteSettings = satelliteSettingsStore.get()
        // Need a reference to voiceOutput as it needs to be passed to
        // both the VoiceAssistant and MediaPlayerEntity
        val voiceOutput = playerSettingsStore.toVoiceOutput()
        // The reconnect button needs the device after it has been built.
        val deviceHolder = AtomicReference<EspHomeDevice?>(null)
        val device = EspHomeDevice(
            coroutineContext = coroutineContext,
            port = satelliteSettings.serverPort,
            server = ServerImpl(),
            deviceInfo = deviceInfoResponse {
                name = satelliteSettings.name
                macAddress = satelliteSettings.macAddress
                voiceAssistantFeatureFlags = VoiceAssistantFeature.VOICE_ASSISTANT.flag or
                        VoiceAssistantFeature.API_AUDIO.flag or
                        VoiceAssistantFeature.TIMERS.flag or
                        VoiceAssistantFeature.ANNOUNCE.flag or
                        VoiceAssistantFeature.START_CONVERSATION.flag
            },
            voiceAssistant = VoiceAssistant(
                coroutineContext = coroutineContext,
                voiceInput = microphoneSettingsStore.toVoiceInput(),
                voiceOutput = voiceOutput
            ),
            logger = TimberLogger(),
            entities = buildEntities(voiceOutput, deviceHolder)
        )
        deviceHolder.set(device)
        return device
    }

    private suspend fun buildEntities(
        voiceOutput: VoiceOutputImpl,
        deviceHolder: AtomicReference<EspHomeDevice?>
    ): List<Entity> {
        val keyAllocator = EntityKeyAllocator(0)
        val entities = mutableListOf<Entity>()

        // Voice satellite controls
        entities += MediaPlayerEntity(
            key = keyAllocator.next(),
            name = "Media Player",
            objectId = "media_player",
            mediaPlayer = voiceOutput,
            getVolumeState = playerSettingsStore.volume,
            setVolume = { playerSettingsStore.volume.set(it) },
            getMutedState = playerSettingsStore.muted,
            setMuted = { playerSettingsStore.muted.set(it) }
        )
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Mute Microphone",
            objectId = "mute_microphone",
            getState = microphoneSettingsStore.muted
        ) { microphoneSettingsStore.muted.set(it) }
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Enable Wake Sound",
            objectId = "enable_wake_sound",
            getState = playerSettingsStore.enableWakeSound
        ) { playerSettingsStore.enableWakeSound.set(it) }
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Repeat Timer Sound",
            objectId = "repeat_timer_sound",
            getState = playerSettingsStore.repeatTimerFinishedSound
        ) { playerSettingsStore.repeatTimerFinishedSound.set(it) }

        // Infrared devices
        entities += buildIrEntities(keyAllocator)

        // Gateway/activity controls
        entities += buildActivityEntities(keyAllocator, deviceHolder)

        return entities
    }

    private suspend fun buildIrEntities(
        keyAllocator: EntityKeyAllocator
    ): List<Entity> {
        val irSettings = irSettingsStore.get()
        val devices = irSettings.devices.filter { it.enabled }
        if (devices.isEmpty()) return emptyList()

        val infraredManager = InfraredManager(context)
        if (!infraredManager.available) {
            Timber.w("IR devices configured but the box has no IR emitter, skipping")
            return emptyList()
        }

        val entities = mutableListOf<Entity>()
        for (device in devices) {
            // Raw timings transmitter (ESPHome infrared service)
            entities += InfraredEntity(
                key = keyAllocator.next(),
                name = device.name,
                objectId = device.objectId ?: irObjectId(device.name),
                transmit = { carrierFrequencyHz, timings, repeatCount ->
                    infraredManager.transmit(carrierFrequencyHz, timings, repeatCount)
                }
            )
            // One button per named IR command in the codebook
            for ((buttonName, timings) in device.buttons) {
                entities += ButtonEntity(
                    key = keyAllocator.next(),
                    name = "$buttonName (${device.name})",
                    objectId = device.irButtonObjectId(buttonName),
                    onPress = {
                        infraredManager.transmit(INFRARED_CARRIER_FREQUENCY_HZ, timings, 1)
                    }
                )
            }
        }
        return entities
    }

    private suspend fun buildActivityEntities(
        keyAllocator: EntityKeyAllocator,
        deviceHolder: AtomicReference<EspHomeDevice?>
    ): List<Entity> {
        val irDevices = irSettingsStore.get().devices.filter { it.enabled }.map { it.name }
        val activityPages = (irDevices + activitySettingsStore.get().pages).distinct()

        return listOf(
            SelectEntity(
                key = keyAllocator.next(),
                name = "Current Activity",
                objectId = "current_activity",
                options = activityPages,
                initialState = activityPages.firstOrNull() ?: "",
                onSelect = { page ->
                    Timber.d("Activity changed to: $page")
                }
            ),
            ButtonEntity(
                key = keyAllocator.next(),
                name = "Reconnect Home Assistant",
                objectId = "reconnect_ha",
                onPress = {
                    Timber.d("Reconnecting the Home Assistant client")
                    deviceHolder.get()?.disconnectClient()
                }
            )
        )
    }

    private fun IrDeviceSettings.irButtonObjectId(buttonName: String): String =
        "${objectId ?: irObjectId(name)}_${irObjectId(buttonName)}"

    private fun MicrophoneSettingsStore.toVoiceInput() = VoiceInputImpl(
        microphone = audioProcessingSettingsStore.toMicrophone(),
        wakeWord = MicroWakeWord(),
        availableWakeWords = { get().availableWakeWords(context) },
        availableStopWords = { get().availableStopWords(context) },
        activeWakeWords = activeWakeWords,
        activeStopWords = activeStopWords,
        muted = muted
    )

    private fun AudioProcessingSettingsStore.toMicrophone() = audioRecordMicrophoneFlow(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        audioSource = audioSource,
        audioMode = audioMode,
        useSpeakerphone = speakerphone
    )

    private suspend fun PlayerSettingsStore.toVoiceOutput(): VoiceOutputImpl {
        val playerSettings = get()
        return VoiceOutputImpl(
            ttsPlayer = context.media3MediaPlayer(
                USAGE_ASSISTANT,
                AUDIO_CONTENT_TYPE_SPEECH,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

            ),
            mediaPlayer = context.media3MediaPlayer(
                USAGE_MEDIA,
                AUDIO_CONTENT_TYPE_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ),
            enableWakeSound = { enableWakeSound.get() },
            wakeSound = { wakeSound.get() },
            timerFinishedSound = { timerFinishedSound.get() },
            repeatTimerFinishedSound = { repeatTimerFinishedSound.get() },
            enableErrorSound = { enableErrorSound.get() },
            errorSound = { errorSound.get() },
            volume = playerSettings.volume,
            muted = playerSettings.muted,
        )
    }

    private companion object {
        /**
         * IR devices usually transmit on 38 kHz. The platform emitter filters
         * the frequency when the request carries a value; when transmitting a
         * stored code we use this default carrier.
         */
        const val INFRARED_CARRIER_FREQUENCY_HZ = 38_000
    }
}

/**
 * Hands out monotonically increasing entity keys, keeping every entity
 * unique inside the running device.
 */
private class EntityKeyAllocator(baseKey: Int) {
    private var next = baseKey
    fun next(): Int = next++
}