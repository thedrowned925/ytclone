package com.thedrowned925.ytclone.catalog

import android.content.Context
import com.thedrowned925.ytclone.storage.GitHubReleaseReader
import com.thedrowned925.ytclone.storage.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class LocalCatalogRepository(private val context: Context) {
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

    data class Subtitle(
        val id: String,
        val label: String,
        val language: String,
        val logicalName: String,
        val localFile: File?,
    )

    data class Video(
        val id: String,
        val title: String,
        val description: String,
        val channelId: String,
        val channel: String,
        val durationSeconds: Double,
        val uploadDate: String,
        val sourceUrl: String,
        val status: String,
        val thumbnailFile: File?,
        val thumbnailLogicalName: String,
        val primaryReleaseTag: String?,
        val qualities: List<Quality>,
        val audioTracks: List<Audio>,
        val subtitles: List<Subtitle>,
    )

    data class Channel(
        val id: String,
        val name: String,
        val handle: String,
        val description: String,
        val subscriberCount: Long,
        val avatarFile: File?,
        val bannerFile: File?,
        val videoIds: List<String>,
    )

    data class Snapshot(val videos: List<Video>, val channels: List<Channel>)

    private val settings = SettingsStore(context)
    private val ingestRoot = File(context.filesDir, "ingest-state")
    private val cacheRoot = File(context.filesDir, "catalog-cache").apply { mkdirs() }
    private val catalogCache = File(cacheRoot, "catalog.json")
    private val channelsCache = File(cacheRoot, "channels.json")
    private val imageRoot = File(cacheRoot, "images").apply { mkdirs() }
    private val readers = ConcurrentHashMap<String, GitHubReleaseReader>()

    fun loadCached(): Snapshot {
        if (catalogCache.exists() && channelsCache.exists()) {
            return runCatching {
                parseRemote(JSONObject(catalogCache.readText()), JSONObject(channelsCache.readText()), false)
            }.getOrElse { localSnapshot() }
        }
        return localSnapshot()
    }

    /** Refreshes both videos and channels from the single ytclone-catalog release. */
    fun refreshFromGitHub(): Snapshot {
        val token = settings.gitHubToken() ?: return loadCached()
        val repo = settings.mediaRepo()
        val payload = GitHubCatalogReader(repo, token).load()
        catalogCache.writeText(payload.catalog.toString(2))
        channelsCache.writeText(payload.channels.toString(2))
        return parseRemote(payload.catalog, payload.channels, true)
    }

    private fun parseRemote(catalog: JSONObject, channelsJson: JSONObject, fetchImages: Boolean): Snapshot {
        val channels = mutableListOf<Channel>()
        val channelArray = channelsJson.optJSONArray("channels") ?: JSONArray()
        for (index in 0 until channelArray.length()) {
            val item = channelArray.optJSONObject(index) ?: continue
            val channelId = item.optString("channelId").ifBlank { "channel-$index" }
            val avatar = cachePointer(item.optJSONObject("avatar"), "channel-$channelId-avatar", fetchImages)
            val banner = cachePointer(item.optJSONObject("banner"), "channel-$channelId-banner", fetchImages)
            val videoIds = mutableListOf<String>()
            val ids = item.optJSONArray("videos") ?: JSONArray()
            for (i in 0 until ids.length()) ids.optString(i).takeIf(String::isNotBlank)?.let(videoIds::add)
            channels += Channel(
                id = channelId,
                name = item.optString("name", "Bilinmeyen kanal"),
                handle = item.optString("handle"),
                description = item.optString("description"),
                subscriberCount = item.optLong("subscriberCount", 0L),
                avatarFile = avatar,
                bannerFile = banner,
                videoIds = videoIds.distinct(),
            )
        }

        val videos = mutableListOf<Video>()
        val array = catalog.optJSONArray("videos") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").ifBlank { "video-$index" }
            val releaseTag = item.optString("releaseTag").takeIf(String::isNotBlank)
            val thumbnailLogical = item.optString("thumbnailLogicalName")
            val thumbnail = if (releaseTag != null && thumbnailLogical.isNotBlank()) {
                cacheLogical(releaseTag, thumbnailLogical, "video-$id-thumb", fetchImages)
            } else null

            val qualities = mutableListOf<Quality>()
            val qualityArray = item.optJSONArray("qualities") ?: JSONArray()
            for (q in 0 until qualityArray.length()) {
                val value = qualityArray.optJSONObject(q) ?: continue
                val logical = value.optString("logicalName")
                if (logical.isBlank()) continue
                qualities += Quality(
                    id = value.optString("id", "q$q"),
                    height = value.optInt("height"),
                    fps = value.optInt("fps", 30).coerceAtLeast(1),
                    logicalName = logical,
                    localFile = null,
                )
            }

            val audios = mutableListOf<Audio>()
            val audioArray = item.optJSONArray("audioTracks") ?: JSONArray()
            for (a in 0 until audioArray.length()) {
                val value = audioArray.optJSONObject(a) ?: continue
                val logical = value.optString("logicalName")
                if (logical.isBlank()) continue
                audios += Audio(
                    id = logical,
                    label = value.optString("label", "Ses ${a + 1}"),
                    language = value.optString("language", "und"),
                    logicalName = logical,
                    localFile = null,
                    isDefault = value.optBoolean("default", a == 0),
                )
            }

            val subtitles = mutableListOf<Subtitle>()
            val subtitleArray = item.optJSONArray("subtitles") ?: JSONArray()
            for (s in 0 until subtitleArray.length()) {
                val value = subtitleArray.optJSONObject(s) ?: continue
                val logical = value.optString("logicalName")
                if (logical.isBlank()) continue
                val language = value.optString("language", "und")
                subtitles += Subtitle(
                    id = logical,
                    label = language.takeUnless { it.isBlank() || it == "und" } ?: "Altyazı ${s + 1}",
                    language = language,
                    logicalName = logical,
                    localFile = null,
                )
            }

            videos += Video(
                id = id,
                title = item.optString("title", "Adsız video"),
                description = item.optString("description"),
                channelId = item.optString("channelId"),
                channel = item.optString("channel", "Bilinmeyen kanal"),
                durationSeconds = item.optDouble("durationSeconds", 0.0),
                uploadDate = item.optString("uploadDate"),
                sourceUrl = item.optString("sourceUrl"),
                status = "published",
                thumbnailFile = thumbnail,
                thumbnailLogicalName = thumbnailLogical,
                primaryReleaseTag = releaseTag,
                qualities = qualities.sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.fps }),
                audioTracks = audios.sortedWith(compareByDescending<Audio> { it.isDefault }.thenBy { it.language }.thenBy { it.label }),
                subtitles = subtitles.sortedBy { it.language },
            )
        }

        // Old catalog entries may predate explicit channel video lists. Reconcile
        // from video.channelId so one YouTube channel always maps to one UI channel.
        val reconciled = channels.map { channel ->
            val ids = (channel.videoIds + videos.filter { it.channelId == channel.id }.map { it.id }).distinct()
            channel.copy(videoIds = ids)
        }.toMutableList()
        videos.groupBy { it.channelId }.forEach { (channelId, items) ->
            if (channelId.isBlank() || reconciled.any { it.id == channelId }) return@forEach
            val first = items.first()
            reconciled += Channel(
                id = channelId,
                name = first.channel,
                handle = "",
                description = "",
                subscriberCount = 0L,
                avatarFile = null,
                bannerFile = null,
                videoIds = items.map { it.id },
            )
        }
        return Snapshot(videos = videos, channels = reconciled.sortedBy { it.name.lowercase() })
    }

    private fun localSnapshot(): Snapshot {
        if (!ingestRoot.exists()) return Snapshot(emptyList(), emptyList())
        val videos = ingestRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readLocalVideo)
            ?.sortedByDescending { video -> File(ingestRoot, video.id).lastModified() }
            ?.toList()
            ?: emptyList()
        val channels = videos.groupBy { it.channelId.ifBlank { "name:${it.channel}" } }
            .map { (id, items) ->
                Channel(id, items.first().channel, "", "", 0L, null, null, items.map { it.id })
            }
        return Snapshot(videos, channels)
    }

    private fun readLocalVideo(jobDir: File): Video? = runCatching {
        val manifestFile = File(jobDir, "manifest.json")
        if (!manifestFile.exists()) return null
        val json = JSONObject(manifestFile.readText())
        val qualities = mutableListOf<Quality>()
        val qualityJson = json.optJSONObject("qualities") ?: JSONObject()
        for (key in qualityJson.keys()) {
            val value = qualityJson.optJSONObject(key) ?: continue
            val logical = value.optString("logicalName")
            if (logical.isBlank()) continue
            qualities += Quality(key, value.optInt("height"), value.optInt("fps", 30), logical, File(jobDir, logical).takeIf(File::exists))
        }
        val audios = mutableListOf<Audio>()
        val audioJson = json.optJSONArray("audioTracks") ?: JSONArray()
        for (index in 0 until audioJson.length()) {
            val value = audioJson.optJSONObject(index) ?: continue
            val logical = value.optString("logicalName")
            if (logical.isBlank()) continue
            audios += Audio(logical, value.optString("label"), value.optString("language", "und"), logical, File(jobDir, logical).takeIf(File::exists), value.optBoolean("default", index == 0))
        }
        val subtitles = mutableListOf<Subtitle>()
        val subs = json.optJSONArray("subtitles") ?: JSONArray()
        for (index in 0 until subs.length()) {
            val value = subs.optJSONObject(index) ?: continue
            val logical = value.optString("logicalName")
            val language = value.optString("language", "und")
            if (logical.isNotBlank()) subtitles += Subtitle(logical, language, language, logical, File(jobDir, logical).takeIf(File::exists))
        }
        Video(
            id = json.optString("sourceId").ifBlank { jobDir.name },
            title = json.optString("title", "Adsız video"),
            description = json.optString("description"),
            channelId = json.optString("channelId"),
            channel = json.optString("channel", "Bilinmeyen kanal"),
            durationSeconds = json.optDouble("durationSeconds", 0.0),
            uploadDate = json.optString("uploadDate"),
            sourceUrl = json.optString("webpageUrl"),
            status = json.optString("status", "processing"),
            thumbnailFile = null,
            thumbnailLogicalName = json.optString("thumbnailLogicalName"),
            primaryReleaseTag = json.optJSONObject("storage")?.optString("primaryReleaseTag")?.takeIf(String::isNotBlank),
            qualities = qualities.sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.fps }),
            audioTracks = audios,
            subtitles = subtitles,
        )
    }.getOrNull()

    private fun cachePointer(pointer: JSONObject?, key: String, fetch: Boolean): File? {
        if (pointer == null) return null
        val release = pointer.optString("releaseTag")
        val logical = pointer.optString("logicalName")
        if (release.isBlank() || logical.isBlank()) return null
        return cacheLogical(release, logical, key, fetch)
    }

    private fun cacheLogical(releaseTag: String, logicalName: String, key: String, fetch: Boolean): File? {
        val extension = logicalName.substringAfterLast('.', "bin").take(8).replace(Regex("[^A-Za-z0-9]"), "")
        val target = File(imageRoot, "${sha256(key).take(24)}.${extension.ifBlank { "bin" }}")
        if (target.exists() && target.length() > 0L) return target
        if (!fetch) return null
        val token = settings.gitHubToken() ?: return null
        val repo = settings.mediaRepo()
        return runCatching {
            val reader = readers.getOrPut(releaseTag) { GitHubReleaseReader(repo, token) }
            val logical = reader.logicalFile(releaseTag, logicalName)
            require(logical.sizeBytes in 1..MAX_IMAGE_BYTES) { "Görsel cache sınırını aşıyor" }
            FileOutputStream(target).use { output ->
                logical.parts.sortedBy { it.offset }.forEach { part ->
                    reader.openPartSlice(part, 0L, part.sizeBytes).use { slice -> slice.input.copyTo(output) }
                }
            }
            target.takeIf { it.length() > 0L }
        }.getOrElse {
            target.delete()
            null
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_IMAGE_BYTES = 20L * 1024L * 1024L
    }
}
