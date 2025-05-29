@file:OptIn(UnstableApi::class)

package org.sunsetware.phocid.data

import androidx.annotation.OptIn
import androidx.compose.runtime.Immutable
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import org.sunsetware.phocid.FILE_PATH_KEY
import org.sunsetware.phocid.UNSHUFFLED_INDEX_KEY

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

fun Track.getMediaItem(unshuffledIndex: Int?): MediaItem {
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
