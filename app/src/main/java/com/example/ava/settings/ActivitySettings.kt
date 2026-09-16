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

private const val SETTINGS_FILE_NAME = "activity_settings.json"

@Serializable
data class ActivitySettings(
    val pages: List<String> = emptyList()
)

private val DEFAULT = ActivitySettings()

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object ActivitySettingsModule {
    @Provides
    @Singleton
    fun provideActivitySettingsStore(@ApplicationContext context: Context): ActivitySettingsStore =
        object : ActivitySettingsStore,
            SettingsStore<ActivitySettings> by SettingsStoreImpl(
                default = DEFAULT,
                produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
                serializer = ActivitySettings.serializer()
            ) {}
}

interface ActivitySettingsStore : SettingsStore<ActivitySettings> {
    val pages: SettingState<List<String>>
        get() = setting(get = { pages }, set = { copy(pages = it) })

    suspend fun addPage(name: String) {
        if (name.isBlank()) return
        update {
            it.copy(pages = it.pages + name.trim())
        }
    }

    suspend fun removePage(index: Int) {
        update {
            if (index !in it.pages.indices) it
            else it.copy(pages = it.pages.filterIndexed { i, _ -> i != index })
        }
    }
}