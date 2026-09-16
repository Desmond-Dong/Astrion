package com.example.ava.settings

import android.content.Context
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "display_settings.json"

/**
 * Display / power behaviours tuned from Home Assistant through number
 * entities (§3.10.7 screensaver, §3.8 raise to wake). The appliance has no
 * in-app settings — every knob lives in Home Assistant.
 */
@Serializable
data class DisplaySettings(
    /**
     * Idle seconds without key/touch before the screensaver overlay shows;
     * 0 keeps the screensaver off (default).
     */
    val screenSaverTimeout: Int = 0,
    /**
     * Raise-to-wake accelerometer threshold in m/s²; 0 disables the feature
     * (default). Picking the panel up produces jerk spikes well above 4.
     */
    val raiseToWakeThreshold: Float = 0f,
)

private val DEFAULT = DisplaySettings()

/**
 * Used to inject a concrete implementation of DisplaySettingsStore
 */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object DisplaySettingsModule {
    @Provides
    @Singleton
    fun provideDisplaySettingsStore(@ApplicationContext context: Context): DisplaySettingsStore =
        object : DisplaySettingsStore, SettingsStore<DisplaySettings> by SettingsStoreImpl(
            default = DEFAULT,
            produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
            serializer = DisplaySettings.serializer()
        ) {}
}

interface DisplaySettingsStore : SettingsStore<DisplaySettings> {
    /**
     * The screensaver idle timeout in seconds; 0 keeps the screensaver off.
     */
    val screenSaverTimeout: SettingState<Int>
        get() = setting(get = { screenSaverTimeout }, set = { copy(screenSaverTimeout = it) })

    /**
     * The raise-to-wake accelerometer threshold in m/s²; 0 keeps the feature
     * off.
     */
    val raiseToWakeThreshold: SettingState<Float>
        get() = setting(get = { raiseToWakeThreshold }, set = { copy(raiseToWakeThreshold = it) })
}
