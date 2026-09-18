package com.example.astrion.panel

import android.content.Context
import android.media.AudioManager
import com.example.astrion.services.ActivityNavigator
import com.example.astrion.services.HaActionBus
import com.example.astrion.services.SatelliteStateHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** A physical key press reported by the activity. */
private const val KEY_BACK = 4
private const val KEY_HOME_PANEL = 164
private const val KEY_VOLUME_UP = 24
private const val KEY_VOLUME_DOWN = 25

data class KeyPress(
    val keyCode: Int,
    val longPress: Boolean,
    /** Set when the long press was detected by the activity timer. */
    val cancel: Boolean = false,
)

/**
 * Raw physical key events from [com.example.astrion.MainActivity]. Screens never
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
    private val keyBindingExecutor: KeyBindingExecutor,
    private val eventHub: PanelEventHub,
    private val panelUiEvents: PanelUiEvents,
    @ApplicationContext private val context: Context
) {
    // 原版 BaseActivity：页面进入注册按键监听、离开注销，栈顶优先。
    // 用栈而不是单个引用，叠加打开多个设备页时返回后上一页按键不丢。
    private val handlers = mutableListOf<(suspend (KeyPress) -> Boolean)>()

    fun pushHandler(h: (suspend (KeyPress) -> Boolean)) {
        handlers.remove(h)
        handlers.add(h)
    }

    fun popHandler(h: (suspend (KeyPress) -> Boolean)) {
        handlers.remove(h)
    }

    private fun topHandler(): (suspend (KeyPress) -> Boolean)? = handlers.lastOrNull()

    /** @return true when the key was consumed. */
    suspend fun dispatch(press: KeyPress): Boolean {
        if (press.cancel) {
            // 长按松开：交给顶层页面停止自动重复步进；不被任何绑定消费
            topHandler()?.invoke(press)
            return true
        }
        // Every physical key is reported to Home Assistant so automations can
        // see (and bind) any key, even when the panel itself ignores it.
        eventHub.announceKeyPressed(press.keyCode, press.longPress)
        // 返回键：离开设备详情页回到上一级；首页键：回到应用首页
        if (press.keyCode == KEY_BACK) {
            if (handlers.isNotEmpty()) panelUiEvents.requestBack()
            return true
        }
        if (press.keyCode == KEY_HOME_PANEL) {
            panelUiEvents.requestGoHome()
            return true
        }
        // 164=静音（原版 BaseActivity 语义）：透传给设备页（媒体页 toggleMute、
        // TV 页 toggleMute），非设备页落到绑定系统

        if (topHandler()?.invoke(press) == true) return true
        if (keyBindingExecutor.handle(press)) return true
        // 原版 BaseActivity 语义：未绑定的音量键直接调媒体音量（外放），
        // 任何页面都可用 - 正常遥控器的使用习惯。
        if (!press.longPress && (press.keyCode == KEY_VOLUME_UP || press.keyCode == KEY_VOLUME_DOWN)) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (press.keyCode == KEY_VOLUME_UP) android.media.AudioManager.ADJUST_RAISE
                else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            return true
        }
        return false
    }

}

/**
 * Executes the on-device key bindings (original 快捷键 system). 解析顺序
 * （原版 ShortcutKeyEventHandler）：可绑定键长按进绑定页 → 面板本地绑定
 * （一键快速进入）→ 内置默认（F4–F11 首张匹配卡片 / 语音键 / 回首页）。
 */
