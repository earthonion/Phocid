@file:OptIn(UnstableApi::class)

package org.sunsetware.phocid.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.sunsetware.phocid.AUDIO_OFFLOADING_KEY
import org.sunsetware.phocid.AUDIO_SESSION_ID_KEY
import org.sunsetware.phocid.FILE_PATH_KEY
import org.sunsetware.phocid.PAUSE_ON_FOCUS_LOSS
import org.sunsetware.phocid.PLAY_ON_OUTPUT_DEVICE_CONNECTION_KEY
import org.sunsetware.phocid.PlaybackService
import org.sunsetware.phocid.RESHUFFLE_ON_REPEAT_KEY
import org.sunsetware.phocid.SET_PLAYBACK_PREFERENCE_COMMAND
import org.sunsetware.phocid.SET_TIMER_COMMAND
import org.sunsetware.phocid.TIMER_FINISH_LAST_TRACK_KEY
import org.sunsetware.phocid.TIMER_TARGET_KEY
import org.sunsetware.phocid.UNSHUFFLED_INDEX_KEY
import org.sunsetware.phocid.utils.Random
import org.sunsetware.phocid.utils.wrap

@Serializable
@Immutable
data class PlayerState(
    /** To restore the unshuffled play queue: `(0..<length).map { actualPlayQueue[it] }` */
    val unshuffledPlayQueueMapping: List<Int>? = null,
    val actualPlayQueue: List<Long> = emptyList(),
    val currentIndex: Int = 0,
    val currentPosition: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    val pitch: Float = 1f,
)

@Immutable data class PlayerTransientState(val version: Long = -1, val isPlaying: Boolean = false)

/** This method should work even if values of [UNSHUFFLED_INDEX_KEY] are discontinuous. */
fun Player.capturePlayerState(): PlayerState {
    val mediaItems = (0..<mediaItemCount).map { getMediaItemAt(it) }
    fun getUnshuffledPlayQueueMapping(): List<Int> {
        return mediaItems
            .mapIndexedNotNull { index, mediaItem ->
                mediaItem.getUnshuffledIndex()?.let { Pair(index, it) }
            }
            .sortedBy { it.second }
            .map { it.first }
    }
    val actualPlayQueue = mediaItems.map { it.mediaId.toLong() }
    return PlayerState(
        if (shuffleModeEnabled) getUnshuffledPlayQueueMapping() else null,
        actualPlayQueue,
        currentMediaItemIndex,
        if (isPlaying) 0 else currentPosition,
        shuffleModeEnabled,
        repeatMode,
        playbackParameters.speed,
        playbackParameters.pitch,
    )
}

fun Player.restorePlayerState(state: PlayerState, unfilteredTrackIndex: UnfilteredTrackIndex) {
    // Shuffle must be set before items or items will be shuffled again
    shuffleModeEnabled = state.shuffle
    setMediaItems(
        state.actualPlayQueue.mapIndexedNotNull { index, id ->
            unfilteredTrackIndex.tracks[id]?.getMediaItem(
                state.unshuffledPlayQueueMapping?.indexOf(index)
            )
        }
    )
    seekTo(state.currentIndex, state.currentPosition)
    repeatMode = state.repeat
    playbackParameters = PlaybackParameters(state.speed, state.pitch)
}

private fun MediaController.captureTransientState(version: Long): PlayerTransientState {
    return PlayerTransientState(version, playbackState == Player.STATE_READY && playWhenReady)
}

@Stable
class PlayerManager : AutoCloseable {
    private val _state = MutableStateFlow(PlayerState())
    val state = _state.asStateFlow()

    private val _transientState = MutableStateFlow(PlayerTransientState())
    val transientState = _transientState.asStateFlow()

    private lateinit var mediaController: MediaController
    private val transientStateVersion = AtomicLong(0)

    val currentPosition: Long
        get() = mediaController.currentPosition

    private var playbackPreferenceJob = null as Job?

    override fun close() {
        playbackPreferenceJob?.cancel()
        mediaController.release()
    }

