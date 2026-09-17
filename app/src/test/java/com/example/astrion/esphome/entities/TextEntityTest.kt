package com.example.astrion.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesTextResponse
import com.example.esphomeproto.api.TextCommandRequest
import com.example.esphomeproto.api.TextStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEntityTest {

    @Test
    fun `list entities advertises the text entity with max length`() = runTest {
        val entity = TextEntity(
            key = 42,
            name = "Panel Layout",
            objectId = "astrion_layout"
        )

        val responses = entity.handleMessage(ListEntitiesRequest.getDefaultInstance()).toList()

        assertEquals(1, responses.size)
        val response = responses[0] as ListEntitiesTextResponse
        assertEquals(42, response.key)
        assertEquals("Panel Layout", response.name)
        assertEquals("astrion_layout", response.objectId)
        assertEquals(TextEntity.DEFAULT_MAX_LENGTH, response.maxLength)
    }

    @Test
    fun `text command invokes callback and updates state`() = runTest {
        val received = mutableListOf<String>()
        val entity = TextEntity(
            key = 7,
            name = "IR Codes",
            objectId = "astrion_ir_codes",
            onText = { received.add(it) }
        )

        entity.handleMessage(TextCommandRequest.newBuilder().apply {
            key = 7
            state = "{\"dev\": {\"POWER\": \"abc\"}}"
        }.build()).toList()

        assertEquals(1, received.size)
        assertEquals("{\"dev\": {\"POWER\": \"abc\"}}", received[0])

        // Subscribed state now reports the new value
        val stateResponse = entity.subscribe().first() as TextStateResponse
        assertEquals(7, stateResponse.key)
        assertEquals("{\"dev\": {\"POWER\": \"abc\"}}", stateResponse.state)
        assertTrue(!stateResponse.missingState)
    }

    @Test
    fun `commands for other keys are ignored`() = runTest {
        val received = mutableListOf<String>()
        val entity = TextEntity(
            key = 7,
            name = "Panel Layout",
            objectId = "astrion_layout",
            onText = { received.add(it) }
        )

        val messages: List<MessageLite> = entity.handleMessage(
            TextCommandRequest.newBuilder().apply {
                key = 99
                state = "should be ignored"
            }.build()
        ).toList()

        assertTrue(messages.isEmpty())
        assertTrue(received.isEmpty())

        val stateResponse = entity.subscribe().first() as TextStateResponse
        assertTrue(stateResponse.missingState)
    }
}
