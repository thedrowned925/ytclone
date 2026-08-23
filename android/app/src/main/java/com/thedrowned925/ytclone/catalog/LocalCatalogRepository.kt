package com.thedrowned925.ytclone.catalog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LocalCatalogRepository(context: Context) {
    data class Quality(
        val id: String,
        val height: Int,
        val fps: Int,
        val logicalName: String,
        val localFile: File?,
    )

    data class Audio(
        val id: String,
        val label: String,
        val language: String,
        val logicalName: String,
        val localFile: File?,
        val isDefault: Boolean,
    )

    data class Video(
        val id: String,
        val title: String,
        val channel: String,
        val durationSeconds: Double,
        val status: String,
        val thumbnailFile: File?,
        val primaryReleaseTag: String?,
        val qualities: List<Quality>,
        val audioTracks: List<Audio>,
    )

    private val ingestRoot = File(context.filesDir, "ingest")

    fun listVideos(): List<Video> {
        if (!ingestRoot.exists()) return emptyList()
        return ingestRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readVideo)
            ?.sortedByDescending { video -> File(ingestRoot, video.id).lastModified() }
            ?.toList()
            ?: emptyList()
    }

    private fun readVideo(jobDir: File): Video? = runCatching {
        val manifestFile = File(jobDir, "manifest.json")
        if (!manifestFile.exists()) return null
        val json = JSONObject(manifestFile.readText())

        val qualities = mutableListOf<Quality>()
        val qualityJson = json.optJSONObject("qualities") ?: JSONObject()
        for (key in qualityJson.keys()) {
            val item = qualityJson.optJSONObject(key) ?: continue
            val logicalName = item.optString("logicalName")
            if (logicalName.isBlank()) continue
            val file = File(jobDir, logicalName).takeIf { it.exists() && it.length() > 0L }
            qualities += Quality(
                id = key,
                height = item.optInt("height", key.takeWhile { it.isDigit() }.toIntOrNull() ?: 0),
                fps = item.optInt("fps", 30).coerceAtLeast(1),
                logicalName = logicalName,
                localFile = file,
            )
        }

        if (qualities.isEmpty()) {
            json.optJSONObject("sourceVideo")?.let { source ->
                val logicalName = source.optString("logicalName")
                if (logicalName.isNotBlank()) {
                    val file = File(jobDir, logicalName).takeIf { it.exists() && it.length() > 0L }
                    qualities += Quality(
                        id = "source",
                        height = source.optInt("height"),
                        fps = source.optInt("fps", 30),
                        logicalName = logicalName,
                        localFile = file,
                    )
                }
            }
        }

        val audioTracks = mutableListOf<Audio>()
        val audioJson = json.optJSONArray("audioTracks") ?: JSONArray()
        for (index in 0 until audioJson.length()) {
            val item = audioJson.optJSONObject(index) ?: continue
            val logicalName = item.optString("logicalName")
            if (logicalName.isBlank()) continue
            val file = File(jobDir, logicalName).takeIf { it.exists() && it.length() > 0L }
            audioTracks += Audio(
                id = item.optString("formatId", "audio-$index"),
                label = item.optString("label", "Ses ${index + 1}"),
                language = item.optString("language", "und"),
                logicalName = logicalName,
                localFile = file,
                isDefault = item.optBoolean("default", index == 0),
            )
        }

        val thumbnail = jobDir.listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    file.name.startsWith("video.") &&
                    file.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png")
            }

        Video(
            id = jobDir.name,
            title = json.optString("title", "Adsız video"),
            channel = json.optString("channel", "Bilinmeyen kanal"),
            durationSeconds = json.optDouble("durationSeconds", 0.0),
            status = json.optString("status", "processing"),
            thumbnailFile = thumbnail,
            primaryReleaseTag = json.optJSONObject("storage")?.optString("primaryReleaseTag")?.takeIf(String::isNotBlank),
            qualities = qualities.sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.fps }.thenBy { it.id }),
            audioTracks = audioTracks.sortedWith(compareByDescending<Audio> { it.isDefault }.thenBy { it.language }.thenBy { it.label }),
        )
    }.getOrNull()
}
