package com.example.ava.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared source of truth for the currently displayed activity page.
 *
 * Both sides of the system write to and read from this store:
 * - The on-device UI (home screen page chips).
 * - The ESPHome "navigate" select (A-Type) received from Home Assistant,
 *   which jumps to a page and then resets.
 * - The ESPHome "current activity" select (B-Type) which mirrors the
 *   persistent page so Home Assistant automations can track it.
 */
@Singleton
class ActivityNavigator @Inject constructor() {
    private val _currentPage = MutableStateFlow("")
    val currentPage: StateFlow<String> = _currentPage.asStateFlow()

    fun setPage(page: String) {
        if (_currentPage.value != page) {
            _currentPage.value = page
        }
    }
}