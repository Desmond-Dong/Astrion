package com.example.astrion.ha

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** 保存前的一次性 HA 连接验证（真实 WebSocket 握手 + 令牌认证）。 */
object HaConnectionProbe {

    sealed interface Result {
        data object Ok : Result

        /** [reason] 是给人看的失败原因。 */
        data class Failure(val reason: String) : Result
    }

    private const val CLOSED = "__closed__"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 完整走一遍 auth 握手；成功返回 [Result.Ok]，否则返回带原因的
     * [Result.Failure]。最长约 10 秒。
     */
    suspend fun test(host: String, port: Int, token: String, useSsl: Boolean): Result {
        val scheme = if (useSsl) "wss" else "ws"
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val messages = Channel<String>(Channel.UNLIMITED)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                messages.trySend(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.trySend(CLOSED)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                messages.trySend(CLOSED)
            }
        }
        val webSocket = client.newWebSocket(
            Request.Builder().url("$scheme://$host:$port/api/websocket").build(),
            listener
        )
        try {
            val timeout = withTimeoutOrNull(10_000) {
                val first = messages.receive()
                if (first == CLOSED || !first.contains("auth_required")) {
                    return@withTimeoutOrNull Result.Failure("该地址不是 Home Assistant（握手失败）")
                }
                webSocket.send(buildJsonObject {
                    put("type", "auth")
                    put("access_token", token)
                }.toString())
                while (true) {
                    when (val msg = messages.receive()) {
                        CLOSED -> return@withTimeoutOrNull Result.Failure("连接被拒绝或中途断开")
                        else -> when {
                            msg.contains("auth_ok") -> return@withTimeoutOrNull Result.Ok
                            msg.contains("auth_invalid") ->
                                return@withTimeoutOrNull Result.Failure("访问令牌无效")
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                Result.Failure("未知错误")
            }
            return timeout ?: Result.Failure("连接超时（10 秒无响应，检查地址与端口）")
        } catch (e: Exception) {
            Timber.d(e, "Probe failed")
            return Result.Failure("验证出错：${e.message ?: e.javaClass.simpleName}")
        } finally {
            webSocket.cancel()
            client.dispatcher.executorService.shutdown()
        }
    }
}
