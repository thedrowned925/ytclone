package com.thedrowned925.ytclone.ingest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
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

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.trim().orEmpty()
        if (url.isBlank()) return Result.failure(errorData("Video bağlantısı boş"))

        ensureNotificationChannel()
        setForeground(createForegroundInfo(0, "YTClone hazırlanıyor"))

        val options = IngestOptions(
            allAudioTracks = inputData.getBoolean(KEY_ALL_AUDIO, true),
            subtitles = inputData.getBoolean(KEY_SUBTITLES, true),
            keepOriginal = inputData.getBoolean(KEY_KEEP_ORIGINAL, true),
            createRenditions = inputData.getBoolean(KEY_RENDITIONS, true),
        )
        val jobId = sha256(url).take(24)
        val jobDir = File(applicationContext.filesDir, "ingest/$jobId").apply { mkdirs() }

        if (File(jobDir, "publish.json").exists()) {
            updateProgress(100, "Bu video daha önce yayınlandı")
            return Result.success(
                Data.Builder()
                    .putString(OUTPUT_JOB_ID, jobId)
                    .putString(OUTPUT_STATE, "published")
                    .build(),
            )
        }

        return try {
            val importEngine = YoutubeImportEngine()
            val imported = loadImported(jobDir) ?: withContext(Dispatchers.IO) {
                importEngine.import(
                    url = url,
                    jobDir = jobDir,
                    options = options,
                ) { _, percent, detail ->
                    updateProgress(percent.coerceIn(0, 70), detail)
                }
            }

            val manifest = JSONObject(imported.manifestFile.readText())
            val renditionEngine = RenditionEngine(applicationContext)

            var audioTracks = imported.audioTracks
            if (audioTracks.isEmpty() && manifest.optJSONObject("sourceVideo")?.optBoolean("containsAudio", false) == true) {
                updateProgress(70, "Varsayılan ses parçası ayrıştırılıyor")
                val audioFile = renditionEngine.extractFallbackAudio(imported.sourceVideo, jobDir)
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
                imported.manifestFile.writeText(manifest.toString(2))
            }

            if (audioTracks.isEmpty()) {
                error("Bu kaynakta kullanılabilir bir ses parçası bulunamadı")
            }

            val renditions = if (options.createRenditions) {
                renditionEngine.createRenditions(
                    sourceVideo = imported.sourceVideo,
                    sourceHeight = imported.sourceHeight,
                    outputDir = jobDir,
                ) { height, index, total ->
                    val stage = 71 + ((index.toDouble() / total.coerceAtLeast(1)) * 10.0).toInt()
                    updateProgress(stage.coerceAtMost(81), "${height}p Android donanım kodlayıcısıyla hazırlanıyor (${index + 1}/$total)")
                }
            } else {
                emptyList()
            }

            manifest.put("qualities", JSONObject().apply {
                if (renditions.isEmpty()) {
                    put("source", JSONObject()
                        .put("logicalName", imported.sourceVideo.relativeTo(jobDir).invariantSeparatorsPath)
                        .put("height", imported.sourceHeight))
                } else {
                    renditions.forEach { rendition ->
                        put("${rendition.height}p", JSONObject()
                            .put("logicalName", rendition.file.relativeTo(jobDir).invariantSeparatorsPath)
                            .put("height", rendition.height)
                            .put("codec", "h264"))
                    }
                }
            })
            manifest.put("status", "ready-to-upload")
            imported.manifestFile.writeText(manifest.toString(2))

            val settings = SettingsStore(applicationContext)
            val token = settings.gitHubToken()
            if (token.isNullOrBlank() || !settings.mediaRepo().contains('/')) {
                manifest.put("status", "waiting-for-github-settings")
                imported.manifestFile.writeText(manifest.toString(2))
                updateProgress(100, "İşleme tamamlandı; GitHub ayarları bekleniyor")
                return Result.success(
                    Data.Builder()
                        .putString(OUTPUT_JOB_ID, jobId)
                        .putString(OUTPUT_STATE, "waiting-for-github-settings")
                        .build(),
                )
            }

            updateProgress(82, "GitHub Release hazırlanıyor")
            val canExcludeSource = !options.keepOriginal && renditions.isNotEmpty()
            val excluded = if (canExcludeSource) {
                setOf(imported.sourceVideo.relativeTo(jobDir).invariantSeparatorsPath)
            } else {
                emptySet()
            }

            val published = withContext(Dispatchers.IO) {
                GitHubReleaseUploader().publishJob(
                    jobDir = jobDir,
                    mediaManifest = manifest,
                    repoValue = settings.mediaRepo(),
                    token = token,
                    excludedLogicalNames = excluded,
                ) { percent, detail ->
                    val mapped = 82 + ((percent.coerceIn(0, 100) / 100.0) * 17.0).toInt()
                    updateProgress(mapped.coerceAtMost(99), detail)
                }
            }

            manifest.put("status", "published")
            manifest.put("storage", JSONObject()
                .put("provider", "github-release")
                .put("primaryReleaseTag", published.primaryReleaseTag)
                .put("releaseTags", JSONArray(published.releaseTags)))
            imported.manifestFile.writeText(manifest.toString(2))

            if (canExcludeSource) imported.sourceVideo.delete()
            updateProgress(100, "YTClone'a yayınlandı")

            Result.success(
                Data.Builder()
                    .putString(OUTPUT_JOB_ID, jobId)
                    .putString(OUTPUT_STATE, "published")
                    .putString(OUTPUT_VIDEO_ID, published.videoId)
                    .putString(OUTPUT_RELEASE_TAG, published.primaryReleaseTag)
                    .build(),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val message = error.message?.take(300) ?: error.javaClass.simpleName
            updateProgress(0, "Hata: $message")
            if (runAttemptCount < 2) Result.retry() else Result.failure(errorData(message))
        }
    }

    private fun loadImported(jobDir: File): YoutubeImportEngine.ImportedMedia? {
        val manifestFile = File(jobDir, "manifest.json")
        if (!manifestFile.exists()) return null

        return runCatching {
            val manifest = JSONObject(manifestFile.readText())
            val source = manifest.getJSONObject("sourceVideo")
            val sourceFile = File(jobDir, source.getString("logicalName"))
            if (!sourceFile.exists() || sourceFile.length() <= 0L) return null

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

            YoutubeImportEngine.ImportedMedia(
                title = manifest.optString("title", "Adsız video"),
                channel = manifest.optString("channel", "Bilinmeyen kanal"),
                sourceHeight = source.optInt("height", 0),
                sourceVideo = sourceFile,
                audioTracks = audio,
                manifestFile = manifestFile,
            )
        }.getOrNull()
    }

    private fun audioTrackJson(track: YoutubeImportEngine.AudioTrack): JSONObject = JSONObject()
        .put("logicalName", track.file.name)
        .put("formatId", track.formatId)
        .put("language", track.language)
        .put("label", track.label)
        .put("codec", track.codec)
        .put("default", track.isDefault)

    private fun updateProgress(percent: Int, detail: String) {
        val safePercent = percent.coerceIn(0, 100)
        setProgressAsync(
            Data.Builder()
                .putInt(PROGRESS_PERCENT, safePercent)
                .putString(PROGRESS_DETAIL, detail.take(500))
                .build(),
        )
        notifications.notify(NOTIFICATION_ID, buildNotification(safePercent, detail))
    }

    private fun createForegroundInfo(percent: Int, detail: String): ForegroundInfo {
        val notification = buildNotification(percent, detail)
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

    private fun buildNotification(percent: Int, detail: String): Notification =
        Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YTClone video arşivliyor")
            .setContentText(detail.take(90))
            .setStyle(Notification.BigTextStyle().bigText(detail.take(500)))
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOnlyAlertOnce(true)
            .setOngoing(percent in 0..99)
            .build()

    private fun ensureNotificationChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Video arşivleme",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "YTClone indirme, kalite oluşturma ve GitHub yükleme işlemleri"
            },
        )
    }

    private fun errorData(message: String): Data = Data.Builder()
        .putString(OUTPUT_STATE, "failed")
        .putString(OUTPUT_ERROR, message.take(500))
        .build()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_URL = "url"
        const val KEY_ALL_AUDIO = "allAudio"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_KEEP_ORIGINAL = "keepOriginal"
        const val KEY_RENDITIONS = "renditions"

        const val PROGRESS_PERCENT = "progressPercent"
        const val PROGRESS_DETAIL = "progressDetail"
        const val OUTPUT_JOB_ID = "jobId"
        const val OUTPUT_STATE = "state"
        const val OUTPUT_VIDEO_ID = "videoId"
        const val OUTPUT_RELEASE_TAG = "releaseTag"
        const val OUTPUT_ERROR = "error"

        private const val CHANNEL_ID = "ytclone-ingest"
        private const val NOTIFICATION_ID = 9251
    }
}
