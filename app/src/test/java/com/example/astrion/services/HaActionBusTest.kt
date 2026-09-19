package com.example.astrion.services

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class HaActionBusTest {

    @Test
    fun `callService emits a service call with data map`() = runTest {
        val bus = HaActionBus()
        val deferred = async { bus.requests.first() }
        yield()

        bus.callService(
            "remote.send_command",
            mapOf("entity_id" to "remote.mi_tv", "command" to "POWER")
        )

        val request: HaServiceCall = deferred.await()
        assertEquals("remote.send_command", request.service)
        assertEquals("remote.mi_tv", request.data["entity_id"])
        assertEquals("POWER", request.data["command"])
    }

    @Test
    fun `callService with no data emits empty map`() = runTest {
        val bus = HaActionBus()
        val deferred = async { bus.requests.first() }
        yield()

        bus.callService("light.turn_on")

        val request: HaServiceCall = deferred.await()
        assertEquals("light.turn_on", request.service)
        assertEquals(0, request.data.size)
    }
}
