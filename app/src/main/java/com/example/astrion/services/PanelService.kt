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
import timber.log.Timber
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
    lateinit var enrollServer: com.example.astrion.ha.HaEnrollServer

    @Inject
    lateinit var connectionSettingsStore: com.example.astrion.ha.HaConnectionSettingsStore

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

    /**
     * startForeground 兜底：应用更新后的首次调用可能撞上 system_server 的
     * UidRecord 竞态（NPE，本设备实测单次 300ms 重试不够，新旧版本都崩过）。
     * 立即试一次，失败转后台线程指数退避重试（总时长控制在前台服务 5 秒
     * 窗口内）；全部失败才放弃，START_STICKY 会再次拉起服务。
     */
    private fun startForegroundSafe(id: Int, notification: android.app.Notification) {
        if (tryStartForeground(id, notification)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            var delayMs = 300L
            repeat(5) { attempt ->
                kotlinx.coroutines.delay(delayMs)
                Timber.w("startForeground race (attempt %d), retrying", attempt + 1)
                if (tryStartForeground(id, notification)) return@launch
                delayMs = (delayMs * 2).coerceAtMost(1_000)
            }
            Timber.e("startForeground kept failing; giving up until the service is restarted")
        }
    }

    private fun tryStartForeground(id: Int, notification: android.app.Notification): Boolean =
        runCatching { startForeground(id, notification) }
            .onFailure { Timber.w(it, "startForeground failed") }
            .isSuccess

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
        // 网页配对：未配置时自动开启临时 HTTP 配对服务（手机浏览器粘贴令牌），
        // 配置成功后自动关闭。
        lifecycleScope.launch {
            connectionSettingsStore.getFlow { isConfigured }.collect { configured ->
                if (configured) enrollServer.stop() else enrollServer.start()
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
        enrollServer.stop()
        panelBridge.stop()
        raiseToWakeController.stop()
        wifiWakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "PanelService"
    }
}
