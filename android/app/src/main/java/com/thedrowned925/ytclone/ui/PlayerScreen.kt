package com.thedrowned925.ytclone.ui

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.ui.PlayerView
import com.thedrowned925.ytclone.catalog.LocalCatalogRepository
import com.thedrowned925.ytclone.media.CloudMediaSourceFactory
import com.thedrowned925.ytclone.storage.GitHubReleaseReader
import com.thedrowned925.ytclone.storage.SettingsStore

@Composable
fun PlayerScreen(
    video: LocalCatalogRepository.Video,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var quality by remember(video.id) { mutableStateOf(video.qualities.firstOrNull()) }
    var audio by remember(video.id) {
        mutableStateOf(video.audioTracks.firstOrNull { it.isDefault } ?: video.audioTracks.firstOrNull())
    }
    var sourceError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    LaunchedEffect(quality, audio, video.primaryReleaseTag) {
        val selectedQuality = quality ?: return@LaunchedEffect
        val selectedAudio = audio
        val previousPosition = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady || player.mediaItemCount == 0

        runCatching {
            createMediaSource(
                context = context,
                video = video,
                quality = selectedQuality,
                audio = selectedAudio,
            )
        }.onSuccess { mediaSource ->
            sourceError = null
            player.setMediaSource(mediaSource)
            player.prepare()
            if (previousPosition > 0L) player.seekTo(previousPosition)
            player.playWhenReady = shouldPlay
        }.onFailure { error ->
            sourceError = error.message ?: error.javaClass.simpleName
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri") }
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(video.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text(video.channel, maxLines = 1, style = MaterialTheme.typography.bodySmall)
            }
        }

        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    keepScreenOn = true
                }
            },
            update = { it.player = player },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        )

        sourceError?.let {
            Text(
                "Oynatma hatası: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }

        Text("Kalite", modifier = Modifier.padding(start = 12.dp, top = 14.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            video.qualities.forEach { item ->
                FilterChip(
                    selected = quality?.id == item.id,
                    onClick = { quality = item },
                    label = {
                        Text(
                            if (item.id == "source") "Orijinal · ${item.fps} FPS"
                            else "${item.height}p · ${item.fps} FPS",
                        )
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Text("Ses parçası", modifier = Modifier.padding(start = 12.dp, top = 10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            video.audioTracks.forEach { track ->
                FilterChip(
                    selected = audio?.id == track.id,
                    onClick = { audio = track },
                    label = { Text(listOf(track.label, track.language).filter { it.isNotBlank() && it != "und" }.distinct().joinToString(" · ").ifBlank { "Orijinal" }) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        AssistChip(
            onClick = { },
            label = {
                Text(
                    if (quality?.localFile != null && audio?.localFile != null) {
                        "Telefondan oynatılıyor"
                    } else {
                        "GitHub Releases üzerinden oynatılıyor"
                    },
                )
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

private fun createMediaSource(
    context: android.content.Context,
    video: LocalCatalogRepository.Video,
    quality: LocalCatalogRepository.Quality,
    audio: LocalCatalogRepository.Audio?,
): MediaSource {
    val localVideo = quality.localFile
    val localAudio = audio?.localFile
    if (localVideo != null && (audio == null || localAudio != null)) {
        val dataSource = DefaultDataSource.Factory(context)
        val videoSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(localVideo)))
        if (localAudio == null) return videoSource
        val audioSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(localAudio)))
        return MergingMediaSource(videoSource, audioSource)
    }

    val settings = SettingsStore(context)
    val token = settings.gitHubToken() ?: error("GitHub token ayarlı değil")
    val repo = settings.mediaRepo().takeIf { it.contains('/') } ?: error("GitHub media repo ayarlı değil")
    val releaseTag = video.primaryReleaseTag ?: error("Bu videonun GitHub Release bilgisi yok")
    val reader = GitHubReleaseReader(repo, token)
    return CloudMediaSourceFactory.create(
        reader = reader,
        primaryReleaseTag = releaseTag,
        videoLogicalName = quality.logicalName,
        audioLogicalName = audio?.logicalName,
    )
}
