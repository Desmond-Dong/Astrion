package com.example.ava.esphome.entities

import com.example.esphomeproto.api.ButtonCommandRequest
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesButtonResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * A momentary push button. Pressing it from Home Assistant runs [onPress].
 *
 * This maps the original gateway "refresh" button and the per-command IR
 * buttons exposed by the old astrion integration to ESPHome-native buttons.
 */
class ButtonEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    private val onPress: suspend () -> Unit
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesButtonResponse {
                key = this@ButtonEntity.key
                name = this@ButtonEntity.name
                objectId = this@ButtonEntity.objectId
            })

            is ButtonCommandRequest -> {
                if (message.key == key) {
                    Timber.d("Button command received: $name")
                    onPress()
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = emptyFlow() // Buttons are momentary and have no state
}