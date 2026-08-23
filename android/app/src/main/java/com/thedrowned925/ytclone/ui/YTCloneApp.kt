package com.thedrowned925.ytclone.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedrowned925.ytclone.catalog.LocalCatalogRepository
import com.thedrowned925.ytclone.ingest.IngestOptions
import com.thedrowned925.ytclone.library.PlaylistStore
import com.thedrowned925.ytclone.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val YTCloneColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF0033),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF272727),
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFDDDDDD),
    onPrimary = Color.White,
)

private enum class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Home("Ana Sayfa", Icons.Default.Home),
    Shorts("Shorts", Icons.Default.PlayArrow),
    Add("Ekle", Icons.Default.AddCircle),
    Channels("Kanallar", Icons.Default.Subscriptions),
    Library("Kitaplık", Icons.Default.VideoLibrary),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YTCloneApp(
    incomingUrl: String?,
    onIncomingUrlConsumed: () -> Unit,
    onArchive: (String, IngestOptions) -> String,
    mediaRepo: String,
    tokenConfigured: Boolean,
    onSaveStorageSettings: (String, String?) -> Unit,
    isPipMode: Boolean,
    onPlaybackActiveChanged: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val catalogRepository = remember { LocalCatalogRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    val playlistStore = remember { PlaylistStore(context) }
    var snapshot by remember { mutableStateOf(catalogRepository.loadCached()) }
    var playlists by remember { mutableStateOf(playlistStore.list()) }
    var selectedTab by remember { mutableStateOf(Tab.Home) }
    var selectedVideo by remember { mutableStateOf<LocalCatalogRepository.Video?>(null) }
    var selectedChannel by remember { mutableStateOf<LocalCatalogRepository.Channel?>(null) }
    var selectedPlaylist by remember { mutableStateOf<PlaylistStore.Playlist?>(null) }
    var importUrl by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeWorkId by remember { mutableStateOf(settingsStore.lastIngestWorkId()) }
    var refreshing by remember { mutableStateOf(false) }
    var createPlaylistDialog by remember { mutableStateOf(false) }
    var createPlaylistName by remember { mutableStateOf("") }

    BackHandler(
        enabled = selectedVideo != null || selectedChannel != null || selectedPlaylist != null ||
            searchOpen || settingsOpen || createPlaylistDialog || selectedTab != Tab.Home,
    ) {
        when {
            settingsOpen -> settingsOpen = false
            createPlaylistDialog -> createPlaylistDialog = false
            selectedVideo != null -> selectedVideo = null
            selectedChannel != null -> selectedChannel = null
            selectedPlaylist != null -> selectedPlaylist = null
            searchOpen -> searchOpen = false
            selectedTab != Tab.Home -> selectedTab = Tab.Home
        }
    }

    suspend fun refreshCatalog() {
        if (!tokenConfigured || refreshing) return
        refreshing = true
        snapshot = runCatching { withContext(Dispatchers.IO) { catalogRepository.refreshFromGitHub() } }
            .getOrElse { catalogRepository.loadCached() }
        selectedChannel = selectedChannel?.let { old -> snapshot.channels.firstOrNull { it.id == old.id } ?: old }
        refreshing = false
    }

    LaunchedEffect(Unit, tokenConfigured) { refreshCatalog() }
    LaunchedEffect(selectedTab, selectedVideo, selectedPlaylist) {
        if (selectedTab == Tab.Home || selectedTab == Tab.Channels || selectedTab == Tab.Library) refreshCatalog()
        if (selectedTab == Tab.Library || selectedVideo == null) playlists = playlistStore.list()
    }
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            importUrl = incomingUrl
            selectedTab = Tab.Add
            searchOpen = false
            onIncomingUrlConsumed()
        }
    }

    MaterialTheme(colorScheme = YTCloneColors) {
        val activeVideo = selectedVideo
        if (activeVideo != null) {
            val channel = snapshot.channels.firstOrNull { it.id == activeVideo.channelId }
            PlayerScreen(
                video = activeVideo,
                channel = channel,
                isPipMode = isPipMode,
                onBack = { selectedVideo = null },
                onChannelClick = {
                    selectedVideo = null
                    selectedChannel = channel
                    selectedTab = Tab.Channels
                },
                onPlaybackActiveChanged = onPlaybackActiveChanged,
            )
            return@MaterialTheme
        }

        val channelPage = selectedChannel
        if (channelPage != null) {
            ChannelDetailScreen(
                channel = channelPage,
                videos = snapshot.videos.filter { it.id in channelPage.videoIds || it.channelId == channelPage.id },
                onBack = { selectedChannel = null },
                onOpenVideo = { selectedVideo = it },
            )
            return@MaterialTheme
        }

        val playlistPage = selectedPlaylist
        if (playlistPage != null) {
            PlaylistDetailScreen(
                playlist = playlistPage,
                videos = snapshot.videos.filter { it.id in playlistPage.videoIds },
                channels = snapshot.channels,
                onBack = { selectedPlaylist = null },
                onOpen = { selectedVideo = it },
                onChannel = { id -> selectedChannel = snapshot.channels.firstOrNull { it.id == id } },
            )
            return@MaterialTheme
        }

        if (searchOpen) {
            SearchScreen(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                videos = snapshot.videos,
                channels = snapshot.channels,
                onBack = { searchOpen = false },
                onOpen = { selectedVideo = it },
                onChannel = { id -> selectedChannel = snapshot.channels.firstOrNull { it.id == id } },
            )
            return@MaterialTheme
        }

        Scaffold(
            containerColor = Color(0xFF0F0F0F),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(width = 32.dp, height = 22.dp).background(Color(0xFFFF0033), RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                            Spacer(Modifier.size(8.dp))
                            Text("YTClone", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Default.Cast, "Yayınla", tint = Color.White) }
                        IconButton(onClick = {}) { Icon(Icons.Default.Notifications, "Bildirimler", tint = Color.White) }
                        IconButton(onClick = { searchOpen = true }) { Icon(Icons.Default.Search, "Ara", tint = Color.White) }
                        if (selectedTab == Tab.Home || selectedTab == Tab.Channels) {
                            IconButton(onClick = { snapshot = catalogRepository.loadCached() }) { Icon(Icons.Default.Refresh, "Yenile", tint = Color.White) }
                        }
                        IconButton(onClick = { settingsOpen = true }) {
                            Box(Modifier.size(30.dp).background(Color(0xFF5C6BC0), CircleShape), contentAlignment = Alignment.Center) {
                                Text("H", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F0F0F), titleContentColor = Color.White, actionIconContentColor = Color.White),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0F0F0F)) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, tab.title) },
                            label = { Text(tab.title, fontSize = 10.sp) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFF0F0F0F))) {
                when (selectedTab) {
                    Tab.Home -> HomeScreen(
                        videos = snapshot.videos,
                        channels = snapshot.channels,
                        onOpen = { selectedVideo = it },
                        onChannel = { id -> selectedChannel = snapshot.channels.firstOrNull { it.id == id } },
                        onAdd = { selectedTab = Tab.Add },
                    )
                    Tab.Shorts -> SimplePage("Shorts", "Dikey videolar için tam ekran kaydırmalı oynatıcı sonraki aşamada eklenecek.")
                    Tab.Add -> ImportScreen(importUrl, { importUrl = it }, activeWorkId, { url, options -> activeWorkId = onArchive(url, options) }, tokenConfigured)
                    Tab.Channels -> ChannelsScreen(snapshot.channels, snapshot.videos, onOpen = { selectedChannel = it })
                    Tab.Library -> LibraryScreen(
                        videos = snapshot.videos,
                        playlists = playlists,
                        channels = snapshot.channels,
                        onOpen = { selectedVideo = it },
                        onOpenPlaylist = { selectedPlaylist = it },
                        onCreatePlaylist = { createPlaylistDialog = true },
                    )
                }
            }
        }

        if (settingsOpen) {
            SettingsDialog(initialRepo = mediaRepo, tokenConfigured = tokenConfigured, onDismiss = { settingsOpen = false }, onSave = onSaveStorageSettings)
        }

        if (createPlaylistDialog) {
            AlertDialog(
                onDismissRequest = { createPlaylistDialog = false },
                title = { Text("Yeni oynatma listesi", color = Color.White) },
                text = {
                    OutlinedTextField(
                        value = createPlaylistName,
                        onValueChange = { createPlaylistName = it },
                        label = { Text("Liste adı") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (createPlaylistName.isNotBlank()) {
                            playlistStore.create(createPlaylistName)
                            playlists = playlistStore.list()
                            createPlaylistName = ""
                            createPlaylistDialog = false
                        }
                    }) { Text("Oluştur") }
                },
                dismissButton = { TextButton(onClick = { createPlaylistDialog = false }) { Text("İptal") } },
                containerColor = Color(0xFF212121),
            )
        }
    }
}

