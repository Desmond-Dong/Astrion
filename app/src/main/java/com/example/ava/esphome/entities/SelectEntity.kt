package com.example.ava.esphome.entities

import com.example.esphomeproto.api.ListEntitiesRequest
import com.example.esphomeproto.api.ListEntitiesSelectResponse
import com.example.esphomeproto.api.SelectCommandRequest
import com.example.esphomeproto.api.selectStateResponse
import com.google.protobuf.MessageLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A selection list backed by a state value.
 *
 * Selecting a value from Home Assistant calls [onSelect]. Box-side changes
 * are pushed to Home Assistant through [subscribe].
 *
 * Two behaviours are supported, matching the original HaRemote/Astrion select
 * semantics:
 *
 * - **B-Type (persistent)**: the value stays selected until it is changed.
 *   Used for the "current activity / scene" select.
 *
 * - **A-Type (navigate / momentary)**: pass [autoResetAfterMs] and [sentinel].
 *   After a selection the entity resets its state back to [sentinel] after the
 *   given delay, so Home Assistant automations can fire the same navigation
 *   repeatedly without "re-selecting" the same option. The sentinel itself is a
 *   regular option (typically `—`) and selecting it is a no-op.
 *
 * [externalState] can mirror a box-side flow into the entity state, keeping
 * Home Assistant in sync when the panel page changes locally.
 */
class SelectEntity(
    val key: Int,
    val name: String,
    val objectId: String,
    val disabledByDefault: Boolean = false,
    private val options: List<String>,
    initialState: String = "",
    private val onSelect: suspend (String) -> Unit = {},
    private val autoResetAfterMs: Long? = null,
    private val sentinel: String? = null,
    private val scope: CoroutineScope? = null,
    externalState: Flow<String>? = null,
) : Entity {
    private val state = MutableStateFlow(initialState)
    private var resetJob: Job? = null

    init {
        if (externalState != null && scope != null) {
            externalState
                .onEach { newState ->
                    if (newState != state.value) {
                        Timber.d("Mirroring external state for $name: $newState")
                        state.value = newState
                    }
                }
                .launchIn(scope)
        }
    }

    /**
     * Pushes a new value from the box side (for example when the active
     * activity changes locally) so Home Assistant state stays in sync.
     */
    fun updateState(newState: String) {
        state.value = newState
    }

    override fun handleMessage(message: MessageLite) = flow {
        when (message) {
            is ListEntitiesRequest -> emit(ListEntitiesSelectResponse.newBuilder().apply {
                key = this@SelectEntity.key
                name = this@SelectEntity.name
                objectId = this@SelectEntity.objectId
                disabledByDefault = this@SelectEntity.disabledByDefault
                addAllOptions(this@SelectEntity.options)
            }.build())

            is SelectCommandRequest -> {
                if (message.key == key && message.state in options) {
                    Timber.d("Select command received for $name: ${message.state}")
                    val selected = message.state
                    state.value = selected
                    if (selected != sentinel) {
                        resetJob?.cancel()
                        onSelect(selected)
                        if (autoResetAfterMs != null && sentinel != null) {
                            scheduleReset()
                        }
                    }
                }
            }
        }
    }

    private fun scheduleReset() {
        val resetDelay = autoResetAfterMs ?: return
        val sentinelValue = sentinel ?: return
        val targetScope = scope ?: return
        resetJob = targetScope.launch {
            delay(resetDelay)
            Timber.d("Select $name resetting to sentinel: $sentinelValue")
            state.value = sentinelValue
        }
    }

    override fun subscribe(): Flow<MessageLite> = state.map {
        selectStateResponse {
            key = this@SelectEntity.key
            this.state = it
            missingState = it.isEmpty()
        }
    }
}