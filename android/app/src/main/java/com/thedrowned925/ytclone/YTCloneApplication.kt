package com.thedrowned925.ytclone

import android.app.Application
import android.util.Log
import dev.ffmpegkit_maintained.ytdlp.YtDlp

class YTCloneApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        runCatching { YtDlp.init(this) }
            .onFailure { Log.e(TAG, "yt-dlp initialization failed", it) }
    }

    companion object {
        private const val TAG = "YTClone"
    }
}
