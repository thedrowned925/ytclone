package com.thedrowned925.ytclone.ingest

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

object IngestQueue {
    fun enqueue(context: Context, url: String) {
        val request = OneTimeWorkRequestBuilder<YoutubeIngestWorker>()
            .setInputData(Data.Builder().putString(YoutubeIngestWorker.KEY_URL, url).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG_INGEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "ytclone-ingest-${request.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    const val TAG_INGEST = "ytclone-ingest"
}
