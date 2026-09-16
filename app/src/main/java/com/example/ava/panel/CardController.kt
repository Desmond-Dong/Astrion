package com.example.ava.panel

import com.example.ava.services.HaActionBus
import com.example.ava.services.HomeAssistantStatesStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single bridge between the on-device card UI and the outside world
 * (§6.1-3 按键控制): on-screen buttons and the dynamic physical keys call into
 * this controller, which either fires the matching IR code locally (when the
 * card name matches a codebook device) or asks Home Assistant to run the
 * service for the referenced entity.
 */
@Singleton
class CardController @Inject constructor(
    private val haActionBus: HaActionBus,
    private val irController: PanelIrController,
    private val haStatesStore: HomeAssistantStatesStore,
) {
    // ── TV remote keys ──────────────────────────────────────────────────

    /**
     * Presses a named key (POWER, UP, VOLUME_UP, NUM_5, ...) on a tv card.
     * Local codebook first, then the HA service matching the entity domain
     * (§4.1 服务分派, ESPHome 化: astrion.send_command → remote.send_command).
     */
    suspend fun pressTvKey(card: PanelCard, key: String) {
        val ref = card.entities.firstOrNull { it.key.equals(key, ignoreCase = true) }
            ?: PanelEntityRef(key = key, entityId = card.primaryEntity?.entityId ?: "")
        when (ref.entityDomain) {
            "media_player" -> pressMediaKey(ref, key)
            "select" -> haActionBus.callService(
                "select.select_option",
                mapOf("entity_id" to ref.entityId, "option" to ref.effectiveCommand)
            )

            "script" -> haActionBus.callService(
                "script.turn_on",
                mapOf("entity_id" to ref.entityId)
            )

            "button" -> haActionBus.callService(
                "button.press",
                mapOf("entity_id" to ref.entityId)
            )

            else -> pressRemoteKey(card, ref)
        }
    }

    private suspend fun pressRemoteKey(card: PanelCard, ref: PanelEntityRef) {
        // Local IR first: the card name doubles as the codebook device name
        // and the key value as the button name (配置指南 documents this
        // pairing). When the codebook has no such code, fall back to the
        // Home Assistant remote service.
        if (irController.transmit(card.name, ref.effectiveCommand)) return
        if (ref.entityId.isNotBlank()) {
            haActionBus.callService(
                "remote.send_command",
                mapOf(
                    "entity_id" to ref.entityId,
                    "command" to ref.effectiveCommand
                )
            )
        }
    }

    private suspend fun pressMediaKey(ref: PanelEntityRef, key: String) {
        val data = mapOf("entity_id" to ref.entityId)
        when (key.uppercase()) {
            "PLAY" -> haActionBus.callService("media_player.media_play", data)
            "PAUSE" -> haActionBus.callService("media_player.media_pause", data)
            "PLAY_PAUSE" -> haActionBus.callService("media_player.media_play_pause", data)
            "NEXT" -> haActionBus.callService("media_player.media_next_track", data)
            "PREVIOUS" -> haActionBus.callService("media_player.media_previous_track", data)
            "STOP" -> haActionBus.callService("media_player.media_stop", data)
            "VOLUME_UP" -> haActionBus.callService("media_player.volume_up", data)
            "VOLUME_DOWN" -> haActionBus.callService("media_player.volume_down", data)
            "MUTE" -> haActionBus.callService(
                "media_player.volume_mute",
                data + ("is_volume_muted" to "true")
            )

            "UN_MUTE" -> haActionBus.callService(
                "media_player.volume_mute",
                data + ("is_volume_muted" to "false")
            )

            else -> haActionBus.callService("media_player.media_play_pause", data)
        }
    }

    // ── Lights ──────────────────────────────────────────────────────────

    suspend fun lightTurnOn(
        entityId: String,
        brightnessPct: Int? = null,
        kelvin: Int? = null,
        rgb: List<Int>? = null,
    ) {
        val data = buildMap {
            put("entity_id", entityId)
            brightnessPct?.let { put("brightness_pct", it.toString()) }
            kelvin?.let { put("color_temp_kelvin", it.toString()) }
            rgb?.let { put("rgb_color", "${it[0]},${it[1]},${it[2]}") }
        }
        haActionBus.callService("light.turn_on", data)
    }

    suspend fun lightTurnOff(entityId: String) =
        haActionBus.callService("light.turn_off", mapOf("entity_id" to entityId))

    // ── Switch / scene / script ─────────────────────────────────────────

    suspend fun turnOn(entityId: String) =
        haActionBus.callService("${entityId.substringBefore('.')}.turn_on", mapOf("entity_id" to entityId))

    suspend fun turnOff(entityId: String) =
        haActionBus.callService("${entityId.substringBefore('.')}.turn_off", mapOf("entity_id" to entityId))

    // ── Climate ─────────────────────────────────────────────────────────

    suspend fun climateSetTemperature(entityId: String, temperature: Double) =
        haActionBus.callService(
            "climate.set_temperature",
            mapOf("entity_id" to entityId, "temperature" to formatNumber(temperature))
        )

    suspend fun climateSetHvacMode(entityId: String, mode: String) =
        haActionBus.callService(
            "climate.set_hvac_mode",
            mapOf("entity_id" to entityId, "hvac_mode" to mode)
        )

    suspend fun climateSetFanMode(entityId: String, mode: String) =
        haActionBus.callService(
            "climate.set_fan_mode",
            mapOf("entity_id" to entityId, "fan_mode" to mode)
        )

    // ── Fan ─────────────────────────────────────────────────────────────

    suspend fun fanSetPercentage(entityId: String, percentage: Int) =
        haActionBus.callService(
            "fan.set_percentage",
            mapOf("entity_id" to entityId, "percentage" to percentage.coerceIn(0, 100).toString())
        )

    // ── Cover ───────────────────────────────────────────────────────────

    suspend fun coverOpen(entityId: String) =
        haActionBus.callService("cover.open_cover", mapOf("entity_id" to entityId))

    suspend fun coverStop(entityId: String) =
        haActionBus.callService("cover.stop_cover", mapOf("entity_id" to entityId))

    suspend fun coverClose(entityId: String) =
        haActionBus.callService("cover.close_cover", mapOf("entity_id" to entityId))

    suspend fun coverSetPosition(entityId: String, position: Int) =
        haActionBus.callService(
            "cover.set_cover_position",
            mapOf("entity_id" to entityId, "position" to position.coerceIn(0, 100).toString())
        )

    // ── Media player entity ─────────────────────────────────────────────

    suspend fun mediaCommand(entityId: String, command: String) =
        haActionBus.callService("media_player.$command", mapOf("entity_id" to entityId))

    suspend fun mediaVolumeSet(entityId: String, level: Float) =
        haActionBus.callService(
            "media_player.volume_set",
            mapOf("entity_id" to entityId, "volume_level" to level.coerceIn(0f, 1f).toString())
        )

    // ── Dynamic physical key semantics (§3.10.4 物理键语义汇总) ─────────

    /**
     * Applies the original device-page physical key semantics: the same keys
     * change meaning depending on the card type currently on screen.
     *
     * @return true when the key was consumed by this card.
     */
    suspend fun handleDeviceKey(card: PanelCard, keyCode: Int, longPress: Boolean): Boolean {
        if (longPress) return false
        val entityId = card.primaryEntity?.entityId ?: return false
        val state = haStatesStore.states.value[entityId]?.state
        return when (card.type) {
            PanelCardTypes.TV -> {
                when (keyCode) {
                    19 -> pressTvKey(card, "UP")
                    20 -> pressTvKey(card, "DOWN")
                    21 -> pressTvKey(card, "LEFT")
                    22 -> pressTvKey(card, "RIGHT")
                    23 -> pressTvKey(card, "CENTER")
                    4 -> pressTvKey(card, "BACK")
                    24 -> pressTvKey(card, "VOLUME_UP")
                    25 -> pressTvKey(card, "VOLUME_DOWN")
                    164 -> pressTvKey(card, "MUTE")
                    92 -> pressTvKey(card, "CHANNEL_UP")
                    93 -> pressTvKey(card, "CHANNEL_DOWN")
                    82 -> pressTvKey(card, "MENU")
                    else -> return false
                }
                true
            }

            PanelCardTypes.CLIMATE -> {
                val current = haStatesStore.states.value["$entityId.temperature"]
                    ?.state?.toDoubleOrNull()
                val hvacOn = state != "off"
                when (keyCode) {
                    24 -> {
                        climateSetTemperature(entityId, (current ?: 24.0) + 0.5); true
                    }

                    25 -> {
                        climateSetTemperature(entityId, (current ?: 24.0) - 0.5); true
                    }

                    92, 93 -> {
                        cycleClimateFanMode(entityId); true
                    }

                    132 -> {
                        climateSetHvacMode(entityId, if (hvacOn) "off" else "cool"); true
                    }

                    else -> false
                }
            }

            PanelCardTypes.FAN -> {
                val percentage = haStatesStore.states.value["$entityId.percentage"]
                    ?.state?.toFloatOrNull()
                when (keyCode) {
                    24 -> {
                        fanSetPercentage(entityId, ((percentage ?: 0f) + 20f).toInt()); true
                    }

                    25 -> {
                        fanSetPercentage(entityId, ((percentage ?: 100f) - 20f).toInt()); true
                    }

                    132 -> {
                        if (state == "on") turnOff(entityId) else turnOn(entityId); true
                    }

                    else -> false
                }
            }

            PanelCardTypes.LIGHT -> {
                val brightness = haStatesStore.states.value["$entityId.brightness"]
                    ?.state?.toFloatOrNull()
                when (keyCode) {
                    24 -> {
                        lightTurnOn(entityId, brightnessPct = ((brightness ?: 0f) + 10f).toInt()); true
                    }

                    25 -> {
                        lightTurnOn(entityId, brightnessPct = ((brightness ?: 100f) - 10f).toInt()); true
                    }

                    132 -> {
                        if (state == "on") lightTurnOff(entityId) else lightTurnOn(entityId); true
                    }

                    else -> false
                }
            }

            PanelCardTypes.COVER -> when (keyCode) {
                132 -> {
                    // Toggle open/close on the power key, stop on OK (原版 ①⑤)
                    if (state == "open") coverClose(entityId) else coverOpen(entityId); true
                }

                23 -> {
                    coverStop(entityId); true
                }

                else -> false
            }

            PanelCardTypes.MEDIA_PLAYER -> {
                when (keyCode) {
                    23 -> mediaCommand(entityId, "media_play_pause")
                    24 -> mediaCommand(entityId, "volume_up")
                    25 -> mediaCommand(entityId, "volume_down")
                    92 -> mediaCommand(entityId, "media_previous_track")
                    93 -> mediaCommand(entityId, "media_next_track")
                    164 -> haActionBus.callService(
                        "media_player.volume_mute",
                        mapOf("entity_id" to entityId, "is_volume_muted" to "true")
                    )

                    else -> return false
                }
                true
            }

            PanelCardTypes.SWITCH, PanelCardTypes.SCENE -> when (keyCode) {
                132 -> {
                    if (state == "on") turnOff(entityId) else turnOn(entityId); true
                }

                else -> false
            }

            else -> false
        }
    }

    /** Steps through the reported fan mode list of the climate entity. */
    private suspend fun cycleClimateFanMode(entityId: String) {
        val current = haStatesStore.states.value["$entityId.fan_mode"]?.state ?: return
        // The attribute list is unavailable without subscribing to the
        // `fan_modes` attribute; document a `fan_modes` sync entry for full
        // support. Cycle a sensible default order when unknown.
        val modes = listOf("auto", "low", "medium", "high")
        val next = modes[(modes.indexOf(current) + 1).mod(modes.size)]
        climateSetFanMode(entityId, next)
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString()
}
