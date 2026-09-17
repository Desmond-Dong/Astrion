package com.example.astrion.server

import com.example.esphomeproto.AsynchronousCodedChannel
import com.google.protobuf.MessageLite
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.IOException
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.atomic.AtomicBoolean

class ClientConnection(socket: AsynchronousSocketChannel) : AutoCloseable {
    private val isClosed = AtomicBoolean(false)
    private val channel = AsynchronousCodedChannel(socket)
    private val sendMutex = Mutex()

    /**
     * Reads messages, closing the connection when no message arrives within
     * [idleTimeoutMs]. Home Assistant pings well within this window; a
     * half-open/zombie connection (e.g. HA restarted without closing the
     * socket) is dropped so the accept loop can serve reconnects.
     */
    fun readMessages(idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS) =
        flow {
            while (true) {
                val message = withTimeoutOrNull(idleTimeoutMs) { channel.readMessage() }
                    ?: throw IdleTimeoutException(idleTimeoutMs)
                emit(message)
            }
        }.catch {
            if (it !is IOException && it !is IdleTimeoutException) throw it
            // Exception is expected if client was manually closed
            if (!isClosed.get())
                Timber.e(it, "Error reading from socket")
        }

    suspend fun sendMessage(message: MessageLite) {
        // Multiple send requests are not allowed at the same time so hold the lock until the send is complete
        sendMutex.withLock {
            try {
                channel.writeMessage(message)
            } catch (e: IOException) {
                // Exception is expected if client was manually closed
                if (!isClosed.get())
                    Timber.e(e, "Error writing to socket")
            }
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true))
            channel.close()
    }

    class IdleTimeoutException(val idleTimeoutMs: Long) :
        IllegalStateException("No message received within ${idleTimeoutMs}ms")

    companion object {
        /**
         * Home Assistant pings its connections frequently; 2 minutes without a
         * single inbound message means the peer is gone.
         */
        const val DEFAULT_IDLE_TIMEOUT_MS = 120_000L
    }
}