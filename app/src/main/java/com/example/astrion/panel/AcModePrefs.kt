package com.example.astrion.panel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 原版 AcControlView 的电源模式记忆：当前 hvac_mode 存入
 * SharedPreferences `AcModePrefs`（键 `cached_mode_<entityId>`），关机再开机
 * 时恢复。首页物理键电源与空调详情页共用同一份记忆。
 */
@Singleton
class AcModePrefs @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("AcModePrefs", Context.MODE_PRIVATE)

    fun getCachedMode(entityId: String): String? =
        prefs.getString(cachedModeKey(entityId), null)?.takeIf { it.isNotBlank() }

    fun setCachedMode(entityId: String, mode: String) {
        prefs.edit().putString(cachedModeKey(entityId), mode).apply()
    }

    private fun cachedModeKey(entityId: String) = "cached_mode_$entityId"
}
