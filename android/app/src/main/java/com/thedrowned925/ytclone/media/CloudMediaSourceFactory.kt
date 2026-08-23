package com.thedrowned925.ytclone.media

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.thedrowned925.ytclone.storage.GitHubReleaseReader

@UnstableApi
object CloudMediaSourceFactory {
    data class SubtitleTrack(
        val logicalName: String,
        val language: String,
        val label: String,
    )

    fun create(
        reader: GitHubReleaseReader,
        primaryReleaseTag: String,
        mediaId: String,
        title: String,
        channel: String,
        artworkUri: Uri?,
        videoLogicalName: String,
        audioLogicalName: String?,
        subtitles: List<SubtitleTrack> = emptyList(),
    ): MediaSource {
        val dataSourceFactory = GitHubChunkDataSource.Factory(reader, primaryReleaseTag)
        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channel)
            .apply { artworkUri?.let(::setArtworkUri) }
            .build()
        val subtitleConfigs = subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(uri(track.logicalName))
                .setId(track.logicalName)
                .setLanguage(track.language.takeUnless { it == "und" })
                .setLabel(track.label)
                .setMimeType(mimeType(track.logicalName))
                .build()
        }
        val videoItem = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri(videoLogicalName))
            .setMediaMetadata(mediaMetadata)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        val video = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(videoItem)
        if (audioLogicalName.isNullOrBlank()) return video

        val audioItem = MediaItem.Builder()
            .setMediaId("$mediaId-audio")
            .setUri(uri(audioLogicalName))
            .build()
        val audio = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(audioItem)
        return MergingMediaSource(video, audio)
    }

    private fun uri(logicalName: String): Uri = Uri.parse("ytclone://media/${Uri.encode(logicalName)}")

    private fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
    }
}
