package com.example.astrion.services

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
 * Collects Home Assistant entity states pulled over the WebSocket API
 * (`get_states` + `state_changed` events, written by
 * [com.example.astrion.ha.HaPanelBridge]). The panel renders these as the
 * on-device "device tree", matching the original HaRemote/Astrion behaviour
 * where rooms/pages and their device states come from Home Assistant.
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

    /**
     * Drops every imported entry whose entity is not in [entityIds] — used
     * when a new layout retires previously synced entities without wiping
     * the live state of the surviving ones.
     */
    fun retainEntities(entityIds: Set<String>) {
        _states.update { map ->
            map.filterKeys { key -> entityOfKey(key) in entityIds }
        }
        _entities.update { set -> set.filter { it in entityIds }.toSet() }
    }

    private fun entityOfKey(key: String): String {
        val parts = key.split('.')
        return if (parts.size > 2) parts.take(2).joinToString(".") else key
    }
}