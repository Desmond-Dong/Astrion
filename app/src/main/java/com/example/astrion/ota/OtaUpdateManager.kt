package com.example.astrion.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "ota_settings.json"

/**
 * Update manifest pushed by Home Assistant through the `astrion_ota_manifest`
 * text entity (§3.9/§8.6):
 *
 * ```
 * {"version": "1.2.1", "url": "https://.../astrion.apk", "sha256": "...", "force": false}
 * ```
 */
@Serializable
data class OtaManifest(
    val version: String = "",
    val url: String = "",
    val sha256: String = "",
    val force: Boolean = false,
) {
    val isValid: Boolean get() = version.isNotBlank() && url.isNotBlank()

    companion object {
        fun fromJson(json: String): OtaManifest? = try {
            Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), json)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

@Serializable
data class OtaSettings(
    /** Last manifest JSON received from Home Assistant (persisted). */
    val manifestJson: String = "",
)

private val OTA_DEFAULT = OtaSettings()

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object OtaSettingsModule {
    @Provides
    @Singleton
    fun provideOtaSettingsStore(@ApplicationContext context: Context): OtaSettingsStore =
        object : OtaSettingsStore, SettingsStore<OtaSettings> by SettingsStoreImpl(
            default = OTA_DEFAULT,
            produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
            serializer = OtaSettings.serializer()
        ) {}
}

interface OtaSettingsStore : SettingsStore<OtaSettings> {
    val manifestJson: SettingState<String>
        get() = setting(get = { manifestJson }, set = { copy(manifestJson = it) })
}

/** Progress of an OTA install triggered from the panel or Home Assistant. */
sealed interface OtaInstallState {
    data object Idle : OtaInstallState
    data class Downloading(val percent: Int) : OtaInstallState
    data object Verifying : OtaInstallState
    data object Installing : OtaInstallState

    /** The package left the panel (root install) or the prompt was accepted. */
    data object Done : OtaInstallState
    data class Failed(val message: String) : OtaInstallState
}

/**
 * OTA self-update channel (§3.9/§8.6): Home Assistant pushes a manifest into
 * the `astrion_ota_manifest` text entity; when its version is newer than the
 * installed one the panel UI shows an update banner, and pressing the
 * `astrion_ota_install` button (or the banner) downloads the APK, verifies the
 * optional SHA-256 checksum and installs it.
 *
 * Two install paths, in order:
 * 1. **Root**: `su -c pm install -r` with the APK staged under
 *    `/data/local/tmp` (the deployed HA100 is rooted).
 * 2. **PackageInstaller**: a session commit that surfaces the system install
 *    confirmation prompt (non-root fallback).
 */
@Singleton
class OtaUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: OtaSettingsStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _availableUpdate = MutableStateFlow<OtaManifest?>(null)
    val availableUpdate: StateFlow<OtaManifest?> = _availableUpdate.asStateFlow()

    private val _installState = MutableStateFlow<OtaInstallState>(OtaInstallState.Idle)
    val installState: StateFlow<OtaInstallState> = _installState.asStateFlow()

    private val currentVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private var commitReceiverRegistered = false

    /**
     * Restores the last persisted manifest (entity initial state) and flags an
     * update when it is newer than the installed version. Called when the
     * device entity list is built.
     */
    suspend fun restore() {
        applyManifest(settingsStore.manifestJson.get(), autoInstall = false)
    }

    /** The last persisted manifest JSON (text entity initial state). */
    suspend fun lastManifestJson(): String = settingsStore.manifestJson.get()

    /**
     * Applies a manifest JSON pushed through the text entity. Invalid JSON is
     * rejected; a newer version shows the banner, a forced update installs
     * right away.
     */
    suspend fun applyManifestJson(json: String): Boolean {
        val manifest = OtaManifest.fromJson(json)
        if (manifest?.isValid != true) {
            Timber.w("Rejected invalid OTA manifest (${json.length} chars)")
            return false
        }
        settingsStore.manifestJson.set(json.trim())
        applyManifest(json.trim(), autoInstall = true)
        return true
    }

    private suspend fun applyManifest(json: String, autoInstall: Boolean) {
        val manifest = OtaManifest.fromJson(json) ?: return
        val update = manifest.takeIf { isNewerVersion(currentVersion, it.version) }
        if (update != null) {
            Timber.i("OTA update available: ${update.version} (installed: $currentVersion)")
        } else {
            Timber.i("OTA manifest ${manifest.version} not newer than $currentVersion")
        }
        _availableUpdate.value = update
        if (update != null && autoInstall && update.force) {
            Timber.i("OTA manifest flagged force, installing immediately")
            scope.launch { installNow() }
        }
    }

    /**
     * Downloads the pending update, verifies it and installs it. Safe to call
     * repeatedly; fails gracefully when no valid manifest is pending. The
     * blocking work runs on IO so callers (HA button, UI) are never stalled.
     */
    suspend fun installNow() {
        val manifest = _availableUpdate.value
        if (manifest == null || !manifest.isValid) {
            _installState.value = OtaInstallState.Failed("没有待安装的更新")
            return
        }
        withContext(Dispatchers.IO) {
            try {
                _installState.value = OtaInstallState.Downloading(0)
                val apk = download(manifest.url) { percent ->
                    _installState.value = OtaInstallState.Downloading(percent)
                }
                _installState.value = OtaInstallState.Verifying
                verifySha256(apk, manifest.sha256)
                _installState.value = OtaInstallState.Installing
                if (!installWithRoot(apk)) {
                    Timber.i("Root install unavailable, falling back to PackageInstaller prompt")
                    installWithPackageInstaller(apk)
                }
            } catch (e: Exception) {
                Timber.e(e, "OTA install failed")
                _installState.value = OtaInstallState.Failed(e.message ?: "安装失败")
            }
        }
    }

    /** Downloads [url] into the cache dir, reporting percent [0, 100]. */
    private fun download(url: String, onProgress: (Int) -> Unit): File {
        val target = File(context.cacheDir, APK_FILE_NAME)
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.connect()
            if (connection.responseCode !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw IOException("下载失败: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        Timber.i("OTA APK downloaded (${target.length()} bytes)")
        return target
    }

    /** Skips silently when the manifest carries no checksum. */
    private fun verifySha256(apk: File, expectedSha256: String) {
        if (expectedSha256.isBlank()) {
            Timber.w("OTA manifest has no sha256, skipping checksum verification")
            return
        }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
            throw IOException("SHA-256 校验失败")
        }
        Timber.i("OTA APK SHA-256 verified")
    }

    /**
     * Root path: stage the APK under `/data/local/tmp` (per §8.6) and run
     * `pm install -r` through `su`. Returns false when root is unavailable.
     */
    private fun installWithRoot(apk: File): Boolean = try {
        val remote = REMOTE_APK_PATH
        val command =
            "cp ${apk.absolutePath} $remote && chmod 644 $remote && pm install -r $remote"
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        Timber.d("Root install exit=$exit out=$output err=$error")
        exit == 0
    } catch (e: Exception) {
        Timber.w(e, "Root install unavailable")
        false
    }

    /**
     * Non-root fallback: a PackageInstaller session whose commit surfaces the
     * system confirmation prompt (ACTION_INSTALL_PROMPT equivalent).
     */
    private fun installWithPackageInstaller(apk: File) {
        ensureCommitReceiver()
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = packageInstaller.createSession(sessionParams)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite(APK_FILE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val confirmIntent = Intent(ACTION_INSTALL_COMMIT).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                context, sessionId, confirmIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
            Timber.i("OTA session committed, waiting for user confirmation")
        } finally {
            session.close()
        }
    }

    /** Registers (once) the receiver that tracks the install session result. */
    private fun ensureCommitReceiver() {
        if (commitReceiverRegistered) return
        commitReceiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiveContext: Context?, intent: Intent?) {
                intent ?: return
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                Intent.EXTRA_INTENT, Intent::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            receiveContext?.startActivity(confirm)
                        }
                    }

                    PackageInstaller.STATUS_SUCCESS -> {
                        Timber.i("OTA PackageInstaller install confirmed")
                        _installState.value = OtaInstallState.Done
                    }

                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "安装确认失败"
                        Timber.w("OTA PackageInstaller failed: $message")
                        _installState.value = OtaInstallState.Failed(message)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_INSTALL_COMMIT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private companion object {
        const val APK_FILE_NAME = "astrion_update.apk"

        /** Staging path used with the root install (§8.6). */
        const val REMOTE_APK_PATH = "/data/local/tmp/astrion_update.apk"

        const val ACTION_INSTALL_COMMIT = "com.example.ota.action.INSTALL_COMMIT"

        const val CONNECT_TIMEOUT_MS = 15000
        const val READ_TIMEOUT_MS = 60000
        const val BUFFER_SIZE = 8192
        const val HTTP_OK_MIN = 200
        const val HTTP_OK_MAX = 299
    }
}

/**
 * True when [candidate] is a strictly newer version than [current]. Dotted
 * numeric versions compare component-wise; anything else falls back to "any
 * difference is an update".
 */
fun isNewerVersion(current: String, candidate: String): Boolean {
    if (candidate.isBlank() || candidate == current) return false
    val currentParts = current.split('.').map { it.toIntOrNull() }
    val candidateParts = candidate.split('.').map { it.toIntOrNull() }
    if (currentParts.all { it != null } && candidateParts.all { it != null }) {
        val count = maxOf(currentParts.size, candidateParts.size)
        for (index in 0 until count) {
            val a = currentParts.getOrNull(index) ?: 0
            val b = candidateParts.getOrNull(index) ?: 0
            if (a != b) return b > a
        }
        return false
    }
    return true
}
