package com.example.ava.panel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Panel → Home Assistant event uplink (§5.2): the original astrion
 * integration's bus events (`astrion/page_visited`, `astrion/control_command`)
 * survive here as payload-less announcements that the ESPHome event entities
 * (`panel_page_visited` / `panel_button_pressed`) forward to Home Assistant.
 *
 * ESPHome events carry no payload, so automations that need details read the
 * `current_activity` select or the `panel_pages` text sensor alongside.
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

    /** Announces a user-initiated page navigation (原 page_visited, source=user). */
    fun announcePageVisited() {
        _pageVisited.tryEmit(Unit)
    }

    /** Announces a remote/TV key press on the panel (原 control_command). */
    fun announceButtonPressed() {
        _buttonPressed.tryEmit(Unit)
    }
}
