package com.thedrowned925.ytclone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.thedrowned925.ytclone.catalog.LocalCatalogRepository
import com.thedrowned925.ytclone.library.OfflineDownloadQueue
import com.thedrowned925.ytclone.library.PlaylistStore
import com.thedrowned925.ytclone.media.CloudMediaSourceFactory
import com.thedrowned925.ytclone.media.PlaybackManager
import com.thedrowned925.ytclone.storage.GitHubReleaseReader
import com.thedrowned925.ytclone.storage.SettingsStore
import kotlinx.coroutines.delay
import java.io.File

private enum class PlayerMenu { ROOT, QUALITY, AUDIO, SUBTITLES, SPEED }

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun PlayerScreen(
    video: LocalCatalogRepository.Video,
    channel: LocalCatalogRepository.Channel?,
    isPipMode: Boolean,
    onBack: () -> Unit,
    onChannelClick: () -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val player = remember { PlaybackManager.player(context) }
    val playlistStore = remember { PlaylistStore(context) }
    val metadataDurationMs = remember(video.id, video.durationSeconds) {
        (video.durationSeconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    var quality by remember(video.id) { mutableStateOf(video.qualities.firstOrNull()) }
    var audio by remember(video.id) { mutableStateOf(video.audioTracks.firstOrNull { it.isDefault } ?: video.audioTracks.firstOrNull()) }
    var subtitle by remember(video.id) { mutableStateOf<LocalCatalogRepository.Subtitle?>(null) }
    var sourceError by remember(video.id) { mutableStateOf<String?>(null) }
    var controlsVisible by remember(video.id) { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember(video.id) { mutableLongStateOf(PlaybackManager.position(video.id)) }
    var duration by remember(video.id) {
        mutableLongStateOf(player.duration.takeIf { it > 0 } ?: metadataDurationMs)
    }
    var speed by remember(video.id) { mutableStateOf(player.playbackParameters.speed) }
    var menu by remember { mutableStateOf<PlayerMenu?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(position) }
    var playlistPicker by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if (fullscreen) fullscreen = false else onBack()
    }

    DisposableEffect(video.id) {
        onPlaybackActiveChanged(true)
        onDispose { onPlaybackActiveChanged(false) }
    }

    DisposableEffect(player, video.id) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = player.duration.takeIf { it > 0 } ?: metadataDurationMs.coerceAtLeast(duration)
                if (playbackState == Player.STATE_READY) {
                    PlaybackManager.retryPendingSeek(video.id)
                }
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                duration = player.duration.takeIf { it > 0 } ?: metadataDurationMs.coerceAtLeast(duration)
                PlaybackManager.retryPendingSeek(video.id)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                sourceError = error.errorCodeName + ": " + (error.message ?: "Oynatma hatası")
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, video.id, metadataDurationMs) {
        while (true) {
            if (PlaybackManager.isActive(video.id)) {
                val raw = player.currentPosition.coerceAtLeast(0L)
                PlaybackManager.rememberPosition(video.id, raw)
                if (!isScrubbing) position = PlaybackManager.position(video.id)
                duration = player.duration.takeIf { it > 0 } ?: metadataDurationMs.coerceAtLeast(duration)
            }
            delay(250)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(quality, audio, subtitle, video.primaryReleaseTag) {
        val selectedQuality = quality ?: return@LaunchedEffect
        val shouldPlay = if (PlaybackManager.isActive(video.id)) player.playWhenReady else true
        runCatching { createMediaSource(context, video, selectedQuality, audio, subtitle) }
            .onSuccess { mediaSource ->
                sourceError = null
                val restored = PlaybackManager.switchSource(context, video.id, mediaSource, shouldPlay)
                position = restored
                scrubPosition = restored
            }
            .onFailure { sourceError = it.message ?: it.javaClass.simpleName }
    }

    LaunchedEffect(speed) { player.setPlaybackSpeed(speed) }

    DisposableEffect(fullscreen) {
        setFullscreen(context, fullscreen)
        onDispose { if (fullscreen) setFullscreen(context, false) }
    }

    fun seekTo(target: Long) {
        val effectiveDuration = duration.takeIf { it > 0 } ?: metadataDurationMs
        val bounded = if (effectiveDuration > 0) target.coerceIn(0L, effectiveDuration) else target.coerceAtLeast(0L)
        PlaybackManager.seek(video.id, bounded)
        position = bounded
        scrubPosition = bounded
        controlsVisible = true
    }

    if (isPipMode) {
        PlayerSurface(
            player = player,
            controlsVisible = false,
            onToggleControls = {},
            onBack = onBack,
            onSettings = {},
            onCaptions = {},
            onFullscreen = {},
            position = position,
            duration = duration.takeIf { it > 0 } ?: metadataDurationMs,
            isPlaying = isPlaying,
            isScrubbing = false,
            scrubPosition = position,
            onScrubChange = {},
            onScrubFinished = {},
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onSeekRelative = { delta -> seekTo(PlaybackManager.position(video.id) + delta) },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val playerModifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        PlayerSurface(
            player = player,
            controlsVisible = controlsVisible,
            onToggleControls = { controlsVisible = !controlsVisible },
            onBack = { if (fullscreen) fullscreen = false else onBack() },
            onSettings = { menu = PlayerMenu.ROOT },
            onCaptions = { subtitle = if (subtitle == null) video.subtitles.firstOrNull() else null },
            onFullscreen = { fullscreen = !fullscreen },
            position = position,
            duration = duration.takeIf { it > 0 } ?: metadataDurationMs,
            isPlaying = isPlaying,
            isScrubbing = isScrubbing,
            scrubPosition = scrubPosition,
            onScrubChange = {
                isScrubbing = true
                scrubPosition = it
            },
            onScrubFinished = {
                seekTo(scrubPosition)
                isScrubbing = false
            },
            onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
            onSeekRelative = { delta -> seekTo(PlaybackManager.position(video.id) + delta) },
            modifier = playerModifier,
        )

        if (!fullscreen) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            ) {
                sourceError?.let {
                    Text("Oynatma hatası: $it", color = Color(0xFFFF6B6B), modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(video.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(top = 14.dp))
                if (video.uploadDate.isNotBlank()) {
                    Text(formatUploadDate(video.uploadDate), color = Color(0xFFAAAAAA), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp).clickable(onClick = onChannelClick),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularFileImage(channel?.avatarFile, video.channel.take(1), 44)
                    Column(modifier = Modifier.padding(start = 11.dp).weight(1f)) {
                        Text(channel?.name ?: video.channel, color = Color.White, fontWeight = FontWeight.SemiBold)
                        val handle = channel?.handle.orEmpty()
                        if (handle.isNotBlank()) Text(handle, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PlayerActionButton(Icons.Default.Download, "İndir") {
                        val q = quality
                        val tag = video.primaryReleaseTag
                        if (q == null || tag.isNullOrBlank()) {
                            feedback = "Bu video için indirilebilir Release bulunamadı"
                        } else {
                            OfflineDownloadQueue.enqueue(
                                context = context,
                                videoId = video.id,
                                title = video.title,
                                releaseTag = tag,
                                qualityLogicalName = q.logicalName,
                                audioLogicalName = audio?.logicalName,
                                subtitleLogicalName = subtitle?.logicalName,
                            )
                            feedback = "İndirme başlatıldı • ${qualityLabel(q)}"
                        }
                    }
                    PlayerActionButton(Icons.Default.PlaylistAdd, "Kaydet") { playlistPicker = true }
                }
                feedback?.let { Text(it, color = Color(0xFF81C784), fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)) }

                HorizontalDivider(color = Color(0xFF272727))
                if (video.description.isNotBlank()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                            .background(Color(0xFF272727), MaterialTheme.shapes.medium)
                            .clickable { descriptionExpanded = !descriptionExpanded }.padding(12.dp),
                    ) {
                        Text("Açıklama", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            video.description,
                            color = Color.White,
                            maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Text(
                    buildString {
                        append(quality?.let(::qualityLabel) ?: "Kalite")
                        append("  •  ")
                        append(audio?.let(::audioLabel) ?: "Ses")
                        subtitle?.let { append("  •  CC ${it.label}") }
                    },
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }

    menu?.let { active ->
        ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = Color(0xFF212121), contentColor = Color.White) {
            when (active) {
                PlayerMenu.ROOT -> {
                    SheetRow("Kalite", quality?.let(::qualityLabel) ?: "Otomatik") { menu = PlayerMenu.QUALITY }
                    SheetRow("Oynatma hızı", speedLabel(speed)) { menu = PlayerMenu.SPEED }
                    SheetRow("Ses parçası", audio?.let(::audioLabel) ?: "Orijinal") { menu = PlayerMenu.AUDIO }
                    SheetRow("Altyazılar", subtitle?.label ?: "Kapalı") { menu = PlayerMenu.SUBTITLES }
                }
                PlayerMenu.QUALITY -> video.qualities.forEach { item ->
                    ChoiceRow(qualityLabel(item), quality?.id == item.id) { quality = item; menu = null }
                }
                PlayerMenu.AUDIO -> video.audioTracks.forEach { item ->
                    ChoiceRow(audioLabel(item), audio?.id == item.id) { audio = item; menu = null }
                }
                PlayerMenu.SUBTITLES -> {
                    ChoiceRow("Kapalı", subtitle == null) { subtitle = null; menu = null }
                    video.subtitles.forEach { item ->
                        ChoiceRow(item.label, subtitle?.id == item.id) { subtitle = item; menu = null }
                    }
                }
                PlayerMenu.SPEED -> listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value ->
                    ChoiceRow(speedLabel(value), speed == value) { speed = value; menu = null }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (playlistPicker) {
        ModalBottomSheet(onDismissRequest = { playlistPicker = false }, containerColor = Color(0xFF212121), contentColor = Color.White) {
            Text("Listeye kaydet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            playlistStore.list().forEach { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, color = Color.White) },
                    supportingContent = { Text("${playlist.videoIds.size} video", color = Color(0xFFAAAAAA)) },
                    trailingContent = { if (video.id in playlist.videoIds) Icon(Icons.Default.Check, null, tint = Color(0xFF81C784)) },
                    modifier = Modifier.clickable {
                        playlistStore.addVideo(playlist.id, video.id)
                        feedback = "${playlist.name} listesine kaydedildi"
                        playlistPicker = false
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            OutlinedTextField(
                value = newPlaylistName,
                onValueChange = { newPlaylistName = it },
                label = { Text("Yeni liste adı") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                singleLine = true,
            )
            Button(
                onClick = {
                    val created = playlistStore.create(newPlaylistName)
                    playlistStore.addVideo(created.id, video.id)
                    newPlaylistName = ""
                    feedback = "${created.name} oluşturuldu ve video kaydedildi"
                    playlistPicker = false
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                enabled = newPlaylistName.isNotBlank(),
            ) { Text("Liste oluştur") }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun PlayerSurface(
    player: Player,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onCaptions: () -> Unit,
    onFullscreen: () -> Unit,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    isScrubbing: Boolean,
    scrubPosition: Long,
    onScrubChange: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    modifier: Modifier,
) {
    val safeDuration = duration.coerceAtLeast(1L)
    val visiblePosition = (if (isScrubbing) scrubPosition else position).coerceIn(0L, safeDuration)

    Box(
        modifier = modifier.background(Color.Black).pointerInput(Unit) {
            detectTapGestures(
                onTap = { onToggleControls() },
                onDoubleTap = { offset -> onSeekRelative(if (offset.x < size.width / 2f) -10_000L else 10_000L) },
            )
        },
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; useController = false; keepScreenOn = true } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onCaptions) { Icon(Icons.Default.ClosedCaption, "Altyazılar", tint = Color.White) }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ayarlar", tint = Color.White) }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onSeekRelative(-10_000L) }, modifier = Modifier.size(50.dp)) {
                        Icon(Icons.Default.Replay10, "10 saniye geri", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(62.dp).background(Color(0xE6FFFFFF), CircleShape)) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = { onSeekRelative(10_000L) }, modifier = Modifier.size(50.dp)) {
                        Icon(Icons.Default.Forward10, "10 saniye ileri", tint = Color.White, modifier = Modifier.size(34.dp))
                    }
                }

                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(34.dp)) {
                        Text("${formatMs(visiblePosition)} / ${formatMs(duration)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onFullscreen, modifier = Modifier.size(34.dp)) {
                            Icon(Icons.Default.Fullscreen, "Tam ekran", tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                    Slider(
                        value = visiblePosition.toFloat(),
                        onValueChange = { onScrubChange(it.toLong()) },
                        onValueChangeFinished = onScrubFinished,
                        valueRange = 0f..safeDuration.toFloat(),
                        modifier = Modifier.fillMaxWidth().height(24.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFFF0033),
                            activeTrackColor = Color(0xFFFF0033),
                            inactiveTrackColor = Color.White.copy(alpha = 0.45f),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Box(Modifier.size(38.dp).background(Color(0xFF272727), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SheetRow(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = Color.White) },
        supportingContent = { Text(value, color = Color(0xFFAAAAAA)) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = Color.White) },
        leadingContent = { RadioButton(selected = selected, onClick = onClick) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun CircularFileImage(file: File?, fallback: String, sizeDp: Int) {
    val bitmap = remember(file?.absolutePath, file?.lastModified()) {
        file?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
    }
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), null, modifier = Modifier.size(sizeDp.dp).background(Color(0xFF303030), CircleShape))
    } else {
        Box(Modifier.size(sizeDp.dp).background(Color(0xFF5C6BC0), CircleShape), contentAlignment = Alignment.Center) {
            Text(fallback.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

private fun createMediaSource(
    context: Context,
    video: LocalCatalogRepository.Video,
    quality: LocalCatalogRepository.Quality,
    audio: LocalCatalogRepository.Audio?,
    subtitle: LocalCatalogRepository.Subtitle?,
): MediaSource {
    val artwork = video.thumbnailFile?.let { Uri.fromFile(it) }
    val metadata = MediaMetadata.Builder().setTitle(video.title).setArtist(video.channel).apply { artwork?.let(::setArtworkUri) }.build()
    val offlineRoot = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTClone/Offline/${video.id}")
    val localVideo = quality.localFile ?: File(offlineRoot, quality.logicalName).takeIf { it.exists() && it.length() > 0L }
    val localAudio = audio?.let { track -> track.localFile ?: File(offlineRoot, track.logicalName).takeIf { it.exists() && it.length() > 0L } }
    val localSubtitle = subtitle?.let { track -> track.localFile ?: File(offlineRoot, track.logicalName).takeIf { it.exists() && it.length() > 0L } }

    if (localVideo != null && (audio == null || localAudio != null)) {
        val dataSource = DefaultDataSource.Factory(context)
        val subtitleConfigs = localSubtitle?.let { file ->
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                    .setId(subtitle?.id ?: file.name)
                    .setLanguage(subtitle?.language?.takeUnless { it == "und" })
                    .setLabel(subtitle?.label)
                    .setMimeType(subtitleMime(file.name))
                    .build(),
            )
        } ?: emptyList()
        val videoItem = MediaItem.Builder()
            .setMediaId(video.id)
            .setUri(Uri.fromFile(localVideo))
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()
        val videoSource = DefaultMediaSourceFactory(dataSource).createMediaSource(videoItem)
        if (localAudio == null) return videoSource
        val audioSource = ProgressiveMediaSource.Factory(dataSource)
            .createMediaSource(MediaItem.Builder().setMediaId("${video.id}-audio").setUri(Uri.fromFile(localAudio)).build())
        return MergingMediaSource(videoSource, audioSource)
    }

    val settings = SettingsStore(context)
    val token = settings.gitHubToken() ?: error("GitHub token ayarlı değil")
    val repo = settings.mediaRepo().takeIf { it.contains('/') } ?: error("GitHub media repo ayarlı değil")
    val releaseTag = video.primaryReleaseTag ?: error("Bu videonun GitHub Release bilgisi yok")
    return CloudMediaSourceFactory.create(
        reader = GitHubReleaseReader(repo, token),
        primaryReleaseTag = releaseTag,
        mediaId = video.id,
        title = video.title,
        channel = video.channel,
        artworkUri = artwork,
        videoLogicalName = quality.logicalName,
        audioLogicalName = audio?.logicalName,
        subtitles = subtitle?.let { listOf(CloudMediaSourceFactory.SubtitleTrack(it.logicalName, it.language, it.label)) } ?: emptyList(),
    )
}

private fun qualityLabel(item: LocalCatalogRepository.Quality): String = if (item.fps > 30) "${item.height}p${item.fps}" else "${item.height}p"

private fun audioLabel(item: LocalCatalogRepository.Audio): String {
    val language = when (item.language.lowercase()) {
        "tr", "tr-tr" -> "Türkçe"
        "en", "en-us", "en-gb" -> "English"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "es" -> "Español"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "und", "" -> "Orijinal"
        else -> item.language
    }
    val generic = item.label.lowercase() in setOf("low", "medium", "high", "default", "original")
    return if (generic || item.label.isBlank()) language else listOf(item.label, language).distinct().joinToString(" · ")
}

private fun subtitleMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "srt" -> MimeTypes.APPLICATION_SUBRIP
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "xml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.TEXT_VTT
}

private fun speedLabel(value: Float): String = if (value == 1f) "Normal" else "${value}x"

private fun formatUploadDate(value: String): String = if (value.length == 8 && value.all(Char::isDigit)) {
    "${value.substring(6, 8)}.${value.substring(4, 6)}.${value.substring(0, 4)}"
} else value

private fun formatMs(value: Long): String {
    val total = value.coerceAtLeast(0L) / 1000L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun setFullscreen(context: Context, fullscreen: Boolean) {
    val activity = context.findActivity() ?: return
    activity.requestedOrientation = if (fullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
        if (fullscreen) hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
