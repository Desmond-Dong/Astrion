package com.example.ava.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Card types, matching the original `aiks-*` RosCard family (§4) rendered
 * natively by the panel (§5.2: layout rendered by the App, entities owned by
 * the ESPHome device in Home Assistant).
 */
object PanelCardTypes {
    const val TV = "tv"
    const val LIGHT = "light"
    const val FAN = "fan"
    const val SCENE = "scene"
    const val MEDIA_PLAYER = "media-player"
    const val CLIMATE = "climate"
    const val COVER = "cover"
    const val SWITCH = "switch"
    const val WEATHER = "weather"
    const val HOST = "host"
    const val SWITCH_MONITOR = "switch-monitor"

    val all = setOf(
        TV, LIGHT, FAN, SCENE, MEDIA_PLAYER, CLIMATE,
        COVER, SWITCH, WEATHER, HOST, SWITCH_MONITOR
    )
}

/**
 * The full panel configuration pushed from Home Assistant through the
 * `astrion_layout` text entity. It replaces the original `astrion/devices`
 * WebSocket device tree (§2.8): rooms with cards, extra navigation pages and
 * the list of Home Assistant entities whose states the panel imports.
 */
@Serializable
data class PanelLayout(
    val rooms: List<PanelRoom> = emptyList(),
    /**
     * Additional navigation page names exposed on the A/B navigate selects
     * (original `astrion/navigate_list_upload` pages, §3.7).
     */
    val pages: List<String> = emptyList(),
    /**
     * Home Assistant entity ids the panel subscribes to for state rendering.
     * An entry of the form `entity_id.attribute` subscribes to a single
     * attribute (e.g. `climate.ac.temperature`).
     */
    @SerialName("sync_entities") val syncEntities: List<String> = emptyList(),
) {
    companion object {
        fun fromJson(json: String): PanelLayout? = runCatching {
            if (json.isBlank()) null else panelJson.decodeFromString<PanelLayout>(json)
        }.getOrNull()

        /**
         * Accepts either the full JSON form or a plain-text form that needs no
         * JSON at all — one line per room, comma-separated entity ids:
         *
         * ```
         * 客厅=remote.mi_tv, media_player.mi_tv, light.ceiling
         * 卧室=light.bed, fan.bed
         * ```
         *
         * A line without `=` puts its entities into the default room
         * "所有设备". Card types are inferred from the entity domain and the
         * entities are auto-subscribed for state rendering. Lines starting
         * with `#` are comments. Everything after a `|` on a line is the card
         * display name: `客厅=light.ceiling | 吸顶灯`.
         */
        fun parseFlexible(text: String): PanelLayout? {
            if (text.isBlank()) return null
            if (text.trimStart().startsWith("{")) return fromJson(text)
            return runCatching {
                var defaultRoom: PanelRoom? = null
                val rooms = mutableListOf<PanelRoom>()
                val pages = mutableListOf<String>()
                for (rawLine in text.lines()) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val (roomPart, entityPart, cardName) = splitLine(line)
                    val entityIds = entityPart.split(',', '，')
                        .map { it.trim() }
                        .filter { it.contains('.') }
                    if (entityIds.isEmpty()) {
                        // A line with entities or a title only: extra page
                        if (entityPart.isNotBlank() || roomPart.isNotBlank()) pages.add(roomPart.ifBlank { entityPart })
                        continue
                    }
                    val cards = entityIds.mapIndexed { index, entityId ->
                        PanelCard(
                            name = if (index == 0) cardName else "",
                            entities = listOf(PanelEntityRef(entityId = entityId))
                        )
                    }
                    if (roomPart.isBlank() || roomPart == DEFAULT_ROOM_TITLE) {
                        val room = defaultRoom ?: PanelRoom(DEFAULT_ROOM_TITLE).also {
                            defaultRoom = it; rooms.add(it)
                        }
                        room.cards += cards
                    } else {
                        rooms.add(PanelRoom(roomPart, cards.toMutableList()))
                    }
                }
                PanelLayout(
                    rooms = rooms,
                    pages = pages.distinct(),
                    syncEntities = rooms.flatMap { it.cards }
                        .flatMap { it.entities }.map { it.entityId }.distinct()
                )
            }.getOrNull()
        }

        private fun splitLine(line: String): Triple<String, String, String> {
            val titlePart = line.substringBefore('|').trim()
            val cardName = line.substringAfter('|', "").trim()
            val hasRoom = titlePart.contains('=')
            val room = if (hasRoom) titlePart.substringBefore('=').trim() else ""
            val entities = if (hasRoom) titlePart.substringAfter('=').trim() else titlePart
            return Triple(room, entities, cardName)
        }

        const val DEFAULT_ROOM_TITLE = "所有设备"
    }
}

