package com.example.astrion.services

import com.example.esphomeproto.api.HomeassistantActionRequest
import com.example.esphomeproto.api.homeassistantActionRequest
import com.example.esphomeproto.api.homeassistantServiceMap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device → Home Assistant service call channel.
 *
 * The ESPHome API has no client-side `call_service`, but the device (acting as
 * the API server) can ask Home Assistant to execute services through
 * [HomeassistantActionRequest] — the same message ESPHome firmware's
 * `homeassistant.service` action uses (§5.2: 按键控制 → 对目标实体走 HA 服务).
 *
 * Card screens emit calls here; [com.example.astrion.esphome.EspHomeDevice]
 * forwards them to the connected Home Assistant client.
 */
@Singleton
class HaActionBus @Inject constructor() {
    private val _requests = MutableSharedFlow<HomeassistantActionRequest>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val requests: SharedFlow<HomeassistantActionRequest> = _requests.asSharedFlow()

    /**
     * Requests Home Assistant to execute [service] (e.g. `remote.send_command`)
     * with the given string [data] map (e.g. `entity_id` / `command`).
     */
    suspend fun callService(service: String, data: Map<String, String> = emptyMap()) {
        _requests.emit(homeassistantActionRequest {
            this.service = service
            data.forEach { (key, value) ->
                this.data.add(homeassistantServiceMap {
                    this.key = key
                    this.value = value
                })
            }
        })
    }
}
