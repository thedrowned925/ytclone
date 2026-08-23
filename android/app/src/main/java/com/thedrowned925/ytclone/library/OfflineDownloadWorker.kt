package com.thedrowned925.ytclone.library

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thedrowned925.ytclone.R
import com.thedrowned925.ytclone.storage.GitHubReleaseReader
import com.thedrowned925.ytclone.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

object OfflineDownloadQueue {
    fun enqueue(
        context: Context,
        videoId: String,
        title: String,
        releaseTag: String,
        qualityLogicalName: String,
        audioLogicalName: String?,
        subtitleLogicalName: String?,
    ): String {
        val data = Data.Builder()
            .putString(OfflineDownloadWorker.KEY_VIDEO_ID, videoId)
            .putString(OfflineDownloadWorker.KEY_TITLE, title)
            .putString(OfflineDownloadWorker.KEY_RELEASE_TAG, releaseTag)
            .putString(OfflineDownloadWorker.KEY_QUALITY, qualityLogicalName)
            .putString(OfflineDownloadWorker.KEY_AUDIO, audioLogicalName)
            .putString(OfflineDownloadWorker.KEY_SUBTITLE, subtitleLogicalName)
            .build()
        val request = OneTimeWorkRequestBuilder<OfflineDownloadWorker>().setInputData(data).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ytclone-offline-$videoId-${qualityLogicalName.hashCode()}",
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return request.id.toString()
    }
}

class OfflineDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID).orEmpty()
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "YTClone video" }
        val releaseTag = inputData.getString(KEY_RELEASE_TAG).orEmpty()
        val quality = inputData.getString(KEY_QUALITY).orEmpty()
        val audio = inputData.getString(KEY_AUDIO).orEmpty().takeIf(String::isNotBlank)
        val subtitle = inputData.getString(KEY_SUBTITLE).orEmpty().takeIf(String::isNotBlank)
        if (videoId.isBlank() || releaseTag.isBlank() || quality.isBlank()) return@withContext Result.failure()

        val settings = SettingsStore(applicationContext)
        val token = settings.gitHubToken() ?: return@withContext Result.failure()
        val repo = settings.mediaRepo().takeIf { it.contains('/') } ?: return@withContext Result.failure()
        val reader = GitHubReleaseReader(repo, token)
        val logicalNames = listOfNotNull(quality, audio, subtitle).distinct()
        val logicalFiles = runCatching { logicalNames.map { reader.logicalFile(releaseTag, it) } }
            .getOrElse { return@withContext Result.failure() }
        val totalBytes = logicalFiles.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        val written = AtomicLong(0L)

        setForeground(foregroundInfo(title, 0, totalBytes, 0L))
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "YTClone/Offline/$videoId",
        ).apply { mkdirs() }

        try {
            logicalFiles.forEach { logical ->
                val target = File(root, logical.logicalName)
                if (target.exists() && target.length() == logical.sizeBytes) {
                    written.addAndGet(logical.sizeBytes)
                    return@forEach
                }
                val temp = File(root, logical.logicalName + ".part")
                temp.parentFile?.mkdirs()
                FileOutputStream(temp, false).use { output ->
                    logical.parts.sortedBy { it.offset }.forEach { part ->
                        reader.openPartSlice(part, 0L, part.sizeBytes).use { slice ->
                            val buffer = ByteArray(1024 * 1024)
                            while (true) {
                                val read = slice.input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                val now = written.addAndGet(read.toLong())
                                val percent = ((now * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                setProgress(Data.Builder().putInt("percent", percent).putLong("bytes", now).putLong("total", totalBytes).build())
                                if (now % (8L * 1024L * 1024L) < read) setForeground(foregroundInfo(title, percent, totalBytes, now))
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }
            setForeground(foregroundInfo(title, 100, totalBytes, totalBytes))
            Result.success(Data.Builder().putString("directory", root.absolutePath).build())
        } catch (_: Throwable) {
            Result.failure()
        }
    }

    private fun foregroundInfo(title: String, percent: Int, total: Long, done: Long): ForegroundInfo {
        ensureChannel()
        val text = if (percent >= 100) "İndirme tamamlandı" else "$percent% • ${formatBytes(done)} / ${formatBytes(total)}"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(percent < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "YTClone indirmeleri", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L * 1024L -> "%.2f GiB".format(value.toDouble() / (1024.0 * 1024.0 * 1024.0))
        value >= 1024L * 1024L -> "%.1f MiB".format(value.toDouble() / (1024.0 * 1024.0))
        else -> "%.0f KiB".format(value.toDouble() / 1024.0)
    }

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_TITLE = "title"
        const val KEY_RELEASE_TAG = "releaseTag"
        const val KEY_QUALITY = "quality"
        const val KEY_AUDIO = "audio"
        const val KEY_SUBTITLE = "subtitle"
        private const val CHANNEL = "ytclone-offline-downloads"
        private const val NOTIFICATION_ID = 2905
    }
}