@Serializable
data class PanelRoom(
    val title: String,
    val cards: MutableList<PanelCard> = mutableListOf(),
)

/**
 * One device card. [type] selects the detail page; [entities] lists the Home
 * Assistant entities backing the card. [type] is optional — it is inferred
 * from the primary entity's domain when omitted, so minimal layouts only need
 * a name and an entity_id.
 */
@Serializable
data class PanelCard(
    val type: String = "",
    val uuid: String = "",
    val name: String = "",
    /** `android_tv` / `apple_tv` for tv cards (§4.1). */
    @SerialName("tv_type") val tvType: String = "android_tv",
    /** Optional icon override. */
    val icon: String = "",
    /** Percentage step for fan cards (§3.10.4 ④: getPercentageStep). */
    @SerialName("percentage_step") val percentageStep: Int = 0,
    val entities: List<PanelEntityRef> = emptyList(),
) {
    /**
     * The card type after domain-based inference (§4 aiks-* card types).
     */
    val resolvedType: String
        get() = type.ifBlank {
            when (primaryEntity?.entityDomain) {
                "light" -> PanelCardTypes.LIGHT
                "climate", "water_heater" -> PanelCardTypes.CLIMATE
                "fan" -> PanelCardTypes.FAN
                "cover", "valve" -> PanelCardTypes.COVER
                "media_player" -> PanelCardTypes.MEDIA_PLAYER
                "remote" -> PanelCardTypes.TV
                "scene", "script" -> PanelCardTypes.SCENE
                "weather" -> PanelCardTypes.WEATHER
                "vacuum", "lawn_mower" -> PanelCardTypes.HOST
                else -> PanelCardTypes.SWITCH
            }
        }

    /** Stable identity used for navigation; falls back to a type+name slug. */
    val cardId: String
        get() = uuid.ifBlank {
            "${type.ifBlank { resolvedType }}_${name.ifBlank { entities.firstOrNull()?.entityId ?: "" }}"
        }

    /** Primary entity used for state display and default actions. */
    val primaryEntity: PanelEntityRef? get() = entities.firstOrNull()
}

/**
 * A single Home Assistant entity reference inside a card.
 *
 * - [key] is the app-side key name for tv cards (POWER, VOLUME_UP, ...).
 * - [value] is the raw command name sent for remote entities; defaults to [key].
 * - [domain] overrides the domain derived from [entityId].
 */
@Serializable
data class PanelEntityRef(
    val key: String = "",
    @SerialName("entity_id") val entityId: String = "",
    val value: String = "",
    val alias: String = "",
    val domain: String = "",
) {
    val entityDomain: String
        get() = domain.ifBlank { entityId.substringBefore('.') }

    val effectiveCommand: String get() = value.ifBlank { key }
}

/**
 * Physical key bindings pushed from Home Assistant through the
 * `astrion_key_bindings` text entity (§3.10.5 shortcut keys): each hardware
 * key can be bound to jump to a room, open a card page, or run an HA service,
 * separately for short and long press.
 */
