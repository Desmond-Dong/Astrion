package com.example.astrion.settings

import android.content.Context
import androidx.core.net.toUri
import androidx.datastore.dataStoreFile
import com.example.astrion.wakewords.providers.AssetWakeWordProvider
import com.example.astrion.wakewords.providers.DocumentTreeWakeWordProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "microphone_settings.json"

@Serializable
data class MicrophoneSettings(
    val wakeWord: String = "okay_nabu",
    val secondWakeWord: String? = null,
    val stopWord: String = "stop",
    val customWakeWordLocation: String? = null,
    val muted: Boolean = false,
    /**
     * Wake word sensitivity (0.01..0.5), mapped onto the model probability
     * cutoff as `cutoff = 1 - sensitivity`. `null` uses each model's own
     * default cutoff.
     */
    val wakeWordSensitivity: Float? = null,
    /**
     * Device-side end-of-speech (VAD) silence threshold, as a fraction of
     * full scale. Audio averaging below this level counts as silence; `0`
     * disables the local detector and falls back to the server-side VAD.
     */
    val vadThreshold: Float = 0.008f,
    /**
     * Device-side end-of-speech (VAD) silence timeout in seconds: how long
     * the audio must stay silent after speech before the panel finishes the
     * utterance on its own.
     */
    val vadTimeout: Float = 1.2f,
    /**
     * Keep the wake-word engine running on battery. Default false: the
     * wake-word inference (always-on CPU) only runs while the panel is on
     * the charging dock, unless explicitly enabled here.
     */
    val voiceOnBattery: Boolean = false,
)

private val DEFAULT = MicrophoneSettings()

/**
 * Used to inject a concrete implementation of MicrophoneSettingsStore
 */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object MicrophoneSettingsModule {
    @Provides
    @Singleton
    fun provideMicrophoneSettingsStore(@ApplicationContext context: Context): MicrophoneSettingsStore =
        object : MicrophoneSettingsStore, SettingsStore<MicrophoneSettings> by SettingsStoreImpl(
            default = DEFAULT,
            produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
            serializer = MicrophoneSettings.serializer()
        ) {}
}

interface MicrophoneSettingsStore : SettingsStore<MicrophoneSettings> {
    /**
     * The wake word to use for wake word detection.
     */
    val wakeWord: SettingState<String>
        get() = setting(get = { wakeWord }, set = { copy(wakeWord = it) })

    /**
     * Optional second wake word to use for wake word detection.
     */
    val secondWakeWord: SettingState<String?>
        get() = setting(get = { secondWakeWord }, set = { copy(secondWakeWord = it) })

    /**
     * The stop word to use for stop word detection.
     */
    val stopWord: SettingState<String>
        get() = setting(get = { stopWord }, set = { copy(stopWord = it) })

    /**
     * The Uri of the directory containing custom wake words or null if not set.
     */
    val customWakeWordLocation: SettingState<String?>
        get() = setting(
            get = { customWakeWordLocation },
            set = { copy(customWakeWordLocation = it) })

    /**
     * Keep the wake-word engine running while off the dock (power hungry).
     */
    val voiceOnBattery: SettingState<Boolean>
        get() = setting(get = { voiceOnBattery }, set = { copy(voiceOnBattery = it) })

    /**
     * The muted state of the microphone.
     */
    val muted: SettingState<Boolean>
        get() = setting(get = { muted }, set = { copy(muted = it) })

    /**
     * Wake word sensitivity; `null` means "use the model default cutoff".
     */
    val wakeWordSensitivity: SettingState<Float?>
        get() = setting(
            get = { wakeWordSensitivity },
            set = { copy(wakeWordSensitivity = it) }
        )

    /**
     * Device-side end-of-speech (VAD) silence threshold; `0` disables the
     * local end-of-speech detection.
     */
    val vadThreshold: SettingState<Float>
        get() = setting(
            get = { vadThreshold },
            set = { copy(vadThreshold = it) }
        )

    /**
     * Device-side end-of-speech (VAD) silence timeout in seconds.
     */
    val vadTimeout: SettingState<Float>
        get() = setting(
            get = { vadTimeout },
            set = { copy(vadTimeout = it) }
        )

    /**
     * Helper property that allows getting and setting [wakeWord] and [secondWakeWord] as a list.
     */
    val activeWakeWords
        get() = SettingState(
            flow = combine(wakeWord, secondWakeWord) { wakeWord, secondWakeWord ->
                listOfNotNull(wakeWord, secondWakeWord)
            }
        ) {
            if (it.isNotEmpty()) {
                wakeWord.set(it[0])
                secondWakeWord.set(it.getOrNull(1))
            } else Timber.w("Attempted to set empty active wake word list")
        }

    /**
     * Helper property that allows getting and setting [stopWord] as a list.
     */
    val activeStopWords
        get() = SettingState(
            flow = stopWord.map { listOf(it) }
        ) {
            if (it.isNotEmpty()) {
                stopWord.set(it[0])
            } else Timber.w("Attempted to set empty stop word list")
        }
}

/**
 * Returns a list of available wake words from configured providers.
 */
suspend fun MicrophoneSettings.availableWakeWords(context: Context) =
    if (customWakeWordLocation != null) {
        AssetWakeWordProvider(assets = context.assets).get() + DocumentTreeWakeWordProvider(
            context = context,
            treeUri = customWakeWordLocation.toUri()
        ).get()
    } else AssetWakeWordProvider(assets = context.assets).get()


/**
 * Returns a list of available stop words from configured providers.
 */
suspend fun MicrophoneSettings.availableStopWords(context: Context) =
    AssetWakeWordProvider(
        assets = context.assets,
        path = "stopWords"
    ).get()