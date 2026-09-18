package com.example.astrion.panel

import com.example.astrion.services.HaActionBus
import com.example.astrion.services.HomeAssistantStatesStore
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
    private val eventHub: PanelEventHub,
) {
    // ── TV remote keys ──────────────────────────────────────────────────

    /**
     * Presses a named key (POWER, UP, VOLUME_UP, NUM_5, ...) on a tv card.
     * Local codebook first, then the HA service matching the entity domain
     * (§4.1 服务分派, ESPHome 化: astrion.send_command → remote.send_command).
     */
    suspend fun pressTvKey(card: PanelCard, key: String) {
        // Panel-initiated key presses are announced to Home Assistant
        // (原 astrion/control_command).
        eventHub.announceButtonPressed()
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

    suspend fun climateSetPresetMode(entityId: String, preset: String) =
        haActionBus.callService(
            "climate.set_preset_mode",
            mapOf("entity_id" to entityId, "preset_mode" to preset)
        )

    suspend fun climateSetTemperatureRange(entityId: String, low: Double, high: Double) {
        if (low >= high) return
        haActionBus.callService(
            "climate.set_temperature",
            mapOf(
                "entity_id" to entityId,
                "target_temp_low" to formatNumber(low),
                "target_temp_high" to formatNumber(high)
            )
        )
    }

    suspend fun climateSetFanMode(entityId: String, mode: String) =
        haActionBus.callService(
            "climate.set_fan_mode",
            mapOf("entity_id" to entityId, "fan_mode" to mode)
        )

    suspend fun lightEffect(entityId: String, effect: String) =
        haActionBus.callService(
            "light.turn_on",
            mapOf("entity_id" to entityId, "effect" to effect)
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

    suspend fun coverSetTiltPosition(entityId: String, tilt: Int) =
        haActionBus.callService(
            "cover.set_cover_tilt_position",
            mapOf("entity_id" to entityId, "tilt_position" to tilt.coerceIn(0, 100).toString())
        )

    // ── Media player entity ─────────────────────────────────────────────

    suspend fun mediaCommand(entityId: String, command: String) =
        haActionBus.callService("media_player.$command", mapOf("entity_id" to entityId))

    suspend fun mediaVolumeSet(entityId: String, level: Float) =
        haActionBus.callService(
            "media_player.volume_set",
            mapOf("entity_id" to entityId, "volume_level" to level.coerceIn(0f, 1f).toString())
        )

    /**
     * 原版 MediaPlayControlView.setVolumeMute：is_volume_muted = !当前值。
     */
    suspend fun mediaToggleMute(entityId: String) {
        val muted = haStatesStore.states.value["$entityId.is_volume_muted"]?.state == "true"
        haActionBus.callService(
            "media_player.volume_mute",
            mapOf("entity_id" to entityId, "is_volume_muted" to (!muted).toString())
        )
    }

    suspend fun mediaSeek(entityId: String, positionSec: Int) =
        haActionBus.callService(
            "media_player.media_seek",
            mapOf("entity_id" to entityId, "seek_position" to positionSec.coerceAtLeast(0).toString())
        )

    suspend fun mediaPlayMedia(entityId: String, contentId: String) =
        haActionBus.callService(
            "media_player.play_media",
            mapOf(
                "entity_id" to entityId,
                "media_content_type" to "favorite_item_id",
                "media_content_id" to contentId
            )
        )

    suspend fun mediaSelectSource(entityId: String, source: String) =
        haActionBus.callService(
            "media_player.select_source",
            mapOf("entity_id" to entityId, "source" to source)
        )

    suspend fun mediaSelectSoundMode(entityId: String, soundMode: String) =
        haActionBus.callService(
            "media_player.select_sound_mode",
            mapOf("entity_id" to entityId, "sound_mode" to soundMode)
        )

    /**
     * 原版 MediaPlayCommandManager 状态机：OFF/STANDBY 先 turn_on 再 play，
     * 其余状态直接 play/pause 切换。
     */
    suspend fun mediaTogglePlayback(entityId: String) {
        val state = haStatesStore.states.value[entityId]?.state
        if (state == "off" || state == "standby" || state == "unknown") {
            mediaCommand(entityId, "turn_on")
            mediaCommand(entityId, "media_play")
        } else {
            mediaCommand(entityId, "media_play_pause")
        }
    }

    // ── Dynamic physical key semantics (§3.10.4 物理键语义汇总) ─────────

    /**
     * Applies the original device-page physical key semantics: the same keys
     * change meaning depending on the card type currently on screen. 短按
     * ±1（温度 ±0.5），长按 ±5（原版 长按 400ms 起始 / 200ms 重复）。
     *
     * @return true when the key was consumed by this card.
     */
    suspend fun handleDeviceKey(card: PanelCard, keyCode: Int, longPress: Boolean): Boolean {
        if (longPress) {
            return applyDeviceStep(card, keyCode, large = true)
        }
        return applyDeviceStep(card, keyCode, large = false)
    }

    /**
     * 长按自动重复步进（原版 200ms 重复）。由设备详情页持有，按键松开
     * （cancel 事件）后停止。
     */
    suspend fun holdDeviceStep(card: PanelCard, keyCode: Int) {
        if (!applyDeviceStep(card, keyCode, large = true)) return
    }

    private suspend fun applyDeviceStep(card: PanelCard, keyCode: Int, large: Boolean): Boolean {
        val entityId = card.primaryEntity?.entityId ?: return false
        val state = haStatesStore.states.value[entityId]?.state
        return when (card.type) {
            PanelCardTypes.TV -> {
                // 原版 TV 键：音量→media_player；频道→send_command；OK 等单键
                when (keyCode) {
                    19 -> pressTvKey(card, "UP")
                    20 -> pressTvKey(card, "DOWN")
                    21 -> pressTvKey(card, "LEFT")
                    22 -> pressTvKey(card, "RIGHT")
                    23 -> pressTvKey(card, "CENTER")
                    4 -> {
                        // 原版 BACK：短按发送 BACK 键；长按 >2s 退出页面（不连发）
                        if (!large) pressTvKey(card, "BACK")
                        true
                    }
                    24 -> pressTvKey(card, "VOLUME_UP")
                    25 -> pressTvKey(card, "VOLUME_DOWN")
                    164 -> pressTvKey(card, "MUTE")
                    92 -> pressTvKey(card, "CHANNEL_UP")
                    93 -> pressTvKey(card, "CHANNEL_DOWN")
                    82 -> pressTvKey(card, "MENU")
                    132 -> pressTvKey(card, "POWER")
                    // 原版 initKeyMap：134-141 = F4-F11（send_command 键名）
                    134 -> pressTvKey(card, "F4")
                    135 -> pressTvKey(card, "F5")
                    136 -> pressTvKey(card, "F6")
                    137 -> pressTvKey(card, "F7")
                    138 -> pressTvKey(card, "F8")
                    139 -> pressTvKey(card, "F9")
                    140 -> pressTvKey(card, "F10")
                    141 -> pressTvKey(card, "F11")
                    else -> return false
                }
                true
            }

            PanelCardTypes.CLIMATE -> {
                val current = haStatesStore.states.value["$entityId.temperature"]
                    ?.state?.toDoubleOrNull()
                // 原版 mTemperatureStep：设备步进（target_temp_step），默认 1.0；
                // 物理键长按由调用方以 300ms（原版 KEY_INTERVAL）节奏重复触发
                val step = haStatesStore.states.value["$entityId.target_temp_step"]
                    ?.state?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
                when (keyCode) {
                    24 -> {
                        climateSetTemperature(entityId, (current ?: 24.0) + step); true
                    }

                    25 -> {
                        climateSetTemperature(entityId, (current ?: 24.0) - step); true
                    }

                    92, 93 -> {
                        cycleClimateFanMode(entityId); true
                    }

                    132 -> {
                        if (large) false
                        else {
                            climateSetHvacMode(entityId, if (state != "off") "off" else "cool"); true
                        }
                    }

                    else -> false
                }
            }

            PanelCardTypes.FAN -> {
                val percentage = haStatesStore.states.value["$entityId.percentage"]
                    ?.state?.toFloatOrNull()
                when (keyCode) {
                    24, 25 -> {
                        // 原版 adjustFanSpeedByKey：按 percentage_step 档位上下移动
                        // （步进默认 20 → 最多 5 档），到边界忽略按键
                        val step = haStatesStore.states.value["$entityId.percentage_step"]
                            ?.state?.toFloatOrNull()?.takeIf { it > 0f } ?: 20f
                        val count = (100f / step).toInt().coerceIn(1, 5)
                        val levels = (1..count).map { (it * step).toInt().coerceAtMost(100) }
                        val current = percentage ?: 0f
                        val idx = levels.withIndex()
                            .minByOrNull { kotlin.math.abs(it.value - current) }?.index ?: 0
                        val nextIdx = idx + if (keyCode == 24) 1 else -1
                        if (nextIdx in levels.indices) {
                            fanSetPercentage(entityId, levels[nextIdx])
                        }
                        true
                    }

                    132 -> {
                        if (large) false
                        else {
                            if (state == "on") turnOff(entityId) else turnOn(entityId); true
                        }
                    }

                    else -> false
                }
            }

            PanelCardTypes.LIGHT -> {
                // 原版 LightControlView：24/25 = 亮度 ±1%（按键重复 + 100ms 节流），
                // 132 = 电源开关；其余键不消费（原版直接透传系统）。
                val pct = haStatesStore.states.value["$entityId.brightness"]
                    ?.state?.toFloatOrNull()?.div(2.55f)
                when (keyCode) {
                    24, 25 -> {
                        if (state != "on") {
                            true // 原版：关灯时按键被消费但不动作
                        } else {
                            val delta = if (keyCode == 24) 1f else -1f
                            val next = ((pct ?: 0f) + delta).coerceIn(0f, 100f).toInt()
                            lightTurnOn(entityId, brightnessPct = next)
                            true
                        }
                    }

                    132 -> {
                        if (large) false
                        else {
                            if (state == "on") lightTurnOff(entityId) else lightTurnOn(entityId); true
                        }
                    }

                    else -> false
                }
            }

            PanelCardTypes.COVER -> when (keyCode) {
                // 原版 CurtainActivity.dispatchKeyEvent：只消费 132（开/关切换，
                // keyControl0penOrClose 按 openFlag 翻转）与 23（停止）；
                // 其余键透传系统（返回 false 落到音量回退）。
                132 -> {
                    if (large) false
                    else {
                        if (state == "open") coverClose(entityId) else coverOpen(entityId); true
                    }
                }

                23 -> {
                    coverStop(entityId); true
                }

                else -> false
            }

            PanelCardTypes.MEDIA_PLAYER -> {
                when (keyCode) {
                    23 -> mediaTogglePlayback(entityId)
                    24 -> mediaCommand(entityId, "volume_up")
                    25 -> mediaCommand(entityId, "volume_down")
                    92 -> mediaCommand(entityId, "media_previous_track")
                    93 -> mediaCommand(entityId, "media_next_track")
                    164 -> haActionBus.callService(
                        "media_player.volume_mute",
                        mapOf("entity_id" to entityId, "is_volume_muted" to "true")
                    )
                    132 -> {
                        if (state == "playing" || state == "paused") mediaCommand(entityId, "turn_off")
                        else mediaCommand(entityId, "turn_on")
                    }

                    else -> return false
                }
                true
            }

            PanelCardTypes.SWITCH, PanelCardTypes.SCENE -> when (keyCode) {
                132 -> {
                    if (large) false
                    else {
                        if (state == "on") turnOff(entityId) else turnOn(entityId); true
                    }
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
