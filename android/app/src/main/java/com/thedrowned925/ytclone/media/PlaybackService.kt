package com.thedrowned925.ytclone.media

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Hosts the same player used by PlayerScreen so Android system media controls,
 * Bluetooth/headset controls and background playback always address live media. */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlaybackManager.player(this)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Premium-style behavior: swiping the UI away does not kill active media.
        val player = mediaSession?.player
        if (player == null || (!player.playWhenReady && player.mediaItemCount == 0)) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        // Do not release PlaybackManager here. The service may be recreated by
        // Android while the Activity still owns the visible video surface.
        super.onDestroy()
    }
}
