package org.sunsetware.phocid

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sunsetware.phocid.data.capturePlayerState
import org.sunsetware.phocid.data.restorePlayerState
import org.sunsetware.phocid.globals.GlobalData
import org.sunsetware.phocid.service.CustomizedBitmapLoader
import org.sunsetware.phocid.service.CustomizedPlayer
import org.sunsetware.phocid.utils.Random

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val mainScope = MainScope()
    private val timerMutex = Mutex()
    @Volatile private var timerTarget = -1L
    @Volatile private var timerJob = null as Job?
    @Volatile private var timerFinishLastTrack = true
    @Volatile private var playOnOutputDeviceConnection = false
    @Volatile private var audioOffloading = true
    @Volatile private var lastIndex = null as Int?
    @Volatile private var reshuffleOnRepeat = false

    override fun onCreate() {
        super.onCreate()
        while (!GlobalData.initialized.get()) {
            Thread.sleep(1)
        }

        val player = CustomizedPlayer(this)

        // Integrate with system equalizer.
        sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
                putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.inner.audioSessionId)
                putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
            }
        )
        player.addListener(createListener(player))

        // Register listener for auto playback on device connection.
        // Must register before restoring the state, or it'll unexpectedly start playback
        // on app startup.
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(audioDeviceCallback, null)

        // Restore state.
        player.restorePlayerState(
            GlobalData.playerState.value,
            GlobalData.unfilteredTrackIndex.value,
        )
        mainScope.launch {
            GlobalData.preferences
                .onEach { preferences ->
                    playOnOutputDeviceConnection = preferences.playOnOutputDeviceConnection
                    player.setAudioAttributes(player.audioAttributes, preferences.pauseOnFocusLoss)
                    audioOffloading = preferences.audioOffloading
                    player.updateAudioOffloading(audioOffloading)
                    reshuffleOnRepeat = preferences.reshuffleOnRepeat
                }
                .collect()
        }

        mediaSession =
            MediaSession.Builder(this, player)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        packageManager.getLaunchIntentForPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .setCallback(
                    createMediaSessionCallback(player, mapOf(SET_TIMER_COMMAND to ::onSetTimer))
                )
                .setBitmapLoader(CustomizedBitmapLoader(this))
                .setSessionExtras(bundleOf(AUDIO_SESSION_ID_KEY to player.inner.audioSessionId))
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {}

    override fun onDestroy() {
        getSystemService(AudioManager::class.java)
            .unregisterAudioDeviceCallback(audioDeviceCallback)
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        mainScope.cancel()
        super.onDestroy()
    }

    // region Initialization details

    private fun createListener(player: CustomizedPlayer): Player.Listener {
        return object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                GlobalData.playerState.update { player.capturePlayerState() }

                if (
                    events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                    )
                ) {
                    runBlocking {
                        timerMutex.withLock {
                            if (
                                timerTarget >= 0 &&
                                    SystemClock.elapsedRealtime() >= timerTarget &&
                                    timerFinishLastTrack
                            ) {
                                player.pause()
                                timerTarget = -1
                                mediaSession?.updateSessionExtras { putLong(TIMER_TARGET_KEY, -1) }
                                timerJob?.cancel()
                                timerJob = null
                            }
                        }
                    }
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                player.updateAudioOffloading(audioOffloading)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (
                    player.currentMediaItemIndex == 0 &&
                        lastIndex == player.mediaItemCount - 1 &&
                        (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) &&
                        player.shuffleModeEnabled &&
                        reshuffleOnRepeat &&
                        player.mediaItemCount > 2
                ) {
                    player.seekTo(Random.nextInt(0, player.mediaItemCount - 1), 0)
                    player.disableShuffle()
                    player.enableShuffle()
                }
                lastIndex = player.currentMediaItemIndex
            }
        }
    }

    private fun createMediaSessionCallback(
        player: CustomizedPlayer,
        commands: Map<String, (CustomizedPlayer, MediaSession, Bundle) -> Unit>,
    ): MediaSession.Callback {
        return object : MediaSession.Callback {
            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                val command = commands[customCommand.customAction]
                if (command != null) {
                    command(player, session, args)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                } else {
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            }

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ConnectionResult {
                return ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                        ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .addSessionCommands(
                                commands.map { (command, _) ->
                                    SessionCommand(command, Bundle.EMPTY)
                                }
                            )
                            .build()
                    )
                    .build()
            }
        }
    }

    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo?>?) {
                if (playOnOutputDeviceConnection) {
                    mediaSession?.player?.play()
                }
            }
        }

    // endregion

    // region Commands

    private fun newTimerJob(player: Player): Job {
        return mainScope.launch {
            while (isActive) {
                timerMutex.withLock {
                    if (
                        timerTarget >= 0 &&
                            SystemClock.elapsedRealtime() >= timerTarget &&
                            (!timerFinishLastTrack || !player.isPlaying)
                    ) {
                        player.pause()
                        timerTarget = -1
                        mediaSession?.updateSessionExtras { putLong(TIMER_TARGET_KEY, -1) }
                        timerJob?.cancel()
                        timerJob = null
                    } else if (timerTarget < 0) {
                        timerJob?.cancel()
                        timerJob = null
                    }
                }

                delay(1.seconds)
            }
        }
    }

    private fun onSetTimer(player: CustomizedPlayer, session: MediaSession, args: Bundle) {
        runBlocking {
            timerMutex.withLock {
                val target = args.getLong(TIMER_TARGET_KEY, -1)
                val finishLastTrack = args.getBoolean(TIMER_FINISH_LAST_TRACK_KEY, true)
                timerTarget = target
                timerFinishLastTrack = finishLastTrack
                session.updateSessionExtras {
                    putLong(TIMER_TARGET_KEY, target)
                    putBoolean(TIMER_FINISH_LAST_TRACK_KEY, finishLastTrack)
                }
                timerJob?.cancel()
                timerJob = newTimerJob(player)
            }
        }
    }

    // endregion

    // region Utils

    private inline fun MediaSession.updateSessionExtras(crossinline action: Bundle.() -> Unit) {
        val bundle = sessionExtras.clone() as Bundle
        action(bundle)
        sessionExtras = bundle
    }

    private fun Player.updateAudioOffloading(audioOffloading: Boolean) {
        trackSelectionParameters =
            trackSelectionParameters
                .buildUpon()
                .setAudioOffloadPreferences(
                    if (audioOffloading) {
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                            .setIsSpeedChangeSupportRequired(
                                playbackParameters.speed != 1f || playbackParameters.pitch != 1f
                            )
                            .build()
                    } else {
                        AudioOffloadPreferences.Builder()
                            .setAudioOffloadMode(
                                AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                            )
                            .build()
                    }
                )
                .build()
    }

    // endregion
}
