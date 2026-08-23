package com.thedrowned925.ytclone.ingest

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the extractor independent from APK releases. The bundled yt-dlp is only
 * the bootstrap copy; YTClone updates it from the official yt-dlp nightly channel.
 */
object YtDlpUpdateManager {
    private val mutex = Mutex()

    suspend fun updateNow(
        context: Context,
        onStatus: (String) -> Unit = {},
    ): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            onStatus("yt-dlp güncelliği kontrol ediliyor…")
            val youtubeDL = YoutubeDL.getInstance()
            youtubeDL.init(context.applicationContext)
            val status = youtubeDL.updateYoutubeDL(
                context.applicationContext,
                YoutubeDL.UpdateChannel.NIGHTLY,
            )
            val version = youtubeDL.versionName(context.applicationContext)
                ?: youtubeDL.version(context.applicationContext)
                ?: "güncel"
            onStatus(
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp güncellendi: $version"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp zaten güncel: $version"
                    else -> "yt-dlp hazır: $version"
                },
            )
            version
        }
    }
}
