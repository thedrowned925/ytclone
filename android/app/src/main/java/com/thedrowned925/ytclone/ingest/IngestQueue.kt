package com.thedrowned925.ytclone.ingest

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.thedrowned925.ytclone.storage.SettingsStore
import java.security.MessageDigest

object IngestQueue {
    fun enqueue(context: Context, url: String, options: IngestOptions = IngestOptions()): String {
        val request = OneTimeWorkRequestBuilder<YoutubeIngestWorker>()
            .setInputData(
                Data.Builder()
                    .putString(YoutubeIngestWorker.KEY_URL, url)
                    .putBoolean(YoutubeIngestWorker.KEY_ALL_AUDIO, options.allAudioTracks)
                    .putBoolean(YoutubeIngestWorker.KEY_SUBTITLES, options.subtitles)
                    .putBoolean(YoutubeIngestWorker.KEY_KEEP_ORIGINAL, options.keepOriginal)
                    .putBoolean(YoutubeIngestWorker.KEY_RENDITIONS, options.createRenditions)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG_INGEST)
            .build()

        // The schema version is part of the unique-work key. When the physical
        // archive representation changes (v2 = seekable Matroska), the same URL
        // is allowed to run once again so its existing Release can be repaired.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "ytclone-ingest-v${ArchiveMediaSchema.VERSION}-${sha256(url).take(20)}",
            ExistingWorkPolicy.KEEP,
            request,
        )

        val workId = request.id.toString()
        SettingsStore(context).apply {
            saveLastIngestWorkId(workId)
            saveIngestOptions(options)
        }
        return workId
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    const val TAG_INGEST = "ytclone-ingest"
}
