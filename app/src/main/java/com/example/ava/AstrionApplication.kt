package com.example.ava

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@HiltAndroidApp
class AstrionApplication : Application() {

    @Inject
    lateinit var crashFailsafe: CrashFailsafe

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        crashFailsafe.attach(this)
    }
}

/**
 * Crash-loop failsafe for the unattended appliance (§6.2 崩溃有兜底): when the
 * app crashes repeatedly in a short window, the config datastore files pushed
 * from Home Assistant are quarantined so the next start begins from a clean,
 * known-good state instead of looping on the same bad data forever.
 *
 * The device identity store (mac address) is never touched, so the panel
 * keeps its Home Assistant adoption across a failsafe reset.
 */
class CrashFailsafe @Inject constructor() {
    private val crashTimes = ArrayDeque<Long>()
    private val defaultHandler = AtomicReference<Thread.UncaughtExceptionHandler?>(null)

    fun attach(app: Application) {
        defaultHandler.compareAndSet(null, Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val now = System.currentTimeMillis()
                var count = 0
                synchronized(crashTimes) {
                    crashTimes.add(now)
                    while (crashTimes.isNotEmpty() && now - crashTimes.first() > CRASH_WINDOW_MS) {
                        crashTimes.removeFirst()
                    }
                    count = crashTimes.size
                }
                if (count >= CRASH_LIMIT) {
                    quarantineConfigFiles(app)
                }
            }
            defaultHandler.get()?.uncaughtException(thread, throwable)
        }
    }

    private fun quarantineConfigFiles(app: Application) {
        val datastore = File(app.filesDir, "datastore")
        val stamp = System.currentTimeMillis()
        datastore.listFiles()?.forEach { file ->
            if (file.name in QUARANTINE_FILES) {
                val backup = File(datastore, "${file.name}.quarantine_$stamp")
                if (file.renameTo(backup)) {
                    Timber.w("Quarantined ${file.name} after repeated crashes")
                }
            }
        }
        synchronized(crashTimes) { crashTimes.clear() }
    }

    companion object {
        const val CRASH_LIMIT = 3
        const val CRASH_WINDOW_MS = 5 * 60_000L
        val QUARANTINE_FILES = setOf(
            "panel_config.json",
            "audio_processing_settings.json",
            "display_settings.json",
        )
    }
}
