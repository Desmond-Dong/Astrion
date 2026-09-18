package com.example.astrion.services

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真熄屏（背光全灭）：
 *
 * 1. root：`su 0 input keyevent 26`（本机固件的 su 授予 root，已验证）；
 * 2. 回退：背光亮度调到最低（面板驱动有最低亮度底限，无法全黑，
 *    故仅作为 su 不可用时的兜底）。
 *
 * 原版官方 App 是系统 uid，直接持有 DEVICE_POWER 权限；本应用以普通
 * 应用运行，因此借助固件自带的 su。
 */
@Singleton
class ScreenOffController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var suTested = false
    private var suWorks = false

    /** 息屏（背光全灭）。返回是否走了真熄屏路径。 */
    fun turnScreenOffNow(): Boolean {
        if (tryRootScreenOff()) {
            Timber.d("Screen off via root keyevent")
            return true
        }
        return runCatching {
            Settings.System.putInt(
                context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 0
            )
            Timber.d("Screen off via brightness 0 (root unavailable)")
            true
        }.getOrDefault(false)
    }

    private fun tryRootScreenOff(): Boolean = runCatching {
        ensureSuTested()
        if (!suWorks) return false
        val process = ProcessBuilder("su", "0", "input", "keyevent", "26").start()
        process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
    }.onFailure {
        Timber.w(it, "su screen-off failed")
    }.getOrDefault(false)

    private fun ensureSuTested() {
        if (suTested) return
        suTested = true
        runCatching {
            val process = ProcessBuilder("su", "0", "id").start()
            val output = process.inputStream.bufferedReader().readText()
            suWorks = process.waitFor(3, TimeUnit.SECONDS) &&
                output.contains("uid=0")
        }.onFailure {
            suWorks = false
        }
    }
}
