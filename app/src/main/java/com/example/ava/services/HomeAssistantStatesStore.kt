package com.example.ava.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single Home Assistant entity state imported into the panel.
 *
 * When no [attribute] is set the value is the entity's primary state value.
 */
data class HaEntityState(
    val entityId: String,
    val state: String,
    val attribute: String = "",
)

/**
 * Collects Home Assistant entity states pushed to the panel through the
 * ESPHome homeassistant API (`HomeAssistantStateResponse`). The panel renders
 * these as the on-device "device tree", matching the original HaRemote/Astrion
 * behaviour where rooms/pages and their device states come from Home Assistant.
 */
@Singleton
class HomeAssistantStatesStore @Inject constructor() {
    private val _states = MutableStateFlow<Map<String, HaEntityState>>(emptyMap())
    val states: StateFlow<Map<String, HaEntityState>> = _states.asStateFlow()

    private val _entities = MutableStateFlow<Set<String>>(emptySet())
    val entities: StateFlow<Set<String>> = _entities.asStateFlow()

    fun import(state: HaEntityState) {
        val key = if (state.attribute.isEmpty()) state.entityId else "${state.entityId}.${state.attribute}"
        _states.update { it + (key to state) }
        _entities.update { it + state.entityId }
    }

    fun clear() {
        _states.value = emptyMap()
        _entities.value = emptySet()
    }
}