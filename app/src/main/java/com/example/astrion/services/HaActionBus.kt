package com.example.astrion.services

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A single Home Assistant service call request from the card UI. */
data class HaServiceCall(
    /** Full service name, e.g. `light.turn_on` / `remote.send_command`. */
    val service: String,
    /** String service data, e.g. `entity_id` / `command`. */
    val data: Map<String, String> = emptyMap(),
)

/**
 * Device → Home Assistant service call channel.
 *
 * Card screens emit calls here; [com.example.astrion.ha.HaPanelBridge]
 * forwards them to Home Assistant as WebSocket `call_service` commands
 * (§5.2: 按键控制 → 对目标实体走 HA 服务).
 */
@Singleton
class HaActionBus @Inject constructor() {
    private val _requests = MutableSharedFlow<HaServiceCall>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val requests: SharedFlow<HaServiceCall> = _requests.asSharedFlow()

    /**
     * Requests Home Assistant to execute [service] (e.g. `remote.send_command`)
     * with the given string [data] map (e.g. `entity_id` / `command`).
     */
    suspend fun callService(service: String, data: Map<String, String> = emptyMap()) {
        _requests.emit(HaServiceCall(service, data))
    }
}
