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
 * 面板固件默认 `stay_on_while_plugged_in=7`（通电常亮），任何 keyevent
 * 睡眠都会被系统拉回唤醒状态，所以之前"息屏"只表现为调低亮度。这里先
 * 临时清掉常亮开关再发 KEYCODE_SLEEP(223) 强制睡眠，睡稳后把原值还原。
 *
 * 唤醒方式：面板物理电源键（关键码 26，已验证可唤醒）。
 *
 * 1. root：`su 0 settings put global stay_on_while_plugged_in 0` +
 *    `su 0 input keyevent 223`（本机固件的 su 授予 root，已验证）；
 * 2. 回退：背光亮度调到最低（仅 su 不可用时的兜底）。
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
            Timber.d("Screen off via root settings+keyevent")
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

        val previous = exec("settings", "get", "global", "stay_on_while_plugged_in")
            .trim().takeIf { it.isNotBlank() }

        exec("settings", "put", "global", "stay_on_while_plugged_in", "0")
        val cleared = exec("settings", "get", "global", "stay_on_while_plugged_in").trim() == "0"
        if (!cleared) {
            exec("settings", "put", "global", "stay_on_while_plugged_in", previous ?: "7")
            return false
        }

        // 强制睡眠。失败（异常）会走 onFailure 兜底。
        exec("input", "keyevent", "223")

        // 睡稳后还原常亮开关，保持设备原有行为（还原不影响已入睡的屏幕）。
        if (previous != null && previous != "0") {
            Thread {
                try {
                    Thread.sleep(3000)
                    exec("settings", "put", "global", "stay_on_while_plugged_in", previous)
                } catch (t: Throwable) {
                    Timber.w(t, "restore stay_on failed")
                }
            }.start()
        }

        true
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

    private fun exec(vararg args: String): String {
        val process = ProcessBuilder(*(listOf("su", "0") + args).toTypedArray())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val ok = process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        if (!ok) throw RuntimeException("su command failed: ${args.joinToString(" ")}: $output")
        return output
    }
}