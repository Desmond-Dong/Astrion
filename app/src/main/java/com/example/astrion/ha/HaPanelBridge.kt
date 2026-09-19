package com.example.astrion.ha

import android.content.Context
import com.example.astrion.panel.PanelConfigStore
import com.example.astrion.panel.PanelEventHub
import com.example.astrion.panel.PanelIrController
import com.example.astrion.ota.OtaUpdateManager
import com.example.astrion.services.ActivityNavigator
import com.example.astrion.services.HaActionBus
import com.example.astrion.services.HaEntityState
import com.example.astrion.services.HomeAssistantStatesStore
import com.example.astrion.services.PageSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bridge between the panel and the astrion Home Assistant integration.
 *
 * The panel talks to Home Assistant directly over its WebSocket API (there is
 * no ESPHome adoption anymore):
 *
 * - pairs itself by calling `astrion/submit_pair_data` (again on
 *   `astrion/pair_request`),
 * - pulls the layout via `astrion/get_cards` (converted to [com.example.astrion.panel.PanelLayout]
 *   by [HaCardsAdapter]) and the IR codebook via `astrion/get_device_codes`,
 * - imports entity states from `get_states` + `state_changed` events,
 * - forwards card actions ([HaActionBus]) as WebSocket `call_service` commands,
 * - reports page visits / key presses by firing astrion bus events,
 * - reacts to `astrion/navigate_to`, `astrion/cards_updated`,
 *   `astrion/refresh_request` and `astrion/ota_manifest`.
 */
