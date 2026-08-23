package com.thedrowned925.ytclone.media

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer

/**
 * Single process-wide playback engine. The Activity UI and MediaSessionService
 * deliberately share this exact player so background playback, system media
 * controls and PiP never drift into separate playback sessions.
 */
object PlaybackManager {
    @Volatile
    private var instance: ExoPlayer? = null

    fun player(context: Context): ExoPlayer = instance ?: synchronized(this) {
        instance ?: ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
            .also { instance = it }
    }

    fun ensureService(context: Context) {
        // Called while the Activity is visible. MediaSessionService promotes
        // itself to foreground as soon as actual playback becomes ongoing.
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, PlaybackService::class.java),
            )
        }
    }

    fun release() {
        synchronized(this) {
            instance?.release()
            instance = null
        }
    }
}
