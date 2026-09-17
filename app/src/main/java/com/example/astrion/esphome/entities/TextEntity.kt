package com.example.astrion.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.TextCommandRequest
import com.example.esphomeproto.api.listEntitiesTextResponse
import com.example.esphomeproto.api.textStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * A text entity that Home Assistant can write to (the ESPHome `text` domain).
 *
 * This is the configuration channel that makes the panel fully configurable
 * from Home Assistant (§5.2/§6.1 of the requirements): Home Assistant pushes
 * panel layout JSON, HA entity sync lists and IR codebooks into these entities
 * via `text.set_value`, and the panel reacts to [onText].
 *
 * The protocol carries the state as a protobuf string, so a generous
 * [maxLength] is advertised to Home Assistant to allow sizeable JSON blobs.
 */
class TextEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    val disabledByDefault: Boolean = false,
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    initialState: String = "",
    private val onText: suspend (String) -> Unit = {},
) : Entity {
    private val state = MutableStateFlow(initialState)

    /**
     * Pushes a new value from the box side so Home Assistant state stays in
     * sync (for example after the panel rewrites its own config).
     */
    fun updateState(newState: String) {
        state.value = newState
    }

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesTextResponse {
                disabledByDefault = this@TextEntity.disabledByDefault
                key = this@TextEntity.key
                name = this@TextEntity.name
                objectId = this@TextEntity.objectId
                maxLength = this@TextEntity.maxLength
            })

            is TextCommandRequest -> {
                if (message.key == key) {
                    Timber.d("Text command received for $name (${message.state.length} chars)")
                    state.value = message.state
                    onText(message.state)
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = state.map {
        textStateResponse {
            key = this@TextEntity.key
            state = it
            missingState = it.isEmpty()
        }
    }

    companion object {
        /**
         * ESPHome firmware caps text entities at 255 chars, but the API
         * protocol itself has no such limit and the HA integration honours the
         * advertised max_length, so config JSON payloads fit in one entity.
         */
        const val DEFAULT_MAX_LENGTH = 16384
    }
}
