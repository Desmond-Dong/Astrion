package com.example.ava.services

import android.app.NotificationManager
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.ava.esphome.EspHomeDevice
import com.example.ava.esphome.Stopped
import com.example.ava.notifications.createVoiceSatelliteServiceNotification
import com.example.ava.notifications.createVoiceSatelliteServiceNotificationChannel
import com.example.ava.nsd.NsdRegistration
import com.example.ava.nsd.registerVoiceSatelliteNsd
import com.example.ava.settings.VoiceSatelliteSettings
import com.example.ava.settings.VoiceSatelliteSettingsStore
import com.example.ava.tasker.observeTaskerState
import com.example.ava.utils.setTrustAllSSLCertificates
import com.example.ava.utils.translate
import com.example.ava.wakelocks.WifiWakeLock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@androidx.annotation.OptIn(UnstableApi::class)
@AndroidEntryPoint
class VoiceSatelliteService() : LifecycleService() {
    @Inject
    lateinit var satelliteSettingsStore: VoiceSatelliteSettingsStore

    @Inject
    lateinit var deviceBuilder: DeviceBuilder

    @Inject
    lateinit var panelConfigStore: com.example.ava.panel.PanelConfigStore

    @Inject
    lateinit var satelliteStateHolder: SatelliteStateHolder

    @Inject
    lateinit var raiseToWakeController: RaiseToWakeController

    private val wifiWakeLock = WifiWakeLock()
    private var voiceSatelliteNsd = AtomicReference<NsdRegistration?>(null)
    private val _voiceSatellite = MutableStateFlow<EspHomeDevice?>(null)

    val voiceSatelliteState = _voiceSatellite.flatMapLatest {
        it?.voiceAssistant?.state ?: flowOf(Stopped)
    }

    val voiceTimers = _voiceSatellite.flatMapLatest {
        it?.voiceAssistant?.allTimers ?: flowOf(listOf())
    }

    fun startVoiceSatellite() {
        val serviceIntent = Intent(this, this::class.java)
        applicationContext.startForegroundService(serviceIntent)
    }

    fun stopVoiceSatellite() {
        val satellite = _voiceSatellite.getAndUpdate { null }
        if (satellite != null) {
            Timber.d("Stopping voice satellite")
            satellite.close()
            satelliteStateHolder.voiceAssistant = null
            voiceSatelliteNsd.getAndSet(null)?.unregister(this)
            wifiWakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiWakeLock.create(applicationContext, TAG)
        createVoiceSatelliteServiceNotificationChannel(this)
        updateNotificationOnStateChanges()
        startNetworkSettingsObserver()
        startTaskerStateObserver()
        startPanelConfigObserver()
        startDeviceStatePublisher()
        // Raise to wake (§3.8) follows the HA-configured threshold while the
        // always-on service runs.
        raiseToWakeController.start(lifecycleScope)
    }

    /** Mirrors the ESPHome device state for the always-on appliance UI. */
    private fun startDeviceStatePublisher() = _voiceSatellite
        .flatMapLatest { it?.state ?: emptyFlow() }
        .onEach { satelliteStateHolder.set(it) }
        .launchIn(lifecycleScope)

    class VoiceSatelliteBinder(val service: VoiceSatelliteService) : Binder()

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return VoiceSatelliteBinder(this)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleScope.launch {
            // already started?
            if (_voiceSatellite.value == null) {
                startSatellite()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @androidx.annotation.RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private suspend fun startSatellite() {
        Timber.d("Starting voice satellite")
        startForeground(
            2,
            createVoiceSatelliteServiceNotification(
                this@VoiceSatelliteService,
                Stopped.translate(resources)
            )
        )
        satelliteSettingsStore.ensureMacAddressIsSet()
        val settings = satelliteSettingsStore.get()
        val satellite =
            deviceBuilder.buildVoiceSatellite(lifecycleScope.coroutineContext)
                .apply { start() }
        _voiceSatellite.value = satellite
        // Exposed for the physical mic key push-to-talk (免唤醒对话).
        satelliteStateHolder.voiceAssistant = satellite.voiceAssistant
        voiceSatelliteNsd.set(registerVoiceSatelliteNsd(settings))
        wifiWakeLock.acquire()
    }

    /**
     * Rebuilds the ESPHome device when Home Assistant pushes new panel config:
     * the entity list (IR codebook buttons, navigate select options) is derived
     * from it. Debounced so a layout + IR codes push collapses into one rebuild.
     */
    @OptIn(FlowPreview::class)
    private fun startPanelConfigObserver() = lifecycleScope.launch {
        panelConfigStore.version
            .drop(1)
            .debounce(CONFIG_REBUILD_DEBOUNCE_MS)
            .collect {
                if (_voiceSatellite.value != null) {
                    Timber.i("Panel config changed, rebuilding the ESPHome device")
                    _voiceSatellite.getAndUpdate { null }?.close()
                    satelliteStateHolder.voiceAssistant = null
                    voiceSatelliteNsd.getAndSet(null)?.unregister(this@VoiceSatelliteService)
                    startSatellite()
                }
            }
    }

    fun startNetworkSettingsObserver() = satelliteSettingsStore.trustAllSSLCerts.onEach {
        setTrustAllSSLCertificates(it)
    }.launchIn(lifecycleScope)

    private fun startTaskerStateObserver() = lifecycleScope.launch {
        _voiceSatellite.collectLatest { it?.observeTaskerState(this@VoiceSatelliteService) }
    }

    private fun updateNotificationOnStateChanges() = _voiceSatellite
        .flatMapLatest {
            it?.voiceAssistant?.state ?: emptyFlow()
        }
        .onEach {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(
                2,
                createVoiceSatelliteServiceNotification(
                    this,
                    it.translate(resources)
                )
            )
        }
        .launchIn(lifecycleScope)

    private fun registerVoiceSatelliteNsd(settings: VoiceSatelliteSettings) =
        registerVoiceSatelliteNsd(
            context = this,
            name = settings.name,
            port = settings.serverPort,
            macAddress = settings.macAddress
        )

    override fun onDestroy() {
        raiseToWakeController.stop()
        _voiceSatellite.getAndUpdate { null }?.close()
        voiceSatelliteNsd.getAndSet(null)?.unregister(this)
        wifiWakeLock.release()
        super.onDestroy()
    }

    companion object {
        const val TAG = "VoiceSatelliteService"

        /**
         * Wait for Home Assistant to finish pushing all config text entities
         * (layout + IR codes) before rebuilding the device entity list.
         */
        const val CONFIG_REBUILD_DEBOUNCE_MS = 3000L
    }
}