@Composable
private fun HomeScreen(
    videos: List<LocalCatalogRepository.Video>,
    channels: List<LocalCatalogRepository.Channel>,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onChannel: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val chips = listOf("Tümü", "Oyun", "Müzik", "Dublaj")
    val channelMap = channels.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { chip -> Box(Modifier.background(Color(0xFF272727), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 7.dp)) { Text(chip, color = Color.White, fontSize = 12.sp) } }
            }
        }
        if (videos.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(48.dp))
                    Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(58.dp), tint = Color(0xFFAAAAAA))
                    Spacer(Modifier.height(16.dp))
                    Text("Kişisel YouTube arşivin", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("YouTube'dan paylaş veya bağlantı yapıştır. Arşiv tamamlanınca video, kanal, thumbnail, ses ve altyazı katalogdan burada görünür.", color = Color(0xFFAAAAAA))
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = onAdd) { Text("İlk videoyu arşivle") }
                }
            }
        } else items(videos, key = { it.id }) { video -> VideoCard(video, channelMap[video.channelId], onOpen, onChannel) }
    }
}

@Composable
private fun VideoCard(
    video: LocalCatalogRepository.Video,
    channel: LocalCatalogRepository.Channel?,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onChannel: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).background(Color(0xFF0F0F0F))) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF202124)).clickable { onOpen(video) }) {
            FileImage(video.thumbnailFile, Modifier.fillMaxSize(), ContentScale.Crop)
            if (video.thumbnailFile == null) Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(58.dp).align(Alignment.Center), tint = Color.White)
            Text(formatDuration(video.durationSeconds), color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(Modifier.clickable { if (video.channelId.isNotBlank()) onChannel(video.channelId) }) {
                CircularFileImage(channel?.avatarFile, video.channel.take(1), 38)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp).clickable { onOpen(video) }) {
                Text(video.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${video.channel} · ${video.qualities.size} kalite · ${video.audioTracks.size} ses", color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChannelsScreen(
    channels: List<LocalCatalogRepository.Channel>,
    videos: List<LocalCatalogRepository.Video>,
    onOpen: (LocalCatalogRepository.Channel) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        item { Text("Kanallar", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }
        if (channels.isEmpty()) item { Text("Henüz katalogda kanal yok.", color = Color(0xFFAAAAAA), modifier = Modifier.padding(16.dp)) }
        items(channels, key = { it.id }) { channel ->
            val count = videos.count { it.channelId == channel.id || it.id in channel.videoIds }
            Row(Modifier.fillMaxWidth().clickable { onOpen(channel) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularFileImage(channel.avatarFile, channel.name.take(1), 58)
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(channel.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (channel.handle.isNotBlank()) Text(channel.handle, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Text("$count video", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChannelDetailScreen(
    channel: LocalCatalogRepository.Channel,
    videos: List<LocalCatalogRepository.Video>,
    onBack: () -> Unit,
    onOpenVideo: (LocalCatalogRepository.Video) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        item {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF252525))) {
                FileImage(channel.bannerFile, Modifier.fillMaxSize(), ContentScale.Crop)
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0x88000000), CircleShape)) {
                    Icon(Icons.Default.ArrowBack, "Geri", tint = Color.White)
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularFileImage(channel.avatarFile, channel.name.take(1), 76)
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(channel.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                    if (channel.handle.isNotBlank()) Text(channel.handle, color = Color(0xFFAAAAAA))
                    if (channel.subscriberCount > 0) Text(formatCount(channel.subscriberCount) + " abone", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Text("${videos.size} video", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
            }
            if (channel.description.isNotBlank()) Text(channel.description, color = Color.White, maxLines = 5, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Text("Videolar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        }
        items(videos, key = { it.id }) { video -> VideoCard(video, channel, onOpenVideo) {} }
    }
}

@Composable
private fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    videos: List<LocalCatalogRepository.Video>,
    channels: List<LocalCatalogRepository.Channel>,
    onBack: () -> Unit,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onChannel: (String) -> Unit,
) {
    val normalized = query.trim().lowercase()
    val results = if (normalized.isBlank()) emptyList() else videos.filter {
        it.title.lowercase().contains(normalized) ||
            it.channel.lowercase().contains(normalized) ||
            it.description.lowercase().contains(normalized)
    }
    val channelMap = channels.associateBy { it.id }
    Column(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri", tint = Color.White) }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Arşivinde ara") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (normalized.isNotBlank() && results.isEmpty()) item { Text("Sonuç bulunamadı", color = Color(0xFFAAAAAA), modifier = Modifier.padding(20.dp)) }
            items(results, key = { it.id }) { video -> VideoCard(video, channelMap[video.channelId], onOpen, onChannel) }
        }
    }
}

@Composable
private fun PlaylistDetailScreen(
    playlist: PlaylistStore.Playlist,
    videos: List<LocalCatalogRepository.Video>,
    channels: List<LocalCatalogRepository.Channel>,
    onBack: () -> Unit,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onChannel: (String) -> Unit,
) {
    val channelMap = channels.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Geri", tint = Color.White) }
                Column { Text(playlist.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp); Text("${videos.size} video", color = Color(0xFFAAAAAA), fontSize = 12.sp) }
            }
        }
        items(videos, key = { it.id }) { video -> VideoCard(video, channelMap[video.channelId], onOpen, onChannel) }
    }
}

@Composable
private fun ImportScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    activeWorkId: String?,
    onArchive: (String, IngestOptions) -> Unit,
    storageReady: Boolean,
) {
    var queued by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).background(Color(0xFF0F0F0F))) {
        item {
            Text("Video arşivle", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("YouTube uygulamasında Paylaş → YTClone da kullanabilirsin.", color = Color(0xFFAAAAAA), modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
            if (!storageReady) Text("GitHub token ayarlı değil. Sağ üstteki profilden token'ı bir kez kaydet.", color = Color(0xFFFFB74D), modifier = Modifier.padding(bottom = 12.dp))
            OutlinedTextField(value = url, onValueChange = { queued = false; onUrlChange(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Video bağlantısı") }, singleLine = true)
            Spacer(Modifier.height(18.dp))
            ArchivePolicyRow("4K'ya kadar tüm kaliteler", "Her çözünürlükten tek sürüm: 1080p60 varsa 1080p60, yoksa 1080p.")
            ArchivePolicyRow("Tüm ses parçalarını al", "Orijinal, dublaj ve erişilebilir alternatif diller aynı Release'e eklenir.")
            ArchivePolicyRow("Altyazıları al", "Gerçek/manüel ve kaynak otomatik altyazılar saklanır; yüzlerce anlık çeviri isteği yapılmaz.")
            ArchivePolicyRow("Tek video = tek GitHub Release", "1.8 GiB chunk, metadata, thumbnail ve kanal bağlantısı katalogda tutulur.")
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (url.isNotBlank()) {
                    onArchive(url.trim(), IngestOptions(allAudioTracks = true, subtitles = true, keepOriginal = true, createRenditions = false))
                    queued = true
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = url.isNotBlank() && !queued) { Text(if (queued) "İş kuyruğa eklendi" else "Android'de indir ve arşivle") }
            IngestProgressCard(activeWorkId)
        }
    }
}

@Composable
private fun ArchivePolicyRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = true, onCheckedChange = null)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryScreen(
    videos: List<LocalCatalogRepository.Video>,
    playlists: List<PlaylistStore.Playlist>,
    channels: List<LocalCatalogRepository.Channel>,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onOpenPlaylist: (PlaylistStore.Playlist) -> Unit,
    onCreatePlaylist: () -> Unit,
) {
    val channelMap = channels.associateBy { it.id }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp).background(Color(0xFF0F0F0F))) {
        item { Text("Kitaplık", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
        item {
            listOf(
                Triple(Icons.Default.History, "Geçmiş", "Kaldığın yerden devam et"),
                Triple(Icons.Default.Download, "İndirilenler", "Download/YTClone/Offline"),
                Triple(Icons.Default.Favorite, "Favoriler", "Kaydettiğin videolar"),
            ).forEach { (icon, title, subtitle) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Color.White, modifier = Modifier.size(28.dp)); Spacer(Modifier.size(18.dp)); Column { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Oynatma listeleri", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onCreatePlaylist) { Icon(Icons.Default.Add, "Yeni liste", tint = Color.White) }
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            Row(Modifier.fillMaxWidth().clickable { onOpenPlaylist(playlist) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(Color(0xFF272727), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PlaylistPlay, null, tint = Color.White) }
                Column(Modifier.padding(start = 12.dp)) { Text(playlist.name, color = Color.White, fontWeight = FontWeight.SemiBold); Text("${playlist.videoIds.size} video", color = Color(0xFFAAAAAA), fontSize = 12.sp) }
            }
        }
        item { Text("Arşiv", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) }
        items(videos, key = { it.id }) { video ->
            val channel = channelMap[video.channelId]
            Row(Modifier.fillMaxWidth().clickable { onOpen(video) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 120.dp, height = 68.dp).background(Color(0xFF272727))) { FileImage(video.thumbnailFile, Modifier.fillMaxSize(), ContentScale.Crop) }
                CircularFileImage(channel?.avatarFile, video.channel.take(1), 34)
                Column(Modifier.padding(start = 10.dp).weight(1f)) { Text(video.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(video.channel, color = Color(0xFFAAAAAA), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SimplePage(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize().padding(24.dp).background(Color(0xFF0F0F0F))) { Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Color(0xFFAAAAAA), modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun FileImage(file: File?, modifier: Modifier, contentScale: ContentScale) {
    val bitmap = remember(file?.absolutePath, file?.lastModified()) { file?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) } }
    if (bitmap != null) Image(bitmap.asImageBitmap(), null, modifier = modifier, contentScale = contentScale)
}

private fun formatDuration(secondsValue: Double): String {
    val total = secondsValue.toLong().coerceAtLeast(0L)
    val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatCount(value: Long): String = when {
    value >= 1_000_000 -> "%.1f Mn".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1f B".format(value / 1_000.0)
    else -> value.toString()
}
