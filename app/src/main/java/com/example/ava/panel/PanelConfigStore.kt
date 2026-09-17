package com.example.ava.panel

import android.content.Context
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val SETTINGS_FILE_NAME = "panel_config.json"

/**
 * Raw configuration strings received from Home Assistant, persisted so the
 * panel keeps its layout and IR codebook across restarts without HA having to
 * re-push them.
 */
@Serializable
data class PanelConfig(
    /** Raw JSON of [PanelLayout] received through the `astrion_layout` text entity. */
    val layoutJson: String = "",
    /** Raw JSON of [IrCodebook] received through the `astrion_ir_codes` text entity. */
    val irCodesJson: String = "",
    /** Raw JSON of [PanelKeyBindings] received through the `astrion_key_bindings` text entity. */
    val keyBindingsJson: String = "",
)

private val DEFAULT = PanelConfig()

/**
 * Store of the HA-driven panel configuration. Home Assistant writes config
 * JSON into the panel's text entities; this store parses, persists and
 * re-exposes it as typed flows for the UI and the ESPHome entity builders.
 */
class PanelConfigStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val settings = com.example.ava.settings.SettingsStoreImpl(
        default = DEFAULT,
        produceFile = { context.dataStoreFile(SETTINGS_FILE_NAME) },
        serializer = PanelConfig.serializer()
    )

    private val _version = MutableStateFlow(0)

    /**
     * Bumped every time the configuration content actually changes. The
     * satellite service observes this to rebuild the ESPHome entity list
     * (IR codebook changes add/remove button entities).
     */
    val version: StateFlow<Int> = _version.asStateFlow()

    val raw: Flow<PanelConfig> = settings.getFlow()

    val layout: Flow<PanelLayout> =
        raw.map { PanelLayout.parseFlexible(it.layoutJson) ?: PanelLayout() }

    val irCodebook: Flow<IrCodebook> =
        raw.map { IrCodebook.parseFlexible(it.irCodesJson) ?: IrCodebook() }

    /**
     * The layout the panel renders. When no layout has been pushed yet but an
     * IR codebook exists, a default "所有设备" room with one tv card per
     * codebook device is synthesised — pushing only `astrion_ir_codes` yields
     * a working remote (两步上手).
     */
    val effectiveLayout: Flow<PanelLayout> =
        kotlinx.coroutines.flow.combine(layout, irCodebook) { layout, codebook ->
            val deduped = dedupeCards(layout)
            if (deduped.rooms.isEmpty() && codebook.devices.isNotEmpty()) {
                PanelLayout(
                    rooms = listOf(
                        PanelRoom(
                            title = "所有设备",
                            cards = codebook.devices.map { (deviceName, buttons) ->
                                PanelCard(
                                    type = PanelCardTypes.TV,
                                    name = deviceName,
                                    entities = buttons.keys.map { PanelEntityRef(key = it) }
                                )
                            }.toMutableList()
                        )
                    ),
                    pages = deduped.pages,
                    syncEntities = deduped.syncEntities
                )
            } else {
                deduped
            }
        }

        // Duplicate entries (same entity listed twice) would crash the grid
        // with duplicate LazyColumn keys: later duplicates get a numbered id.
        fun dedupeCards(layout: PanelLayout): PanelLayout {
            val seen = mutableMapOf<String, Int>()
            val rooms = layout.rooms.map { room ->
                PanelRoom(
                    room.title,
                    room.cards.map { card ->
                        val n = seen.merge(card.cardId, 1, Int::plus) ?: 1
                        if (n == 1) card else card.copy(uuid = "${card.cardId}_$n")
                    }.toMutableList()
                )
            }
            return PanelLayout(rooms, layout.pages, layout.syncEntities)
        }

    val keyBindings: Flow<PanelKeyBindings> =
        raw.map { PanelKeyBindings.parseFlexible(it.keyBindingsJson) ?: PanelKeyBindings() }

    /**
     * Home Assistant entity ids the panel subscribes to, parsed from the
     * layout (`sync_entities`). `entity.attribute` entries are preserved for
     * attribute-level subscriptions.
     */
    val syncEntities: Flow<List<String>> = layout.map { l ->
        val expanded = l.syncEntities.toMutableList()
        // Light capability attributes (调色温/颜色/亮度联动) are subscribed
        // automatically for every plain light entity in the list.
        l.syncEntities.forEach { entry ->
            val tail = entry.substringAfter('.', "")
            val isPlainEntity = tail.isNotBlank() && !tail.contains('.')
            if (isPlainEntity) {
                // The friendly HA display name and light capability attributes
                // (调色温/颜色/亮度联动) are subscribed automatically.
                expanded += "$entry.friendly_name"
            }
            if (entry.startsWith("light.") && isPlainEntity) {
                expanded += listOf(
                    "$entry.supported_color_modes",
                    "$entry.color_temp_kelvin",
                    "$entry.min_color_temp_kelvin",
                    "$entry.max_color_temp_kelvin",
                    "$entry.brightness"
                )
            }
        }
        expanded.distinct()
    }

    /**
     * Applies layout configuration received from Home Assistant: full JSON or
     * the plain per-line form (客厅=entity1, entity2). Invalid content keeps
     * the previous configuration.
     *
     * @return true when the config was accepted (changed or confirmed).
     */
    suspend fun applyLayoutJson(json: String): Boolean = apply("layout", json) { current ->
        if (PanelLayout.parseFlexible(json) == null) null
        else current.copy(layoutJson = json.trim())
    }

    /**
     * Applies IR codebook JSON received from Home Assistant. Invalid JSON
     * keeps the previous codebook.
     */
    suspend fun applyIrCodesJson(json: String): Boolean = apply("ir_codes", json) { current ->
        if (IrCodebook.parseFlexible(json) == null) null
        else current.copy(irCodesJson = json.trim())
    }

    /**
     * Applies physical key binding JSON received from Home Assistant. Invalid
     * JSON keeps the previous bindings.
     */
    suspend fun applyKeyBindingsJson(json: String): Boolean =
        apply("key_bindings", json) { current ->
            if (PanelKeyBindings.parseFlexible(json) == null) null
            else current.copy(keyBindingsJson = json.trim())
        }

    private suspend fun apply(
        tag: String,
        json: String,
        transform: (PanelConfig) -> PanelConfig?
    ): Boolean {
        val current = settings.get()
        val updated = transform(current)
        if (updated == null) {
            Timber.w("Rejected invalid $tag config from Home Assistant (${json.length} chars)")
            return false
        }
        if (updated != current) {
            settings.update { updated }
            Timber.i("Panel $tag config updated (${json.length} chars), bumping version")
            _version.value += 1
        }
        return true
    }
}

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
object PanelConfigModule {
    @Provides
    @Singleton
    fun providePanelConfigStore(@ApplicationContext context: Context): PanelConfigStore =
        PanelConfigStore(context)
}

/**
 * Derives an ESPHome safe object id from a display name.
 */
fun irObjectId(name: String): String {
    val cleaned = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString(separator = "")
        .trim('_')
    return cleaned.ifEmpty { "ir_device" }
}
