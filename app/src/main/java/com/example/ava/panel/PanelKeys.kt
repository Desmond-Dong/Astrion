package com.example.ava.panel

import com.example.ava.services.ActivityNavigator
import com.example.ava.services.HaActionBus
import com.example.ava.services.SatelliteStateHolder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A physical key press reported by the activity. */
data class KeyPress(
    val keyCode: Int,
    val longPress: Boolean,
    /** Set when the long press was detected by the activity timer. */
    val cancel: Boolean = false,
)

/**
 * Raw physical key events from [com.example.ava.MainActivity]. Screens never
 * collect this directly — they register handlers on [KeyRouter].
 */
@Singleton
class PhysicalKeyBus @Inject constructor() {
    private val _keys = MutableSharedFlow<KeyPress>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val keys: SharedFlow<KeyPress> = _keys.asSharedFlow()

    fun publish(press: KeyPress) {
        _keys.tryEmit(press)
    }
}

/**
 * Routes physical key presses: the mic key starts a hands-free Assist
 * conversation first (§3.10.1 语音键), then the topmost screen handler (device
 * pages reuse keys as remote commands, §3.10.4 物理键语义), then the
 * HA-configured bindings ([KeyBindingExecutor], §3.10.5 快捷键).
 */
@Singleton
class KeyRouter @Inject constructor(
    private val satelliteStateHolder: SatelliteStateHolder,
    private val keyBindingExecutor: KeyBindingExecutor,
    private val eventHub: PanelEventHub
) {
    private var handler: (suspend (KeyPress) -> Boolean)? = null

    fun setHandler(h: (suspend (KeyPress) -> Boolean)?) {
        handler = h
    }

    /** @return true when the key was consumed. */
    suspend fun dispatch(press: KeyPress): Boolean {
        if (press.cancel) return true
        // Every physical key is reported to Home Assistant so automations can
        // see (and bind) any key, even when the panel itself ignores it.
        eventHub.announceKeyPressed(press.keyCode, press.longPress)
        // Physical mic/voice key: start an Assist pipeline without a wake
        // word, on every screen, like the original voice dialog.
        if (press.keyCode == KEY_VOICE_X9_HA10 || press.keyCode == KEY_VOICE_HA100) {
            if (!press.longPress) {
                satelliteStateHolder.voiceAssistant?.wakeAssistant()
            }
            return true
        }
        if (handler?.invoke(press) == true) return true
        return keyBindingExecutor.handle(press)
    }

    private companion object {
        /** Voice assistant keys per device model (§3.10.1). */
        const val KEY_VOICE_X9_HA10 = 131
        const val KEY_VOICE_HA100 = 133
    }
}

/**
 * Executes the HA-configured key bindings (original 快捷键 system, now pushed
 * through the `astrion_key_bindings` text entity instead of the on-device
 * binding UI).
 */
@Singleton
class KeyBindingExecutor @Inject constructor(
    private val panelConfigStore: PanelConfigStore,
    private val activityNavigator: ActivityNavigator,
    private val haActionBus: HaActionBus,
    private val panelUiEvents: PanelUiEvents,
    private val satelliteStateHolder: SatelliteStateHolder,
    private val haStatesStore: com.example.ava.services.HomeAssistantStatesStore,
) {
    /** @return true when a binding matched and was executed. */
    suspend fun handle(press: KeyPress): Boolean {
        if (press.cancel) return false
        val binding = panelConfigStore.keyBindings.first().bindings
            .firstOrNull { it.keycode == press.keyCode && it.longPress == press.longPress }
            ?: return false
        Timber.d("Key binding fired: keyCode=${press.keyCode} long=${press.longPress} action=${binding.action}")
        when (binding.action) {
            KeyBindingActions.ROOM -> activityNavigator.setPage(binding.target)
            KeyBindingActions.HOME -> {
                val firstRoom = panelConfigStore.effectiveLayout.first().rooms
                    .firstOrNull()?.title.orEmpty()
                activityNavigator.setPage(firstRoom)
            }

            KeyBindingActions.VOICE -> satelliteStateHolder.voiceAssistant?.wakeAssistant()
            KeyBindingActions.CARD -> panelUiEvents.tryOpenCard(binding.target)
            KeyBindingActions.SERVICE ->
                if (binding.service.isNotBlank()) {
                    haActionBus.callService(
                        binding.service,
                        buildMap {
                            if (binding.entityId.isNotBlank()) put("entity_id", binding.entityId)
                            putAll(binding.data)
                        }
                    )
                } else {
                    // Plain entity id: default action by domain (§4.1 服务分派)
                    val domain = binding.entityId.substringBefore('.')
                    val service = when (domain) {
                        "scene", "script" -> "${domain}.turn_on"
                        "button", "input_button" -> "${domain}.press"
                        else -> defaultToggleService(binding.entityId, domain)
                    }
                    haActionBus.callService(
                        service,
                        mapOf("entity_id" to binding.entityId)
                    )
                }

            else -> {
                Timber.w("Unknown key binding action: ${binding.action}")
                return false
            }
        }
        return true
    }

    /** Toggles on/off domains based on the imported HA state. */
    private suspend fun defaultToggleService(entityId: String, domain: String): String {
        val state = haStatesStore.states.value[entityId]?.state
        val turnOff = state == "on"
        return "${domain}.${if (turnOff) "turn_off" else "turn_on"}"
    }
}

/**
 * UI-level events produced outside the compose tree (key bindings) that the
 * nav host turns into navigation.
 */
@Singleton
class PanelUiEvents @Inject constructor() {
    private val _openCard = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val openCard: SharedFlow<String> = _openCard.asSharedFlow()

    fun tryOpenCard(cardId: String) {
        _openCard.tryEmit(cardId)
    }
}
