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
    val title: String,
    val fromPercent: Int,
)

private val ingestSteps = listOf(
    IngestStep("Video bilgileri alınıyor", 0),
    IngestStep("Kaynak video indiriliyor", 3),
    IngestStep("Ses parçaları ve altyazılar alınıyor", 46),
    IngestStep("Kalite sürümleri oluşturuluyor", 71),
    IngestStep("1.8 GiB chunk planı hazırlanıyor", 82),
    IngestStep("GitHub Release'e yükleniyor", 84),
    IngestStep("Release yayınlanıyor", 98),
    IngestStep("Katalog güncelleniyor", 99),
    IngestStep("Yüklendi ve güncellendi", 100),
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
    val percent = when (state) {
        WorkInfo.State.SUCCEEDED -> 100
        else -> rawPercent.coerceIn(0, 100)
    }
    val detail = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Kuyrukta · ağ bağlantısı bekleniyor olabilir"
        WorkInfo.State.SUCCEEDED -> "Yüklendi ve katalog güncellendi. Video YTClone'da hazır."
        WorkInfo.State.FAILED -> info.outputData.getString(YoutubeIngestWorker.OUTPUT_ERROR)?.let { "Hata: $it" }
            ?: "İşlem başarısız oldu"
        WorkInfo.State.CANCELLED -> "İşlem iptal edildi"
        else -> info?.progress?.getString(YoutubeIngestWorker.PROGRESS_DETAIL)
            ?: "İş başlatılıyor…"
    }

    val failed = state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED
    val activeIndex = if (failed) {
        ingestSteps.indexOfLast { percent >= it.fromPercent }.coerceAtLeast(0)
    } else {
        ingestSteps.indexOfLast { percent >= it.fromPercent }.coerceAtLeast(0)
    }

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
        Text(detail, color = Color(0xFFBDBDBD), fontSize = 12.sp)
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
