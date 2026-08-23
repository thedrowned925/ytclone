package com.thedrowned925.ytclone.ingest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.thedrowned925.ytclone.storage.GitHubCatalogPublisher
import com.thedrowned925.ytclone.storage.GitHubReleaseUploader
import com.thedrowned925.ytclone.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class YoutubeIngestWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val notifications = appContext.getSystemService(NotificationManager::class.java)

    @Volatile
    private var lastProgressStage: String = "queued"

    @Volatile
    private var lastProgressPercent: Int = 0

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        if (url.isBlank()) return Result.failure(errorData("Video bağlantısı boş", "metadata", 0))

        ensureNotificationChannel()
        setForeground(createForegroundInfo(0, "queued", "YTClone hazırlanıyor"))

        val options = IngestOptions(
            allAudioTracks = inputData.getBoolean(KEY_ALL_AUDIO, true),
            subtitles = inputData.getBoolean(KEY_SUBTITLES, true),
            keepOriginal = true,
            createRenditions = false,
        )
        val jobId = sha256(ArchiveMediaSchema.stateKey(url)).take(24)
        val stateDir = File(applicationContext.filesDir, "ingest-state/$jobId").apply { mkdirs() }
        val jobDir = workingDirectory(jobId).apply { mkdirs() }
        val statePublishFile = File(stateDir, "publish.json")

        if (File(stateDir, COMPLETE_MARKER).exists()) {
            updateProgress("complete", 100, "Bu video daha önce tamamen yayınlandı")
            return successResult(jobId, statePublishFile)
        }

        return try {
            val settings = SettingsStore(applicationContext)
            val token = settings.gitHubToken()
            val repo = settings.mediaRepo()

            // Release daha önce yayınlandıysa indirmeye geri dönme. Sadece katalog/temizlik
            // aşamasını kaldığı yerden tamamla.
            if (statePublishFile.exists()) {
                require(!token.isNullOrBlank()) { "GitHub token ayarlı değil" }
                finalizeCatalogAndCleanup(jobId, stateDir, jobDir, repo, token, statePublishFile)
                return successResult(jobId, statePublishFile)
            }

            refreshYtDlp()

            // WorkManager seviyesinde tüm işi yeniden başlatmıyoruz. Import motoru aynı
            // kalite üzerinde yt-dlp'nin kendi retry/continue mekanizmasını kullanıyor;
            // tamamlanmış kalite ve ses dosyalarını da yeniden indirmiyor.
            val imported = loadImported(jobDir) ?: withContext(Dispatchers.IO) {
                YoutubeImportEngine().import(
                    url = url,
                    jobDir = jobDir,
                    processId = "ytclone-$jobId",
                    options = options,
                ) { stage, percent, detail ->
                    updateProgress(stage, percent.coerceIn(0, 70), detail)
                }
            }

            val manifest = JSONObject(imported.manifestFile.readText())
            var audioTracks = imported.audioTracks
            if (audioTracks.isEmpty() && imported.videoVariants.any { it.containsAudio }) {
                updateProgress("audio-extract", 70, "Kaynak videodaki varsayılan ses ayrıştırılıyor")
                val sourceWithAudio = imported.videoVariants.first { it.containsAudio }
                val audioFile = RenditionEngine(applicationContext).extractFallbackAudio(sourceWithAudio.file, jobDir)
                val fallback = YoutubeImportEngine.AudioTrack(
                    formatId = "source-audio",
                    language = "und",
                    label = "Orijinal",
                    codec = "aac",
                    file = audioFile,
                    isDefault = true,
                )
                audioTracks = listOf(fallback)
                manifest.put("audioTracks", JSONArray().put(audioTrackJson(fallback)))
            }
            if (audioTracks.isEmpty()) error("Bu kaynakta kullanılabilir bir ses parçası bulunamadı")

            manifest.put("status", "ready-to-upload")
            manifest.put(
                "catalog",
                JSONObject()
                    .put("releaseTag", GitHubCatalogPublisher.CATALOG_TAG)
                    .put("videoAsset", "catalog.json")
                    .put("channelAsset", "channels.json"),
            )
            imported.manifestFile.writeText(manifest.toString(2))

            if (token.isNullOrBlank()) {
                manifest.put("status", "waiting-for-github-token")
                imported.manifestFile.writeText(manifest.toString(2))
                updateProgress("waiting-settings", 70, "Dosyalar hazır; GitHub token bekleniyor")
                return Result.success(
                    Data.Builder()
                        .putString(OUTPUT_JOB_ID, jobId)
                        .putString(OUTPUT_STATE, "waiting-for-github-token")
                        .build(),
                )
            }

            updateProgress("chunk-plan", 71, "1.8 GiB chunk planı ve tek Release hazırlanıyor")
            val published = withContext(Dispatchers.IO) {
                GitHubReleaseUploader().publishJob(
                    jobDir = jobDir,
                    mediaManifest = manifest,
                    repoValue = repo,
                    token = token,
                ) { progress ->
                    val mapped = 72 + ((progress.percent.coerceIn(0, 100) / 100.0) * 23.0).toInt()
                    updateProgress(
                        stage = progress.stage,
                        percent = mapped.coerceAtMost(95),
                        detail = progress.detail,
                        doneBytes = progress.doneBytes,
                        totalBytes = progress.totalBytes,
                        speedBytesPerSecond = progress.speedBytesPerSecond,
                        etaSeconds = progress.etaSeconds,
                    )
                }
            }
            check(published.verified) { "GitHub Release doğrulanamadı; Downloads klasöründeki dosyalar korunuyor" }

            // Sadece küçük state dosyalarını private alana kopyala; video/ses payload'u
            // Downloads altında kalır ve doğrulamadan sonra temizlenir.
            saveStateSnapshot(jobDir, stateDir)
            check(statePublishFile.exists()) { "GitHub yayın durumu kaydedilemedi; yerel dosyalar korunuyor" }

            finalizeCatalogAndCleanup(jobId, stateDir, jobDir, repo, token, statePublishFile)
            successResult(jobId, statePublishFile)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = readableError(error)
            val failedStage = lastProgressStage.takeUnless { it == "failed" } ?: "metadata"
            val failedPercent = lastProgressPercent.coerceIn(0, 99)
            updateProgress("failed", failedPercent, "Hata: $message")

            // ÖNEMLİ: Result.retry() yok. Eski davranış tüm pipeline'ı baştan başlatıp
            // metadata -> kalite indirme -> metadata döngüsü yaratıyordu. Ağ/fragment
            // retry'ları YoutubeImportEngine içinde aynı dosya üzerinde yapılır.
            Result.failure(errorData(message, failedStage, failedPercent))
        }
    }

    private suspend fun refreshYtDlp() {
        runCatching {
            YtDlpUpdateManager.updateNow(applicationContext) { detail ->
                updateProgress("ytdlp-update", 1, detail)
            }
        }.onFailure { error ->
            // Güncelleme best-effort. Mevcut yt-dlp çalışabiliyorsa indirme devam eder;
            // gerçek hata artık tüm işi yeniden başlatmadan kullanıcıya gösterilir.
            updateProgress(
                "ytdlp-update",
                1,
                "yt-dlp güncelleme kontrolü başarısız (${readableError(error)}); mevcut sürüm deneniyor",
            )
        }
    }

    private suspend fun finalizeCatalogAndCleanup(
        jobId: String,
        stateDir: File,
        jobDir: File,
        repo: String,
        token: String,
        publishFile: File,
    ) {
        if (!File(stateDir, "manifest.json").exists()) saveStateSnapshot(jobDir, stateDir)

        val manifestFile = File(stateDir, "manifest.json")
        val channelFile = File(stateDir, "channel.json")
        require(manifestFile.exists()) { "manifest.json bulunamadı; katalog güncellenemedi" }
        require(channelFile.exists()) { "channel.json bulunamadı; kanal kataloğu güncellenemedi" }
        require(publishFile.exists()) { "publish.json bulunamadı; GitHub doğrulaması bilinmiyor" }

        val manifest = JSONObject(manifestFile.readText())
        val channel = JSONObject(channelFile.readText())
        val publish = JSONObject(publishFile.readText())
        require(publish.optBoolean("verified", false)) { "GitHub upload doğrulanmamış; yerel dosyalar silinmeyecek" }
        val releaseTag = publish.getString("primaryReleaseTag")

        val catalogMarker = File(stateDir, CATALOG_MARKER)
        if (!catalogMarker.exists()) {
            updateProgress("catalog", 96, "Video ve kanal katalogları güncelleniyor")
            val catalogResult = withContext(Dispatchers.IO) {
                GitHubCatalogPublisher().update(
                    repoValue = repo,
                    token = token,
                    mediaManifest = manifest,
                    channelSnapshot = channel,
                    videoReleaseTag = releaseTag,
                ) { detail -> updateProgress("catalog", 97, detail) }
            }
            catalogMarker.writeText(
                JSONObject()
                    .put("releaseTag", catalogResult.releaseTag)
                    .put("videoCount", catalogResult.videoCount)
                    .put("channelCount", catalogResult.channelCount)
                    .toString(2),
            )
        }

        manifest.put("status", "published")
        manifest.put("localPayloadDeleted", true)
        manifestFile.writeText(manifest.toString(2))

        updateProgress("cleanup", 99, "GitHub doğrulandı; Download/YTClone geçici medya klasörü siliniyor")
        cleanupWorkingDirectory(jobDir)

        File(stateDir, COMPLETE_MARKER).writeText(
            JSONObject()
                .put("jobId", jobId)
                .put("completedAtEpochMs", System.currentTimeMillis())
                .toString(2),
        )
        updateProgress("complete", 100, "Yüklendi, katalog güncellendi ve Downloads çalışma klasörü temizlendi")
    }

    private fun saveStateSnapshot(jobDir: File, stateDir: File) {
        stateDir.mkdirs()
        listOf("manifest.json", "channel.json", "publish.json").forEach { name ->
            val source = File(jobDir, name)
            if (source.exists() && source.length() > 0L) {
                source.copyTo(File(stateDir, name), overwrite = true)
            }
        }
    }

    private fun cleanupWorkingDirectory(jobDir: File) {
        if (jobDir.exists()) jobDir.deleteRecursively()
        val workingRoot = jobDir.parentFile
        if (workingRoot?.isDirectory == true && workingRoot.listFiles().isNullOrEmpty()) {
            workingRoot.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun workingDirectory(jobId: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloads, "YTClone/Working/$jobId")
    }

    private fun loadImported(jobDir: File): YoutubeImportEngine.ImportedMedia? {
        val manifestFile = File(jobDir, "manifest.json")
        if (!manifestFile.exists()) return null
        return runCatching {
            val manifest = JSONObject(manifestFile.readText())
            val qualityObject = manifest.optJSONObject("qualities") ?: return null
            val videos = mutableListOf<YoutubeImportEngine.VideoVariant>()
            for (key in qualityObject.keys()) {
                val item = qualityObject.optJSONObject(key) ?: continue
                val file = File(jobDir, item.optString("logicalName"))
                if (!file.exists() || file.length() <= 0L) continue
                videos += YoutubeImportEngine.VideoVariant(
                    formatId = item.optString("formatId"),
                    height = item.optInt("height"),
                    fps = item.optInt("fps", 30),
                    codec = item.optString("codec"),
                    container = item.optString("container"),
                    file = file,
                    containsAudio = item.optBoolean("containsAudio", false),
                )
            }
            if (videos.isEmpty()) return null
            val source = videos.maxWith(compareBy<YoutubeImportEngine.VideoVariant> { it.height }.thenBy { it.fps })

            val audio = mutableListOf<YoutubeImportEngine.AudioTrack>()
            val array = manifest.optJSONArray("audioTracks") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val file = File(jobDir, item.getString("logicalName"))
                if (!file.exists() || file.length() <= 0L) continue
                audio += YoutubeImportEngine.AudioTrack(
                    formatId = item.optString("formatId"),
                    language = item.optString("language", "und"),
                    label = item.optString("label", "Ses ${index + 1}"),
                    codec = item.optString("codec"),
                    file = file,
                    isDefault = item.optBoolean("default", index == 0),
                )
            }

            val channelFile = File(jobDir, manifest.optString("channelLogicalName", "channel.json"))
            if (!channelFile.exists()) return null
            YoutubeImportEngine.ImportedMedia(
                title = manifest.optString("title", "Adsız video"),
                channel = manifest.optString("channel", "Bilinmeyen kanal"),
                sourceHeight = source.height,
                sourceVideo = source.file,
                videoVariants = videos,
                audioTracks = audio,
                manifestFile = manifestFile,
                channelFile = channelFile,
            )
        }.getOrNull()
    }

    private fun successResult(jobId: String, publishFile: File): Result {
        val publish = runCatching { JSONObject(publishFile.readText()) }.getOrNull()
        return Result.success(
            Data.Builder()
                .putString(OUTPUT_JOB_ID, jobId)
                .putString(OUTPUT_STATE, "published")
                .putString(OUTPUT_VIDEO_ID, publish?.optString("videoId"))
                .putString(OUTPUT_RELEASE_TAG, publish?.optString("primaryReleaseTag"))
                .build(),
        )
    }

    private fun audioTrackJson(track: YoutubeImportEngine.AudioTrack): JSONObject = JSONObject()
        .put("logicalName", track.file.name)
        .put("formatId", track.formatId)
        .put("language", track.language)
        .put("label", track.label)
        .put("codec", track.codec)
        .put("default", track.isDefault)

    private fun updateProgress(
        stage: String,
        percent: Int,
        detail: String,
        doneBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSecond: Long = 0L,
        etaSeconds: Long = 0L,
    ) {
        val safePercent = percent.coerceIn(0, 100)
        lastProgressStage = stage
        lastProgressPercent = safePercent

        setProgressAsync(
            Data.Builder()
                .putString(PROGRESS_STAGE, stage)
                .putInt(PROGRESS_PERCENT, safePercent)
                .putString(PROGRESS_DETAIL, detail.take(700))
                .putLong(PROGRESS_DONE_BYTES, doneBytes)
                .putLong(PROGRESS_TOTAL_BYTES, totalBytes)
                .putLong(PROGRESS_SPEED_BPS, speedBytesPerSecond)
                .putLong(PROGRESS_ETA_SECONDS, etaSeconds)
                .build(),
        )
        notifications.notify(
            NOTIFICATION_ID,
            buildNotification(
                percent = safePercent,
                stage = stage,
                detail = detail,
                doneBytes = doneBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = speedBytesPerSecond,
                etaSeconds = etaSeconds,
            ),
        )
    }

    private fun createForegroundInfo(percent: Int, stage: String, detail: String): ForegroundInfo {
        val notification = buildNotification(percent, stage, detail)
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= 29 -> ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        percent: Int,
        stage: String,
        detail: String,
        doneBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSecond: Long = 0L,
        etaSeconds: Long = 0L,
    ): Notification {
        val stats = buildStats(doneBytes, totalBytes, speedBytesPerSecond, etaSeconds)
        val body = listOf(detail.take(170), stats).filter { it.isNotBlank() }.joinToString("\n")
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(
                if (stage == "upload" || stage == "verify" || stage == "catalog") {
                    android.R.drawable.stat_sys_upload
                } else {
                    android.R.drawable.stat_sys_download
                },
            )
            .setContentTitle(notificationTitle(stage))
            .setContentText(if (stats.isNotBlank()) stats else detail.take(100))
            .setStyle(Notification.BigTextStyle().bigText(body.take(700)))
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .setOngoing(stage !in setOf("complete", "failed") && percent < 100)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    private fun buildStats(done: Long, total: Long, speed: Long, eta: Long): String {
        if (total <= 0L) return ""
        return buildString {
            append(formatBytes(done))
            append(" / ")
            append(formatBytes(total))
            if (speed > 0L) append(" • ${formatSpeed(speed)}")
            if (eta > 0L) append(" • ETA ${formatEta(eta)}")
        }
    }

    private fun notificationTitle(stage: String): String = when (stage) {
        "ytdlp-update" -> "YTClone yt-dlp'yi güncelliyor"
        "metadata", "channel" -> "YTClone bilgileri alıyor"
        "download-video" -> "YTClone kalite sürümlerini indiriyor"
        "download-audio", "audio-extract" -> "YTClone sesleri indiriyor"
        "chunk-plan" -> "YTClone yüklemeyi hazırlıyor"
        "upload" -> "YTClone GitHub'a yüklüyor"
        "verify" -> "YTClone yüklemeyi doğruluyor"
        "catalog" -> "YTClone kataloğu güncelliyor"
        "cleanup" -> "YTClone Downloads klasörünü temizliyor"
        "complete" -> "YTClone arşivleme tamamlandı"
        "failed" -> "YTClone arşivleme hatası"
        else -> "YTClone video arşivliyor"
    }

    private fun ensureNotificationChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video arşivleme",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "YTClone yt-dlp güncelleme, indirme ve GitHub yükleme işlemleri"
                setShowBadge(false)
            },
        )
    }

    private fun readableError(error: Throwable): String {
        val raw = error.message?.ifBlank { error.cause?.message }.orEmpty()
        return raw
            .lineSequence()
            .filterNot { it.contains("Your yt-dlp version", ignoreCase = true) }
            .filterNot { it.contains("strongly recommended to always use the latest", ignoreCase = true) }
            .filterNot { it.contains("--no-update", ignoreCase = true) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(500)
            .ifBlank { error.javaClass.simpleName }
    }

    private fun errorData(message: String, failedStage: String, failedPercent: Int): Data = Data.Builder()
        .putString(OUTPUT_STATE, "failed")
        .putString(OUTPUT_ERROR, message.take(500))
        .putString(OUTPUT_FAILED_STAGE, failedStage)
        .putInt(OUTPUT_FAILED_PERCENT, failedPercent.coerceIn(0, 99))
        .build()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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

    companion object {
        const val KEY_URL = "url"
        const val KEY_ALL_AUDIO = "allAudio"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_KEEP_ORIGINAL = "keepOriginal"
        const val KEY_RENDITIONS = "renditions"

        const val PROGRESS_STAGE = "progressStage"
        const val PROGRESS_PERCENT = "progressPercent"
        const val PROGRESS_DETAIL = "progressDetail"
        const val PROGRESS_DONE_BYTES = "progressDoneBytes"
        const val PROGRESS_TOTAL_BYTES = "progressTotalBytes"
        const val PROGRESS_SPEED_BPS = "progressSpeedBps"
        const val PROGRESS_ETA_SECONDS = "progressEtaSeconds"
        const val OUTPUT_JOB_ID = "jobId"
        const val OUTPUT_STATE = "state"
        const val OUTPUT_VIDEO_ID = "videoId"
        const val OUTPUT_RELEASE_TAG = "releaseTag"
        const val OUTPUT_ERROR = "error"
        const val OUTPUT_FAILED_STAGE = "failedStage"
        const val OUTPUT_FAILED_PERCENT = "failedPercent"

        private const val CATALOG_MARKER = "catalog-published.json"
        private const val COMPLETE_MARKER = "complete.json"
        private const val CHANNEL_ID = "ytclone-ingest"
        private const val NOTIFICATION_ID = 9251
    }
}
