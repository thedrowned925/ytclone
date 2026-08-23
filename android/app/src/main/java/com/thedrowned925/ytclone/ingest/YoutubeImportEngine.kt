package com.thedrowned925.ytclone.ingest

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.math.roundToInt

class YoutubeImportEngine {
    data class ImportedMedia(
        val title: String,
        val channel: String,
        val sourceHeight: Int,
        val sourceVideo: File,
        val videoVariants: List<VideoVariant>,
        val audioTracks: List<AudioTrack>,
        val manifestFile: File,
        val channelFile: File,
    )

    data class VideoVariant(
        val formatId: String,
        val height: Int,
        val fps: Int,
        val codec: String,
        val container: String,
        val file: File,
        val containsAudio: Boolean,
    ) {
        val id: String get() = "${height}p${fps.takeIf { it > 30 } ?: ""}"
        val displayLabel: String get() = id
    }

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
        val fps: Int,
        val tbr: Double,
        val abr: Double,
        val filesize: Long,
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
        onProgress("metadata", 1, "Video bilgileri ve kalite listesi okunuyor")

        val metadata = readMetadata(url, "$processId-metadata")
        val formats = readFormats(metadata)
        val selectedVideos = chooseVideoVariants(formats)
        require(selectedVideos.isNotEmpty()) { "4K veya altında uygun video formatı bulunamadı" }

        val selectedAudio = chooseAudioTracks(formats).let { tracks ->
            if (options.allAudioTracks) tracks else tracks.take(1)
        }

        downloadSidecars(
            url = url,
            jobDir = jobDir,
            processId = "$processId-sidecars",
            subtitles = options.subtitles,
            onProgress = onProgress,
        )

        val downloadedVideos = mutableListOf<VideoVariant>()
        selectedVideos.forEachIndexed { index, format ->
            val quality = qualityLabel(format.height, format.fps)
            val base = "video.$quality.${sanitize(format.id)}."
            val start = 4 + ((index.toDouble() / selectedVideos.size) * 48.0).roundToInt()
            val existing = findDownloaded(jobDir, base)

            if (existing != null) {
                onProgress(
                    "download-video",
                    start,
                    "$quality zaten tamamlanmış; tekrar indirilmeden devam ediliyor (${index + 1}/${selectedVideos.size})",
                )
                downloadedVideos += VideoVariant(
                    formatId = format.id,
                    height = format.height,
                    fps = format.fps,
                    codec = format.vcodec,
                    container = existing.extension.ifBlank { format.ext },
                    file = existing,
                    containsAudio = format.acodec != "none",
                )
                return@forEachIndexed
            }

            onProgress(
                "download-video",
                start,
                "$quality indiriliyor (${index + 1}/${selectedVideos.size})",
            )

            val request = YoutubeDLRequest(url).apply {
                commonOptions()
                addOption("-f", format.id)
                addOption("-o", File(jobDir, "${base}%(ext)s").absolutePath)
            }

            try {
                executeChecked(request, "$processId-video-$index") { progress, eta, line ->
                    val span = 48.0 / selectedVideos.size.coerceAtLeast(1)
                    val mapped = 4 + ((index * span) + (progress.coerceIn(0f, 100f) / 100f * span)).roundToInt()
                    val etaText = if (eta > 0) " • kalan ~${formatEta(eta)}" else ""
                    onProgress(
                        "download-video",
                        mapped.coerceAtMost(52),
                        "$quality • ${progress.roundToInt()}%$etaText • ${cleanLine(line)}",
                    )
                }
            } catch (error: Throwable) {
                throw IllegalStateException("$quality indirilirken hata: ${shortError(error)}", error)
            }

            val file = findDownloaded(jobDir, base)
                ?: error("$quality indirme tamamlandı ancak dosya bulunamadı")
            downloadedVideos += VideoVariant(
                formatId = format.id,
                height = format.height,
                fps = format.fps,
                codec = format.vcodec,
                container = file.extension.ifBlank { format.ext },
                file = file,
                containsAudio = format.acodec != "none",
            )
        }

