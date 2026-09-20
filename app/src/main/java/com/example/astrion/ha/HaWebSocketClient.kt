package com.example.astrion.ha

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Marker written into the message channel when the socket dies. */
private const val CLOSED_SENTINEL = "__closed__"

/**
 * Home Assistant WebSocket API client.
 *
 * Connects to `ws(s)://<host>:<port>/api/websocket`, authenticates with a
 * long-lived access token and keeps the connection alive with exponential
 * backoff reconnects. Commands are correlated by id; subscribed events are
 * exposed through [incoming].
 */
class HaWebSocketClient(
    private val scope: CoroutineScope,
    private val settingsStore: HaConnectionSettingsStore,
) {
    sealed interface Incoming {
        data class Event(val eventType: String, val data: JsonObject)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _state = MutableStateFlow<HaConnectionState>(HaConnectionState.Disconnected)
    val state: StateFlow<HaConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<Incoming.Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incoming: SharedFlow<Incoming.Event> = _incoming.asSharedFlow()

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val messages = Channel<String>(Channel.UNLIMITED)
    private val pendingResults =
        ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()
    private val nextId = AtomicInteger(1)
    private var webSocket: WebSocket? = null
    private var running = false
    private var loopJob: Job? = null

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            messages.trySend(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.d(t, "HA WebSocket failed")
            messages.trySend(CLOSED_SENTINEL)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            messages.trySend(CLOSED_SENTINEL)
        }
    }

    fun connect() {
        if (running) return
        running = true
        loopJob = scope.launch { reconnectLoop() }
    }

    fun disconnect() {
        running = false
        loopJob?.cancel()
        loopJob = null
        webSocket?.cancel()
        webSocket = null
        _state.value = HaConnectionState.Disconnected
    }

    /**
     * Sends a WebSocket command and suspends until its result arrives
     * (or [timeoutMs] elapses). Returns the `result` object, or null.
     */
    suspend fun sendCommand(
        type: String,
        data: JsonObject = buildJsonObject { },
        timeoutMs: Long = 10_000,
    ): JsonObject? {
        val ws = webSocket ?: return null
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pendingResults[id] = deferred
        val message = buildJsonObject {
            put("id", id)
            put("type", type)
            data.forEach { (key, value) -> put(key, value) }
        }
        if (!ws.send(message.toString())) {
            pendingResults.remove(id)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }?.let {
                runCatching { it.jsonObject }.getOrNull()
            }
        } finally {
            pendingResults.remove(id)
        }
    }

    /** Sends a fire-and-forget command (no result awaited). */
    fun sendNow(type: String, data: JsonObject = buildJsonObject { }) {
        val ws = webSocket ?: return
        val id = nextId.getAndIncrement()
        val message = buildJsonObject {
            put("id", id)
            put("type", type)
            data.forEach { (key, value) -> put(key, value) }
        }
        ws.send(message.toString())
    }

    private suspend fun reconnectLoop() {
        var backoff = 1_000L
        while (running && scope.isActive) {
            val settings = settingsStore.get()
            if (!settings.isConfigured) {
                _state.value = HaConnectionState.Error("未配置 Home Assistant 地址或访问令牌")
                delay(5_000)
                continue
            }
            _state.value = HaConnectionState.Connecting
            Timber.i("HA connect attempt to %s:%d…", settings.host, settings.port)
            val authed = connectOnce(settings)
            if (!authed) {
                webSocket?.cancel()
                webSocket = null
                if (_state.value !is HaConnectionState.Error) {
                    _state.value = HaConnectionState.Error("连接失败")
                }
                Timber.i("HA connect failed; retrying in %dms", backoff)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
                continue
            }
            backoff = 1_000L
            _state.value = HaConnectionState.Connected
            dispatchLoop()
            webSocket?.cancel()
            webSocket = null
            if (running && scope.isActive) {
                Timber.i("HA WebSocket closed; reconnecting in %dms", backoff)
                _state.value = HaConnectionState.Disconnected
                delay(backoff)
            }
        }
    }

    private suspend fun connectOnce(settings: HaConnectionSettings): Boolean {
        val scheme = if (settings.useSsl) "wss" else "ws"
        val request = Request.Builder()
            .url("$scheme://${settings.host}:${settings.port}/api/websocket")
            .build()
        webSocket = okHttpClient.newWebSocket(request, listener)

        val first = withTimeoutOrNull(15_000) { messages.receive() } ?: return false
        if (first == CLOSED_SENTINEL || !first.contains("auth_required")) return false
        webSocket?.send(buildJsonObject {
            put("type", "auth")
            put("access_token", settings.token)
        }.toString())
        while (true) {
            val msg = withTimeoutOrNull(15_000) { messages.receive() } ?: return false
            when {
                msg == CLOSED_SENTINEL -> return false
                msg.contains("auth_ok") -> return true
                msg.contains("auth_invalid") -> {
                    _state.value = HaConnectionState.Error("访问令牌无效")
                    return false
                }
            }
        }
    }

    private suspend fun dispatchLoop() {
        while (true) {
            val msg = messages.receive()
            if (msg == CLOSED_SENTINEL) return
            val obj = runCatching { json.parseToJsonElement(msg).jsonObject }.getOrNull() ?: continue
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "result" -> {
                    val id = obj["id"]?.jsonPrimitive?.intOrNull ?: continue
                    pendingResults.remove(id)?.complete(obj["result"])
                }

                "event" -> {
                    val event = obj["event"] as? JsonObject ?: continue
                    val eventType = event["event_type"]?.jsonPrimitive?.contentOrNull ?: continue
                    val data = event["data"] as? JsonObject ?: JsonObject(emptyMap())
                    _incoming.tryEmit(Incoming.Event(eventType, data))
                }
            }
        }
    }
}
