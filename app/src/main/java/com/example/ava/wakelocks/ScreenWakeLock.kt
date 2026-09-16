package com.example.ava.wakelocks

import android.content.Context
import android.content.Context.POWER_SERVICE
import android.os.PowerManager
import timber.log.Timber

/**
 * Short bright-screen wakeup used by raise to wake (§3.8 WakeupUtils).
 *
 * `SCREEN_BRIGHT_WAKE_LOCK` is deprecated since API 33 but still works and is
 * the only way to light up the screen without the signature-protected
 * `PowerManager.wakeUp`; the HA100 runs Android 8.1 where the deprecated flags
 * behave exactly like the original app.
 */
class ScreenWakeLock(context: Context) {
    private val powerManager =
        context.getSystemService(POWER_SERVICE) as PowerManager

    private val wakeLock: PowerManager.WakeLock = powerManager.newWakeLock(
        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
        "$TAG::RaiseToWake"
    )

    /** Lights the screen up and keeps it bright for [timeoutMs]. */
    fun wakeUp(timeoutMs: Long) {
        @Suppress("DEPRECATION")
        wakeLock.acquire(timeoutMs)
        Timber.d("Raise to wake: screen woken for ${timeoutMs}ms")
    }

    fun release() {
        if (wakeLock.isHeld)
            wakeLock.release()
        Timber.d("Raise to wake lock released")
    }

    private companion object {
        const val TAG = "Astrion"
    }
}
