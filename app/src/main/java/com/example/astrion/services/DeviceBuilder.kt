package com.example.astrion.services

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC
import androidx.media3.common.C.AUDIO_CONTENT_TYPE_SPEECH
import androidx.media3.common.C.USAGE_ASSISTANT
import androidx.media3.common.C.USAGE_MEDIA
import com.example.astrion.esphome.EspHomeDevice
import com.example.astrion.esphome.android.logger.TimberLogger
import com.example.astrion.esphome.android.mediaplayer.media3MediaPlayer
import com.example.astrion.esphome.android.microphone.audioRecordMicrophoneFlow
import com.example.astrion.esphome.android.wakeword.MicroWakeWord
import com.example.astrion.esphome.entities.ButtonEntity
import com.example.astrion.esphome.entities.Entity
import com.example.astrion.esphome.entities.EventEntity
import com.example.astrion.esphome.entities.InfraredEntity
import com.example.astrion.esphome.entities.MediaPlayerEntity
import com.example.astrion.esphome.entities.NumberEntity
import com.example.astrion.esphome.entities.SelectEntity
import com.example.astrion.esphome.entities.SwitchEntity
import com.example.astrion.esphome.entities.TextEntity
import com.example.astrion.esphome.entities.TextSensorEntity
import com.example.astrion.esphome.infrared.InfraredManager
import com.example.astrion.esphome.voiceassistant.VadConfig
import com.example.astrion.esphome.voiceassistant.VoiceAssistant
import com.example.astrion.esphome.voiceassistant.VoiceInputImpl
import com.example.astrion.esphome.voiceassistant.VoiceOutputImpl
import com.example.astrion.ota.OtaUpdateManager
import com.example.astrion.panel.PanelConfigStore
import com.example.astrion.panel.PanelIrController
import com.example.astrion.panel.irObjectId
import com.example.astrion.server.ServerImpl
import com.example.astrion.settings.AudioProcessingSettingsStore
import com.example.astrion.settings.DisplaySettingsStore
import com.example.astrion.settings.MicrophoneSettingsStore
import com.example.astrion.settings.PlayerSettingsStore
import com.example.astrion.settings.VoiceSatelliteSettingsStore
import com.example.astrion.settings.availableStopWords
import com.example.astrion.settings.availableWakeWords
import com.example.astrion.utils.getLocalIpAddress
import com.example.esphomeproto.api.VoiceAssistantFeature
import com.example.esphomeproto.api.deviceInfoResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
    private val displaySettingsStore: DisplaySettingsStore,
    private val otaUpdateManager: OtaUpdateManager,
    private val irController: PanelIrController,
    private val activityNavigator: ActivityNavigator,
    private val haStatesStore: HomeAssistantStatesStore,
    private val haActionBus: HaActionBus,
    private val eventHub: com.example.astrion.panel.PanelEventHub,
    private val screensaverController: com.example.astrion.panel.ScreensaverController
) {
    suspend fun buildVoiceSatellite(coroutineContext: CoroutineContext): EspHomeDevice {
        val satelliteSettings = satelliteSettingsStore.get()
        // Need a reference to voiceOutput as it needs to be passed to
        // both the VoiceAssistant and MediaPlayerEntity
        val voiceOutput = playerSettingsStore.toVoiceOutput()
        val wakeWordDetector = MicroWakeWord()
        val scope = CoroutineScope(coroutineContext + Job())
        // Keep the runtime wake word sensitivity in sync with the HA number
        // entity (cutoff = 1 - sensitivity; null restores model defaults).
        microphoneSettingsStore.wakeWordSensitivity
            .onEach { wakeWordDetector.setSensitivity(it) }
            .launchIn(scope)
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
                voiceInput = microphoneSettingsStore.toVoiceInput(wakeWordDetector),
                voiceOutput = voiceOutput,
                voiceEnabled = combine(
                    screensaverController.charging,
                    microphoneSettingsStore.voiceOnBattery
                ) { charging, onBattery -> charging || onBattery },
                // Device-side end-of-speech (VAD): finish the audio stream
                // locally when the user stops speaking instead of waiting for
                // the server-side VAD, which never fires on some setups.
                vadConfig = {
                    VadConfig(
                        silenceThreshold = microphoneSettingsStore.vadThreshold.get(),
                        silenceDurationMs =
                            (microphoneSettingsStore.vadTimeout.get() * 1000).roundToLong()
                    )
                }
            ),
            logger = TimberLogger(),
            entities = buildEntities(coroutineContext, voiceOutput, deviceHolder, scope),
            haStatesStore = haStatesStore,
            getHaSyncedEntityIds = {
                // 布局经模板 sensor 的 `layout` 属性传输（HA 对 sensor 状态
                // 限 255 字符，但属性不受限；不新增/依赖任何 text 实体）。
                panelConfigStore.syncEntities.first() + TRANSPORT_LAYOUT_ID
            },
            haActionBus = haActionBus
        )
        deviceHolder.set(device)
        return device
    }

    private suspend fun buildEntities(
        coroutineContext: CoroutineContext,
        voiceOutput: VoiceOutputImpl,
        deviceHolder: AtomicReference<EspHomeDevice?>,
        scope: CoroutineScope,
    ): List<Entity> {
        val keyAllocator = EntityKeyAllocator(0)
        val entities = mutableListOf<Entity>()

        // 布局传输：模板 sensor `sensor.astrion_layout` 的 `layout` 属性。
        // 属性不受 HA 255 字符状态限制，ContentAddr 订阅实体.attribute 即可
        // 收到任意长度布局，App 照 text 实体同样格式解析应用。
        haStatesStore.states
            .map { states -> states[TRANSPORT_LAYOUT_ID]?.state?.takeIf { it.isNotBlank() } }
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { state ->
                runCatching { panelConfigStore.applyLayoutJson(state) }
                    .onFailure { Timber.e(it, "Bad layout from transport sensor") }
            }
            .launchIn(scope)

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
            name = "Charging Display",
            objectId = "charging_display",
            // 充电时的空闲显示：保持屏保时钟，还是熄屏
            options = listOf(
                com.example.astrion.settings.DisplaySettings.CHARGING_SCREENSAVER,
                com.example.astrion.settings.DisplaySettings.CHARGING_SCREEN_OFF
            ),
            externalState = displaySettingsStore.chargingDisplay,
            scope = scope,
            onSelect = { displaySettingsStore.chargingDisplay.set(it) }
        )
        // 省电开关：不充电（不在底座）时是否保持语音唤醒，默认关
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Voice On Battery",
            objectId = "voice_on_battery",
            getState = microphoneSettingsStore.voiceOnBattery
        ) { microphoneSettingsStore.voiceOnBattery.set(it) }

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
            disabledByDefault = true,
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
        // Sensitivity maps to the microWakeWord probability cutoff as
        // `cutoff = 1 - sensitivity`; the default 0.03 equals the stock 0.97.
        entities += NumberEntity(
            key = keyAllocator.next(),
            name = "Wake Word Sensitivity",
            objectId = "wake_word_sensitivity",
            minValue = 0.01f,
            maxValue = 0.5f,
            step = 0.01f,
            getState = microphoneSettingsStore.wakeWordSensitivity
                .map { it ?: DEFAULT_WAKE_WORD_SENSITIVITY },
            setState = { microphoneSettingsStore.wakeWordSensitivity.set(it) }
        )
        // Device-side end-of-speech (VAD) tuning: the panel detects the
        // trailing silence itself and closes the audio stream
        // (`VoiceAssistantAudio.end`) so the conversation no longer depends
        // on the HA-side VAD. A threshold of 0 disables the local detector.
        entities += NumberEntity(
            key = keyAllocator.next(),
            name = "VAD Threshold",
            objectId = "vad_threshold",
            minValue = 0f,
            maxValue = 0.1f,
            step = 0.001f,
            getState = microphoneSettingsStore.vadThreshold,
            setState = { microphoneSettingsStore.vadThreshold.set(it) }
        )
        entities += NumberEntity(
            key = keyAllocator.next(),
            name = "VAD Timeout",
            objectId = "vad_timeout",
            minValue = 0.3f,
            maxValue = 5f,
            step = 0.1f,
            getState = microphoneSettingsStore.vadTimeout,
            setState = { microphoneSettingsStore.vadTimeout.set(it) }
        )

        // Microphone capture: built-in mic on the platform default voice
        // recognition source, like the original app — no source selection.
        // Noise suppression / echo cancellation / auto gain remain
        // individually switchable from Home Assistant.
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Noise Suppression",
            objectId = "noise_suppression",
            disabledByDefault = true,
            getState = audioProcessingSettingsStore.noiseSuppression
        ) { audioProcessingSettingsStore.noiseSuppression.set(it) }
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Echo Cancellation",
            objectId = "echo_cancellation",
            disabledByDefault = true,
            getState = audioProcessingSettingsStore.echoCancellation
        ) { audioProcessingSettingsStore.echoCancellation.set(it) }
        entities += SwitchEntity(
            key = keyAllocator.next(),
            name = "Auto Gain",
            objectId = "auto_gain",
            disabledByDefault = true,
            getState = audioProcessingSettingsStore.autoGain
        ) { audioProcessingSettingsStore.autoGain.set(it) }

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

        // Panel → HA event uplink (原 astrion/page_visited + control_command)
        entities += EventEntity(
            key = keyAllocator.next(),
            name = "Page Visited",
            objectId = "panel_page_visited",
            eventTypes = listOf("page_visited"),
            events = eventHub.pageVisited.map { "page_visited" }
        )
        entities += EventEntity(
            key = keyAllocator.next(),
            name = "Button Pressed",
            objectId = "panel_button_pressed",
            eventTypes = listOf("button_pressed", "key_pressed", "key_long_pressed"),
            events = kotlinx.coroutines.flow.merge(
                eventHub.buttonPressed.map { "button_pressed" },
                eventHub.keyEvents.map {
                    if (it.longPress) "key_long_pressed" else "key_pressed"
                }
            )
        )
        // The last physical key pressed, e.g. `135` / `135_long`
        entities += TextSensorEntity(
            key = keyAllocator.next(),
            name = "Panel Last Key",
            objectId = "panel_last_key",
            getState = eventHub.lastKey
        )
        // The panel's page list, mirrored to HA (原 navigate_list_upload)
        entities += TextSensorEntity(
            key = keyAllocator.next(),
            name = "Panel Pages",
            objectId = "panel_pages",
            getState = panelConfigStore.effectiveLayout.map { layout ->
                (layout.rooms.map { it.title } + layout.pages)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString(",")
            }
        )

        // HA-driven configuration channel: Home Assistant writes the panel
        // layout and IR codebook JSON into these text entities.
        entities += buildConfigEntities(keyAllocator)

        // Display / power tuning (§3.10.7 screensaver, §3.8 raise to wake)
        entities += buildDisplayEntities(keyAllocator)

        // OTA self-update channel (§3.9/§8.6)
        entities += buildOtaEntities(keyAllocator)

        // IR devices from the HA codebook
        entities += buildIrEntities(keyAllocator)

        // Gateway/activity controls
        entities += buildActivityEntities(scope, keyAllocator, deviceHolder)

        // Android diagnostics (battery / WiFi / storage / memory / uptime …)
        entities += buildDiagnosticEntities(scope, keyAllocator)

        entities += ButtonEntity(
            key = keyAllocator.next(),
            name = "Wake Assistant",
            objectId = "wake_assistant",
            onPress = {
                Timber.d("Wake Assistant button pressed")
                deviceHolder.get()?.voiceAssistant?.wakeAssistant()
            }
        )

        return entities
    }

    private suspend fun buildConfigEntities(
        keyAllocator: EntityKeyAllocator
    ): List<Entity> {
        val config = panelConfigStore.raw.first()
        return buildList {
            // 布局不再走 text 实体（HA 对 text 值限 255 字符），改由模板
            // sensor `sensor.astrion_layout` 的 `layout` 属性传输（见
            // TRANSPORT_LAYOUT_ID / blueprints/template/astrion_panel_layout.yaml）。
            // IR codebook 仍为单 text 实体（JSON 较短）。
            add(
                TextEntity(
                    key = keyAllocator.next(),
                    name = "IR Codes",
                    objectId = "astrion_ir_codes",
                    initialState = config.irCodesJson,
                    onText = { json -> panelConfigStore.applyIrCodesJson(json) }
                )
            )
        }
    }

    /**
     * Display / power tuning exposed to Home Assistant (§3.10.7/§3.8): the
     * screensaver idle seconds (0 disables it) and the raise-to-wake
     * accelerometer threshold in m/s² (0 disables it). Values are stored in
     * [DisplaySettingsStore] and picked up live by the screensaver controller
     * and the [RaiseToWakeController].
     */
    /**
     * Android diagnostics reported as hidden text sensors (☰): battery,
     * WiFi signal, storage, memory, uptime, IP, system and app version.
     * Refreshed every 30 s — cheap reads only, negligible battery impact.
     */
    private fun buildDiagnosticEntities(
        scope: CoroutineScope,
        keyAllocator: EntityKeyAllocator,
    ): List<Entity> {
        val battery = MutableStateFlow("")
        val wifiSignal = MutableStateFlow("")
        val storage = MutableStateFlow("")
        val memory = MutableStateFlow("")
        val uptime = MutableStateFlow("")
        val ipAddress = MutableStateFlow("")
        val system = MutableStateFlow("")
        val appVersion = MutableStateFlow("")

        suspend fun refresh() {
            val bm = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (bm != null) {
                val level = bm.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bm.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val plugged = bm.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                if (level >= 0 && scale > 0) {
                    battery.value = "${level * 100 / scale}%" + if (plugged) " (charging)" else ""
                }
            }
            wifiSignal.value = runCatching {
                val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                "${wm.connectionInfo.rssi} dBm"
            }.getOrDefault("n/a")
            storage.value = runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                "%.1f / %.1f GB".format(
                    stat.availableBytes / 1e9,
                    stat.totalBytes / 1e9
                )
            }.getOrDefault("n/a")
            memory.value = runCatching {
                val mi = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .getMemoryInfo(mi)
                "%.0f MB free / %.0f MB".format(mi.availMem / 1e6, mi.totalMem / 1e6)
            }.getOrDefault("n/a")
            uptime.value = runCatching {
                "%.1f h".format(SystemClock.elapsedRealtime() / 3_600_000.0)
            }.getOrDefault("n/a")
            ipAddress.value = getLocalIpAddress() ?: ""
            system.value = "Android ${Build.VERSION.RELEASE} · ${Build.MODEL}"
            appVersion.value = runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
        }

        scope.launch {
            while (currentCoroutineContext().isActive) {
                runCatching { refresh() }
                delay(30_000)
            }
        }

        return listOf(
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "Battery",
                objectId = "diag_battery",
                disabledByDefault = true,
                getState = battery
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "WiFi Signal",
                objectId = "diag_wifi_rssi",
                disabledByDefault = true,
                getState = wifiSignal
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "Storage",
                objectId = "diag_storage",
                disabledByDefault = true,
                getState = storage
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "Memory",
                objectId = "diag_memory",
                disabledByDefault = true,
                getState = memory
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "Uptime",
                objectId = "diag_uptime",
                disabledByDefault = true,
                getState = uptime
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "IP Address",
                objectId = "diag_ip_address",
                disabledByDefault = true,
                getState = ipAddress
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "System",
                objectId = "diag_system",
                disabledByDefault = true,
                getState = system
            ),
            TextSensorEntity(
                key = keyAllocator.next(),
                name = "App Version",
                objectId = "diag_app_version",
                disabledByDefault = true,
                getState = appVersion
            )
        )
    }

    private fun buildDisplayEntities(
        keyAllocator: EntityKeyAllocator
    ): List<Entity> = listOf(
        NumberEntity(
            key = keyAllocator.next(),
            name = "Screen Saver Timeout",
            objectId = "screen_saver_timeout",
            minValue = 0f,
            maxValue = 600f,
            step = 5f,
            getState = displaySettingsStore.screenSaverTimeout.map { it.toFloat() },
            setState = { seconds -> displaySettingsStore.screenSaverTimeout.set(seconds.roundToInt()) }
        ),
        NumberEntity(
            key = keyAllocator.next(),
            name = "Raise To Wake Threshold",
            objectId = "raise_to_wake_threshold",
            disabledByDefault = true,
            minValue = 0f,
            maxValue = 10f,
            step = 0.1f,
            getState = displaySettingsStore.raiseToWakeThreshold,
            setState = { displaySettingsStore.raiseToWakeThreshold.set(it) }
        )
    )

    /**
     * OTA self-update channel (§3.9/§8.6): Home Assistant pushes the update
     * manifest JSON ({version, url, sha256, force?}) into the text entity;
     * when its version is newer the panel shows an update banner and the
     * button (or the banner) downloads, verifies and installs the APK. Both
     * are advanced entities — hidden by default.
     */
    private suspend fun buildOtaEntities(
        keyAllocator: EntityKeyAllocator
    ): List<Entity> {
        otaUpdateManager.restore()
        return listOf(
            TextEntity(
                key = keyAllocator.next(),
                name = "OTA Manifest",
                objectId = "astrion_ota_manifest",
                disabledByDefault = true,
                initialState = otaUpdateManager.lastManifestJson(),
                onText = { json -> otaUpdateManager.applyManifestJson(json) }
            ),
            ButtonEntity(
                key = keyAllocator.next(),
                name = "OTA Install",
                objectId = "astrion_ota_install",
                disabledByDefault = true,
                onPress = {
                    Timber.d("OTA install requested from Home Assistant")
                    otaUpdateManager.installNow()
                }
            )
        )
    }

    private suspend fun buildIrEntities(keyAllocator: EntityKeyAllocator): List<Entity> {
        val codebook = panelConfigStore.irCodebook.first()

        val infraredManager = InfraredManager(context)
        if (!infraredManager.available) {
            Timber.w("This box has no IR emitter; skipping infrared entities")
            return emptyList()
        }

        val entities = mutableListOf<Entity>()

        // The hardware transmitter is always exposed so Home Assistant's
        // native infrared integration can drive the panel directly, even
        // before any codebook is pushed.
        entities += InfraredEntity(
            key = keyAllocator.next(),
            name = "Infrared",
            objectId = "infrared",
            transmit = { carrierFrequencyHz, timings, repeatCount ->
                irController.transmitTimings(carrierFrequencyHz, timings, repeatCount)
            }
        )

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
        scope: CoroutineScope,
        keyAllocator: EntityKeyAllocator,
        deviceHolder: AtomicReference<EspHomeDevice?>
    ): List<Entity> {
        val layout = panelConfigStore.effectiveLayout.first()
        val activityPages = (layout.rooms.map { it.title } + layout.pages)
            .filter { it.isNotBlank() }
            .distinct()
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
                        // HA-initiated: not announced as page_visited
                        activityNavigator.setPage(page, PageSource.AUTO)
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
                    activityNavigator.setPage(page, PageSource.AUTO)
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

    private fun MicrophoneSettingsStore.toVoiceInput(
        wakeWord: MicroWakeWord
    ) = VoiceInputImpl(
        microphone = audioProcessingSettingsStore.toMicrophone(),
        wakeWord = wakeWord,
        availableWakeWords = { get().availableWakeWords(context) },
        availableStopWords = { get().availableStopWords(context) },
        activeWakeWords = activeWakeWords,
        activeStopWords = activeStopWords,
        muted = muted
    )

    private fun AudioProcessingSettingsStore.toMicrophone() = audioRecordMicrophoneFlow(
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
        // Built-in microphone, platform default voice recognition source —
        // exactly what the original app used (no source selection).
        noiseSuppression = noiseSuppression,
        echoCancellation = echoCancellation,
        autoGain = autoGain
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

        /** Default wake word sensitivity, equal to the stock 0.97 cutoff. */
        const val DEFAULT_WAKE_WORD_SENSITIVITY = 0.03f

        /** Fixed HA sensor attribute carrying layouts of any length. */
        const val TRANSPORT_LAYOUT_ID = "sensor.astrion_layout.layout"
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
