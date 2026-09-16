package com.example.ava.wakewords.providers

import android.content.res.AssetManager
import com.example.ava.wakewords.models.WakeWord
import com.example.ava.wakewords.models.WakeWordWithId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import timber.log.Timber
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.pathString

private const val EXTENSION_JSON = "json"

@OptIn(ExperimentalSerializationApi::class)
class AssetWakeWordProvider(
    private val assets: AssetManager,
    private val path: String = DEFAULT_WAKE_WORD_PATH,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : WakeWordProvider {
    override suspend fun get(): List<WakeWordWithId> = withContext(dispatcher) {
        val assetsList = assets.list(path)?.map { Path(path, it) } ?: return@withContext emptyList()
        val wakeWords = buildList {
            for (asset in assetsList.filter { it.extension == EXTENSION_JSON }) {
                runCatching {
                    val wakeWord = assets.open(asset.pathString).use {
                        Json.decodeFromStream<WakeWord>(it)
                    }
                    val id = asset.nameWithoutExtension
                    val modelPath = Path(path, wakeWord.model)
                    add(WakeWordWithId(id, wakeWord) {
                        loadModel(modelPath.pathString)
                    })
                }.onFailure {
                    Timber.e(it, "Error loading wake word: $asset")
                }
            }
        }
        return@withContext wakeWords
    }

    /**
     * Loads a TFLite model from the assets directory into a MappedByteBuffer.
     * @param modelPath The absolute path to the model file in the assets directory.
     */
    private suspend fun loadModel(modelPath: String): ByteBuffer = withContext(dispatcher) {
        val modelFileDescriptor = assets.openFd(modelPath)
        modelFileDescriptor.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                val fileChannel = stream.channel
                val startOffset: Long = modelFileDescriptor.startOffset
                val declaredLength: Long = modelFileDescriptor.declaredLength
                return@use fileChannel.map(
                    FileChannel.MapMode.READ_ONLY,
                    startOffset,
                    declaredLength
                )
            }
        }
    }

    companion object {
        const val DEFAULT_WAKE_WORD_PATH = "wakeWords"
    }
}