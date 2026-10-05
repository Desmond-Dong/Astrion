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
    private val acModePrefs: AcModePrefs,
    private val panelUiEvents: PanelUiEvents,
) {
    // ── TV remote keys ──────────────────────────────────────────────────

    /**
     * Presses a named key (POWER, UP, VOLUME_UP, NUM_5, ...) on a tv card.
     * Local codebook first, then the HA service matching the entity domain
     * (§4.1 服务分派; astrion 化: astrion/control_command → remote.send_command).
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
                buildMap {
                    put("entity_id", ref.entityId)
                    put("command", ref.effectiveCommand)
                    // 原版 TvControlItem.harmony_device：按键绑定了网关设备时
                    // 附带 device 参数，由 Harmony/万能遥控实体路由命令
                    if (ref.device.isNotBlank()) put("device", ref.device)
                }
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

            // 原版 TvControlItem：media_player 型的 turn_on/turn_off 控制项走
            // sendTvMediaTurnOn/TurnOff（即 media_player.turn_on/off），不是 play_pause
            "POWER", "TURN_ON" -> haActionBus.callService("media_player.turn_on", data)
            "TURN_OFF" -> haActionBus.callService("media_player.turn_off", data)
            "STOP" -> haActionBus.callService("media_player.media_stop", data)

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
     * 长按自动重复（原版各页 KEY_INTERVAL 节奏）。由设备详情页持有，按键松开
     * （cancel 事件）后停止。[repeat] 为重复序号（长按首触发=0，之后 1、2…），
     * 空调温度用原始步进 + repeat×1.0 递增（原版 step 累加）。
     */
    suspend fun holdDeviceStep(card: PanelCard, keyCode: Int, repeat: Int) {
        if (!applyDeviceStep(card, keyCode, large = true, repeat = repeat)) return
    }

    private suspend fun applyDeviceStep(
        card: PanelCard,
        keyCode: Int,
        large: Boolean,
        repeat: Int = 0,
    ): Boolean {
        val entityId = card.primaryEntity?.entityId ?: return false
        val state = haStatesStore.states.value[entityId]?.state
        // resolvedType（含 domain 推断）：集成生成的布局卡片可以不带 type 字段
        return when (card.resolvedType) {
            PanelCardTypes.TV -> {
                // 原版 TV 键：音量→media_player；频道→send_command；OK 等单键。
                // 连发仅限原版 LongPressKeyHandler 键集 {方向/音量/频道}；其它键
                // 长按只发一次（首触发），hold 重复不执行
                val fire = !large || repeat == 0
                when (keyCode) {
                    19 -> pressTvKey(card, "UP")
                    20 -> pressTvKey(card, "DOWN")
                    21 -> pressTvKey(card, "LEFT")
                    22 -> pressTvKey(card, "RIGHT")
                    23 -> if (fire) pressTvKey(card, "CENTER")
                    4 -> when {
                        !large -> pressTvKey(card, "BACK")
                        // 原版 BACK 按住 2s 退出页面（只触发一次）
                        repeat == 0 -> panelUiEvents.requestBack()
                    }
                    24 -> pressTvKey(card, "VOLUME_UP")
                    25 -> pressTvKey(card, "VOLUME_DOWN")
                    164 -> if (fire) pressTvKey(card, "MUTE")
                    92 -> pressTvKey(card, "CHANNEL_UP")
                    93 -> pressTvKey(card, "CHANNEL_DOWN")
                    82 -> if (fire) pressTvKey(card, "MENU")
                    132 -> if (fire) pressTvKey(card, "POWER")
                    // 原版 keyCodeToTvKey：HA100 上 131 → HOME（136 仍是 F6）
                    131 -> pressTvKey(card, "HOME")
                    // 原版 initKeyMap：134-141 = F4-F11（send_command 键名）
                    in 134..141 -> if (fire) pressTvKey(card, "F${keyCode - 130}")
                    else -> return false
                }
                true
            }

            PanelCardTypes.CLIMATE -> {
                val current = haStatesStore.states.value["$entityId.temperature"]
                    ?.state?.toDoubleOrNull()
                // 原版 mTemperatureStep：设备步进（target_temp_step），默认 1.0；
                // 长按（原版 300ms KEY_INTERVAL）时步进累加 1.0f：repeat 为长按
                // 重复序号（0=长按首触发，与单击同幅）
                val step = haStatesStore.states.value["$entityId.target_temp_step"]
                    ?.state?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 1.0
                val delta = step + repeat * 1.0f
                // 原版门控：canControlTemperature 排除 heat_cool（该模式走范围面板）；
                // min/max 夹取自 min_temp/max_temp 属性
                val isHeatCool = state == "heat_cool"
                val minTemp = haStatesStore.states.value["$entityId.min_temp"]
                    ?.state?.toDoubleOrNull() ?: 16.0
                val maxTemp = haStatesStore.states.value["$entityId.max_temp"]
                    ?.state?.toDoubleOrNull() ?: 30.0
                when (keyCode) {
                    24, 25 -> {
                        // 原版 heat_cool 下 24/25 消费但不动作；temperature 属性
                        // 缺失不发（原版 mPendingTemp 来自设备值）
                        if (isHeatCool || current == null) {
                            true
                        } else {
                            val next = if (keyCode == 24) current + delta else current - delta
                            climateSetTemperature(
                                entityId, next.coerceIn(minTemp, maxTemp)
                            )
                            true
                        }
                    }

                    // 原版 keyControlFanSpeed：92=风速降 / 93=风速升，
                    // (idx+dir+size)%size 循环回绕（首尾相接）
                    92 -> {
                        shiftClimateFanMode(entityId, -1); true
                    }

                    93 -> {
                        shiftClimateFanMode(entityId, +1); true
                    }

                    // 原版 AcControlView：UP 164 → 启动 HomeActivity（回首页）
                    164 -> {
                        panelUiEvents.requestGoHome(); true
                    }

                    132 -> {
                        if (large) false
                        else {
                            // 原版 canTogglePower：hvac_modes 含 off 才可开关；
                            // 关机前缓存当前模式，开机恢复缓存（缺失/非法时
                            // 回落 supportedModes 首个，仍无则不动）
                            val supported = haStatesStore.states.value["$entityId.hvac_modes"]
                                ?.state?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                                .orEmpty()
                            if (!supported.contains("off") || supported.none { it != "off" }) {
                                false // 原版开关禁用：不消费
                            } else if (state != "off" && state != "unavailable" && state != "unknown") {
                                state?.let { acModePrefs.setCachedMode(entityId, it) }
                                climateSetHvacMode(entityId, "off")
                                true
                            } else {
                                val resume = acModePrefs.getCachedMode(entityId)
                                    ?.takeIf { it != "off" && (supported.isEmpty() || it in supported) }
                                    ?: supported.firstOrNull { it != "off" }
                                if (resume == null) false
                                else {
                                    climateSetHvacMode(entityId, resume)
                                    true
                                }
                            }
                        }
                    }

                    else -> false
                }
            }

            PanelCardTypes.FAN -> {
                val percentage = haStatesStore.states.value["$entityId.percentage"]
                    ?.state?.toFloatOrNull()
                // 原版 dispatchKeyEvent：24/25 仅 isOn 且支持百分比时生效（UP
                // 单发、无连发）；132=电源翻转
                val fire = !large || repeat == 0
                when (keyCode) {
                    24, 25 -> {
                        if (state != "on" || percentage == null) {
                            true // 关机或不支持：消费但不动作
                        } else {
                            if (fire) {
                                val step = haStatesStore.states.value["$entityId.percentage_step"]
                                    ?.state?.toFloatOrNull()?.takeIf { it > 0f } ?: 20f
                                val count = (100f / step).toInt().coerceIn(1, 5)
                                val levels = (1..count).map { (it * step).toInt().coerceAtMost(100) }
                                val idx = levels.withIndex()
                                    .minByOrNull { kotlin.math.abs(it.value - percentage) }?.index ?: 0
                                val nextIdx = idx + if (keyCode == 24) 1 else -1
                                if (nextIdx in levels.indices) {
                                    fanSetPercentage(entityId, levels[nextIdx])
                                }
                            }
                            true
                        }
                    }

                    132 -> {
                        if (!fire) true
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

            PanelCardTypes.COVER -> {
                // 原版 CurtainActivity：132（开/关切换）与 23（停止）；
                // 百叶窗（CurtainBlindsActivity）：24/25→位置 ±（长按 step=5）、
                // 92/93→倾斜 ±。普通窗帘其余键透传。
                val supportsTilt =
                    haStatesStore.states.value["$entityId.current_tilt_position"] != null
                when (keyCode) {
                    132 -> {
                        if (large) false
                        else {
                            if (state == "open") coverClose(entityId) else coverOpen(entityId); true
                        }
                    }

                    23 -> {
                        coverStop(entityId); true
                    }

                    24, 25 -> {
                        if (!supportsTilt) false
                        else {
                            // 24=开大位置 / 25=收小位置；长按步进 5（原版 LONG_STEP_VALUE）
                            val dir = if (keyCode == 24) 1 else -1
                            shiftCoverPosition(entityId, dir * if (large) 5 else 1)
                        }
                    }

                    92, 93 -> {
                        if (!supportsTilt) false
                        else {
                            // 原版 handleTiltKey：92=倾斜加大 / 93=倾斜减小
                            //（注意与空调风速的 92=降/93=升 方向相反）
                            val dir = if (keyCode == 92) 1 else -1
                            shiftCoverTilt(entityId, dir * if (large) 5 else 1)
                        }
                    }

                    else -> false
                }
            }

            PanelCardTypes.MEDIA_PLAYER -> {
                // 原版媒体页：23/92/93/132/164 均 UP 单发（长按忽略），
                // 仅 24/25 长按连发音量；132 按原版 isDeviceOn 集合判定
                val isDeviceOn =
                    state in setOf("on", "idle", "playing", "paused", "buffering")
                val fire = !large || repeat == 0
                when (keyCode) {
                    23 -> if (fire) mediaTogglePlayback(entityId)
                    24 -> mediaCommand(entityId, "volume_up")
                    25 -> mediaCommand(entityId, "volume_down")
                    92 -> if (fire) mediaCommand(entityId, "media_previous_track")
                    93 -> if (fire) mediaCommand(entityId, "media_next_track")
                    // 原版 setVolumeMute：静音状态取反（toggle），不是固定置 true
                    164 -> if (fire) {
                        val mutedNow = haStatesStore.states.value["$entityId.is_volume_muted"]
                            ?.state == "true"
                        haActionBus.callService(
                            "media_player.volume_mute",
                            mapOf("entity_id" to entityId, "is_volume_muted" to (!mutedNow).toString())
                        )
                    }
                    132 -> if (fire) {
                        if (isDeviceOn) mediaCommand(entityId, "turn_off")
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

    /** 实体上报的风速档位清单（原始值透传），属性未同步时退回常见档位。 */
    private fun climateFanModes(entityId: String): List<String> =
        haStatesStore.states.value["$entityId.fan_modes"]?.state
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf("auto", "low", "medium", "high")

    /**
     * 原版 changeFanSpeed：风速在档位清单上按方向移动一步，
     * (idx + dir + size) % size **循环回绕**（首尾相接）。
     * [dir] 为 +1（升，93 键）或 -1（降，92 键）。
     */
    private suspend fun shiftClimateFanMode(entityId: String, dir: Int) {
        val modes = climateFanModes(entityId)
        if (modes.isEmpty()) return
        val current = haStatesStore.states.value["$entityId.fan_mode"]?.state
        val idx = modes.indexOf(current)
        // 原版：当前不在列表时 down→0、up→末档前（idx=-1 处理）
        val base = if (idx < 0) (if (dir > 0) -1 else 0) else idx
        val next = modes[(base + dir + modes.size) % modes.size]
        climateSetFanMode(entityId, next)
    }

    /** 百叶窗位置 ±[delta]%（原版 vSliderPosition 单步 1 / 长按 5）。 */
    private suspend fun shiftCoverPosition(entityId: String, delta: Int): Boolean {
        val cur = haStatesStore.states.value["$entityId.current_position"]
            ?.state?.toIntOrNull() ?: return true // 无位置数据：消费按键不动作
        val next = (cur + delta).coerceIn(0, 100)
        haActionBus.callService(
            "cover.set_cover_position",
            mapOf("entity_id" to entityId, "position" to next.toString())
        )
        return true
    }

    /** 百叶窗倾斜 ±[delta]%（原版 vSliderTilt 单步 1 / 长按 5）。 */
    private suspend fun shiftCoverTilt(entityId: String, delta: Int): Boolean {
        val cur = haStatesStore.states.value["$entityId.current_tilt_position"]
            ?.state?.toIntOrNull() ?: return true
        val next = (cur + delta).coerceIn(0, 100)
        haActionBus.callService(
            "cover.set_cover_tilt_position",
            mapOf("entity_id" to entityId, "tilt_position" to next.toString())
        )
        return true
    }

    private fun formatNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString()
        else value.toString()
}
