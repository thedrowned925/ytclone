package com.thedrowned925.ytclone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thedrowned925.ytclone.catalog.LocalCatalogRepository
import com.thedrowned925.ytclone.ingest.IngestOptions
import com.thedrowned925.ytclone.storage.SettingsStore

private val YTCloneColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF0033),
    background = Color(0xFF0F0F0F),
    surface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFF272727),
    onBackground = Color.White,
    onSurface = Color.White,
)

private enum class Tab(val title: String, val icon: ImageVector) {
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
) {
    val context = LocalContext.current
    val catalogRepository = remember { LocalCatalogRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    var catalog by remember { mutableStateOf(catalogRepository.listVideos()) }
    var selectedTab by remember { mutableStateOf(Tab.Home) }
    var selectedVideo by remember { mutableStateOf<LocalCatalogRepository.Video?>(null) }
    var importUrl by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var activeWorkId by remember { mutableStateOf(settingsStore.lastIngestWorkId()) }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            importUrl = incomingUrl
            selectedTab = Tab.Add
            onIncomingUrlConsumed()
        }
    }

    LaunchedEffect(selectedTab, selectedVideo) {
        if (selectedTab == Tab.Home && selectedVideo == null) {
            catalog = catalogRepository.listVideos()
        }
    }

    MaterialTheme(colorScheme = YTCloneColors) {
        if (selectedVideo != null) {
            PlayerScreen(video = selectedVideo!!, onBack = { selectedVideo = null })
            return@MaterialTheme
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(width = 32.dp, height = 22.dp)
                                    .background(Color(0xFFFF0033), RoundedCornerShape(7.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text("YTClone", fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp)
                        }
                    },
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Default.Cast, "Yayınla") }
                        IconButton(onClick = {}) { Icon(Icons.Default.Notifications, "Bildirimler") }
                        IconButton(onClick = {}) { Icon(Icons.Default.Search, "Ara") }
                        IconButton(onClick = { settingsOpen = true }) {
                            Box(
                                modifier = Modifier.size(30.dp).background(Color(0xFF5C6BC0), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text("H", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
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
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (selectedTab) {
                    Tab.Home -> HomeScreen(catalog, onOpen = { selectedVideo = it }, onAdd = { selectedTab = Tab.Add })
                    Tab.Shorts -> SimplePage("Shorts", "Dikey videolar tam ekran kaydırmalı oynatılacak.")
                    Tab.Add -> ImportScreen(
                        url = importUrl,
                        onUrlChange = { importUrl = it },
                        activeWorkId = activeWorkId,
                        onArchive = { url, options -> activeWorkId = onArchive(url, options) },
                        storageReady = tokenConfigured,
                    )
                    Tab.Channels -> SimplePage("Kanallar", "İçe aktarılan videolar kaynak kanalına göre otomatik gruplanacak.")
                    Tab.Library -> LibraryScreen(catalog, onOpen = { selectedVideo = it })
                }
            }
        }

        if (settingsOpen) {
            SettingsDialog(
                initialRepo = mediaRepo,
                tokenConfigured = tokenConfigured,
                onDismiss = { settingsOpen = false },
                onSave = onSaveStorageSettings,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    videos: List<LocalCatalogRepository.Video>,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
    onAdd: () -> Unit,
) {
    val chips = listOf("Tümü", "Oyun", "Müzik", "Dublaj")
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.forEach { chip ->
                    Box(
                        modifier = Modifier.background(Color(0xFF272727), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text(chip, fontSize = 12.sp) }
                }
            }
        }

        if (videos.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(48.dp))
                    Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(58.dp), tint = Color(0xFFAAAAAA))
                    Spacer(Modifier.height(16.dp))
                    Text("Kişisel YouTube arşivin", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "YouTube'dan paylaş veya bağlantı yapıştır. 4K'ya kadar her çözünürlüğün mevcut en yüksek FPS sürümü, sesler ve altyazılar arşivlenecek.",
                        color = Color(0xFFAAAAAA),
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = onAdd) { Text("İlk videoyu arşivle") }
                }
            }
        } else {
            items(videos, key = { it.id }) { video -> VideoCard(video, onOpen) }
        }
    }
}

