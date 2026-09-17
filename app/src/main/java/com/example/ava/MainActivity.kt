package com.example.ava

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ava.esphome.Connected
import com.example.ava.ota.OtaUpdateManager
import com.example.ava.panel.KeyPress
import com.example.ava.panel.KeyRouter
import com.example.ava.panel.PanelUiEvents
import com.example.ava.panel.ScreensaverController
import com.example.ava.services.SatelliteStateHolder
import com.example.ava.services.VoiceSatelliteService
import com.example.ava.ui.PanelNavHost
import com.example.ava.ui.screens.panel.OtaUpdateBanner
import com.example.ava.ui.screens.panel.ScreensaverHost
import com.example.ava.ui.theme.AstrionPanelTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The appliance main screen: launches the satellite service (always) and
 * shows the remote UI. There are no in-app settings — everything is
 * configured from Home Assistant through the ESPHome integration.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var keyRouter: KeyRouter

    @Inject
    lateinit var panelUiEvents: PanelUiEvents

    @Inject
    lateinit var screensaverController: ScreensaverController

    @Inject
    lateinit var satelliteStateHolder: SatelliteStateHolder

    @Inject
    lateinit var otaUpdateManager: OtaUpdateManager

    private var pendingLongPress: Runnable? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (it.values.all { granted -> granted }) startSatelliteService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        screensaverController.start()
        setContent {
            AstrionPanelTheme {
                OnCreate()
                PanelNavHost(
                    openCard = panelUiEvents.openCard,
                    navigateBack = panelUiEvents.navigateBack,
                    goHome = panelUiEvents.goHome
                )
                val deviceState by satelliteStateHolder.deviceState.collectAsState()
                ScreensaverHost(
                    controller = screensaverController,
                    haConnected = deviceState == Connected
                )
                // OTA update prompt (§3.9/§8.6), above everything else.
                OtaUpdateBanner(manager = otaUpdateManager)
            }
        }
    }

    @Composable
    private fun OnCreate() {
        DisposableEffect(Unit) {
            if (hasAllPermissions()) {
                startSatelliteService()
            } else {
                permissionLauncher.launch(requiredPermissions())
            }
            onDispose { }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun startSatelliteService() {
        val intent = Intent(this, VoiceSatelliteService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    // ── Physical keys (§3.10.4/§3.10.5) ─────────────────────────────────
    // Short press fires on key-up, long press after 800ms on key-down
    // (original BaseActivity LONG_PRESS_TIMEOUT_MS). Every key is consumed:
    // the panel is a dedicated appliance, keys never reach the system.

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pendingLongPress == null) {
            val posted = Runnable {
                pendingLongPress = null
                dispatchKey(keyCode, longPress = true)
            }
            pendingLongPress = posted
            window.decorView.postDelayed(posted, LONG_PRESS_TIMEOUT_MS)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val posted = pendingLongPress
        if (posted != null) {
            window.decorView.removeCallbacks(posted)
            pendingLongPress = null
            dispatchKey(keyCode, longPress = false)
        }
        return true
    }

    private fun dispatchKey(keyCode: Int, longPress: Boolean) {
        // Any key first dismisses the screensaver without acting (§3.10.7:
        // 屏保显示中任意按键即退出).
        val wasScreensaverActive = screensaverController.active.value
        screensaverController.onUserActivity()
        if (wasScreensaverActive) return
        lifecycleScope.launch {
            keyRouter.dispatch(KeyPress(keyCode = keyCode, longPress = longPress))
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        screensaverController.onUserActivity()
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        /** Original BaseActivity long press threshold. */
        const val LONG_PRESS_TIMEOUT_MS = 800L
    }
}