    suspend fun initialize(
        context: Context,
        coroutineScope: CoroutineScope,
        preferences: StateFlow<Preferences>,
    ) {
        val sessionToken =
            SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        val completed = AtomicBoolean(false)
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
                mediaController.prepare()

                updateState()

                val listener =
                    object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) {
                            updateState()
                        }
                    }
                mediaController.addListener(listener)

                completed.set(true)
            },
            ContextCompat.getMainExecutor(context),
        )

        while (!completed.get()) {
            delay(1)
        }

        playbackPreferenceJob =
            coroutineScope.launch {
                preferences
                    .onEach {
                        mediaController.sendCustomCommand(
                            SessionCommand(SET_PLAYBACK_PREFERENCE_COMMAND, Bundle.EMPTY),
                            bundleOf(
                                Pair(
                                    PLAY_ON_OUTPUT_DEVICE_CONNECTION_KEY,
                                    it.playOnOutputDeviceConnection,
                                ),
                                Pair(PAUSE_ON_FOCUS_LOSS, it.pauseOnFocusLoss),
                                Pair(AUDIO_OFFLOADING_KEY, it.audioOffloading),
                                Pair(RESHUFFLE_ON_REPEAT_KEY, it.reshuffleOnRepeat),
                            ),
                        )
                    }
                    .collect()
            }
    }

    private fun updateState() {
        _state.update { mediaController.capturePlayerState() }
        _transientState.update {
            mediaController.captureTransientState(transientStateVersion.getAndIncrement())
        }
    }

    fun seekToPrevious() {
        val currentIndex = mediaController.currentMediaItemIndex
        val previousIndex =
            (currentIndex - 1).wrap(
                mediaController.mediaItemCount,
                mediaController.repeatMode != Player.REPEAT_MODE_OFF,
            ) ?: currentIndex
        mediaController.seekTo(previousIndex, 0)
        // Force a state emission for UI recomposition.
        updateState()
        mediaController.play()
    }

    fun seekToPreviousSmart() {
        val currentIndex = mediaController.currentMediaItemIndex
        val previousIndex =
            (currentIndex - 1)
                .wrap(
                    mediaController.mediaItemCount,
                    mediaController.repeatMode != Player.REPEAT_MODE_OFF,
                )
                .takeIf {
                    mediaController.currentPosition <= mediaController.maxSeekToPreviousPosition
                } ?: currentIndex
        mediaController.seekTo(previousIndex, 0)
        // Force a state emission for UI recomposition.
        updateState()
        mediaController.play()
    }

    fun seekToNext() {
        val currentIndex = mediaController.currentMediaItemIndex
        val nextIndex =
            (currentIndex + 1).wrap(
                mediaController.mediaItemCount,
                mediaController.repeatMode != Player.REPEAT_MODE_OFF,
            ) ?: currentIndex
        mediaController.seekTo(nextIndex, 0)
        // Force a state emission for UI recomposition.
        updateState()
        mediaController.play()
    }

    fun seekTo(index: Int) {
        mediaController.seekTo(index, 0)
        mediaController.play()
    }

    fun seekToFraction(fraction: Float) {
        val duration = mediaController.duration
        mediaController.seekTo((duration * fraction).toLong().coerceIn(0, duration))
    }

    fun togglePlay() {
        if (mediaController.isPlaying) {
            mediaController.pause()
        } else {
            play()
        }
    }

    fun play() {
        if (
            mediaController.currentPosition >= mediaController.duration - 1 &&
                !mediaController.hasNextMediaItem() &&
                !mediaController.isPlaying
        ) {
            // Media3 might instantly pause instead of starting from the beginning if these
            // conditions are met
            mediaController.seekTo(0)
        }
        mediaController.play()
    }

    fun setTracks(tracks: List<Track>, index: Int?) {
        val seekIndex: Int
        if (!_state.value.shuffle) {
            mediaController.setMediaItems(tracks.map { it.getMediaItem(null) })
            seekIndex = index ?: 0
        } else {
            val shuffledIndices =
                if (index != null) {
                    listOf(index) + tracks.indices.filter { it != index }.shuffled(Random)
                } else {
                    tracks.indices.shuffled(Random)
                }
            mediaController.setMediaItems(shuffledIndices.map { i -> tracks[i].getMediaItem(i) })
            seekIndex = 0
        }
        mediaController.seekTo(seekIndex, 0)
        mediaController.play()
    }

    fun addTracks(tracks: List<Track>) {
        val firstIndex = _state.value.actualPlayQueue.size
        mediaController.addMediaItems(
            tracks.mapIndexed { i, track ->
                track.getMediaItem(if (!_state.value.shuffle) null else firstIndex + i)
            }
        )
    }

    fun playNext(tracks: List<Track>) {
        if (!_state.value.shuffle) {
            mediaController.addMediaItems(
                if (_state.value.actualPlayQueue.isNotEmpty()) {
                    _state.value.currentIndex + 1
                } else {
                    0
                },
                tracks.map { it.getMediaItem(null) },
            )
        } else {
            if (_state.value.actualPlayQueue.isNotEmpty()) {
                val mediaItems =
                    (0..<mediaController.mediaItemCount).map { mediaController.getMediaItemAt(it) }
                val currentIndex = mediaController.currentMediaItemIndex
                val currentUnshuffledIndex = mediaItems[currentIndex].getUnshuffledIndex()!!
                val offsetOriginal =
                    mediaItems.map {
                        it.setUnshuffledIndex(
                            it.getUnshuffledIndex()!!.let {
                                if (it > currentUnshuffledIndex) it + tracks.size else it
                            }
                        )
                    }
                val new =
                    tracks.mapIndexed { i, track ->
                        track.getMediaItem(currentUnshuffledIndex + 1 + i)
                    }
                mediaController.replaceMediaItems(
                    currentIndex + 1,
                    Int.MAX_VALUE,
                    new + offsetOriginal.drop(currentIndex + 1),
                )
                mediaController.replaceMediaItem(currentIndex, offsetOriginal[currentIndex])
                mediaController.replaceMediaItems(
                    0,
                    currentIndex,
                    offsetOriginal.take(currentIndex),
                )
            } else {
                mediaController.addMediaItems(
                    tracks.mapIndexed { i, track -> track.getMediaItem(i) }
                )
            }
        }
    }

    fun moveTrack(from: Int, to: Int) {
        mediaController.moveMediaItem(from, to)
    }

    fun removeTrack(index: Int) {
        // [capturePlayerState] should take care of discontinuous [UNSHUFFLED_INDEX_KEY].
        mediaController.removeMediaItem(index)
    }

    fun clearTracks() {
        mediaController.clearMediaItems()
    }

    fun toggleShuffle() {
        mediaController.shuffleModeEnabled = !mediaController.shuffleModeEnabled
    }

    fun enableShuffle() {
        mediaController.shuffleModeEnabled = true
    }

    fun toggleRepeat() {
        mediaController.repeatMode =
            when (mediaController.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
    }

    fun getTimerState(): Pair<Long, Boolean>? {
        return mediaController.sessionExtras
            .getLong(TIMER_TARGET_KEY, -1)
            .takeIf { it >= 0 }
            ?.let {
                Pair(
                    it,
                    mediaController.sessionExtras.getBoolean(TIMER_FINISH_LAST_TRACK_KEY, true),
                )
            }
    }

    fun setTimer(settings: PlayerTimerSettings) {
        mediaController.sendCustomCommand(
            SessionCommand(SET_TIMER_COMMAND, Bundle.EMPTY),
            bundleOf(
                Pair(
                    TIMER_TARGET_KEY,
                    SystemClock.elapsedRealtime() + settings.duration.inWholeMilliseconds,
                ),
                Pair(TIMER_FINISH_LAST_TRACK_KEY, settings.finishLastTrack),
            ),
        )
    }

    fun cancelTimer() {
        mediaController.sendCustomCommand(
            SessionCommand(SET_TIMER_COMMAND, Bundle.EMPTY),
            bundleOf(Pair(TIMER_TARGET_KEY, -1)),
        )
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        mediaController.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun openSystemEqualizer(context: Context): Boolean {
        val sessionId = mediaController.sessionExtras.getInt(AUDIO_SESSION_ID_KEY)
        return try {
            context.startActivity(
                Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                }
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun Track.getMediaItem(unshuffledIndex: Int?): MediaItem {
    val unshuffledMediaItem =
        MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setArtist(displayArtist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(albumArtist)
                    .setArtworkUri(uri)
                    .setExtras(bundleOf(FILE_PATH_KEY to path))
                    .build()
            )
            .build()

    return if (unshuffledIndex == null) unshuffledMediaItem
    else unshuffledMediaItem.setUnshuffledIndex(unshuffledIndex)
}

fun MediaItem.getUnshuffledIndex(): Int? {
    return mediaMetadata.extras?.getInt(UNSHUFFLED_INDEX_KEY, -1)?.takeIf { it >= 0 }
}

fun MediaItem.setUnshuffledIndex(unshuffledIndex: Int?): MediaItem {
    return buildUpon()
        .setMediaMetadata(
            mediaMetadata
                .buildUpon()
                .setExtras(
                    if (unshuffledIndex == null) bundleOf()
                    else bundleOf(Pair(UNSHUFFLED_INDEX_KEY, unshuffledIndex))
                )
                .build()
        )
        .build()
}

@Immutable
@Serializable
data class PlayerTimerSettings(
    val duration: Duration = 10.minutes,
    val finishLastTrack: Boolean = true,
)
