package com.example.ava.services

import com.example.esphomeproto.api.HomeassistantActionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class HaActionBusTest {

    @Test
    fun `callService emits an action request with data map`() = runTest {
        val bus = HaActionBus()
        val deferred = async { bus.requests.first() }
        yield()

        bus.callService(
            "remote.send_command",
            mapOf("entity_id" to "remote.mi_tv", "command" to "POWER")
        )

        val request: HomeassistantActionRequest = deferred.await()
        assertEquals("remote.send_command", request.service)
        val dataMap = request.dataList.associate { it.key to it.value }
        assertEquals("remote.mi_tv", dataMap["entity_id"])
        assertEquals("POWER", dataMap["command"])
    }

    @Test
    fun `callService keeps the given service and pairs verbatim`() = runTest {
        val bus = HaActionBus()
        val deferred = async { bus.requests.first() }
        yield()

        bus.callService("scene.turn_on", mapOf("entity_id" to "scene.film", "extra" to "1"))

        val request = deferred.await()
        assertEquals("scene.turn_on", request.service)
        assertEquals(2, request.dataCount)
        assertEquals("entity_id", request.dataList[0].key)
        assertEquals("scene.film", request.dataList[0].value)
    }
}
