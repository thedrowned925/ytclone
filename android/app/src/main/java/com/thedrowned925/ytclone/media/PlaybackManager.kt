package com.thedrowned925.ytclone.media

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Single process-wide playback engine shared by PlayerScreen and MediaSessionService.
 * Per-video positions and pending seeks live here so UI recomposition, quality/audio
 * switches and short BUFFERING/0ms transitions cannot accidentally erase the target.
 */
object PlaybackManager {
    @Volatile private var instance: ExoPlayer? = null
    @Volatile private var activeVideoId: String? = null
    private val savedPositions = ConcurrentHashMap<String, Long>()
    private val pendingSeekTargets = ConcurrentHashMap<String, Long>()
    private val pendingSeekStartedAt = ConcurrentHashMap<String, Long>()

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
        if (!previousId.isNullOrBlank() && previousId != videoId) {
            rememberPosition(previousId, player.currentPosition.coerceAtLeast(0L))
        }

        val startPosition = desiredPosition(videoId)
        activeVideoId = videoId
        player.setMediaSource(mediaSource, startPosition)
        player.prepare()
        player.playWhenReady = playWhenReady
        ensureService(context)
        return startPosition
    }

    fun seek(videoId: String, positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        savedPositions[videoId] = target
        pendingSeekTargets[videoId] = target
        pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
        instance?.takeIf { activeVideoId == videoId }?.seekTo(target)
    }

    /**
     * Called from the UI polling loop. A player may report 0ms briefly while a seek
     * causes a new range request. Never let that transient value overwrite the target.
     */
    fun rememberPosition(videoId: String, positionMs: Long) {
        if (activeVideoId != videoId) return
        val position = positionMs.coerceAtLeast(0L)
        val pending = pendingSeekTargets[videoId]
        if (pending != null) {
            val reached = abs(position - pending) <= SEEK_TOLERANCE_MS ||
                (position > pending && position - pending <= SEEK_TOLERANCE_MS * 2)
            if (reached) {
                pendingSeekTargets.remove(videoId)
                pendingSeekStartedAt.remove(videoId)
                savedPositions[videoId] = position
                return
            }

            val elapsed = SystemClock.elapsedRealtime() - (pendingSeekStartedAt[videoId] ?: 0L)
            // During BUFFERING/prepare keep the requested time authoritative. If the
            // source becomes seekable, retry once the timeline is ready.
            if (elapsed < PENDING_SEEK_GUARD_MS || instance?.playbackState == Player.STATE_BUFFERING) return

            if (instance?.isCurrentMediaItemSeekable == true) {
                instance?.seekTo(pending)
                pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
                return
            }
            // Old non-indexed archive: keep target saved so a quality/source switch or
            // repaired archive can reopen at the requested time rather than at zero.
            return
        }
        savedPositions[videoId] = position
    }

    fun retryPendingSeek(videoId: String) {
        if (activeVideoId != videoId) return
        val target = pendingSeekTargets[videoId] ?: return
        val player = instance ?: return
        if (player.playbackState == Player.STATE_READY && player.isCurrentMediaItemSeekable) {
            player.seekTo(target)
            pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
        }
    }

    fun position(videoId: String): Long = when {
        pendingSeekTargets.containsKey(videoId) -> pendingSeekTargets.getValue(videoId)
        activeVideoId == videoId -> instance?.currentPosition?.coerceAtLeast(0L) ?: savedPositions[videoId] ?: 0L
        else -> savedPositions[videoId] ?: 0L
    }

    fun desiredPosition(videoId: String): Long =
        pendingSeekTargets[videoId] ?: savedPositions[videoId] ?: if (activeVideoId == videoId) {
            instance?.currentPosition?.coerceAtLeast(0L) ?: 0L
        } else 0L

    fun isActive(videoId: String): Boolean = activeVideoId == videoId
    fun isSeekable(videoId: String): Boolean = activeVideoId == videoId && instance?.isCurrentMediaItemSeekable == true
    fun hasPendingSeek(videoId: String): Boolean = pendingSeekTargets.containsKey(videoId)

    fun ensureService(context: Context) {
        runCatching {
            context.applicationContext.startService(
                Intent(context.applicationContext, PlaybackService::class.java),
            )
        }
    }

    fun release() {
        synchronized(this) {
            activeVideoId?.let { id ->
                instance?.let { rememberPosition(id, it.currentPosition.coerceAtLeast(0L)) }
            }
            instance?.release()
            instance = null
            activeVideoId = null
            pendingSeekTargets.clear()
            pendingSeekStartedAt.clear()
        }
    }

    private const val SEEK_TOLERANCE_MS = 1_500L
    private const val PENDING_SEEK_GUARD_MS = 4_000L
}
