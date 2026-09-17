package com.example.astrion.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesTextSensorResponse
import com.example.esphomeproto.api.textSensorStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * A text entity whose string state is published to Home Assistant.
 *
 * Used to expose box-side information that has no richer ESPHome counterpart,
 * for example the currently playing media title/artist or diagnostics.
 */
class TextSensorEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    val disabledByDefault: Boolean = false,
    private val getState: Flow<String>,
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesTextSensorResponse {
                disabledByDefault = this@TextSensorEntity.disabledByDefault
                key = this@TextSensorEntity.key
                name = this@TextSensorEntity.name
                objectId = this@TextSensorEntity.objectId
            })
        }
    }

    override fun subscribe(): Flow<MessageLite> = getState.map {
        textSensorStateResponse {
            key = this@TextSensorEntity.key
            state = it
            missingState = it.isEmpty()
        }
    }
}