package com.example.astrion.ui.screens.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.example.astrion.R
import com.example.astrion.ha.HaConnectionSettingsStore
import com.example.astrion.ha.HaConnectionState
import com.example.astrion.ha.HaPanelBridge
import com.example.astrion.ha.displayText
import com.example.astrion.ui.theme.RemoteBackground
import com.example.astrion.ui.theme.RemoteColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home Assistant connection settings (地址 / 端口 / 访问令牌 / 名称).
 *
 * The panel talks to Home Assistant directly over its WebSocket API: the
 * address and a long-lived access token (HA 个人资料页 → 安全 → 长寿命访问
 * 令牌) are entered here, then the panel pairs itself with the astrion
 * integration automatically.
 */
@HiltViewModel
class HaSettingsViewModel @Inject constructor(
    private val connectionSettingsStore: HaConnectionSettingsStore,
    val panelBridge: HaPanelBridge,
) : ViewModel() {
    val connectionState = panelBridge.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HaConnectionState.Disconnected)

    /** Serial number (gateway identity) shown read-only for reference. */
    val serialNumber = connectionSettingsStore.getFlow { serialNumber }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    suspend fun loadSettings(): com.example.astrion.ha.HaConnectionSettings =
        connectionSettingsStore.get()

    fun save(name: String, host: String, port: Int, token: String, useSsl: Boolean) {
        viewModelScope.launch {
            connectionSettingsStore.update(name, host, port, token, useSsl)
            // The client picks the new settings up on its next reconnect.
            panelBridge.reconnect()
        }
    }
}

@Composable
fun HaSettingsScreen(
    navController: NavController,
    viewModel: HaSettingsViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serialNumber by viewModel.serialNumber.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8123") }
    var token by remember { mutableStateOf("") }
    var useSsl by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val settings = viewModel.loadSettings()
        name = settings.name
        host = settings.host
        port = settings.port.toString()
        token = settings.token
        useSsl = settings.useSsl
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RemoteBackground)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back_24px),
                    contentDescription = "返回",
                    tint = RemoteColors.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "连接设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = RemoteColors.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = connectionState.displayText(),
                color = when (connectionState) {
                    HaConnectionState.Connected -> RemoteColors.secondary
                    is HaConnectionState.Error -> RemoteColors.error
                    else -> RemoteColors.onSurfaceVariant
                },
                fontSize = 14.sp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (loaded) {
                SettingsField(
                    label = "设备名称",
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true
                )
                SettingsField(
                    label = "Home Assistant 地址",
                    value = host,
                    onValueChange = { host = it },
                    placeholder = "172.16.1.1",
                    singleLine = true
                )
                SettingsField(
                    label = "端口",
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    placeholder = "8123",
                    singleLine = true
                )
                SettingsField(
                    label = "访问令牌",
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "HA 个人资料页 → 长寿命访问令牌",
                    singleLine = true,
                    password = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text("使用 SSL (wss://)", color = RemoteColors.onSurface, fontSize = 14.sp)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = useSsl, onCheckedChange = { useSsl = it })
                }
                Text(
                    text = "serial: $serialNumber",
                    color = RemoteColors.hintText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Text(
                    text = "保存后面板会自动重连，并向 Astrion 集成上报配对；" +
                            "然后在 HA「设置 → 设备与服务 → Astrion Remote」里完成配对，" +
                            "并在其子条目里配置分类卡片。",
                    color = RemoteColors.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = RemoteColors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.save(
                                name = name,
                                host = host,
                                port = port.toIntOrNull() ?: 8123,
                                token = token,
                                useSsl = useSsl
                            )
                        }
                ) {
                    Text(
                        text = "保存并重连",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = false,
    password: Boolean = false,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            color = RemoteColors.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            placeholder = { Text(placeholder, color = RemoteColors.hintText, fontSize = 14.sp) },
            visualTransformation = if (password) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = RemoteColors.onSurface,
                unfocusedTextColor = RemoteColors.onSurface,
                focusedBorderColor = RemoteColors.accent,
                unfocusedBorderColor = RemoteColors.key
            )
        )
    }
}
