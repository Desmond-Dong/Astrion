package com.example.astrion.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Card types, matching the original `aiks-*` RosCard family (§4) rendered
 * natively by the panel (§5.2: layout rendered by the App, entities owned by
 * the astrion integration in Home Assistant).
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
 * The full panel configuration pulled from the astrion integration's
 * `astrion/get_cards` WebSocket command ([com.example.astrion.ha.HaCardsAdapter]
 * converts it to this format): rooms with cards, extra navigation pages and
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
         * with `#` are comments. Each entity may carry its own display alias
         * after a `|`: `light.ceiling|吸顶灯`; an alias identical to the
         * entity id is ignored so the HA friendly name shows instead.
         */
        fun parseFlexible(text: String): PanelLayout? {
            if (text.isBlank()) return null
            if (text.trimStart().startsWith("{")) return fromJson(text)
            return runCatching {
                var defaultRoom: PanelRoom? = null
                val rooms = mutableListOf<PanelRoom>()
                val pages = mutableListOf<String>()
                // HA text entities are single-line inputs, so ';' splits
                // records just like a newline would.
                for (rawLine in text.split('\n', ';')) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val (roomPart, entityPart) = splitLine(line)
                    val tokens = entityPart.split(',', '，')
                        .map { it.trim() }
                        .filter { it.contains('.') }
                    if (tokens.isEmpty()) {
                        // A line with entities or a title only: extra page
                        if (entityPart.isNotBlank() || roomPart.isNotBlank()) pages.add(roomPart.ifBlank { entityPart })
                        continue
                    }
                    // 每个实体可各自带别名：`light.bed|床头灯`；别名等于实体 id
                    // 时不覆盖（friendly_name 自动解析显示）。
                    val cards = tokens.map { token ->
                        val entityId = token.substringBefore('|').trim()
                        val alias = token.substringAfter('|', "").trim()
                            .takeUnless { it.equals(entityId, ignoreCase = true) } ?: ""
                        PanelCard(
                            name = alias,
                            entities = listOf(
                                PanelEntityRef(entityId = entityId, alias = alias)
                            )
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

        private fun splitLine(line: String): Pair<String, String> {
            val hasRoom = line.contains('=')
            val room = if (hasRoom) line.substringBefore('=').trim() else ""
            val entities = if (hasRoom) line.substringAfter('=').trim() else line
            return room to entities
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
 * One physical key binding used by the built-in defaults and the on-device
 * binding resolver. Bindings themselves live on the panel
 * ([ShortcutBindingStore]); Home Assistant no longer pushes any.
 */
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

    val all = setOf(ROOM, CARD, SERVICE, HOME)
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
 * IR codebook pulled from Home Assistant via the `astrion/get_device_codes`
 * WebSocket command: `{"<device>": {"<button>": "<code>"}}`. Codes are raw
 * strings in any of the three supported formats (comma timings, Broadlink
 * base64, AES base64) decoded by [com.example.astrion.panel.infrared.IrCodec].
 */
@Serializable
data class IrCodebook(
    val devices: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        /**
         * Accepts the direct map form `{"<device>": {"<button>": "<code>"}}`
         * (a top-level object whose only key is `devices` is unwrapped), or a
         * plain one-line-per-button form that needs no JSON:
         *
         * ```
         * # 注释
         * 小米电视 | POWER=38000,9000,4500,560,560
         * 小米电视 | MUTE=sGipAA==
         * 机顶盒 | POWER=JgBMACHgERAQERAAHQAA
         * ```
         *
         * The code string may contain any characters except a newline.
         */
        fun parseFlexible(text: String): IrCodebook? {
            if (text.isBlank()) return null
            if (text.trimStart().startsWith("{")) return fromJson(text)
            return runCatching {
                val devices = mutableMapOf<String, MutableMap<String, String>>()
                for (rawLine in text.split('\n', ';')) {
                    val line = rawLine.trim()
                    if (line.isEmpty() || line.startsWith("#") || !line.contains('=')) continue
                    val device = line.substringBefore('|').trim()
                    val rest = line.substringAfter('|', "").trim()
                    if (device.isEmpty() || !rest.contains('=')) continue
                    val button = rest.substringBefore('=').trim()
                    val code = rest.substringAfter('=').trim()
                    if (button.isEmpty() || code.isEmpty()) continue
                    devices.getOrPut(device) { mutableMapOf() }[button] = code
                }
                if (devices.isEmpty()) null else IrCodebook(devices)
            }.getOrNull()
        }

        /**
         * The JSON form: a direct `{"<device>": {"<button>": "<code>"}}` map,
         * or the same map wrapped under a single `devices` key.
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
