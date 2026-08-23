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
import androidx.compose.material3.DarkColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val YTCloneColors: DarkColorScheme = darkColorScheme(
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
    onArchive: (String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(Tab.Home) }
    var importUrl by remember { mutableStateOf("") }

    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            importUrl = incomingUrl
            selectedTab = Tab.Add
            onIncomingUrlConsumed()
        }
    }

    MaterialTheme(colorScheme = YTCloneColors) {
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
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 10.dp)
                                .size(30.dp)
                                .background(Color(0xFF5C6BC0), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("H", fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (selectedTab) {
                    Tab.Home -> HomeScreen(onAdd = { selectedTab = Tab.Add })
                    Tab.Shorts -> SimplePage("Shorts", "Dikey videolar tam ekran kaydırmalı oynatılacak.")
                    Tab.Add -> ImportScreen(
                        url = importUrl,
                        onUrlChange = { importUrl = it },
                        onArchive = onArchive,
                    )
                    Tab.Channels -> SimplePage("Kanallar", "İçe aktarılan videolar kaynak kanalına göre otomatik gruplanacak.")
                    Tab.Library -> LibraryScreen()
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(onAdd: () -> Unit) {
    val chips = listOf("Tümü", "Oyun", "Müzik", "Dublaj", "Belgesel", "Son eklenenler")
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chips.take(4).forEach { chip ->
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF272727), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) { Text(chip, fontSize = 12.sp) }
                }
            }
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(48.dp))
                Icon(Icons.Default.VideoLibrary, null, modifier = Modifier.size(58.dp), tint = Color(0xFFAAAAAA))
                Spacer(Modifier.height(16.dp))
                Text("Kişisel YouTube arşivin", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("YouTube'dan paylaş veya bağlantı yapıştır. Video, kanal, sesler, altyazılar ve kalite sürümleri Android'de hazırlanacak.", color = Color(0xFFAAAAAA))
                Spacer(Modifier.height(22.dp))
                Button(onClick = onAdd) { Text("İlk videoyu arşivle") }
            }
        }
    }
}

@Composable
private fun ImportScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    onArchive: (String) -> Unit,
) {
    var allAudio by remember { mutableStateOf(true) }
    var subtitles by remember { mutableStateOf(true) }
    var keepOriginal by remember { mutableStateOf(true) }
    var renditions by remember { mutableStateOf(true) }
    var queued by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        item {
            Text("Video arşivle", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("YouTube uygulamasında Paylaş → YTClone da kullanabilirsin.", color = Color(0xFFAAAAAA), modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))

            OutlinedTextField(
                value = url,
                onValueChange = {
                    queued = false
                    onUrlChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Video bağlantısı") },
                placeholder = { Text("https://youtube.com/watch?v=…") },
                singleLine = true,
            )

            Spacer(Modifier.height(18.dp))
            OptionRow("Tüm ses parçalarını al", "Orijinal, dublaj ve erişilebilir alternatif diller", allAudio) { allAudio = it }
            OptionRow("Tüm altyazıları al", "Manuel ve otomatik altyazılar metadata ile saklanır", subtitles) { subtitles = it }
            OptionRow("Orijinali sakla", "Kaynak dosya Release'de korunur", keepOriginal) { keepOriginal = it }
            OptionRow("Kalite sürümlerini oluştur", "1080p / 720p / 480p / 360p; asla upscale yapılmaz", renditions) { renditions = it }

            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        onArchive(url.trim())
                        queued = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = url.isNotBlank(),
            ) {
                Text("Android'de indir ve arşivle")
            }

            if (queued) {
                Text(
                    "İş kuyruğa eklendi. Bildirimden indirme → işleme → 1.8 GiB chunk → GitHub yükleme durumunu takip edebileceksin.",
                    color = Color(0xFF81C784),
                    modifier = Modifier.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFFAAAAAA), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryScreen() {
    val rows = listOf(
        Triple(Icons.Default.History, "Geçmiş", "Kaldığın yerden devam et"),
        Triple(Icons.Default.Download, "İndirilenler", "İnternetsiz izle"),
        Triple(Icons.Default.Favorite, "Favoriler", "Kaydettiğin videolar"),
    )
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item { Text("Kitaplık", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp)) }
        items(rows) { (icon, title, subtitle) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
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
