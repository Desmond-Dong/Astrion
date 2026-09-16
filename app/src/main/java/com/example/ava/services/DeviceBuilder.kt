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
import com.example.ava.esphome.entities.TextEntity
import com.example.ava.esphome.entities.TextSensorEntity
import com.example.ava.esphome.infrared.InfraredManager
import com.example.ava.esphome.voiceassistant.VoiceAssistant
import com.example.ava.esphome.voiceassistant.VoiceInputImpl
import com.example.ava.esphome.voiceassistant.VoiceOutputImpl
import com.example.ava.panel.PanelConfigStore
import com.example.ava.panel.PanelIrController
import com.example.ava.panel.irObjectId
import com.example.ava.settings.MicrophoneSettingsStore
import com.example.ava.settings.PlayerSettingsStore
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.settings.availableStopWords
import com.example.ava.settings.availableWakeWords
import com.example.esphomeproto.api.VoiceAssistantFeature
import com.example.esphomeproto.api.deviceInfoResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

/**
 * Builds the ESPHome device from Home Assistant driven configuration only
 * (§5.2/§6.1): the panel exposes the config text entities themselves plus the
 * entity families derived from them. Nothing is configured inside the app —
 * layout, IR codebooks, HA entity sync list and navigation pages all arrive
 * through Home Assistant, and the entity list is rebuilt whenever that config
 * changes ([PanelConfigStore.version]).
 */
class DeviceBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val satelliteSettingsStore: VoiceSatelliteSettingsStore,
    private val microphoneSettingsStore: MicrophoneSettingsStore,
    private val audioProcessingSettingsStore: AudioProcessingSettingsStore,
    private val playerSettingsStore: PlayerSettingsStore,
    private val panelConfigStore: PanelConfigStore,
    private val irController: PanelIrController,
    private val activityNavigator: ActivityNavigator,
    private val haStatesStore: HomeAssistantStatesStore,
    private val haActionBus: HaActionBus
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
            entities = buildEntities(coroutineContext, voiceOutput, deviceHolder),
            haStatesStore = haStatesStore,
            getHaSyncedEntityIds = { panelConfigStore.syncEntities.first() },
            haActionBus = haActionBus
        )
        deviceHolder.set(device)
        return device
    }

    private suspend fun buildEntities(
        coroutineContext: CoroutineContext,
        voiceOutput: VoiceOutputImpl,
        deviceHolder: AtomicReference<EspHomeDevice?>
    ): List<Entity> {
        val keyAllocator = EntityKeyAllocator(0)
        val entities = mutableListOf<Entity>()

        // Voice satellite controls (state-backed entities, set from HA)
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

        // Wake/stop word selection lives in Home Assistant (select entities),
        // replacing the old in-app settings screen.
        val micSettings = microphoneSettingsStore.get()
        val wakeWordOptions = micSettings.availableWakeWords(context).map { it.id }
        val stopWordOptions = micSettings.availableStopWords(context).map { it.id }
        entities += SelectEntity(
            key = keyAllocator.next(),
            name = "Wake Word",
            objectId = "wake_word",
            options = wakeWordOptions,
            initialState = micSettings.wakeWord,
            onSelect = { microphoneSettingsStore.wakeWord.set(it) }
        )
        entities += SelectEntity(
            key = keyAllocator.next(),
            name = "Second Wake Word",
            objectId = "second_wake_word",
            options = listOf(WAKE_WORD_NONE) + wakeWordOptions,
            initialState = micSettings.secondWakeWord ?: WAKE_WORD_NONE,
            onSelect = { value ->
                microphoneSettingsStore.secondWakeWord.set(
                    value.takeUnless { it == WAKE_WORD_NONE }
                )
            }
        )
        entities += SelectEntity(
            key = keyAllocator.next(),
            name = "Stop Word",
            objectId = "stop_word",
            options = stopWordOptions,
            initialState = micSettings.stopWord,
            onSelect = { microphoneSettingsStore.stopWord.set(it) }
        )

        // Media metadata text sensors (media cards)
        entities += TextSensorEntity(
            key = keyAllocator.next(),
            name = "Media Title",
            objectId = "media_title",
            getState = voiceOutput.metadata.map { it.title }
        )
        entities += TextSensorEntity(
            key = keyAllocator.next(),
            name = "Media Artist",
            objectId = "media_artist",
            getState = voiceOutput.metadata.map { it.artist }
        )

        // HA-driven configuration channel: Home Assistant writes the panel
        // layout and IR codebook JSON into these text entities.
        entities += buildConfigEntities(keyAllocator)

        // IR devices from the HA codebook
        entities += buildIrEntities(keyAllocator)

        // Gateway/activity controls
        entities += buildActivityEntities(coroutineContext, keyAllocator, deviceHolder)

        return entities
    }

    private suspend fun buildConfigEntities(
        keyAllocator: EntityKeyAllocator
    ): List<Entity> {
        val config = panelConfigStore.raw.first()
        return listOf(
            TextEntity(
                key = keyAllocator.next(),
                name = "Panel Layout",
                objectId = "astrion_layout",
                initialState = config.layoutJson,
                onText = { json -> panelConfigStore.applyLayoutJson(json) }
            ),
            TextEntity(
                key = keyAllocator.next(),
                name = "IR Codes",
                objectId = "astrion_ir_codes",
                initialState = config.irCodesJson,
                onText = { json -> panelConfigStore.applyIrCodesJson(json) }
            ),
            TextEntity(
                key = keyAllocator.next(),
                name = "Key Bindings",
                objectId = "astrion_key_bindings",
                initialState = config.keyBindingsJson,
                onText = { json -> panelConfigStore.applyKeyBindingsJson(json) }
            )
        )
    }

    private suspend fun buildIrEntities(keyAllocator: EntityKeyAllocator): List<Entity> {
        val codebook = panelConfigStore.irCodebook.first()
        if (codebook.devices.isEmpty()) return emptyList()

        val infraredManager = InfraredManager(context)
        if (!infraredManager.available) {
            Timber.w("IR codebook configured but the box has no IR emitter, skipping")
            return emptyList()
        }

        val entities = mutableListOf<Entity>()
        for ((deviceName, buttons) in codebook.devices) {
            val deviceSlug = irObjectId(deviceName)
            // Raw timings transmitter (ESPHome infrared service)
            entities += InfraredEntity(
                key = keyAllocator.next(),
                name = deviceName,
                objectId = "ir_$deviceSlug",
                transmit = { carrierFrequencyHz, timings, repeatCount ->
                    irController.transmitTimings(carrierFrequencyHz, timings, repeatCount)
                }
            )
            // One button per named IR command in the codebook
            for (buttonName in buttons.keys) {
                entities += ButtonEntity(
                    key = keyAllocator.next(),
                    name = "$buttonName ($deviceName)",
                    objectId = "ir_${deviceSlug}_${irObjectId(buttonName)}",
                    onPress = {
                        if (!irController.transmit(deviceName, buttonName)) {
                            Timber.w("No decodable code for $deviceName/$buttonName")
                        }
                    }
                )
            }
        }
        return entities
    }

    private suspend fun buildActivityEntities(
        coroutineContext: CoroutineContext,
        keyAllocator: EntityKeyAllocator,
        deviceHolder: AtomicReference<EspHomeDevice?>
    ): List<Entity> {
        val layout = panelConfigStore.layout.first()
        val activityPages = (layout.rooms.map { it.title } + layout.pages)
            .filter { it.isNotBlank() }
            .distinct()
        val scope = CoroutineScope(coroutineContext + Job())
        val initialPage = activityNavigator.currentPage.value
            .ifEmpty { activityPages.firstOrNull() ?: "" }

        return listOf(
            // A-Type: momentary navigation select. Selecting a page jumps the
            // panel there, then the select resets to the sentinel after 300 ms
            // so the same navigation can be re-fired repeatedly.
            SelectEntity(
                key = keyAllocator.next(),
                name = "Navigate",
                objectId = "navigate",
                options = listOf(NAVIGATE_SENTINEL) + activityPages,
                initialState = NAVIGATE_SENTINEL,
                onSelect = { page ->
                    if (page != NAVIGATE_SENTINEL) {
                        Timber.d("Navigating to page: $page")
                        activityNavigator.setPage(page)
                    }
                },
                autoResetAfterMs = NAVIGATE_RESET_MILLIS,
                sentinel = NAVIGATE_SENTINEL,
                scope = scope
            ),
            // B-Type: persistent current-activity select. Kept in sync with
            // both the panel pages and Home Assistant automations.
            SelectEntity(
                key = keyAllocator.next(),
                name = "Current Activity",
                objectId = "current_activity",
                options = activityPages,
                initialState = initialPage,
                onSelect = { page ->
                    Timber.d("Activity changed to: $page")
                    activityNavigator.setPage(page)
                },
                scope = scope,
                externalState = activityNavigator.currentPage
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
         * The navigation select (A-Type) resets back to this sentinel value
         * shortly after a page is selected.
         */
        const val NAVIGATE_SENTINEL = "—"

        /**
         * How long a navigation select stays at the picked page before
         * resetting to the sentinel (milliseconds).
         */
        const val NAVIGATE_RESET_MILLIS = 300L

        /** "No second wake word" option of the second wake word select. */
        const val WAKE_WORD_NONE = "None"
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
