package com.example.astrion.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.NumberCommandRequest
import com.example.esphomeproto.api.NumberMode
import com.example.esphomeproto.api.numberStateResponse
import com.example.esphomeproto.api.listEntitiesNumberResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * A numeric value that Home Assistant can set (the ESPHome `number` domain).
 *
 * Used for runtime-tunable voice parameters such as wake word sensitivity.
 */
class NumberEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    val disabledByDefault: Boolean = false,
    private val minValue: Float,
    private val maxValue: Float,
    private val step: Float = 0.01f,
    private val mode: NumberMode = NumberMode.NUMBER_MODE_SLIDER,
    getState: Flow<Float>,
    private val setState: suspend (Float) -> Unit
) : Entity {
    private val state = getState

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesNumberResponse {
                disabledByDefault = this@NumberEntity.disabledByDefault
                key = this@NumberEntity.key
                name = this@NumberEntity.name
                objectId = this@NumberEntity.objectId
                minValue = this@NumberEntity.minValue
                maxValue = this@NumberEntity.maxValue
                step = this@NumberEntity.step
                mode = this@NumberEntity.mode
            })

            is NumberCommandRequest -> {
                if (message.key == key) {
                    val value = message.state.coerceIn(minValue, maxValue)
                    Timber.d("Number command received for $name: $value")
                    setState(value)
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = state.map {
        numberStateResponse {
            key = this@NumberEntity.key
            state = it
            missingState = false
        }
    }
}
