package com.thedrowned925.ytclone.media

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.util.concurrent.ConcurrentHashMap

/**
 * Single process-wide playback engine shared by PlayerScreen and MediaSessionService.
 * It also owns per-video playback positions so quality/audio/subtitle changes can swap
 * MediaSources without ever jumping back to 0.
 */
object PlaybackManager {
    @Volatile private var instance: ExoPlayer? = null
    @Volatile private var activeVideoId: String? = null
    private val savedPositions = ConcurrentHashMap<String, Long>()

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

    fun switchSource(
        context: Context,
        videoId: String,
        mediaSource: MediaSource,
        playWhenReady: Boolean,
    ): Long {
        val player = player(context)
        val previousId = activeVideoId
        if (!previousId.isNullOrBlank()) {
            savedPositions[previousId] = player.currentPosition.coerceAtLeast(0L)
        }

        val startPosition = when {
            previousId == videoId -> player.currentPosition.coerceAtLeast(0L)
            else -> savedPositions[videoId]?.coerceAtLeast(0L) ?: 0L
        }
        activeVideoId = videoId

        // Supplying the start position as part of setMediaSource is much more stable
        // than prepare() followed by seekTo(), especially for remote/chunked MP4/WebM.
        player.setMediaSource(mediaSource, startPosition)
        player.prepare()
        player.playWhenReady = playWhenReady
        ensureService(context)
        return startPosition
    }

    fun seek(videoId: String, positionMs: Long) {
        val position = positionMs.coerceAtLeast(0L)
        savedPositions[videoId] = position
        instance?.takeIf { activeVideoId == videoId }?.seekTo(position)
    }

    fun rememberPosition(videoId: String, positionMs: Long) {
        if (activeVideoId == videoId) savedPositions[videoId] = positionMs.coerceAtLeast(0L)
    }

    fun position(videoId: String): Long = when {
        activeVideoId == videoId -> instance?.currentPosition?.coerceAtLeast(0L) ?: savedPositions[videoId] ?: 0L
        else -> savedPositions[videoId] ?: 0L
    }

    fun isActive(videoId: String): Boolean = activeVideoId == videoId

    fun ensureService(context: Context) {
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, PlaybackService::class.java),
            )
        }
    }

    fun release() {
        synchronized(this) {
            activeVideoId?.let { id -> instance?.let { savedPositions[id] = it.currentPosition.coerceAtLeast(0L) } }
            instance?.release()
            instance = null
            activeVideoId = null
        }
    }
}
