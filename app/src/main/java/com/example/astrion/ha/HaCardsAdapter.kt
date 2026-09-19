package com.example.astrion.ha

import com.example.astrion.panel.PanelCard
import com.example.astrion.panel.PanelCardTypes
import com.example.astrion.panel.PanelEntityRef
import com.example.astrion.panel.PanelLayout
import com.example.astrion.panel.PanelRoom
import com.example.astrion.services.HaEntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Converts the astrion integration's `astrion/get_cards` response into the
 * panel's [PanelLayout] format, so the existing rendering engine
 * (PanelHomeScreen / DeviceDetailScreen / CardController) works unchanged.
 *
 * Mapping:
 * - One integration card (a category subentry, e.g. TV / 灯光) becomes one
 *   panel room titled after the card.
 * - `tv` cards become one `tv`-type card per bound media_player device: the
 *   remote entity (power_entity / remote_entities[0]) stays the primary
 *   fallback for unhandled keys (local IR codebook → remote.send_command),
 *   media keys go to the volume/media entity, select_options and F-key
 *   bindings become per-key entity refs.
 * - Other categories become one card per entity with the type inferred from
 *   the entity domain; scene/weather/host categories read `config.entities`.
 */
object HaCardsAdapter {

    data class Result(
        val layout: PanelLayout,
        /** remote entity id → the tv card name it was merged under (codebook key). */
        val remoteEntityToCardName: Map<String, String>,
        /** All HA entity ids referenced by the layout. */
        val entityIds: Set<String>,
    )

    fun build(cards: JsonArray, haStates: Map<String, HaEntityState>): Result {
        val rooms = mutableListOf<PanelRoom>()
        val remoteMap = mutableMapOf<String, String>()

        for (element in cards) {
            val card = element as? JsonObject ?: continue
            val cardType = card.str("card_type") ?: continue
            val title = card.str("title") ?: cardType
            val config = card["config"] as? JsonObject ?: JsonObject(emptyMap())
            val boundEntities =
                (card["entities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()

            val room = PanelRoom(title)
            when (cardType) {
                "tv" -> for (mediaEntity in boundEntities.distinct()) {
                    val name = friendlyName(haStates, mediaEntity) ?: title
                    val tvCard = tvCard(config, mediaEntity, name)
                    room.cards += tvCard
                    tvCard.entities.mapNotNull { it.entityId.takeIf { id -> id.startsWith("remote.") } }
                        .forEach { remoteMap.putIfAbsent(it, name) }
                }

                "scene", "weather", "host" -> {
                    // These categories carry entity lists in config instead of devices.
                    val entities =
                        (config["entities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList()
                    entities.distinct().forEach { room.cards += simpleCard(it) }
                }

                else -> boundEntities.distinct().forEach { room.cards += simpleCard(it) }
            }
            if (room.cards.isNotEmpty()) rooms += room
        }

        val entityIds = rooms.flatMap { it.cards }
            .flatMap { it.entities }
            .map { it.entityId }
            .filter { it.isNotBlank() }
            .toSet()
        val layout = PanelLayout(rooms = rooms, syncEntities = entityIds.toList())
        return Result(layout, remoteMap, entityIds)
    }

    private fun simpleCard(entityId: String): PanelCard = PanelCard(
        entities = listOf(PanelEntityRef(entityId = entityId))
    )

    private fun tvCard(config: JsonObject, mediaEntity: String, name: String): PanelCard {
        val volumeMode = config.str("volume_mode") ?: "none"
        val entities = buildList {
            when (volumeMode) {
                "updown" -> {
                    config.str("volume_up_entity")?.let {
                        add(PanelEntityRef(key = "VOLUME_UP", entityId = it))
                    }
                    config.str("volume_down_entity")?.let {
                        add(PanelEntityRef(key = "VOLUME_DOWN", entityId = it))
                    }
                    add(PanelEntityRef(key = "PLAY", entityId = mediaEntity))
                    add(PanelEntityRef(key = "PAUSE", entityId = mediaEntity))
                    add(PanelEntityRef(key = "MUTE", entityId = mediaEntity))
                }

                else -> {
                    val mediaTarget = config.str("volume_entity") ?: mediaEntity
                    add(PanelEntityRef(key = "PLAY", entityId = mediaTarget))
                    add(PanelEntityRef(key = "PAUSE", entityId = mediaTarget))
                    add(PanelEntityRef(key = "VOLUME_UP", entityId = mediaTarget))
                    add(PanelEntityRef(key = "VOLUME_DOWN", entityId = mediaTarget))
                    add(PanelEntityRef(key = "MUTE", entityId = mediaTarget))
                }
            }
            config.str("power_entity")?.let { powerEntity ->
                add(
                    PanelEntityRef(
                        key = "POWER",
                        entityId = powerEntity,
                        value = config.str("power_command").orEmpty()
                    )
                )
            }
            (config["select_options"] as? JsonObject)?.forEach { (selectEntity, options) ->
                (options as? JsonArray)?.forEach { option ->
                    option.jsonPrimitive.contentOrNull?.let {
                        add(PanelEntityRef(key = it, entityId = selectEntity))
                    }
                }
            }
            (config["key_bindings"] as? JsonObject)?.forEach { (fkey, binding) ->
                val bind = binding as? JsonObject ?: return@forEach
                add(
                    PanelEntityRef(
                        key = fkey,
                        entityId = bind.str("entity_id").orEmpty(),
                        value = bind.str("command").orEmpty()
                    )
                )
            }
            // Default fallback: unhandled keys (direction pad, NUM_n, …) go to
            // the remote entity → local IR codebook, then remote.send_command.
            val fallbackEntity = config.str("power_entity")
                ?: (config["remote_entities"] as? JsonArray)
                    ?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?: mediaEntity
            add(PanelEntityRef(key = fallbackEntity, entityId = fallbackEntity))
        }
        return PanelCard(
            type = PanelCardTypes.TV,
            uuid = "tv_${mediaEntity.substringAfter('.').replace('-', '_')}",
            name = name,
            entities = entities,
        )
    }

    private fun friendlyName(haStates: Map<String, HaEntityState>, entityId: String): String? =
        haStates["$entityId.friendly_name"]?.state?.takeIf { it.isNotBlank() }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
}
