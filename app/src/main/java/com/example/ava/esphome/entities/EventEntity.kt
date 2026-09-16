package com.example.ava.esphome.entities

import com.example.esphomeproto.api.EventResponse
import com.example.esphomeproto.api.ListEntitiesEventResponse
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.eventResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * An ESPHome `event` entity: the device announces occurrences to Home
 * Assistant, where automations can trigger on them.
 *
 * This carries the panel → HA event uplink that the original astrion
 * integration implemented with bus events (`astrion/page_visited`,
 * `astrion/control_command`): user actions on the panel become automatable
 * events in Home Assistant.
 */
class EventEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    val disabledByDefault: Boolean = false,
    val eventTypes: List<String>,
    /** Emits the event type to announce each time the event occurs. */
    private val events: Flow<String>,
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(ListEntitiesEventResponse.newBuilder().apply {
                key = this@EventEntity.key
                name = this@EventEntity.name
                objectId = this@EventEntity.objectId
                disabledByDefault = this@EventEntity.disabledByDefault
                addAllEventTypes(this@EventEntity.eventTypes)
            }.build())
        }
    }

    override fun subscribe(): Flow<MessageLite> = events.map { eventType ->
        eventResponse {
            key = this@EventEntity.key
            this.eventType = eventType
        }
    }
}
