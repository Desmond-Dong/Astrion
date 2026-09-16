package com.example.ava.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesSelectResponse
import com.example.esphomeproto.api.SelectCommandRequest
import com.example.esphomeproto.api.selectStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * A selection list backed by a persistent value.
 *
 * Selecting a value from Home Assistant calls [onSelect]. Box-side changes
 * are pushed to Home Assistant through [updateState].
 *
 * This is the ESPHome-native equivalent of the original gateway scene select
 * ("current page / activity") entity.
 */
class SelectEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    private val options: List<String>,
    initialState: String = "",
    private val onSelect: suspend (String) -> Unit = {}
) : Entity {
    private val state = MutableStateFlow(initialState)

    /**
     * Pushes a new value from the box side (for example when the active
     * activity changes locally) so Home Assistant state stays in sync.
     */
    fun updateState(newState: String) {
        state.value = newState
    }

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
is ListEntitiesRequest -> emit(ListEntitiesSelectResponse.newBuilder().apply {
            key = this@SelectEntity.key
            name = this@SelectEntity.name
            objectId = this@SelectEntity.objectId
            addAllOptions(this@SelectEntity.options)
        }.build())

            is SelectCommandRequest -> {
                if (message.key == key && message.state in options) {
                    Timber.d("Select command received for $name: ${message.state}")
                    state.value = message.state
                    onSelect(message.state)
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = state.map {
        selectStateResponse {
            key = this@SelectEntity.key
            this.state = it
            missingState = it.isEmpty()
        }
    }
}