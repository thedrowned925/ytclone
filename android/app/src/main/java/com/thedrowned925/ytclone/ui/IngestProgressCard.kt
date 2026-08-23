package com.thedrowned925.ytclone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.thedrowned925.ytclone.ingest.YoutubeIngestWorker
import java.util.UUID

private data class IngestStep(
    val key: String,
    val title: String,
)

private val ingestSteps = listOf(
    IngestStep("ytdlp-update", "yt-dlp güncelleniyor / kontrol ediliyor"),
    IngestStep("metadata", "Video ve format bilgileri alınıyor"),
    IngestStep("download-video", "4K'ya kadar her kalitenin en yüksek FPS sürümü indiriliyor"),
    IngestStep("download-audio", "Ses parçaları ve altyazılar indiriliyor"),
    IngestStep("channel", "Kanal, avatar ve banner bilgileri alınıyor"),
    IngestStep("chunk-plan", "1.8 GiB chunk planı hazırlanıyor"),
    IngestStep("upload", "Tek GitHub Release'e yükleniyor"),
    IngestStep("verify", "GitHub yüklemesi doğrulanıyor"),
    IngestStep("catalog", "Video ve kanal katalogları güncelleniyor"),
    IngestStep("cleanup", "Download/YTClone çalışma klasörü temizleniyor"),
    IngestStep("complete", "Yüklendi ve güncellendi"),
)

@Composable
fun IngestProgressCard(workId: String?) {
    if (workId.isNullOrBlank()) return
    val uuid = remember(workId) { runCatching { UUID.fromString(workId) }.getOrNull() } ?: return
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val workInfo by remember(uuid) { workManager.getWorkInfoByIdFlow(uuid) }
        .collectAsState(initial = null)

    val info = workInfo
    val state = info?.state
    val rawPercent = info?.progress?.getInt(YoutubeIngestWorker.PROGRESS_PERCENT, 0) ?: 0
    val percent = if (state == WorkInfo.State.SUCCEEDED) 100 else rawPercent.coerceIn(0, 100)
    val stage = when (state) {
        WorkInfo.State.SUCCEEDED -> "complete"
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> "failed"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "queued"
        else -> info?.progress?.getString(YoutubeIngestWorker.PROGRESS_STAGE) ?: "queued"
    }

    val detail = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Kuyrukta · ağ bağlantısı bekleniyor olabilir"
        WorkInfo.State.SUCCEEDED -> "Yüklendi, GitHub doğrulandı, katalog güncellendi ve Download/YTClone geçici medya klasörü temizlendi."
        WorkInfo.State.FAILED -> info.outputData.getString(YoutubeIngestWorker.OUTPUT_ERROR)?.let { "Hata: $it" }
            ?: "İşlem başarısız oldu"
        WorkInfo.State.CANCELLED -> "İşlem iptal edildi"
        else -> info?.progress?.getString(YoutubeIngestWorker.PROGRESS_DETAIL) ?: "İş başlatılıyor…"
    }

    val doneBytes = info?.progress?.getLong(YoutubeIngestWorker.PROGRESS_DONE_BYTES, 0L) ?: 0L
    val totalBytes = info?.progress?.getLong(YoutubeIngestWorker.PROGRESS_TOTAL_BYTES, 0L) ?: 0L
    val speed = info?.progress?.getLong(YoutubeIngestWorker.PROGRESS_SPEED_BPS, 0L) ?: 0L
    val eta = info?.progress?.getLong(YoutubeIngestWorker.PROGRESS_ETA_SECONDS, 0L) ?: 0L
    val failed = state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED
    val activeIndex = activeStepIndex(stage, percent)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 22.dp)
            .background(Color(0xFF1B1B1B), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Arşivleme durumu", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                if (failed) "Hata" else "%$percent",
                color = if (failed) Color(0xFFFF6B6B) else Color(0xFF81C784),
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = if (failed) Color(0xFFFF6B6B) else MaterialTheme.colorScheme.primary,
            trackColor = Color(0xFF353535),
        )
        Spacer(Modifier.height(10.dp))
        Text(detail, color = Color(0xFFD0D0D0), fontSize = 12.sp)

        if (totalBytes > 0L) {
            Spacer(Modifier.height(5.dp))
            Text(
                buildString {
                    append(formatBytes(doneBytes))
                    append(" / ")
                    append(formatBytes(totalBytes))
                    if (speed > 0) append("  ·  ${formatSpeed(speed)}")
                    if (eta > 0) append("  ·  ETA ${formatEta(eta)}")
                },
                color = Color(0xFF9E9E9E),
                fontSize = 11.sp,
            )
        }
        if ((info?.runAttemptCount ?: 0) > 0) {
            Text("Deneme: ${(info?.runAttemptCount ?: 0) + 1}", color = Color(0xFF888888), fontSize = 11.sp)
        }

        Spacer(Modifier.height(14.dp))
        ingestSteps.forEachIndexed { index, step ->
            val symbol: String
            val color: Color
            when {
                state == WorkInfo.State.SUCCEEDED || index < activeIndex -> {
                    symbol = "✓"
                    color = Color(0xFF81C784)
                }
                failed && index == activeIndex -> {
                    symbol = "!"
                    color = Color(0xFFFF6B6B)
                }
                index == activeIndex && state == WorkInfo.State.RUNNING -> {
                    symbol = "●"
                    color = MaterialTheme.colorScheme.primary
                }
                else -> {
                    symbol = "○"
                    color = Color(0xFF777777)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(symbol, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                Text(
                    step.title,
                    color = if (index <= activeIndex || state == WorkInfo.State.SUCCEEDED) Color.White else Color(0xFF777777),
                    fontSize = 13.sp,
                    fontWeight = if (index == activeIndex) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun activeStepIndex(stage: String, percent: Int): Int {
    val normalized = when (stage) {
        "queued" -> "ytdlp-update"
        "audio-extract" -> "download-audio"
        "published" -> "verify"
        "waiting-settings" -> "chunk-plan"
        "failed" -> when {
            percent >= 99 -> "cleanup"
            percent >= 96 -> "catalog"
            percent >= 90 -> "verify"
            percent >= 72 -> "upload"
            percent >= 71 -> "chunk-plan"
            percent >= 69 -> "channel"
            percent >= 53 -> "download-audio"
            percent >= 4 -> "download-video"
            percent >= 2 -> "metadata"
            else -> "ytdlp-update"
        }
        else -> stage
    }
    return ingestSteps.indexOfFirst { it.key == normalized }.takeIf { it >= 0 } ?: 0
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f KiB".format(bytes / 1024.0)
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024L -> "%.1f MiB/s".format(bytesPerSecond / (1024.0 * 1024.0))
    else -> "%.1f KiB/s".format(bytesPerSecond / 1024.0)
}

private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
    else -> "${seconds}s"
}