@Singleton
class KeyBindingExecutor @Inject constructor(
    private val panelConfigStore: PanelConfigStore,
    private val activityNavigator: ActivityNavigator,
    private val haActionBus: HaActionBus,
    private val panelUiEvents: PanelUiEvents,
    private val satelliteStateHolder: SatelliteStateHolder,
    private val haStatesStore: com.example.astrion.services.HomeAssistantStatesStore,
    private val shortcutBindingStore: ShortcutBindingStore,
) {
    /** @return true when a binding matched and was executed. */
    suspend fun handle(press: KeyPress): Boolean {
        if (press.cancel) return false
        // 1. 原版 HA100：长按可绑定键（F4–F11）直接打开该键的绑定页；
        //    设备页消费掉的键不会走到这里。
        if (press.longPress && ShortcutBindingStore.isBindableKey(press.keyCode)) {
            Timber.d("长按可绑定键 ${press.keyCode}，打开绑定页")
            panelUiEvents.tryOpenKeyBinding(press.keyCode)
            return true
        }
        // 2. 面板本地绑定（设备上一键快速进入）
        shortcutBindingStore.get(press.keyCode)?.let { return executeLocal(it) }
        // 3. 内置默认：F4–F11 打开第一张匹配卡片；仍无 → 未绑定短按进绑定页
        val defaults = defaultBindings()
        val default = defaults.firstOrNull {
            it.keycode == press.keyCode && it.longPress == press.longPress
        }
        if (default != null) return execute(default)
        if (ShortcutBindingStore.isBindableKey(press.keyCode)) {
            panelUiEvents.tryOpenKeyBinding(press.keyCode)
            return true
        }
        return false
    }

    private suspend fun execute(binding: KeyBinding): Boolean {
        Timber.d("Key binding fired: keycode=${binding.keycode} long=${binding.longPress} action=${binding.action}")
        when (binding.action) {
            KeyBindingActions.HOME -> {
                val firstRoom = panelConfigStore.effectiveLayout.first()
                    .rooms
                    .firstOrNull()?.title.orEmpty()
                activityNavigator.setPage(firstRoom)
            }

            KeyBindingActions.VOICE -> satelliteStateHolder.voiceAssistant?.wakeAssistant()
            KeyBindingActions.CARD -> panelUiEvents.tryOpenCard(binding.target)

            else -> {
                Timber.w("Unknown key binding action: ${binding.action}")
                return false
            }
        }
        return true
    }

    /**
     * 执行面板本地绑定（原版 processBinding）：开关/灯/风扇 → 按当前状态切换；
     * 场景/脚本 → 执行；其余设备类型 → 打开设备详情页（一键快速进入）；
     * 房间 → 跳房间页。
     */
    private suspend fun executeLocal(binding: ShortcutBinding): Boolean {
        if (binding.type == "DeviceRoom") {
            activityNavigator.setPage(binding.uuid)
            return true
        }
        val card = panelConfigStore.effectiveLayout.first()
            .rooms
            .flatMap { it.cards }
            .firstOrNull { it.cardId == binding.uuid }
            ?: run {
                Timber.w("快捷键绑定的卡片不存在: ${binding.uuid}")
                return false
            }
        val entityId = card.primaryEntity?.entityId
        when (card.resolvedType) {
            PanelCardTypes.SCENE -> {
                if (entityId != null) {
                    haActionBus.callService(
                        if (entityId.startsWith("script.")) "script.turn_on" else "scene.turn_on",
                        mapOf("entity_id" to entityId)
                    )
                }
            }

            PanelCardTypes.SWITCH, PanelCardTypes.LIGHT, PanelCardTypes.FAN -> {
                if (entityId != null) {
                    val domain = entityId.substringBefore('.')
                    haActionBus.callService(
                        defaultToggleService(entityId, domain),
                        mapOf("entity_id" to entityId)
                    )
                }
            }

            else -> {
                // 原版快捷键语义：所有绑定都是快捷操作，不再打开操控页
                if (entityId != null) {
                    val domain = entityId.substringBefore('.')
                    haActionBus.callService(
                        quickActionService(card.resolvedType, entityId, domain),
                        mapOf("entity_id" to entityId)
                    )
                } else {
                    panelUiEvents.tryOpenCard(card.cardId)
                }
            }
        }
        return true
    }

    /** 绑定键的快捷动作：按设备类型与当前状态决定执行的服务。 */
    private suspend fun quickActionService(type: String, entityId: String, domain: String): String {
        val state = haStatesStore.states.value[entityId]?.state
        return when (type) {
            PanelCardTypes.MEDIA_PLAYER ->
                if (state == "playing") "media_player.media_pause" else "media_player.media_play"

            PanelCardTypes.COVER ->
                if (state == "open") "cover.close_cover" else "cover.open_cover"

            PanelCardTypes.CLIMATE ->
                if (state != "off" && state != "unavailable") "climate.turn_off"
                else "climate.turn_on"

            else -> defaultToggleService(entityId, domain)
        }
    }

    /** Toggles on/off domains based on the imported HA state. */
    private suspend fun defaultToggleService(entityId: String, domain: String): String {
        val state = haStatesStore.states.value[entityId]?.state
        val turnOff = state == "on"
        return "${domain}.${if (turnOff) "turn_off" else "turn_on"}"
    }

    /**
     * Fallback defaults used before any bindings are pushed: the dedicated
     * hardware keys open the first matching card in the current layout
     * (原版 HA100A 键位：134=灯 135=窗帘 136=音乐 137=空调).
     */
    private suspend fun defaultBindings(): List<KeyBinding> {
        val layout = panelConfigStore.effectiveLayout.first()
        val cards = layout.rooms.flatMap { it.cards }

        val defaults = listOf(
            134 to PanelCardTypes.LIGHT,
            135 to PanelCardTypes.COVER,
            136 to PanelCardTypes.MEDIA_PLAYER,
            137 to PanelCardTypes.CLIMATE
        )
        val result = mutableListOf(
            KeyBinding(133, false, KeyBindingActions.VOICE),
            KeyBinding(132, false, KeyBindingActions.HOME)
        )
        defaults.forEach { (keycode, type) ->
            cards.firstOrNull { it.resolvedType == type }?.let { card ->
                result.add(KeyBinding(keycode, false, KeyBindingActions.CARD, card.cardId))
            }
        }
        return result
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

    private val _navigateBack = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val navigateBack: SharedFlow<Unit> = _navigateBack.asSharedFlow()

    private val _goHome = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val goHome: SharedFlow<Unit> = _goHome.asSharedFlow()

    // 原版长按/未绑定短按 → ShortCutKeyBindActivity：进入指定键的绑定页
    private val _openShortcutBind = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val openShortcutBind: SharedFlow<Int> = _openShortcutBind.asSharedFlow()

    fun tryOpenKeyBinding(keyCode: Int) {
        _openShortcutBind.tryEmit(keyCode)
    }

    fun tryOpenCard(cardId: String) {
        _openCard.tryEmit(cardId)
    }

    /** Physical BACK: leave the device detail page. */
    fun requestBack() {
        _navigateBack.tryEmit(Unit)
    }

    /** Physical HOME key: pop everything back to the panel home. */
    fun requestGoHome() {
        _goHome.tryEmit(Unit)
    }
}
