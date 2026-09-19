package com.example.astrion.ha

/** Connection state of the panel's Home Assistant WebSocket client. */
sealed interface HaConnectionState {
    data object Disconnected : HaConnectionState
    data object Connecting : HaConnectionState
    data object Connected : HaConnectionState
    data class Error(val message: String) : HaConnectionState
}

fun HaConnectionState.displayText(): String = when (this) {
    HaConnectionState.Connected -> "已连接"
    HaConnectionState.Connecting -> "连接中…"
    HaConnectionState.Disconnected -> "未连接"
    is HaConnectionState.Error -> message
}
