package com.example.ava.ui.screens.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ava.ota.OtaInstallState
import com.example.ava.ota.OtaUpdateManager
import com.example.ava.ui.theme.RemoteColors
import kotlinx.coroutines.launch

/**
 * Update banner shown above the panel UI once Home Assistant has pushed an
 * OTA manifest whose version is newer than the installed one (§3.9/§8.6).
 * The install button (and the `astrion_ota_install` HA button) trigger
 * [OtaUpdateManager.installNow].
 */
@Composable
fun OtaUpdateBanner(manager: OtaUpdateManager) {
    val update by manager.availableUpdate.collectAsState()
    val installState by manager.installState.collectAsState()
    val manifest = update ?: return
    val scope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp),
            shape = RoundedCornerShape(16.dp),
            color = RemoteColors.surface,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "发现新版本 ${manifest.version}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = RemoteColors.onSurface
                    )
                    Text(
                        text = statusLine(installState),
                        style = MaterialTheme.typography.bodySmall,
                        color = RemoteColors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(14.dp))
                when (val state = installState) {
                    is OtaInstallState.Downloading -> CircularProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier.width(24.dp)
                    )

                    OtaInstallState.Verifying, OtaInstallState.Installing ->
                        CircularProgressIndicator(modifier = Modifier.width(24.dp))

                    OtaInstallState.Idle -> TextButton(
                        onClick = { scope.launch { manager.installNow() } }
                    ) { Text(text = "安装") }

                    OtaInstallState.Done -> Text(
                        text = "完成",
                        color = RemoteColors.secondary,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    is OtaInstallState.Failed -> Text(
                        text = "失败",
                        color = RemoteColors.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun statusLine(state: OtaInstallState): String = when (state) {
    OtaInstallState.Idle -> "点按“安装”升级面板固件"
    is OtaInstallState.Downloading -> "下载中 ${state.percent}%"
    OtaInstallState.Verifying -> "校验 SHA-256…"
    OtaInstallState.Installing -> "安装中…"
    OtaInstallState.Done -> "安装完成，应用即将重启"
    is OtaInstallState.Failed -> "失败：${state.message}"
}
