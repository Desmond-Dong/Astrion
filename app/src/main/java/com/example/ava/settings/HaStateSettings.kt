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

private const val SETTINGS_FILE_NAME = "ha_state_settings.json"

@Serializable
data class HaStateSettings(
    val syncedEntityIds: List<String> = emptyList()
)

private val DEFAULT = HaStateSettings()

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object HaStateSettingsModule {
    @Provides
    @Singleton
    fun provideHaStateSettingsStore(@ApplicationContext context: Context): HaStateSettingsStore =
        object : HaStateSettingsStore,
            SettingsStore<HaStateSettings> by SettingsStoreImpl(
                default = DEFAULT,
                produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
                serializer = HaStateSettings.serializer()
            ) {}
}

interface HaStateSettingsStore : SettingsStore<HaStateSettings> {
    val syncedEntityIds: SettingState<List<String>>
        get() = setting(get = { syncedEntityIds }, set = { copy(syncedEntityIds = it) })

    suspend fun addEntityId(entityId: String) {
        if (entityId.isBlank()) return
        update {
            it.copy(syncedEntityIds = (it.syncedEntityIds + entityId.trim()).distinct())
        }
    }

    suspend fun removeEntityId(index: Int) {
        update {
            if (index !in it.syncedEntityIds.indices) it
            else it.copy(syncedEntityIds = it.syncedEntityIds.toMutableList().apply { removeAt(index) })
        }
    }
}