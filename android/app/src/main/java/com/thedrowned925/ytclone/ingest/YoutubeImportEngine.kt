package com.thedrowned925.ytclone.ingest

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class YoutubeImportEngine {
    data class ImportedMedia(
        val title: String,
        val channel: String,
        val sourceHeight: Int,
        val sourceVideo: File,
        val audioTracks: List<AudioTrack>,
        val manifestFile: File,
    )

    data class AudioTrack(
        val formatId: String,
        val language: String,
        val label: String,
        val codec: String,
        val file: File,
        val isDefault: Boolean,
    )

    private data class FormatCandidate(
        val id: String,
        val ext: String,
        val vcodec: String,
        val acodec: String,
        val height: Int,
        val tbr: Double,
        val abr: Double,
        val language: String,
        val note: String,
        val languagePreference: Int,
    )

    fun import(
        url: String,
        jobDir: File,
        processId: String,
        options: IngestOptions = IngestOptions(),
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ): ImportedMedia {
        jobDir.mkdirs()
        onProgress("metadata", 1, "Video bilgileri okunuyor")

        val metadata = readMetadata(url)
        val formats = readFormats(metadata)
        val videoFormat = chooseSourceVideo(formats)
            ?: error("Uygun video formatı bulunamadı")
        val selectedAudio = chooseAudioTracks(formats).let { tracks ->
            if (options.allAudioTracks) tracks else tracks.take(1)
        }

        onProgress("download-video", 3, "Kaynak video indiriliyor")
        val videoRequest = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("-f", videoFormat.id)
            addOption("-o", File(jobDir, "video.source.%(ext)s").absolutePath)
            addOption("--write-info-json")
            addOption("--write-thumbnail")
            if (options.subtitles) {
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", "all")
                addOption("--sub-format", "vtt/best")
            }
        }
        YoutubeDL.getInstance().execute(videoRequest, processId) { progress, _, line ->
            val mapped = 3 + (progress.coerceIn(0f, 100f) * 0.42f).roundToInt()
            onProgress("download-video", mapped, line)
        }

        val sourceVideo = findDownloaded(jobDir, "video.source.")
            ?: error("Kaynak video dosyası indirildi fakat bulunamadı")

        val downloadedAudio = mutableListOf<AudioTrack>()
        selectedAudio.forEachIndexed { index, format ->
            val base = "audio.${(index + 1).toString().padStart(3, '0')}."
            val start = 46 + ((index.toDouble() / selectedAudio.size.coerceAtLeast(1)) * 24.0).roundToInt()
            onProgress("download-audio", start, "Ses ${index + 1}/${selectedAudio.size}: ${format.language} ${format.note}")

            val request = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", format.id)
                addOption("-o", File(jobDir, "${base}%(ext)s").absolutePath)
            }
            YoutubeDL.getInstance().execute(request, "$processId-audio-$index") { progress, _, line ->
                val span = 24.0 / selectedAudio.size.coerceAtLeast(1)
                val mapped = 46 + ((index * span) + (progress.coerceIn(0f, 100f) / 100f * span)).roundToInt()
                onProgress("download-audio", mapped.coerceAtMost(70), line)
            }

            val file = findDownloaded(jobDir, base)
                ?: error("Ses parçası indirildi fakat bulunamadı: ${format.id}")
            downloadedAudio += AudioTrack(
                formatId = format.id,
                language = format.language,
                label = format.note.ifBlank { format.language },
                codec = format.acodec,
                file = file,
                isDefault = index == 0,
            )
        }

        // Some sources only expose progressive video+audio formats. If there was
        // no separate audio-only stream, preserve the source as the default audio
        // carrier for now; the catalog records this explicitly for the player.
        val manifest = buildManifest(metadata, videoFormat, sourceVideo, downloadedAudio, options)
        val manifestFile = File(jobDir, "manifest.json")
        manifestFile.writeText(manifest.toString(2))

        onProgress("download-complete", 70, "Video, sesler, altyazılar ve metadata hazır")
        return ImportedMedia(
            title = metadata.optString("title", "Adsız video"),
            channel = metadata.optString("channel", metadata.optString("uploader", "Bilinmeyen kanal")),
            sourceHeight = videoFormat.height,
            sourceVideo = sourceVideo,
            audioTracks = downloadedAudio,
            manifestFile = manifestFile,
        )
    }

    private fun readMetadata(url: String): JSONObject {
        val request = YoutubeDLRequest(url).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
        }
        val output = YoutubeDL.getInstance().execute(request).out.trim()
        val jsonLine = output.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("yt-dlp metadata JSON döndürmedi")
        return JSONObject(jsonLine)
    }

    private fun readFormats(metadata: JSONObject): List<FormatCandidate> {
        val array = metadata.optJSONArray("formats") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("format_id")
                if (id.isBlank()) continue
                add(
                    FormatCandidate(
                        id = id,
                        ext = item.optString("ext"),
                        vcodec = item.optString("vcodec", "none"),
                        acodec = item.optString("acodec", "none"),
                        height = item.optInt("height", 0),
                        tbr = item.optDoubleSafe("tbr"),
                        abr = item.optDoubleSafe("abr"),
                        language = item.optString("language").takeUnless { it.isBlank() || it == "null" } ?: "und",
                        note = item.optString("format_note").takeUnless { it == "null" } ?: "",
                        languagePreference = item.optInt("language_preference", -1),
                    ),
                )
            }
        }
    }

    private fun chooseSourceVideo(formats: List<FormatCandidate>): FormatCandidate? {
        val videoOnly = formats
            .asSequence()
            .filter { it.vcodec != "none" && it.acodec == "none" && it.height > 0 }
            .maxByOrNull { videoScore(it) }
        if (videoOnly != null) return videoOnly

        return formats
            .asSequence()
            .filter { it.vcodec != "none" && it.height > 0 }
            .maxByOrNull { videoScore(it) }
    }

    private fun videoScore(format: FormatCandidate): Long =
        (format.height.toLong() * 1_000_000L) +
            (format.tbr * 100).toLong() +
            if (format.ext == "mp4") 10_000L else 0L

    private fun chooseAudioTracks(formats: List<FormatCandidate>): List<FormatCandidate> {
        val audio = formats.filter { it.vcodec == "none" && it.acodec != "none" }
        if (audio.isEmpty()) return emptyList()

        val grouped = audio.groupBy { candidate ->
            val cleanedNote = candidate.note
                .lowercase()
                .replace(Regex("\\b(low|medium|high)\\b"), "")
                .replace(Regex("\\b\\d+(?:\\.\\d+)?k\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim(' ', ',', '-')
            "${candidate.language}|$cleanedNote"
        }

        return grouped.values
            .mapNotNull { variants ->
                variants.maxByOrNull {
                    (it.languagePreference.toLong() * 10_000_000L) +
                        (if (it.ext == "m4a") 1_000_000L else 0L) +
                        (it.abr * 1000).toLong()
                }
            }
            .sortedWith(
                compareByDescending<FormatCandidate> { it.languagePreference }
                    .thenBy { it.language }
                    .thenBy { it.note },
            )
    }

    private fun buildManifest(
        metadata: JSONObject,
        video: FormatCandidate,
        sourceVideo: File,
        audioTracks: List<AudioTrack>,
        options: IngestOptions,
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 2)
        put("source", "youtube")
        put("sourceId", metadata.optString("id"))
        put("webpageUrl", metadata.optString("webpage_url"))
        put("title", metadata.optString("title"))
        put("description", metadata.optString("description"))
        put("channel", metadata.optString("channel", metadata.optString("uploader")))
        put("channelId", metadata.optString("channel_id", metadata.optString("uploader_id")))
        put("channelUrl", metadata.optString("channel_url"))
        put("uploadDate", metadata.optString("upload_date"))
        put("durationSeconds", metadata.optDouble("duration", 0.0))
        put("thumbnail", metadata.optString("thumbnail"))
        put("ingestOptions", JSONObject().apply {
            put("allAudioTracks", options.allAudioTracks)
            put("subtitles", options.subtitles)
            put("keepOriginal", options.keepOriginal)
            put("createRenditions", options.createRenditions)
        })
        put("sourceVideo", JSONObject().apply {
            put("logicalName", sourceVideo.name)
            put("formatId", video.id)
            put("height", video.height)
            put("codec", video.vcodec)
            put("container", video.ext)
            put("containsAudio", video.acodec != "none")
        })
        put("audioTracks", JSONArray().apply {
            audioTracks.forEach { track ->
                put(JSONObject().apply {
                    put("logicalName", track.file.name)
                    put("formatId", track.formatId)
                    put("language", track.language)
                    put("label", track.label)
                    put("codec", track.codec)
                    put("default", track.isDefault)
                })
            }
        })
    }

    private fun findDownloaded(directory: File, prefix: String): File? =
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.filterNot { file ->
                file.name.endsWith(".part") ||
                    file.name.endsWith(".info.json") ||
                    file.extension.lowercase() in setOf("vtt", "srt", "webp", "jpg", "jpeg", "png", "json")
            }
            ?.maxByOrNull { it.length() }

    private fun JSONObject.optDoubleSafe(name: String): Double {
        val value = opt(name) ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
}
