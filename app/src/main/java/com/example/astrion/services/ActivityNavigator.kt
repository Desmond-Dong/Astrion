package com.example.astrion.services

import com.example.astrion.panel.PanelEventHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where a page navigation originated. */
object PageSource {
    /** Local user action (screen tap, physical key binding) — automatable. */
    const val USER = "user"

    /** Home Assistant initiated (navigate select, scene change) — not announced. */
    const val AUTO = "auto"
}

/**
 * Shared source of truth for the currently displayed activity page.
 *
 * Both sides of the system write to and read from this store:
 * - The on-device UI (home screen page chips).
 * - The ESPHome "navigate" select (A-Type) received from Home Assistant,
 *   which jumps to a page and then resets.
 * - The ESPHome "current activity" select (B-Type) which mirrors the
 *   persistent page so Home Assistant automations can track it.
 *
 * User-initiated jumps additionally announce `page_visited` on the
 * [PanelEventHub] (原 astrion/page_visited, source=user) so HA automations can
 * react to panel navigation; HA-initiated jumps do not announce, matching the
 * original `source=auto` behaviour (§2.5/§3.7).
 */
@Singleton
class ActivityNavigator @Inject constructor(
    private val eventHub: PanelEventHub
) {
    private val _currentPage = MutableStateFlow("")
    val currentPage: StateFlow<String> = _currentPage.asStateFlow()

    fun setPage(page: String, source: String = PageSource.USER) {
        if (_currentPage.value != page) {
            _currentPage.value = page
            if (source == PageSource.USER) {
                eventHub.announcePageVisited()
            }
        }
    }
}
