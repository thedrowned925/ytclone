package com.thedrowned925.ytclone.ingest

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
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

    private data class SubtitleAsset(
        val language: String,
        val label: String,
        val url: String,
        val automatic: Boolean,
    )

    fun import(
        url: String,
        jobDir: File,
        processId: String,
        options: IngestOptions = IngestOptions(),
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ): ImportedMedia {
        jobDir.mkdirs()
        val warnings = mutableListOf<String>()

        onProgress("metadata", 1, "Video bilgileri ve kalite listesi okunuyor")
        val metadata = readMetadata(url, "$processId-metadata")
        val formats = readFormats(metadata)
        val selectedVideos = chooseVideoVariants(formats)
        require(selectedVideos.isNotEmpty()) { "4K veya altında uygun video formatı bulunamadı" }

        val selectedAudio = chooseAudioTracks(formats).let { tracks ->
            if (options.allAudioTracks) tracks else tracks.take(1)
        }

        // Critical payload comes first. Auxiliary failures (subtitle/thumbnail/channel
        // images) must never prevent a playable video from being archived.
        val downloadedVideos = downloadVideos(
            url = url,
            jobDir = jobDir,
            processId = processId,
            selectedVideos = selectedVideos,
            onProgress = onProgress,
        )

        val downloadedAudio = downloadAudio(
            url = url,
            jobDir = jobDir,
            processId = processId,
            selectedAudio = selectedAudio,
            onProgress = onProgress,
        )

        if (options.subtitles) {
            downloadSubtitlesBestEffort(metadata, jobDir, warnings, onProgress)
        }
        downloadThumbnailBestEffort(metadata, jobDir, warnings, onProgress)

        onProgress("channel", 69, "Kanal profili, avatarı ve banner bilgileri hazırlanıyor")
        val channelSnapshot = buildChannelSnapshotBestEffort(
            metadata = metadata,
            processId = "$processId-channel",
            jobDir = jobDir,
            warnings = warnings,
        )
        val channelFile = File(jobDir, "channel.json").apply { writeText(channelSnapshot.toString(2)) }

        val manifest = buildManifest(
            metadata = metadata,
            videos = downloadedVideos,
            audioTracks = downloadedAudio,
            options = options,
            jobDir = jobDir,
            channelFile = channelFile,
            warnings = warnings,
        )
        val manifestFile = File(jobDir, "manifest.json")
        manifestFile.writeText(manifest.toString(2))

        val warningSuffix = if (warnings.isEmpty()) "" else " • ${warnings.size} yardımcı öğe uyarısı"
        onProgress(
            "download-complete",
            70,
            "${downloadedVideos.size} kalite, ${downloadedAudio.size} ses parçası hazır$warningSuffix",
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

    private fun downloadVideos(
        url: String,
        jobDir: File,
        processId: String,
        selectedVideos: List<FormatCandidate>,
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ): List<VideoVariant> {
        val result = mutableListOf<VideoVariant>()
        selectedVideos.forEachIndexed { index, format ->
            val quality = qualityLabel(format.height, format.fps)
            val base = "video.$quality.${sanitize(format.id)}."
            val start = 4 + ((index.toDouble() / selectedVideos.size.coerceAtLeast(1)) * 48.0).roundToInt()
            val existing = findDownloaded(jobDir, base)

            if (existing != null) {
                onProgress(
                    "download-video",
                    start,
                    "$quality zaten tamamlanmış; tekrar indirilmeden devam ediliyor (${index + 1}/${selectedVideos.size})",
                )
                result += VideoVariant(
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

            onProgress("download-video", start, "$quality indiriliyor (${index + 1}/${selectedVideos.size})")
            val request = YoutubeDLRequest(url).apply {
                commonMediaOptions()
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
            result += VideoVariant(
                formatId = format.id,
                height = format.height,
                fps = format.fps,
                codec = format.vcodec,
                container = file.extension.ifBlank { format.ext },
                file = file,
                containsAudio = format.acodec != "none",
            )
        }
        return result
    }

    private fun downloadAudio(
        url: String,
        jobDir: File,
        processId: String,
        selectedAudio: List<FormatCandidate>,
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ): List<AudioTrack> {
        val result = mutableListOf<AudioTrack>()
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
                result += AudioTrack(
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
                commonMediaOptions()
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
            result += AudioTrack(
                formatId = format.id,
                language = format.language,
                label = format.note.ifBlank { format.language },
                codec = format.acodec,
                file = file,
                isDefault = index == 0,
            )
        }
        return result
    }

    private fun downloadSubtitlesBestEffort(
        metadata: JSONObject,
        jobDir: File,
        warnings: MutableList<String>,
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ) {
        val assets = subtitleAssets(metadata)
        if (assets.isEmpty()) {
            onProgress("download-audio", 68, "Kaynakta indirilebilir altyazı bulunamadı; devam ediliyor")
            return
        }

        onProgress("download-audio", 68, "${assets.size} gerçek/orijinal altyazı parçası indiriliyor")
        assets.forEachIndexed { index, asset ->
            val kind = if (asset.automatic) "auto" else "manual"
            val target = File(jobDir, "subtitle.$kind.${sanitize(asset.language)}.vtt")
            if (target.exists() && target.length() > 0L) return@forEachIndexed

            val error = downloadUrlWithRetry(
                url = asset.url,
                target = target,
                maxAttempts = 4,
                baseDelayMs = 1_500L,
            )
            if (error != null) {
                warnings += "Altyazı ${asset.label} (${asset.language}) alınamadı: $error"
            }
            // Avoid hammering timedtext endpoints; this is especially important for
            // automatic captions and prevents a single archive from causing HTTP 429.
            if (index < assets.lastIndex) Thread.sleep(350L)
        }
    }

    private fun subtitleAssets(metadata: JSONObject): List<SubtitleAsset> {
        val result = mutableListOf<SubtitleAsset>()

        // Manual/uploaded tracks are real source tracks; keep all of them.
        collectSubtitleGroup(
            group = metadata.optJSONObject("subtitles"),
            automatic = false,
            originalOnly = false,
            target = result,
        )

        // automatic_captions often exposes hundreds of on-demand translated variants.
        // Downloading --sub-langs all requests every translation (e.g. "ab") and hits
        // YouTube rate limits. Keep only original/source automatic tracks.
        collectSubtitleGroup(
            group = metadata.optJSONObject("automatic_captions"),
            automatic = true,
            originalOnly = true,
            target = result,
        )

        return result.distinctBy { "${it.automatic}|${it.language}|${it.url}" }
    }

    private fun collectSubtitleGroup(
        group: JSONObject?,
        automatic: Boolean,
        originalOnly: Boolean,
        target: MutableList<SubtitleAsset>,
    ) {
        if (group == null) return
        val keys = buildList { group.keys().forEachRemaining { add(it) } }.sorted()
        val selectedKeys = if (!originalOnly) {
            keys
        } else {
            val original = keys.filter { it.endsWith("-orig", ignoreCase = true) }
            if (original.isNotEmpty()) original else keys.filter { key ->
                val entries = group.optJSONArray(key) ?: return@filter false
                (0 until entries.length()).any { index ->
                    entries.optJSONObject(index)?.optString("name")?.contains("original", ignoreCase = true) == true
                }
            }.take(2)
        }

        selectedKeys.forEach { language ->
            val entries = group.optJSONArray(language) ?: return@forEach
            val chosen = chooseSubtitleEntry(entries) ?: return@forEach
            val url = chosen.optString("url")
            if (!url.startsWith("http")) return@forEach
            target += SubtitleAsset(
                language = language,
                label = chosen.optString("name").ifBlank { language },
                url = url,
                automatic = automatic,
            )
        }
    }

    private fun chooseSubtitleEntry(entries: JSONArray): JSONObject? {
        val candidates = buildList {
            for (index in 0 until entries.length()) {
                entries.optJSONObject(index)?.let { add(it) }
            }
        }
        return candidates.firstOrNull { it.optString("ext").equals("vtt", ignoreCase = true) }
            ?: candidates.firstOrNull { it.optString("url").contains("fmt=vtt", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun downloadThumbnailBestEffort(
        metadata: JSONObject,
        jobDir: File,
        warnings: MutableList<String>,
        onProgress: (stage: String, percent: Int, detail: String) -> Unit,
    ) {
        val url = metadata.optString("thumbnail").takeIf { it.startsWith("http") } ?: return
        if (findThumbnail(jobDir) != null) return
        onProgress("metadata", 68, "Video thumbnail'i alınıyor")
        val target = File(jobDir, "thumbnail${extensionFromUrl(url)}")
        downloadUrlWithRetry(url, target, maxAttempts = 3, baseDelayMs = 1_000L)?.let { error ->
            warnings += "Thumbnail alınamadı: $error"
        }
    }

    private fun YoutubeDLRequest.commonMediaOptions() {
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
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            val request = YoutubeDLRequest(url).apply {
                commonMediaOptions()
                addOption("--dump-single-json")
                addOption("--skip-download")
            }
            try {
                return executeJson(request, "$processId-$attempt")
            } catch (error: Throwable) {
                lastError = error
                if (!isTransient(error) || attempt == 2) return@repeat
                Thread.sleep(listOf(2_000L, 5_000L, 10_000L)[attempt])
            }
        }
        throw IllegalStateException("Video bilgileri alınamadı: ${shortError(lastError ?: IOException("bilinmeyen hata"))}", lastError)
    }

    private fun buildChannelSnapshotBestEffort(
        metadata: JSONObject,
        processId: String,
        jobDir: File,
        warnings: MutableList<String>,
    ): JSONObject {
        val channelUrl = metadata.optString("channel_url").takeIf { it.startsWith("http") }
        val channelMetadata = channelUrl?.let { url ->
            runCatching {
                val request = YoutubeDLRequest(url).apply {
                    commonMediaOptions()
                    addOption("--dump-single-json")
                    addOption("--flat-playlist")
                    addOption("--playlist-end", "1")
                    addOption("--skip-download")
                }
                executeJson(request, processId)
            }.onFailure {
                warnings += "Kanal zengin metadata'sı alınamadı; video metadata'sı kullanıldı: ${shortError(it)}"
            }.getOrNull()
        }

        val merged = channelMetadata ?: metadata
        val thumbnails = merged.optJSONArray("thumbnails") ?: JSONArray()
        val avatar = chooseChannelImage(thumbnails, wantBanner = false)
        val banner = chooseChannelImage(thumbnails, wantBanner = true)

        val avatarFile = avatar?.let { url ->
            val target = File(jobDir, "channel-avatar${extensionFromUrl(url)}")
            downloadUrlWithRetry(url, target, 3, 1_000L).let { error ->
                if (error != null) {
                    warnings += "Kanal avatarı alınamadı: $error"
                    null
                } else target
            }
        }
        val bannerFile = banner?.let { url ->
            val target = File(jobDir, "channel-banner${extensionFromUrl(url)}")
            downloadUrlWithRetry(url, target, 3, 1_000L).let { error ->
                if (error != null) {
                    warnings += "Kanal banner'ı alınamadı: $error"
                    null
                } else target
            }
        }

        return JSONObject()
            .put("schemaVersion", 2)
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

    private fun downloadUrlWithRetry(
        url: String,
        target: File,
        maxAttempts: Int,
        baseDelayMs: Long,
    ): String? {
        if (target.exists() && target.length() > 0L) return null
        var lastMessage = "bilinmeyen ağ hatası"
        val part = File(target.parentFile, "${target.name}.part")

        repeat(maxAttempts) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20_000
                    readTimeout = 35_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", ANDROID_USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    part.parentFile?.mkdirs()
                    connection.inputStream.use { input ->
                        part.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (part.length() <= 0L) throw IOException("boş yanıt")
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    return null
                }

                lastMessage = "HTTP $code ${connection.responseMessage.orEmpty()}".trim()
                val transient = code == 408 || code == 409 || code == 425 || code == 429 || code in 500..599
                if (!transient) return lastMessage
                val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.times(1_000L)
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(retryAfter?.coerceAtMost(30_000L) ?: backoff(baseDelayMs, attempt))
                }
            } catch (error: Throwable) {
                lastMessage = shortError(error)
                if (attempt < maxAttempts - 1) Thread.sleep(backoff(baseDelayMs, attempt))
            } finally {
                connection?.disconnect()
            }
        }
        part.delete()
        return lastMessage
    }

    private fun backoff(baseDelayMs: Long, attempt: Int): Long =
        (baseDelayMs * (1L shl attempt.coerceAtMost(4))).coerceAtMost(30_000L)

    private fun isTransient(error: Throwable): Boolean {
        val text = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return listOf(
            "429",
            "too many requests",
            "timeout",
            "timed out",
            "temporary",
            "connection reset",
            "network is unreachable",
            "502",
            "503",
            "504",
        ).any { it in text }
    }

    private fun executeJson(request: YoutubeDLRequest, processId: String): JSONObject {
        val response = YoutubeDL.getInstance().execute(request, processId, null)
        if (response.exitCode != 0) {
            error("yt-dlp metadata hatası: ${response.err.ifBlank { response.out.ifBlank { "exit=${response.exitCode}" } }}")
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
        val allowed = formats.filter { it.vcodec != "none" && it.height in 1..MAX_HEIGHT }
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
        warnings: List<String>,
    ): JSONObject = JSONObject().apply {
        put("schemaVersion", 5)
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
        put("warnings", JSONArray().apply { warnings.forEach { put(it) } })
        put("ingestOptions", JSONObject().apply {
            put("allAudioTracks", options.allAudioTracks)
            put("subtitles", options.subtitles)
            put("keepOriginal", true)
            put("createRenditions", false)
            put("nativeAllQualities", true)
            put("subtitlePolicy", "all-manual-plus-original-auto")
        })

        val source = videos.maxWith(compareBy<VideoVariant> { it.height }.thenBy { it.fps })
        put("sourceVideo", videoJson(source))
        put("qualities", JSONObject().apply { videos.forEach { video -> put(video.id, videoJson(video)) } })
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
                        .put("language", subtitleLanguage(file.name))
                        .put("automatic", file.name.startsWith("subtitle.auto.")))
                }
        })
        put("availableAutoCaptionLanguages", JSONArray().apply {
            val automatic = metadata.optJSONObject("automatic_captions")
            automatic?.keys()?.forEachRemaining { put(it) }
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
            if (wantBanner) items.filter { it.third.third >= 2.0 }
            else items.filter { it.third.third in 0.75..1.35 }
        }.ifEmpty { items }
        return if (wantBanner) pool.maxByOrNull { it.third.first.toLong() * it.third.second }?.first
        else pool.minByOrNull { it.third.first.coerceAtLeast(1) * it.third.second.coerceAtLeast(1) }?.first
    }

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
        ?.filter { it.isFile && it.length() > 0L && it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }
        ?.filterNot { it.name.startsWith("channel-avatar") || it.name.startsWith("channel-banner") }
        ?.maxByOrNull { it.length() }

    private fun subtitleLanguage(name: String): String {
        val parts = name.split('.')
        return parts.dropLast(1).lastOrNull {
            it.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]+)?"))
        } ?: "und"
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
        private const val ANDROID_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0 Mobile Safari/537.36"
    }
}