@Serializable
data class PanelKeyBindings(
    val bindings: List<KeyBinding> = emptyList(),
) {
    companion object {
        fun fromJson(json: String): PanelKeyBindings? = runCatching {
            if (json.isBlank()) null else panelJson.decodeFromString<PanelKeyBindings>(json)
        }.getOrNull()

        /**
         * Accepts the JSON form or a plain one-line-per-key form that needs no
         * JSON. `keycode` optionally suffixed `_long` maps to a target:
         *
         * ```
         * # 注释
         * 132=home                    # 回到首页
         * 135=light.ceiling           # 实体 id：按域自动动作（开/关/执行场景）
         * 136=scene.film
         * 132_long=climate.ac
         * 93=room:客厅                # 跳到房间
         * 94=card:tv1                 # 打开设备详情页
         * 96=voice                    # 免唤醒语音对话
         * ```
         *
         * Plain entity ids get domain-based default actions: scene/script
         * turn on, other on/off domains toggle. `#` lines are comments.
         */
        fun parseFlexible(text: String): PanelKeyBindings? {
            if (text.isBlank()) return null
            if (text.trimStart().startsWith("{")) return fromJson(text)
            return runCatching {
                val bindings = mutableListOf<KeyBinding>()
                for (rawLine in text.lines()) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) continue
                    val keyPart = line.substringBefore('=').trim().lowercase()
                    val target = line.substringAfter('=').trim()
                    if (target.isBlank()) continue
                    val longPress = keyPart.endsWith("_long")
                    val keycode = keyPart.removeSuffix("_long").toIntOrNull() ?: continue
                    bindings.add(
                        when {
                            target == "voice" -> KeyBinding(keycode, longPress, KeyBindingActions.VOICE)
                            target == "home" -> KeyBinding(keycode, longPress, KeyBindingActions.HOME)
                            target.startsWith("room:") -> KeyBinding(
                                keycode, longPress, KeyBindingActions.ROOM,
                                target.removePrefix("room:").trim()
                            )

                            target.startsWith("card:") -> KeyBinding(
                                keycode, longPress, KeyBindingActions.CARD,
                                target.removePrefix("card:").trim()
                            )

                            target.contains('.') -> KeyBinding(
                                keycode, longPress, KeyBindingActions.SERVICE,
                                entityId = target
                            )

                            else -> continue
                        }
                    )
                }
                PanelKeyBindings(bindings)
            }.getOrNull()
        }
    }
}

/** Binding action types. */
object KeyBindingActions {
    /** Jump to the room whose title is [KeyBinding.target]. */
    const val ROOM = "room"

    /** Open the device detail page of the card whose id is [KeyBinding.target]. */
    const val CARD = "card"

    /** Call the HA service [KeyBinding.service] with [KeyBinding.entityId]/[KeyBinding.data]. */
    const val SERVICE = "service"

    /** Jump back to the first room (panel home). */
    const val HOME = "home"

    /** Start a hands-free Assist conversation (same as the mic key). */
    const val VOICE = "voice"

    val all = setOf(ROOM, CARD, SERVICE, HOME, VOICE)
}

@Serializable
data class KeyBinding(
    /** Android keycode (e.g. 132 = power key on HA100). */
    val keycode: Int,
    @SerialName("long_press") val longPress: Boolean = false,
    /** One of [KeyBindingActions]. */
    val action: String,
    /** Room title ([KeyBindingActions.ROOM]) or card id ([KeyBindingActions.CARD]). */
    val target: String = "",
    /** Service to call, e.g. `scene.turn_on` ([KeyBindingActions.SERVICE]). */
    val service: String = "",
    @SerialName("entity_id") val entityId: String = "",
    val data: Map<String, String> = emptyMap(),
)

/**
 * IR codebook pushed from Home Assistant through the `astrion_ir_codes` text
 * entity: `{"<device>": {"<button>": "<code>"}}`. Codes are raw strings in any
 * of the three supported formats (comma timings, Broadlink base64, AES base64)
 * decoded by [com.example.ava.esphome.infrared.IrCodec].
 */
@Serializable
data class IrCodebook(
    val devices: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        /**
         * Accepts the direct map form `{"<device>": {"<button>": "<code>"}}`.
         * A top-level object whose only key is `devices` is treated as the
         * wrapped form `{"devices": {...}}`.
         */
        fun fromJson(json: String): IrCodebook? = runCatching {
            if (json.isBlank()) return null
            val element = panelJson.parseToJsonElement(json)
            val obj = element.jsonObject
            val target = if (obj.keys == WRAPPED_KEYS) obj.getValue(WRAPPED_KEY) else element
            IrCodebook(panelJson.decodeFromJsonElement(target))
        }.getOrNull()

        private const val WRAPPED_KEY = "devices"
        private val WRAPPED_KEYS = setOf(WRAPPED_KEY)
    }
}

/**
 * Lenient JSON settings shared by all panel config parsing: unknown keys are
 * ignored so Home Assistant side configs can evolve without breaking older
 * panels.
 */
val panelJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
}
