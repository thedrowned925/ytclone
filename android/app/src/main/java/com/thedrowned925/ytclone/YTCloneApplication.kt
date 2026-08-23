package com.thedrowned925.ytclone

import android.app.Application
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.aria2c.Aria2c
import com.yausername.youtubedl_android.ffmpeg.FFmpeg

class YTCloneApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        runCatching { YoutubeDL.getInstance().init(this) }
            .onFailure { Log.e(TAG, "yt-dlp initialization failed", it) }

        runCatching { FFmpeg.getInstance().init(this) }
            .onFailure { Log.e(TAG, "FFmpeg initialization failed", it) }

        runCatching { Aria2c.getInstance().init(this) }
            .onFailure { Log.e(TAG, "aria2 initialization failed", it) }
    }

    companion object {
        private const val TAG = "YTClone"
    }
}
