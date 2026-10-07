package com.example.astrion.esphome.android.wakeword

import com.example.astrion.esphome.wakeword.WakeWord
import com.example.astrion.wakewords.microwakeword.MicroWakeWordDetector
import com.example.astrion.wakewords.microwakeword.MicroWakeWordModel
import com.example.astrion.wakewords.models.WakeWordWithId
import timber.log.Timber
import java.nio.ByteBuffer

class MicroWakeWord() : WakeWord {
    private var detector: MicroWakeWordDetector? = null
    private val models = mutableListOf<MicroWakeWordModel>()

    /** HA-configured sensitivity override; `null` uses the model defaults. */
    private var sensitivity: Float? = null

    override suspend fun setWakeWords(wakeWords: List<WakeWordWithId>) {
        clearWakeWords()
        Timber.d("Setting wake words ${wakeWords.map { it.id }}")
        val loaded = wakeWords.mapNotNull { it.toMicroWakeWordOrNull() }
        models.addAll(loaded)
        applySensitivity()
        detector = MicroWakeWordDetector(loaded)
    }

    override fun clearWakeWords() {
        detector?.let {
            it.close()
            detector = null
            models.clear()
            Timber.d("Cleared wake words")
        }
    }

    override fun detect(audio: ByteBuffer) =
        detector?.detect(audio)?.map { it.id }?.toList() ?: emptyList()

    override fun close() {
        clearWakeWords()
    }

    /**
     * Runtime-adjustable wake word sensitivity, mapped onto the model
     * probability cutoffs: cutoff = 1 - sensitivity (higher sensitivity is a
     * lower threshold, so more wake phrases are accepted). `null` restores
     * each model's own default cutoff.
     */
    fun setSensitivity(sensitivity: Float?) {
        this.sensitivity = sensitivity
        applySensitivity()
    }

    private fun applySensitivity() {
        models.forEach { model ->
            model.probabilityCutoff = sensitivity
                ?.let { 1f - it.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY) }
                ?: model.defaultProbabilityCutoff
        }
    }

    private suspend fun WakeWordWithId.toMicroWakeWordOrNull() = runCatching {
        MicroWakeWordModel(
            id = id,
            model = load(),
            initialProbabilityCutoff = wakeWord.micro.probability_cutoff,
            slidingWindowSize = wakeWord.micro.sliding_window_size
        )
    }.onFailure {
        Timber.e(it, "Error loading wake word: $id")
    }.getOrNull()

    private companion object {
        const val MIN_SENSITIVITY = 0.01f
        const val MAX_SENSITIVITY = 0.5f
    }
}
