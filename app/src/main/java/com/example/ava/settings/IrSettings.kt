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

private const val SETTINGS_FILE_NAME = "ir_settings.json"

@Serializable
data class IrDeviceSettings(
    val name: String,
    val objectId: String? = null,
    val enabled: Boolean = true,
    val defaultCarrierFrequencyHz: Int = 38000,
    val buttons: Map<String, List<Int>> = emptyMap()
)

@Serializable
data class IrSettings(
    val devices: List<IrDeviceSettings> = emptyList()
)

private val DEFAULT = IrSettings()

/**
 * Used to inject a concrete implementation of IrSettingsStore
 */
@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object IrSettingsModule {
    @Provides
    @Singleton
    fun provideIrSettingsStore(@ApplicationContext context: Context): IrSettingsStore =
        object : IrSettingsStore,
            SettingsStore<IrSettings> by SettingsStoreImpl(
                default = DEFAULT,
                produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
                serializer = IrSettings.serializer()
            ) {}
}

interface IrSettingsStore : SettingsStore<IrSettings> {
    /**
     * The infrared devices exposed as ESPHome entities.
     */
    val irDevices: SettingState<List<IrDeviceSettings>>
        get() = setting(get = { devices }, set = { copy(devices = it) })

    /**
     * Adds a device with the given name. The object id is derived from the name.
     */
    suspend fun addIrDevice(name: String) {
        if (name.isBlank()) return
        update {
            it.copy(devices = it.devices + IrDeviceSettings(
                name = name.trim(),
                objectId = irObjectId(name)
            ))
        }
    }

    /**
     * Renames the device at the given index and derives a new object id.
     */
    suspend fun renameIrDevice(index: Int, name: String) {
        if (name.isBlank()) return
        update {
            if (index !in it.devices.indices) it
            else {
                val devices = it.devices.toMutableList()
                devices[index] = devices[index].copy(name = name.trim(), objectId = irObjectId(name))
                it.copy(devices = devices)
            }
        }
    }

    /**
     * Enables or disables the device at the given index. Disabled devices are
     * hidden from Home Assistant but kept in the settings.
     */
    suspend fun setIrDeviceEnabled(index: Int, enabled: Boolean) {
        update {
            if (index !in it.devices.indices) it
            else {
                val devices = it.devices.toMutableList()
                devices[index] = devices[index].copy(enabled = enabled)
                it.copy(devices = devices)
            }
        }
    }

    /**
     * Removes the device at the given index.
     */
    suspend fun removeIrDevice(index: Int) {
        update {
            if (index !in it.devices.indices) it
            else it.copy(devices = it.devices.filterIndexed { deviceIndex, _ -> deviceIndex != index })
        }
    }

    suspend fun addButtonToDevice(
        deviceIndex: Int,
        buttonName: String,
        timings: List<Int>,
        carrierFrequencyHz: Int = 38000
    ) {
        if (buttonName.isBlank()) return
        update {
            if (deviceIndex !in it.devices.indices) it
            else {
                val devices = it.devices.toMutableList()
                val device = devices[deviceIndex]
                devices[deviceIndex] = device.copy(
                    buttons = device.buttons + (buttonName.trim() to timings),
                    defaultCarrierFrequencyHz = carrierFrequencyHz
                )
                it.copy(devices = devices)
            }
        }
    }

    suspend fun removeButtonFromDevice(deviceIndex: Int, buttonName: String) {
        update {
            if (deviceIndex !in it.devices.indices) it
            else {
                val devices = it.devices.toMutableList()
                val device = devices[deviceIndex]
                devices[deviceIndex] = device.copy(buttons = device.buttons - buttonName)
                it.copy(devices = devices)
            }
        }
    }
}

/**
 * Derives an ESPHome safe object id from a display name.
 */
fun irObjectId(name: String): String {
    val cleaned = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString(separator = "")
        .trim('_')
    return cleaned.ifEmpty { "ir_device" }
}