        val downloadedAudio = mutableListOf<AudioTrack>()
        selectedAudio.forEachIndexed { index, format ->
            val base = "audio.${(index + 1).toString().padStart(3, '0')}.${sanitize(format.language)}.${sanitize(format.id)}."
            val start = 53 + ((index.toDouble() / selectedAudio.size.coerceAtLeast(1)) * 15.0).roundToInt()
            val existing = findDownloaded(jobDir, base)

            if (existing != null) {
                onProgress(
                    "download-audio",
                    start,
                    "Ses ${index + 1}/${selectedAudio.size} zaten tamamlanmış; tekrar indirilmeden devam ediliyor",
                )
                downloadedAudio += AudioTrack(
                    formatId = format.id,
                    language = format.language,
                    label = format.note.ifBlank { format.language },
                    codec = format.acodec,
                    file = existing,
                    isDefault = index == 0,
                )
                return@forEachIndexed
            }

            onProgress(
                "download-audio",
                start,
                "Ses ${index + 1}/${selectedAudio.size}: ${format.language} ${format.note}",
            )

            val request = YoutubeDLRequest(url).apply {
                commonOptions()
                addOption("-f", format.id)
                addOption("-o", File(jobDir, "${base}%(ext)s").absolutePath)
            }

            try {
                executeChecked(request, "$processId-audio-$index") { progress, eta, line ->
                    val span = 15.0 / selectedAudio.size.coerceAtLeast(1)
                    val mapped = 53 + ((index * span) + (progress.coerceIn(0f, 100f) / 100f * span)).roundToInt()
                    val etaText = if (eta > 0) " • kalan ~${formatEta(eta)}" else ""
                    onProgress(
                        "download-audio",
                        mapped.coerceAtMost(68),
                        "Ses ${index + 1}/${selectedAudio.size} • ${progress.roundToInt()}%$etaText • ${cleanLine(line)}",
                    )
                }
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "Ses ${index + 1}/${selectedAudio.size} (${format.language}) indirilirken hata: ${shortError(error)}",
                    error,
                )
            }

