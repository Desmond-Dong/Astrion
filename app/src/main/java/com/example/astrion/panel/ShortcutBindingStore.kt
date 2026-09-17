package com.example.astrion.panel

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 面板本地快捷键绑定（原版 ShortcutKeyCacheService 的移植）：在设备上绑定
 * "一键快速进入"，存储形态与原版一致：
 *
 * - 设备/场景：`{"type":"Device","uuid":"<cardId>"}`
 * - 房间：`{"type":"DeviceRoom","uuid":"<房间标题>","openMethod":"HOME_PAGE"}`
 *
 * 与 HA 推送的 `astrion_key_bindings` 独立：按键解析顺序为 HA 推送 → 本地绑定
 * → 内置默认。
 */
@Serializable
data class ShortcutBinding(
    /** `Device`（uuid=卡片 cardId）或 `DeviceRoom`（uuid=房间标题）。 */
    val type: String,
    val uuid: String,
    /** 房间打开方式：`HOME_PAGE`（跳房间页）；原版 POPUP 暂未移植。 */
    @SerialName("open_method") val openMethod: String = "HOME_PAGE",
)

@Singleton
class ShortcutBindingStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("shortcut_bindings", Context.MODE_PRIVATE)

    private val _bindings = MutableStateFlow(load())
    val bindings: StateFlow<Map<Int, ShortcutBinding>> = _bindings.asStateFlow()

    fun get(keycode: Int): ShortcutBinding? = _bindings.value[keycode]

    /** 绑定 / 解绑（binding=null）。原版：保存时不选中即解绑。 */
    fun set(keycode: Int, binding: ShortcutBinding?) {
        prefs.edit().apply {
            if (binding == null) remove(keycode.toString())
            else putString(keycode.toString(), panelJson.encodeToString(ShortcutBinding.serializer(), binding))
        }.apply()
        _bindings.value = load()
    }

    private fun load(): Map<Int, ShortcutBinding> = buildMap {
        for (entry in prefs.all) {
            val keycode = entry.key.toIntOrNull() ?: continue
            val json = entry.value as? String ?: continue
            runCatching {
                put(keycode, panelJson.decodeFromString(ShortcutBinding.serializer(), json))
            }
        }
    }

    companion object {
        /** 可绑定按键：HA100 的 F4–F11（原版 device_firmware_key_config 固定表）。 */
        val BINDABLE_KEYS = listOf(134, 135, 136, 137, 138, 139, 140, 141)

        fun isBindableKey(keycode: Int): Boolean = keycode in BINDABLE_KEYS

        /**
         * 原版核心逻辑：每个物理键**固定专属一类设备**（绑定页里只能选该类型的
         * 设备，未绑定时按下也只在该类型里兜底）。138–141 是自定义/场景键，
         * 只能绑场景（scene/script）。
         */
        fun keyDeviceType(keycode: Int): String? = when (keycode) {
            134 -> PanelCardTypes.LIGHT
            135 -> PanelCardTypes.COVER
            136 -> PanelCardTypes.MEDIA_PLAYER
            137 -> PanelCardTypes.CLIMATE
            else -> null
        }

        /** 自定义/场景键（F8–F11）：只能绑场景或脚本。 */
        fun isSceneKey(keycode: Int): Boolean = keycode in 138..141

        /** 按键显示名（原版 shortcut_key_name_format："<设备类型>按键"）。 */
        fun keyDisplayName(keycode: Int): String = when (keycode) {
            134 -> "灯按键"
            135 -> "窗帘按键"
            136 -> "音乐按键"
            137 -> "空调按键"
            138 -> "自定义按键一"
            139 -> "自定义按键二"
            140 -> "自定义按键三"
            141 -> "自定义按键四"
            else -> "按键$keycode"
        }

        /** 绑定页里"设备"页签的标题（原版用键的设备类型名，如 灯具/窗帘）。 */
        fun keyDeviceTabLabel(keycode: Int): String = when (keycode) {
            134 -> "灯具"
            135 -> "窗帘"
            136 -> "音乐"
            137 -> "空调"
            else -> "设备"
        }
    }
}