@Singleton
class HaPanelBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionSettingsStore: HaConnectionSettingsStore,
    private val panelConfigStore: PanelConfigStore,
    private val haStatesStore: HomeAssistantStatesStore,
    private val haActionBus: HaActionBus,
    private val eventHub: PanelEventHub,
    private val activityNavigator: ActivityNavigator,
    private val irController: PanelIrController,
    private val otaUpdateManager: OtaUpdateManager,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<HaConnectionState>(HaConnectionState.Disconnected)
    val state: StateFlow<HaConnectionState> = _state.asStateFlow()

    private var client: HaWebSocketClient? = null
    private var scope: CoroutineScope? = null
    private var jobs = mutableListOf<Job>()

    @Volatile
    private var currentLayout: com.example.astrion.panel.PanelLayout =
        com.example.astrion.panel.PanelLayout()

    @Volatile
    private var serialNumber: String = ""

    fun start(scope: CoroutineScope) {
        this.scope = scope
        if (jobs.isNotEmpty()) return
        val ws = HaWebSocketClient(scope, connectionSettingsStore)
        client = ws
        jobs += scope.launch {
            connectionSettingsStore.ensureSerialIsSet()
            serialNumber = connectionSettingsStore.get().serialNumber
            ws.connect()
        }
        jobs += scope.launch {
            ws.state.collect { connectionState ->
                _state.value = connectionState
                if (connectionState == HaConnectionState.Connected) onConnected()
            }
        }
        // Event dispatch survives reconnects (the client object persists).
        jobs += scope.launch {
            ws.incoming.collect { handleEvent(it) }
        }
        observePanelSide(scope)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        client?.disconnect()
        client = null
        _state.value = HaConnectionState.Disconnected
    }

    /** Reconnects: tears the current socket down and connects again. */
    fun reconnect() {
        val ws = client ?: return
        ws.disconnect()
        scope?.launch { ws.connect() }
    }

    // ── Panel → HA (fire events) ────────────────────────────────────────

    private fun observePanelSide(scope: CoroutineScope) {
        // Keep the latest layout for the state-changed filter and uploads.
        jobs += scope.launch {
            panelConfigStore.layout.collect { currentLayout = it }
        }
        // Layout changes are uploaded as navigate lists (debounced).
        jobs += scope.launch {
            panelConfigStore.layout
                .drop(1)
                .debounce(1_500)
                .collect { layout -> uploadNavigateList(layout) }
        }
        // User page visits (integration triggers automations / selects).
        jobs += scope.launch {
            eventHub.pageVisited.collect {
                val page = activityNavigator.currentPage.value
                if (page.isBlank()) return@collect
                fireEvent("astrion/page_visited", buildJsonObject {
                    put("serial_number", serial())
                    put("page", page)
                    put("source", "user")
                })
            }
        }
        // Button / physical key presses — automations can bind any key.
        jobs += scope.launch {
            merge(
                eventHub.buttonPressed.map { "button_pressed" },
                eventHub.keyEvents.map {
                    if (it.longPress) "key_long_pressed" else "key_pressed"
                }
            ).collect { type ->
                fireEvent("astrion/$type", buildJsonObject {
                    put("serial_number", serial())
                })
            }
        }
        // Card actions → Home Assistant service calls.
        jobs += scope.launch {
            haActionBus.requests.collect { call ->
                launch { sendServiceCall(call) }
            }
        }
    }

    private fun serial(): String = serialNumber

    // ── Connection lifecycle ────────────────────────────────────────────

    private fun onConnected() {
        Timber.i("HA WebSocket connected, subscribing…")
        haStatesStore.clear()
        val scope = this.scope ?: return
        // Subscriptions are per-connection: re-issue them on every connect.
        jobs += scope.launch {
            val ws = client ?: return@launch
            SUBSCRIBED_EVENTS.forEach { type ->
                ws.sendCommand("subscribe_events", buildJsonObject { put("event_type", type) })
            }
            submitPairData()
            refreshLayout()
        }
    }

    private suspend fun submitPairData() {
        val settings = connectionSettingsStore.get()
        val result = client?.sendCommand("astrion/submit_pair_data", buildJsonObject {
            put("code", "DISCOVER_ALL")
            put("data", buildJsonObject {
                put("serial_number", settings.serialNumber)
                put("model", "Astrion Panel")
                put("name", settings.name)
            })
        })
        if (result == null) {
            Timber.w("submit_pair_data failed (no result)")
        } else {
            Timber.i("Paired with Home Assistant: %s", result.toString())
        }
    }

    // ── Layout / codebook / states ──────────────────────────────────────

    private suspend fun refreshLayout() {
        val ws = client ?: return
        // States first so the layout adapter can resolve friendly names.
        snapshotStates()
        val result = ws.sendCommand("astrion/get_cards", buildJsonObject { })
        val cards = result?.get("cards") as? JsonArray ?: run {
            Timber.w("get_cards returned no cards")
            return
        }
        val adapter = HaCardsAdapter.build(cards, haStatesStore.states.value)
        if (panelConfigStore.applyLayoutJson(json.encodeToString(com.example.astrion.panel.PanelLayout.serializer(), adapter.layout))) {
            Timber.i("Layout applied: %d rooms", adapter.layout.rooms.size)
        }
        haStatesStore.retainEntities(adapter.entityIds)
        refreshCodebook(adapter.remoteEntityToCardName)
    }

    private suspend fun snapshotStates() {
        val ws = client ?: return
        val result = ws.sendCommand("get_states", timeoutMs = 30_000) ?: return
        val arr = runCatching { kotlinx.serialization.json.JsonArray(result.values.toList()) }.getOrNull()
            ?: return
        for (element in arr) {
            val obj = element as? JsonObject ?: continue
            val entityId = obj.str("entity_id") ?: continue
            importState(entityId, obj)
        }
    }

    private suspend fun refreshCodebook(remoteEntityToCardName: Map<String, String>) {
        val ws = client ?: return
        val devices = mutableMapOf<String, MutableMap<String, String>>()
        for ((entityId, cardName) in remoteEntityToCardName) {
            val result = ws.sendCommand("astrion/get_device_codes", buildJsonObject {
                put("entity_id", entityId)
            })
            val codes = result?.get("ir_codes") as? JsonObject ?: continue
            val bucket = devices.getOrPut(cardName) { mutableMapOf() }
            codes.forEach { (button, code) ->
                (code as? JsonPrimitive)?.contentOrNull?.let { bucket[button] = it }
            }
        }
        if (devices.isNotEmpty()) {
            val book = com.example.astrion.panel.IrCodebook(devices)
            if (panelConfigStore.applyIrCodesJson(json.encodeToString(com.example.astrion.panel.IrCodebook.serializer(), book))) {
                Timber.i("IR codebook applied: %d devices", devices.size)
            }
        }
    }

    private suspend fun handleEvent(event: HaWebSocketClient.Incoming.Event) {
        when (event.eventType) {
            "state_changed" -> ingestStateChanged(event.data)

            "astrion/navigate_to" -> {
                val target = event.data.str("target_page") ?: return
                // Only navigate when the event targets this panel (or omits the serial).
                val targetSerial = event.data.str("serial_number")
                if (!targetSerial.isNullOrBlank() && targetSerial != serialNumber) return
                activityNavigator.setPage(target, PageSource.AUTO)
            }

            "astrion/cards_updated", "astrion/refresh_request" -> refreshLayout()

            "astrion/pair_request" -> submitPairData()

            "astrion/ota_manifest" -> {
                val manifest = event.data.str("manifest")
                    ?: json.encodeToString(JsonObject.serializer(), event.data)
                runCatching { otaUpdateManager.applyManifestJson(manifest) }
                    .onFailure { Timber.e(it, "Bad OTA manifest") }
            }

            "astrion/control_command" -> {
                val button = event.data.str("button") ?: return
                if (event.data.str("format") == "broadlink") return
                val targetSerial = event.data.str("serial_number")
                if (!targetSerial.isNullOrBlank() && targetSerial != serialNumber) return
                scope?.launch {
                    if (!irController.transmitRaw(button)) {
                        irController.transmitByButton(button)
                    }
                }
            }
        }
    }

    private suspend fun ingestStateChanged(data: JsonObject) {
        val entityId = data.str("entity_id") ?: return
        // Removed states are ignored; the next layout refresh prunes them.
        val newState = data["new_state"] as? JsonObject ?: return
        if (entityId !in subscribedEntityIds()) return
        importState(entityId, newState)
    }

    private fun importState(entityId: String, obj: JsonObject) {
        val state = obj.str("state") ?: return
        haStatesStore.import(HaEntityState(entityId, state))
        (obj["attributes"] as? JsonObject)?.forEach { (attr, value) ->
            haStatesStore.import(HaEntityState(entityId, attributeValueAsString(value), attr))
        }
    }

    private fun subscribedEntityIds(): Set<String> =
        currentLayout.rooms.flatMap { it.cards }
            .flatMap { it.entities }
            .map { it.entityId }
            .filter { it.isNotBlank() }
            .toSet()

    // ── HA service calls ────────────────────────────────────────────────

    private suspend fun sendServiceCall(call: com.example.astrion.services.HaServiceCall) {
        val ws = client ?: return
        val domain = call.service.substringBefore('.')
        val service = call.service.substringAfter('.', "")
        if (domain.isBlank() || service.isBlank()) return
        val data = buildJsonObject {
            call.data.forEach { (key, value) -> put(key, value) }
        }
        val message = buildJsonObject {
            put("domain", domain)
            put("service", service)
            call.data["entity_id"]?.let { entityId ->
                put("target", buildJsonObject { put("entity_id", entityId) })
            }
            put("service_data", data)
        }
        val result = ws.sendCommand("call_service", message, timeoutMs = 8_000)
        if (result == null) {
            Timber.w("Service call failed: %s", call.service)
        }
    }

    private fun uploadNavigateList(layout: com.example.astrion.panel.PanelLayout) {
        if (_state.value != HaConnectionState.Connected) return
        val pages = (layout.rooms.map { it.title } + layout.pages)
            .filter { it.isNotBlank() }
            .distinct()
        if (pages.isEmpty()) return
        fireEvent("astrion/navigate_list_upload", buildJsonObject {
            put("serial_number", serial())
            put("pages", buildJsonArray { pages.forEach { add(it) } })
        })
    }

    private fun fireEvent(eventType: String, data: JsonObject) {
        client?.sendNow("fire_event", buildJsonObject {
            put("event_type", eventType)
            put("event_data", data)
        })
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun attributeValueAsString(value: kotlinx.serialization.json.JsonElement): String =
        when (value) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString(",").ifBlank { value.toString() }
            else -> value.toString()
        }

    private companion object {
        val SUBSCRIBED_EVENTS = listOf(
            "state_changed",
            "astrion/navigate_to",
            "astrion/cards_updated",
            "astrion/refresh_request",
            "astrion/pair_request",
            "astrion/ota_manifest",
            "astrion/control_command",
        )
    }
}
