package com.thedrowned925.ytclone.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.thedrowned925.ytclone.storage.GitHubReleaseReader

object CloudMediaSourceFactory {
    fun create(
        reader: GitHubReleaseReader,
        primaryReleaseTag: String,
        videoLogicalName: String,
        audioLogicalName: String?,
    ): MediaSource {
        val video = progressiveSource(reader, primaryReleaseTag, videoLogicalName)
        if (audioLogicalName.isNullOrBlank()) return video
        val audio = progressiveSource(reader, primaryReleaseTag, audioLogicalName)
        return MergingMediaSource(video, audio)
    }

    private fun progressiveSource(
        reader: GitHubReleaseReader,
        primaryReleaseTag: String,
        logicalName: String,
    ): MediaSource {
        val factory = GitHubChunkDataSource.Factory(reader, primaryReleaseTag, logicalName)
        val item = MediaItem.fromUri(Uri.parse("ytclone://media/${Uri.encode(logicalName)}"))
        return ProgressiveMediaSource.Factory(factory).createMediaSource(item)
    }
}
