package com.example.astrion.ha

import android.content.Context
import androidx.datastore.dataStoreFile
import com.example.astrion.settings.SettingsStore
import com.example.astrion.settings.SettingState
import com.example.astrion.settings.SettingsStoreImpl
import com.example.astrion.utils.getRandomMacAddressString
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "ha_connection_settings.json"

/**
 * The panel's Home Assistant connection. The panel talks to Home Assistant
 * directly over its WebSocket API — there is no ESPHome adoption anymore — so
 * the address and a long-lived access token are configured on the device
 * (设置 → 连接设置).
 */
@Serializable
data class HaConnectionSettings(
    /** Display name of the panel; reported during pairing. */
    val name: String = "Astrion Panel",
    /** Home Assistant host, e.g. `172.16.1.1`. */
    val host: String = "",
    /** Home Assistant HTTP(S) port (WebSocket reuses it). */
    val port: Int = 8123,
    /** Long-lived access token from the HA profile page. */
    val token: String = "",
    /** Whether to use `wss://` instead of `ws://`. */
    val useSsl: Boolean = false,
    /**
     * Stable random identity of this panel (the gateway `serial_number` in
     * the astrion integration). Random on first start, never regenerated.
     */
    val serialNumber: String = "",
) {
    /** True once host and token are configured. */
    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()
}

private val DEFAULT = HaConnectionSettings()

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object HaConnectionSettingsModule {
    @Provides
    @Singleton
    fun provideHaConnectionSettingsStore(@ApplicationContext context: Context): HaConnectionSettingsStore =
        object : HaConnectionSettingsStore,
            SettingsStore<HaConnectionSettings> by SettingsStoreImpl(
                default = DEFAULT,
                produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
                serializer = HaConnectionSettings.serializer()
            ) {}
}

interface HaConnectionSettingsStore : SettingsStore<HaConnectionSettings> {
    val name: SettingState<String>
        get() = setting(get = { name }, set = { copy(name = it) })

    val host: SettingState<String>
        get() = setting(get = { host }, set = { copy(host = it.trim()) })

    val port: SettingState<Int>
        get() = setting(get = { port }, set = { copy(port = it) })

    val token: SettingState<String>
        get() = setting(get = { token.trim() }, set = { copy(token = it.trim()) })

    val useSsl: SettingState<Boolean>
        get() = setting(get = { useSsl }, set = { copy(useSsl = it) })

    /**
     * Persists the connection details in one write.
     */
    suspend fun update(name: String, host: String, port: Int, token: String, useSsl: Boolean) {
        update {
            it.copy(
                name = name.trim().ifBlank { DEFAULT.name },
                host = host.trim(),
                port = port.coerceIn(1, 65535),
                token = token.trim(),
                useSsl = useSsl
            )
        }
    }

    /**
     * Ensures a stable serial number has been generated and persisted.
     */
    suspend fun ensureSerialIsSet() {
        update {
            if (it.serialNumber.isBlank()) it.copy(serialNumber = getRandomMacAddressString()) else it
        }
    }
}
