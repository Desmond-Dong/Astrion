package com.example.astrion.esphome.entities

import com.example.esphomeproto.api.InfraredRFTransmitRawTimingsRequest
import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.listEntitiesInfraredResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * Capability flag for an infrared entity, matching
 * esphome/components/infrared/infrared.h (enum InfraredCapability).
 *
 * Bit 0 (1): can transmit signals.
 * Bit 1 (2): can receive signals (never advertised here, see [InfraredManager]).
 */
const val INFRARED_CAPABILITY_TRANSMIT = 1

/**
 * Exposes a single infrared device to Home Assistant.
 *
 * Home Assistant sends raw timings to the device through
 * [InfraredRFTransmitRawTimingsRequest], which are forwarded to the platform
 * IR emitter. Entities are per configured device so the user can select in
 * Home Assistant which devices the box should control.
 */
class InfraredEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    private val transmit: suspend (carrierFrequencyHz: Int, timings: List<Int>, repeatCount: Int) -> Unit
) : Entity {
    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(listEntitiesInfraredResponse {
                key = this@InfraredEntity.key
                name = this@InfraredEntity.name
                objectId = this@InfraredEntity.objectId
                capabilities = INFRARED_CAPABILITY_TRANSMIT
            })

            is InfraredRFTransmitRawTimingsRequest -> {
                if (message.key == key) {
                    Timber.d(
                        "Transmitting IR code for $name: " +
                            "${message.timingsCount} timings at ${message.carrierFrequency}Hz " +
                            "repeated ${message.repeatCount} times"
                    )
                    transmit(message.carrierFrequency, message.timingsList, message.repeatCount)
                }
            }
        }
    }

    override fun subscribe(): Flow<MessageLite> = emptyFlow()
}