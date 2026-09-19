package com.example.astrion.services

import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.astrion.ha.HaConnectionState
import com.example.astrion.ha.displayText
import com.example.astrion.notifications.createPanelServiceNotification
import com.example.astrion.notifications.createPanelServiceNotificationChannel
import com.example.astrion.wakelocks.WifiWakeLock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * The panel's always-on connection service: keeps the Home Assistant
 * WebSocket bridge ([com.example.astrion.ha.HaPanelBridge]) alive — pairing,
 * layout pulls, state imports and card actions all flow through it.
 */
@AndroidEntryPoint
class PanelService : LifecycleService() {
    @Inject
    lateinit var panelBridge: com.example.astrion.ha.HaPanelBridge

    @Inject
    lateinit var raiseToWakeController: RaiseToWakeController

    private val wifiWakeLock = WifiWakeLock()
    private val started = AtomicReference(false)

    fun startPanel() {
        val serviceIntent = Intent(this, this::class.java)
        applicationContext.startForegroundService(serviceIntent)
    }

    override fun onCreate() {
        super.onCreate()
        wifiWakeLock.create(applicationContext, TAG)
        createPanelServiceNotificationChannel(this)
        updateNotificationOnStateChanges()
        startForegroundWatchdog()
        // Raise to wake (§3.8) follows the display settings threshold while
        // the always-on service runs.
        raiseToWakeController.start(lifecycleScope)
    }

    /**
     * Re-asserts the foreground notification periodically so OEM app managers
     * cannot demote the service while the panel sits idle.
     */
    private fun startForegroundWatchdog() = lifecycleScope.launch {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            runCatching {
                startForegroundSafe(
                    2,
                    createPanelServiceNotification(
                        this@PanelService,
                        panelBridge.state.value.displayText()
                    )
                )
            }
        }
    }

    class PanelBinder(val service: PanelService) : Binder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return PanelBinder(this)
    }

    /** startForeground 带 300ms 重试：应用更新后的首次调用可能撞上
     *  system_server 的 UidRecord 竞态（一次性 NPE）。 */
    private fun startForegroundSafe(id: Int, notification: android.app.Notification) {
        try {
            startForeground(id, notification)
        } catch (e: RuntimeException) {
            runCatching { Thread.sleep(300) }
            startForeground(id, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Assert foreground synchronously on every start: some OEM app
        // managers (DuraSpeed on this panel) stop "idle" services after
        // ~90s, which would otherwise drop the Home Assistant connection.
        startForegroundSafe(
            2,
            createPanelServiceNotification(
                this,
                panelBridge.state.value.displayText()
            )
        )
        lifecycleScope.launch {
            if (!started.get()) {
                started.set(true)
                panelBridge.start(lifecycleScope)
                wifiWakeLock.acquire()
            }
        }
        // Stick around: the panel is a dedicated always-on appliance.
        return START_STICKY
    }

    private fun updateNotificationOnStateChanges() = panelBridge.state
        .onEach {
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                    2,
                    createPanelServiceNotification(this, it.displayText())
                )
            }
        }
        .launchIn(lifecycleScope)

    override fun onDestroy() {
        panelBridge.stop()
        raiseToWakeController.stop()
        wifiWakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "PanelService"
    }
}
