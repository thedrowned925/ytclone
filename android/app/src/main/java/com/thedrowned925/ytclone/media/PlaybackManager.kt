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
 *
 * Position ownership is intentionally kept outside Compose. In particular, source
 * switches (quality/audio/subtitle) freeze the current position before ExoPlayer is
 * given a new MediaSource. During prepare/buffering ExoPlayer may temporarily report
 * 0ms; that transient value must never become the remembered playback position.
 */
object PlaybackManager {
    @Volatile private var instance: ExoPlayer? = null
    @Volatile private var activeVideoId: String? = null

    private val savedPositions = ConcurrentHashMap<String, Long>()
    private val pendingSeekTargets = ConcurrentHashMap<String, Long>()
    private val pendingSeekStartedAt = ConcurrentHashMap<String, Long>()
    private val sourceSwitching = ConcurrentHashMap.newKeySet<String>()

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

    /**
     * Capture the authoritative position before changing quality/audio/subtitle.
     * This is safe to call from the UI at the exact moment the user makes a choice.
     */
    fun freezeForSourceSwitch(videoId: String): Long {
        val target = authoritativePosition(videoId)
        savedPositions[videoId] = target
        armPending(videoId, target)
        sourceSwitching += videoId
        return target
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
            val previousPosition = player.currentPosition.coerceAtLeast(0L)
            savedPositions[previousId] = previousPosition
            pendingSeekTargets.remove(previousId)
            pendingSeekStartedAt.remove(previousId)
            sourceSwitching.remove(previousId)
        }

        val startPosition = if (previousId == videoId) {
            // Re-use a target already frozen by the UI, otherwise capture the live
            // position now. Never prefer an older saved value over the active player.
            pendingSeekTargets[videoId] ?: freezeForSourceSwitch(videoId)
        } else {
            savedPositions[videoId]?.coerceAtLeast(0L) ?: 0L
        }

        activeVideoId = videoId
        if (startPosition > 0L) {
            armPending(videoId, startPosition)
            sourceSwitching += videoId
        }

        // Passing the start position together with the MediaSource avoids the classic
        // prepare() -> 0ms -> seekTo() race. READY also retries the same target.
        player.setMediaSource(mediaSource, startPosition)
        player.prepare()
        player.playWhenReady = playWhenReady
        ensureService(context)
        return startPosition
    }

    fun seek(videoId: String, positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L)
        savedPositions[videoId] = target
        armPending(videoId, target)
        instance?.takeIf { activeVideoId == videoId }?.seekTo(target)
    }

    /**
     * Called by the UI polling loop. While a seek/source switch is pending, reject
     * transient positions (especially 0ms) until ExoPlayer actually reaches the target.
     */
    fun rememberPosition(videoId: String, positionMs: Long) {
        if (activeVideoId != videoId) return
        val position = positionMs.coerceAtLeast(0L)
        val pending = pendingSeekTargets[videoId]

        if (pending != null) {
            val reached = abs(position - pending) <= SEEK_TOLERANCE_MS ||
                (position > pending && position - pending <= SEEK_TOLERANCE_MS * 2)
            if (reached) {
                savedPositions[videoId] = position
                pendingSeekTargets.remove(videoId)
                pendingSeekStartedAt.remove(videoId)
                sourceSwitching.remove(videoId)
                return
            }

            val player = instance
            val elapsed = SystemClock.elapsedRealtime() - (pendingSeekStartedAt[videoId] ?: 0L)
            if (elapsed < PENDING_SEEK_GUARD_MS ||
                player?.playbackState == Player.STATE_BUFFERING ||
                player?.playbackState == Player.STATE_IDLE
            ) {
                return
            }

            if (player?.playbackState == Player.STATE_READY && player.isCurrentMediaItemSeekable) {
                player.seekTo(pending)
                pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
            }
            return
        }

        // With no pending transition, the live player is authoritative.
        savedPositions[videoId] = position
    }

    fun retryPendingSeek(videoId: String) {
        if (activeVideoId != videoId) return
        val target = pendingSeekTargets[videoId] ?: return
        val player = instance ?: return
        if (player.playbackState == Player.STATE_READY) {
            if (target == 0L || player.isCurrentMediaItemSeekable) {
                player.seekTo(target)
                pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
            }
        }
    }

    fun position(videoId: String): Long = when {
        pendingSeekTargets.containsKey(videoId) -> pendingSeekTargets.getValue(videoId)
        activeVideoId == videoId -> instance?.currentPosition?.coerceAtLeast(0L)
            ?: savedPositions[videoId]
            ?: 0L
        else -> savedPositions[videoId] ?: 0L
    }

    fun desiredPosition(videoId: String): Long = authoritativePosition(videoId)

    fun isActive(videoId: String): Boolean = activeVideoId == videoId
    fun isSeekable(videoId: String): Boolean = activeVideoId == videoId && instance?.isCurrentMediaItemSeekable == true
    fun hasPendingSeek(videoId: String): Boolean = pendingSeekTargets.containsKey(videoId)
    fun isSourceSwitching(videoId: String): Boolean = sourceSwitching.contains(videoId)

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
                instance?.let { player ->
                    val current = player.currentPosition.coerceAtLeast(0L)
                    if (!hasPendingSeek(id)) savedPositions[id] = current
                }
            }
            instance?.release()
            instance = null
            activeVideoId = null
            pendingSeekTargets.clear()
            pendingSeekStartedAt.clear()
            sourceSwitching.clear()
        }
    }

    private fun authoritativePosition(videoId: String): Long {
        pendingSeekTargets[videoId]?.let { return it }
        if (activeVideoId == videoId) {
            // For an active source the current player position wins over any stale
            // remembered value. During a source switch pendingSeekTargets is populated,
            // so a temporary 0ms here cannot leak through.
            return instance?.currentPosition?.coerceAtLeast(0L)
                ?: savedPositions[videoId]
                ?: 0L
        }
        return savedPositions[videoId] ?: 0L
    }

    private fun armPending(videoId: String, target: Long) {
        pendingSeekTargets[videoId] = target.coerceAtLeast(0L)
        pendingSeekStartedAt[videoId] = SystemClock.elapsedRealtime()
    }

    private const val SEEK_TOLERANCE_MS = 1_500L
    private const val PENDING_SEEK_GUARD_MS = 5_000L
}