            val file = findDownloaded(jobDir, base)
                ?: error("Ses parçası indirme tamamlandı ancak dosya bulunamadı: ${format.id}")
            downloadedAudio += AudioTrack(
                formatId = format.id,
                language = format.language,
                label = format.note.ifBlank { format.language },
                codec = format.acodec,
                file = file,
                isDefault = index == 0,
            )
        }

        onProgress("channel", 69, "Kanal profili, avatarı ve banner bilgileri hazırlanıyor")
        val channelSnapshot = buildChannelSnapshot(metadata, "$processId-channel", jobDir)
        val channelFile = File(jobDir, "channel.json").apply { writeText(channelSnapshot.toString(2)) }

        val manifest = buildManifest(metadata, downloadedVideos, downloadedAudio, options, jobDir, channelFile)
        val manifestFile = File(jobDir, "manifest.json")
        manifestFile.writeText(manifest.toString(2))

        onProgress(
            "download-complete",
            70,
            "${downloadedVideos.size} kalite, ${downloadedAudio.size} ses parçası ve altyazılar hazır",
        )
        val source = downloadedVideos.maxWith(compareBy<VideoVariant> { it.height }.thenBy { it.fps })
        return ImportedMedia(
            title = metadata.optString("title", "Adsız video"),
            channel = metadata.optString("channel", metadata.optString("uploader", "Bilinmeyen kanal")),
            sourceHeight = source.height,
            sourceVideo = source.file,
            videoVariants = downloadedVideos,
            audioTracks = downloadedAudio,
            manifestFile = manifestFile,
            channelFile = channelFile,
        )
    }

    private fun downloadSidecars(
        url: String,
        jobDir: File,
        processId: String,
        subtitles: Boolean,
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ) {
        onProgress("metadata", 3, "Thumbnail, metadata${if (subtitles) " ve altyazılar" else ""} hazırlanıyor")
        val request = YoutubeDLRequest(url).apply {
            commonOptions()
            addOption("--skip-download")
            addOption("--write-info-json")
            addOption("--write-thumbnail")
            if (subtitles) {
                addOption("--write-subs")
                addOption("--write-auto-subs")
                addOption("--sub-langs", "all")
                addOption("--sub-format", "vtt/best")
            }
            addOption("-o", File(jobDir, "source.%(ext)s").absolutePath)
        }
        try {
            executeChecked(request, processId) { _, _, _ -> }
        } catch (error: Throwable) {
            throw IllegalStateException("Thumbnail/altyazı hazırlanırken hata: ${shortError(error)}", error)
        }
    }

    private fun YoutubeDLRequest.commonOptions() {
        addOption("--no-playlist")
        addOption("--no-update")
        addOption("--remote-components", "ejs:github")
        addOption("--no-warnings")
        addOption("--continue")
        addOption("--newline")
        addOption("--retries", "10")
        addOption("--fragment-retries", "10")
        addOption("--extractor-retries", "5")
        addOption("--file-access-retries", "3")
        addOption("--retry-sleep", "2")
        addOption("--socket-timeout", "30")
        addOption("--concurrent-fragments", "4")
    }

    private fun readMetadata(url: String, processId: String): JSONObject {
        val request = YoutubeDLRequest(url).apply {
            commonOptions()
            addOption("--dump-single-json")
            addOption("--skip-download")
        }
        return try {
            executeJson(request, processId)
        } catch (error: Throwable) {
            throw IllegalStateException("Video bilgileri alınamadı: ${shortError(error)}", error)
        }
    }

    private fun buildChannelSnapshot(metadata: JSONObject, processId: String, jobDir: File): JSONObject {
        val channelUrl = metadata.optString("channel_url").takeIf { it.startsWith("http") }
        val channelMetadata = channelUrl?.let { url ->
            runCatching {
                val request = YoutubeDLRequest(url).apply {
                    commonOptions()
                    addOption("--dump-single-json")
                    addOption("--flat-playlist")
                    addOption("--playlist-end", "1")
                    addOption("--skip-download")
                }
                executeJson(request, processId)
            }.getOrNull()
        }

        val merged = channelMetadata ?: metadata
        val thumbnails = merged.optJSONArray("thumbnails") ?: JSONArray()
        val avatar = chooseChannelImage(thumbnails, wantBanner = false)
        val banner = chooseChannelImage(thumbnails, wantBanner = true)
        val avatarFile = avatar?.let { downloadImage(it, File(jobDir, "channel-avatar${extensionFromUrl(it)}")) }
        val bannerFile = banner?.let { downloadImage(it, File(jobDir, "channel-banner${extensionFromUrl(it)}")) }

        return JSONObject()
            .put("schemaVersion", 1)
            .put("channelId", metadata.optString("channel_id", metadata.optString("uploader_id")))
            .put("name", metadata.optString("channel", metadata.optString("uploader", "Bilinmeyen kanal")))
            .put("url", channelUrl ?: metadata.optString("uploader_url"))
            .put("handle", metadata.optString("uploader_id"))
            .put("description", merged.optString("description"))
            .put("subscriberCount", merged.optLong("channel_follower_count", metadata.optLong("channel_follower_count", 0L)))
            .put("avatarLogicalName", avatarFile?.name ?: JSONObject.NULL)
            .put("bannerLogicalName", bannerFile?.name ?: JSONObject.NULL)
            .put("source", "youtube")
    }

    private fun executeJson(request: YoutubeDLRequest, processId: String): JSONObject {
        val response = YoutubeDL.getInstance().execute(request, processId, null)
        if (response.exitCode != 0) {
            error("yt-dlp metadata hatası: ${response.err.ifBlank { "exit=${response.exitCode}" }}")
        }
        val output = response.out.trim()
        val jsonLine = output.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: error("yt-dlp metadata JSON döndürmedi")
        return JSONObject(jsonLine)
    }

    private fun executeChecked(
        request: YoutubeDLRequest,
        processId: String,
        callback: (progress: Float, etaSeconds: Long, line: String) -> Unit,
    ) {
        val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            callback(progress, eta, line)
        }
        if (response.exitCode != 0) {
            val detail = response.err.takeIf { it.isNotBlank() }
                ?: response.out.takeIf { it.isNotBlank() }
                ?: "exit=${response.exitCode}"
            error("yt-dlp işlemi başarısız: $detail")
        }
    }

    private fun readFormats(metadata: JSONObject): List<FormatCandidate> {
        val array = metadata.optJSONArray("formats") ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("format_id")
                if (id.isBlank()) continue
                val fps = item.optDoubleSafe("fps").roundToInt().takeIf { it > 0 } ?: 30
                add(
                    FormatCandidate(
                        id = id,
                        ext = item.optString("ext"),
                        vcodec = item.optString("vcodec", "none"),
                        acodec = item.optString("acodec", "none"),
                        height = item.optInt("height", 0),
                        fps = fps,
                        tbr = item.optDoubleSafe("tbr"),
                        abr = item.optDoubleSafe("abr"),
                        filesize = item.optLongSafe("filesize").takeIf { it > 0 } ?: item.optLongSafe("filesize_approx"),
                        language = item.optString("language").takeUnless { it.isBlank() || it == "null" } ?: "und",
                        note = item.optString("format_note").takeUnless { it == "null" } ?: "",
                        languagePreference = item.optInt("language_preference", -1),
                    ),
                )
            }
        }
    }

    private fun chooseVideoVariants(formats: List<FormatCandidate>): List<FormatCandidate> {
        val allowed = formats.filter {
            it.vcodec != "none" && it.height in 1..MAX_HEIGHT
        }
        return allowed
            .groupBy { it.height }
            .values
            .mapNotNull { sameResolution ->
                val highestFps = sameResolution.maxOfOrNull { it.fps } ?: return@mapNotNull null
                sameResolution
                    .filter { it.fps == highestFps }
                    .maxByOrNull(::videoScore)
            }
            .sortedByDescending { it.height }
    }

    private fun videoScore(format: FormatCandidate): Long =
        (if (format.acodec == "none") 10_000_000_000L else 0L) +
            (if (format.ext == "mp4") 1_000_000_000L else 0L) +
            (format.tbr * 10_000).toLong() +
            format.filesize.coerceAtLeast(0L) / 1024L

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
        videos: List<VideoVariant>,
        audioTracks: List<AudioTrack>,
        options: IngestOptions,
        jobDir: File,
        channelFile: File,
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 4)
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
        put("thumbnailLogicalName", findThumbnail(jobDir)?.name ?: "")
        put("maxArchivedHeight", MAX_HEIGHT)
        put("qualityPolicy", "one-highest-fps-stream-per-resolution")
        put("channelLogicalName", channelFile.name)
        put("ingestOptions", JSONObject().apply {
            put("allAudioTracks", options.allAudioTracks)
            put("subtitles", options.subtitles)
            put("keepOriginal", true)
            put("createRenditions", false)
            put("nativeAllQualities", true)
        })

        val source = videos.maxWith(compareBy<VideoVariant> { it.height }.thenBy { it.fps })
        put("sourceVideo", videoJson(source))
        put("qualities", JSONObject().apply {
            videos.forEach { video -> put(video.id, videoJson(video)) }
        })
        put("audioTracks", JSONArray().apply {
            audioTracks.forEach { track ->
                put(JSONObject()
                    .put("logicalName", track.file.name)
                    .put("formatId", track.formatId)
                    .put("language", track.language)
                    .put("label", track.label)
                    .put("codec", track.codec)
                    .put("default", track.isDefault))
            }
        })
        put("subtitles", JSONArray().apply {
            jobDir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in setOf("vtt", "srt", "ass") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    put(JSONObject()
                        .put("logicalName", file.name)
                        .put("language", subtitleLanguage(file.name)))
                }
        })
        put("status", "downloaded")
    }

    private fun videoJson(video: VideoVariant): JSONObject = JSONObject()
        .put("logicalName", video.file.name)
        .put("formatId", video.formatId)
        .put("height", video.height)
        .put("fps", video.fps)
        .put("codec", video.codec)
        .put("container", video.container)
        .put("containsAudio", video.containsAudio)

    private fun chooseChannelImage(thumbnails: JSONArray, wantBanner: Boolean): String? {
        val items = buildList {
            for (index in 0 until thumbnails.length()) {
                val item = thumbnails.optJSONObject(index) ?: continue
                val url = item.optString("url")
                if (!url.startsWith("http")) continue
                val id = item.optString("id").lowercase()
                val width = item.optInt("width", 0)
                val height = item.optInt("height", 0)
                val ratio = if (height > 0) width.toDouble() / height else 0.0
                add(Triple(url, id, Triple(width, height, ratio)))
            }
        }
        if (items.isEmpty()) return null
        val explicit = items.filter { (_, id, _) -> id.contains(if (wantBanner) "banner" else "avatar") }
        val pool = explicit.ifEmpty {
            if (wantBanner) items.filter { it.third.third >= 2.0 } else items.filter { it.third.third in 0.75..1.35 }
        }.ifEmpty { items }
        return if (wantBanner) pool.maxByOrNull { it.third.first.toLong() * it.third.second }?.first
        else pool.minByOrNull { (it.third.first.coerceAtLeast(1) * it.third.second.coerceAtLeast(1)) }?.first
    }

    private fun downloadImage(url: String, target: File): File? = runCatching {
        URL(url).openStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        target.takeIf { it.length() > 0L }
    }.getOrNull()

    private fun extensionFromUrl(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".png") -> ".png"
            clean.endsWith(".webp") -> ".webp"
            else -> ".jpg"
        }
    }

    private fun findDownloaded(directory: File, prefix: String): File? =
        directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.length() > 0L && it.name.startsWith(prefix) }
            ?.filterNot { file ->
                file.name.endsWith(".part") ||
                    file.name.endsWith(".ytdl") ||
                    file.name.endsWith(".info.json") ||
                    file.extension.lowercase() in setOf("vtt", "srt", "ass", "webp", "jpg", "jpeg", "png", "json")
            }
            ?.maxByOrNull { it.length() }

    private fun findThumbnail(directory: File): File? = directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }
        ?.filterNot { it.name.startsWith("channel-avatar") || it.name.startsWith("channel-banner") }
        ?.maxByOrNull { it.length() }

    private fun subtitleLanguage(name: String): String {
        val parts = name.split('.')
        return parts.dropLast(1).lastOrNull { it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]+)?")) } ?: "und"
    }

    private fun JSONObject.optDoubleSafe(name: String): Double {
        val value = opt(name) ?: return 0.0
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }

    private fun JSONObject.optLongSafe(name: String): Long {
        val value = opt(name) ?: return 0L
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun qualityLabel(height: Int, fps: Int): String = "${height}p${fps.takeIf { it > 30 } ?: ""}"
    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
    private fun cleanLine(value: String): String = value.replace(Regex("\\s+"), " ").trim().take(90)
    private fun shortError(error: Throwable): String = error.message.orEmpty()
        .lineSequence()
        .lastOrNull { it.isNotBlank() }
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(350)
        .orEmpty()
        .ifBlank { error.javaClass.simpleName }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }

    companion object {
        const val MAX_HEIGHT = 2160
    }
}
