package com.example.astrion.panel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Panel → Home Assistant event uplink (§5.2): page visits / button and key
 * presses are announced here and forwarded by the WebSocket bridge as
 * astrion bus events, so HA automations can bind any panel action.
 *
 * Events carry no payload beyond the serial; automations that need details
 * read the astrion integration's select entities alongside.
 */
@Singleton
class PanelEventHub @Inject constructor() {
    private val _pageVisited = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pageVisited: SharedFlow<Unit> = _pageVisited.asSharedFlow()

    private val _buttonPressed = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val buttonPressed: SharedFlow<Unit> = _buttonPressed.asSharedFlow()

    private val _keyEvents = MutableSharedFlow<KeyPress>(
        replay = 0, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val keyEvents: SharedFlow<KeyPress> = _keyEvents.asSharedFlow()

    /** The last physical key reported, e.g. `135` / `135_long`. */
    private val _lastKey = MutableStateFlow("")
    val lastKey: StateFlow<String> = _lastKey.asStateFlow()

    /** Announces a user-initiated page navigation (原 page_visited, source=user). */
    fun announcePageVisited() {
        _pageVisited.tryEmit(Unit)
    }

    /** Announces a remote/TV key press on the panel (原 control_command). */
    fun announceButtonPressed() {
        _buttonPressed.tryEmit(Unit)
    }

    /**
     * Announces every physical key press (short/long) so Home Assistant can
     * see which key was used and bind automations to it.
     */
    fun announceKeyPressed(keyCode: Int, longPress: Boolean) {
        _lastKey.value = if (longPress) "${keyCode}_long" else "$keyCode"
        _keyEvents.tryEmit(KeyPress(keyCode = keyCode, longPress = longPress))
    }
}
