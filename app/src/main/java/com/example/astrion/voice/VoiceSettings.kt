package com.example.astrion.voice

import android.content.Context
import androidx.datastore.dataStoreFile
import com.example.astrion.settings.SettingsStore
import com.example.astrion.settings.SettingsStoreImpl
import com.example.astrion.settings.SettingState
import com.example.astrion.settings.setting
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "voice_settings.json"

/**
 * 语音功能设置（下拉快捷面板调节）：
 * - [wakeEnabled]：唤醒词常驻监听开关
 * - [wakeWordId]：唤醒词模型（assets/wakeWords 下的 id，如 hey_jarvis）
 * - [wakeSensitivity]：唤醒灵敏度 0.0-1.0（越高越容易唤醒）
 * - [micMuted]：麦克风静音（暂停唤醒监听与会话）
 */
@Serializable
data class VoiceSettings(
    val wakeEnabled: Boolean = true,
    val wakeWordId: String = "hey_jarvis",
    val wakeSensitivity: Float = 0.5f,
    val micMuted: Boolean = false,
)

private val DEFAULT = VoiceSettings()

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object VoiceSettingsModule {
    @Provides
    @Singleton
    fun provideVoiceSettingsStore(@ApplicationContext context: Context): VoiceSettingsStore =
        object : VoiceSettingsStore, SettingsStore<VoiceSettings> by SettingsStoreImpl(
            default = DEFAULT,
            produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
            serializer = VoiceSettings.serializer()
        ) {}
}

interface VoiceSettingsStore : SettingsStore<VoiceSettings> {
    val wakeEnabled: SettingState<Boolean>
        get() = setting(get = { wakeEnabled }, set = { copy(wakeEnabled = it) })

    val wakeWordId: SettingState<String>
        get() = setting(get = { wakeWordId }, set = { copy(wakeWordId = it) })

    val wakeSensitivity: SettingState<Float>
        get() = setting(get = { wakeSensitivity }, set = { copy(wakeSensitivity = it) })

    val micMuted: SettingState<Boolean>
        get() = setting(get = { micMuted }, set = { copy(micMuted = it) })
}
