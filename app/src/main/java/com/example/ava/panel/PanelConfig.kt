package com.example.ava.panel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    }
}

@Serializable
data class PanelRoom(
    val title: String,
    val cards: List<PanelCard> = emptyList(),
)

/**
 * One device card. [type] selects the detail page; [entities] lists the Home
 * Assistant entities backing the card.
 */
@Serializable
data class PanelCard(
    val type: String,
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
    /** Stable identity used for navigation; falls back to a type+name slug. */
    val cardId: String
        get() = uuid.ifBlank {
            "${type}_${name.ifBlank { entities.firstOrNull()?.entityId ?: "" }}"
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

    val all = setOf(ROOM, CARD, SERVICE)
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
         * Accepts both the direct map form `{"<device>": {"<button>": "<code>"}}`
         * and the wrapped form `{"devices": {...}}`.
         */
        fun fromJson(json: String): IrCodebook? = runCatching {
            if (json.isBlank()) return null
            panelJson.decodeFromString<Map<String, Map<String, String>>>(json)
                .takeIf { it.isNotEmpty() }
                ?.let { IrCodebook(it) }
                ?: panelJson.decodeFromString<IrCodebook>(json)
        }.getOrNull()
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