@Composable
private fun VideoCard(video: LocalCatalogRepository.Video, onOpen: (LocalCatalogRepository.Video) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(210.dp).background(Color(0xFF202124)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { onOpen(video) }, modifier = Modifier.size(70.dp)) {
                Icon(Icons.Default.PlayArrow, "Oynat", modifier = Modifier.size(52.dp), tint = Color.White)
            }
            Text(
                formatDuration(video.durationSeconds),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                fontSize = 11.sp,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                modifier = Modifier.size(38.dp).background(Color(0xFF5C6BC0), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(video.channel.take(1).uppercase(), fontWeight = FontWeight.Bold) }
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(video.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${video.channel} · ${video.qualities.size} kalite · ${video.audioTracks.size} ses · ${video.status}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text("Video arşivle", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text(
                "YouTube uygulamasında Paylaş → YTClone da kullanabilirsin.",
                color = Color(0xFFAAAAAA),
                modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
            )
            if (!storageReady) {
                Text(
                    "GitHub token ayarlı değil. Sağ üstteki H profilinden token'ı bir kez kaydet.",
                    color = Color(0xFFFFB74D),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            OutlinedTextField(
                value = url,
                onValueChange = { queued = false; onUrlChange(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video bağlantısı") },
                placeholder = { Text("https://youtube.com/watch?v=…") },
                singleLine = true,
            )
            Spacer(Modifier.height(18.dp))
            ArchivePolicyRow(
                "4K'ya kadar tüm kaliteler",
                "Her çözünürlükten tek sürüm alınır: 1080p60 varsa 1080p60, yoksa normal 1080p. 30 FPS ve 60 FPS ayrı kopyalar olarak tutulmaz.",
            )
            ArchivePolicyRow(
                "Tüm ses parçalarını al",
                "Orijinal, dublaj ve erişilebilir alternatif diller aynı video Release'ine eklenir.",
            )
            ArchivePolicyRow(
                "Tüm altyazıları al",
                "Manuel ve otomatik altyazılar metadata ile aynı Release'te saklanır.",
            )
            ArchivePolicyRow(
                "Tek video = tek GitHub Release",
                "1.8 GiB chunk, kanal metadata/avatar/banner ve katalog aynı sistemde tutulur. GitHub doğrulamasından sonra Download/YTClone çalışma klasörü silinir.",
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        onArchive(
                            url.trim(),
                            IngestOptions(
                                allAudioTracks = true,
                                subtitles = true,
                                keepOriginal = true,
                                createRenditions = false,
                            ),
                        )
                        queued = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank() && !queued,
            ) {
                Text(if (queued) "İş kuyruğa eklendi" else "Android'de indir ve arşivle")
            }

            IngestProgressCard(activeWorkId)
        }
    }
}

@Composable
private fun ArchivePolicyRow(title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = true, onCheckedChange = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryScreen(
    videos: List<LocalCatalogRepository.Video>,
    onOpen: (LocalCatalogRepository.Video) -> Unit,
) {
    val rows = listOf(
        Triple(Icons.Default.History, "Geçmiş", "Kaldığın yerden devam et"),
        Triple(Icons.Default.Download, "İndirilenler", "İnternetsiz izle"),
        Triple(Icons.Default.Favorite, "Favoriler", "Kaydettiğin videolar"),
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Kitaplık", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
        items(rows) { (icon, title, subtitle) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.size(18.dp))
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp)
                }
            }
        }
        item { Text("Videolar", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)) }
        items(videos, key = { it.id }) { video ->
            Button(onClick = { onOpen(video) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SimplePage(title: String, text: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(text, color = Color(0xFFAAAAAA))
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
