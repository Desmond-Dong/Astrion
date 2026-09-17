package com.example.astrion.esphome.mediaplayer

import com.example.esphomeproto.api.MediaPlayerState
import kotlinx.coroutines.flow.StateFlow

/**
 * Metadata for the currently (or most recently) played media item. Exposed to
 * Home Assistant through text sensors so media cards can display what is
 * playing.
 */
data class MediaMetadata(
    val title: String = "",
    val artist: String = "",
    val source: String = "",
)

interface MediaPlayer {
    /**
     * Called when audio focus is requested and all other playback should be ducked,
     * e.g. when microphone input is being captured and/or playback on this player is about to begin.
     * Implementations should not rely on this method being called before playback.
     */
    fun requestFocus()

    /**
     * The current state of media playback.
     */
    val state: StateFlow<MediaPlayerState>

    /**
     * Metadata of the current media item, or empty metadata when nothing has
     * been played yet. Used to expose media title/artist text sensors.
     */
    val metadata: StateFlow<MediaMetadata>

    /**
     * Starts playback of the specified media.
     * Convenience method for calling [play] for a single url.
     */
    fun play(mediaUrl: String, onCompletion: () -> Unit = {}) =
        play(listOf(mediaUrl), onCompletion)

    /**
     * Starts playback of the specified media.
     */
    fun play(mediaUris: Iterable<String>, onCompletion: () -> Unit = {})

    /**
     * Sets the paused state of media playback.
     */
    fun setPaused(paused: Boolean)

    /**
     * Stops media playback.
     */
    fun stop()

    /**
     * Gets or sets the playback volume.
     */
    var volume: Float

    /**
     * Gets or sets whether playback is muted.
     */
    var muted: Boolean
}