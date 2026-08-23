package com.thedrowned925.ytclone

import android.app.Application
import android.util.Log
import com.thedrowned925.ytclone.ingest.YtDlpUpdateManager
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class YTCloneApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        runCatching { YoutubeDL.getInstance().init(this) }
            .onFailure { Log.e(TAG, "yt-dlp initialization failed", it) }

        // Do not block app startup. Every ingest job checks again before it uses
        // yt-dlp, so a failed startup refresh is harmless and self-healing.
        appScope.launch {
            runCatching { YtDlpUpdateManager.updateNow(this@YTCloneApplication) }
                .onFailure { Log.w(TAG, "yt-dlp startup update failed", it) }
        }
    }

    companion object {
        private const val TAG = "YTClone"
    }
}
