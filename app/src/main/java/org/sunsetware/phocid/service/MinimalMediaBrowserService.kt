package org.sunsetware.phocid.service

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat

class MinimalMediaBrowserService : MediaBrowserServiceCompat() {
    
    private lateinit var mediaSession: MediaSessionCompat
    
    override fun onCreate() {
        super.onCreate()
        
        // Create minimal MediaSession
        mediaSession = MediaSessionCompat(this, "PhocidMinimal")
        sessionToken = mediaSession.sessionToken
        
        // Set basic playback state
        val stateBuilder = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .setState(PlaybackStateCompat.STATE_NONE, 0, 1.0f)
        
        mediaSession.setPlaybackState(stateBuilder.build())
        mediaSession.isActive = true
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        // Just return a root - Android Auto needs this to recognize the app
        return BrowserRoot("ROOT", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // Return empty list - minimal implementation
        result.sendResult(mutableListOf())
    }
    
    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
    
    // Method to update from your existing player
    fun updateNowPlaying(songTitle: String, artist: String) {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, songTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build()
        
        mediaSession.setMetadata(metadata)
        
        val playbackState = PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE)
            .build()
        
        mediaSession.setPlaybackState(playbackState)
    }
}