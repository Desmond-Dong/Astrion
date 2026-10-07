package com.example.astrion.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 语音会话状态（下拉快捷面板/浮层展示用）。 */
sealed interface VoiceState {
    /** 空闲（唤醒词监听中或静音）。 */
    data object Idle : VoiceState

    /** 正在收音（用户说话中，等 HA 侧 VAD 断句）。 */
    data object Listening : VoiceState

    /** 已断句，HA 识别/执行中。 */
    data object Thinking : VoiceState

    /** TTS 回复外放中。 */
    data object Speaking : VoiceState

    data class Error(val message: String) : VoiceState
}

/**
 * 语音核心：HA Assist Pipeline over WebSocket（原版 HAPipelinesClient 方案）。
 *
 * - **按键说话**：麦克风键（133）单击开始收音，说完 HA 侧 VAD 自动断句，
 *   识别+意图+TTS 回复外放，全程无需按住；
 * - **唤醒词直唤醒**：本地 microwakeword 常驻监听（microfeatures），命中即进入
 *   同一条管线；灵敏度/模型/静音可调（[VoiceSettingsStore]）。
 *
 * 协议（用户 HA 2026.9.4 实测）：
 * 1. `assist_pipeline/run {start_stage:"stt", end_stage:"intent",
 *    input:{sample_rate:16000, timeout:15}}`；
 * 2. `run-start` 事件携带 `runner_data.stt_binary_handler_id`；
 * 3. 音频上行：二进制帧 `[handler_id][PCM 16k/16bit/mono]`，结束帧 `[handler_id]`；
 * 4. TTS 回包：二进制帧首字节 0x01 + mp3，仅 0xFE = 播放数据结束。
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: com.example.astrion.ha.HaPanelBridge,
    private val settingsStore: VoiceSettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_FRAMES = 512 // 32ms/帧
        private const val RUN_TIMEOUT_SECONDS = 15
        private const val TTS_PREFIX_DATA = 0x01
        private const val TTS_PREFIX_END = 0xFE
    }

    sealed class Mode { data object Idle : Mode(); data object Wake : Mode(); data object Stream : Mode() }

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 唤醒监听运行中（服务启动且未静音）。 */
    private val _wakeRunning = MutableStateFlow(false)
    val wakeRunning: StateFlow<Boolean> = _wakeRunning.asStateFlow()

    private val started = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var wakeWordEngine: com.example.astrion.esphome.android.wakeword.MicroWakeWord? = null
    private var handlerId: Int? = null
    private var ttsBuffer = java.io.ByteArrayOutputStream()
    private var sessionJobs = mutableListOf<Job>()
    private val mediaPlayer = MediaPlayer()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── 入口 ────────────────────────────────────────────────────────────

    /** 前台服务启动时调用：开启唤醒词常驻监听。 */
    fun startWakeLoop() {
        if (started.getAndSet(true)) return
        scope.launch {
            // 音量拾取期间走音乐流之外的通话路由由麦克风源决定（VOICE_RECOGNITION）
            settingsStore.wakeSensitivity.collect { sensitivity ->
                wakeWordEngine?.setSensitivity(sensitivity)
            }
        }
        scope.launch {
            settingsStore.wakeWordId.collect { reloadWakeWord(it) }
        }
        openCaptureAndRun()
    }

    /** 麦克风键：空闲→开始会话；会话中→打断。 */
    fun toggleFromMicKey() {
        when (_state.value) {
            is VoiceState.Idle -> startSession()
            else -> abort("已打断")
        }
    }

    // ── 会话 ────────────────────────────────────────────────────────────

    fun startSession() {
        if (_state.value !is VoiceState.Idle) return
        if (bridge.state.value != com.example.astrion.ha.HaConnectionState.Connected) {
            _state.value = VoiceState.Error("HA 未连接")
            scope.launch { delay(2000); if (_state.value is VoiceState.Error) _state.value = VoiceState.Idle }
            return
        }
        _state.value = VoiceState.Listening
        ttsBuffer.reset()
        sessionJobs.clear()

        // 1. 启动管线（STT → 意图）
        bridge.sendAssistCommand(buildJsonObject {
            put("type", "assist_pipeline/run")
            put("start_stage", "stt")
            put("end_stage", "intent")
            put("input", buildJsonObject {
                put("sample_rate", SAMPLE_RATE)
                put("timeout", RUN_TIMEOUT_SECONDS)
            })
        })

        // 2. 消费管线事件
        sessionJobs += scope.launch {
            bridge.assistEvents.collect { event ->
                when (event.type) {
                    "run-start" -> {
                        handlerId = (event.data["runner_data"] as? kotlinx.serialization.json.JsonObject)
                            ?.get("stt_binary_handler_id")?.let {
                                it.jsonPrimitive.contentOrNull?.toIntOrNull()
                            }
                        Timber.i("Assist run started, handler=%s", handlerId)
                    }

                    // VAD 断句 / STT 开始：停止上行，发结束帧
                    "vad-end", "stt-start" -> {
                        if (_state.value is VoiceState.Listening) {
                            _state.value = VoiceState.Thinking
                            handlerId?.let { bridge.sendAssistBinary(byteArrayOf(it.toByte())) }
                        }
                    }

                    "stt-end" -> _state.value = VoiceState.Thinking

                    "tts-start" -> _state.value = VoiceState.Speaking

                    "intent-end" -> {
                        // 意图响应文本（如需对话 UI 可从 speech.plain.speech 取）
                    }

                    "error" -> {
                        val code = event.data["code"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                        Timber.w("Assist error: %s", code)
                        if (_state.value !is VoiceState.Speaking) {
                            _state.value = VoiceState.Error("未识别到语音")
                        }
                    }

                    "run-end" -> finishSession()
                }
            }
        }

        // 3. 消费 TTS 二进制回包
        sessionJobs += scope.launch {
            bridge.binary.collect { frame ->
                when {
                    frame.size == 1 && frame[0].toInt() == TTS_PREFIX_END -> playTts()
                    frame.isNotEmpty() && frame[0].toInt() == TTS_PREFIX_DATA ->
                        ttsBuffer.write(frame, 1, frame.size - 1)
                }
            }
        }

        // 4. 整体超时保护
        sessionJobs += scope.launch {
            delay((RUN_TIMEOUT_SECONDS + 10) * 1000L)
            if (_state.value !is VoiceState.Idle) finishSession()
        }

        // 5. 切到音频上行模式
        mode = Mode.Stream
    }

    fun abort(message: String) {
        handlerId?.let { bridge.sendAssistBinary(byteArrayOf(it.toByte())) }
        finishSession()
        _state.value = VoiceState.Error(message)
        scope.launch { delay(1500); if (_state.value is VoiceState.Error) _state.value = VoiceState.Idle }
    }

    private fun finishSession() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        handlerId = null
        mode = Mode.Wake
        _state.value = VoiceState.Idle
        val bytes = ttsBuffer.toByteArray()
        ttsBuffer.reset()
        if (bytes.isNotEmpty() && _state.value !is VoiceState.Error) playBytes(bytes)
    }

    // ── TTS 播放 ────────────────────────────────────────────────────────

    private fun playTts() {
        val bytes = ttsBuffer.toByteArray()
        ttsBuffer.reset()
        if (bytes.isNotEmpty()) playBytes(bytes)
    }

    private fun playBytes(mp3: ByteArray) {
        try {
            val tmp = java.io.File(context.cacheDir, "assist_tts.mp3")
            tmp.writeBytes(mp3)
            mediaPlayer.reset()
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC) // 语音一律外放
            mediaPlayer.setDataSource(tmp.absolutePath)
            mediaPlayer.prepare()
            mediaPlayer.start()
            scope.launch {
                while (mediaPlayer.isPlaying) delay(200)
                _state.value = VoiceState.Idle
            }
        } catch (e: Exception) {
            Timber.e(e, "TTS playback failed")
            _state.value = VoiceState.Idle
        }
    }

    // ── 音频采集循环（唤醒监听 / 会话上行共用一条 AudioRecord） ────────────

    @Volatile
    private var mode: Mode = Mode.Wake

    private fun openCaptureAndRun() {
        captureJob?.cancel()
        captureJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, FORMAT,
                maxOf(minBuf, READ_FRAMES * 2 * 8)
            )
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecord init failed (permission?)")
                _state.value = VoiceState.Error("麦克风不可用")
                return@launch
            }
            record.startRecording()
            _wakeRunning.value = true
            val pcm = ShortArray(READ_FRAMES)
            while (isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                when (mode) {
                    Mode.Stream -> {
                        val handler = handlerId ?: continue
                        val bytes = ByteArray(n * 2)
                        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(pcm, 0, n)
                        bridge.sendAssistBinary(byteArrayOf(handler.toByte()) + bytes)
                    }

                    Mode.Wake -> {
                        if (settingsStore.micMuted.get()) continue
                        val byteBuf = ByteArray(n * 2)
                        ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().put(pcm, 0, n)
                        val detected = wakeWordEngine?.detect(ByteBuffer.wrap(byteBuf)).orEmpty()
                        if (detected.isNotEmpty() && _state.value is VoiceState.Idle) {
                            Timber.i("Wake word detected: %s", detected)
                            startSession()
                        }
                    }

                    Mode.Idle -> delay(100)
                }
            }
            record.stop()
            record.release()
            _wakeRunning.value = false
        }
    }

    private fun reloadWakeWord(modelId: String) {
        try {
            val engine = com.example.astrion.esphome.android.wakeword.MicroWakeWord()
            scope.launch {
                engine.setWakeWords(listOf(wakeWordWithId(modelId)))
                engine.setSensitivity(settingsStore.wakeSensitivity.get())
                wakeWordEngine?.close()
                wakeWordEngine = engine
                Timber.i("Wake word loaded: %s", modelId)
            }
        } catch (e: Exception) {
            Timber.e(e, "Wake word load failed")
        }
    }

    private suspend fun wakeWordWithId(id: String): com.example.astrion.wakewords.models.WakeWordWithId {
        val provider = com.example.astrion.wakewords.providers.AssetWakeWordProvider(context.assets)
        val all = provider.get()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setMicMuted(muted: Boolean) {
        scope.launch { settingsStore.micMuted.set(muted) }
    }
}
