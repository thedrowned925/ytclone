package com.thedrowned925.ytclone.storage

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class GitHubReleaseReader(
    repoValue: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class LogicalPart(
        val releaseTag: String,
        val assetName: String,
        val offset: Long,
        val sizeBytes: Long,
    )

    data class LogicalFile(
        val logicalName: String,
        val sizeBytes: Long,
        val parts: List<LogicalPart>,
    )

    data class StorageManifest(
        val videoId: String,
        val primaryReleaseTag: String,
        val files: List<LogicalFile>,
    ) {
        fun file(name: String): LogicalFile = files.firstOrNull { it.logicalName == name }
            ?: error("Logical file bulunamadı: $name")
    }

    class OpenedSlice internal constructor(
        internal val response: Response,
        val input: InputStream,
        val length: Long,
    ) : AutoCloseable {
        override fun close() = response.close()
    }

    private data class ReleaseRef(val id: Long, val tag: String)
    private data class AssetRef(val id: Long, val name: String, val size: Long)

    private val repoParts = repoValue.trim().split('/').also {
        require(it.size == 2 && it.all(String::isNotBlank)) { "Repo owner/name biçiminde olmalı" }
    }
    private val owner = repoParts[0]
    private val repo = repoParts[1]
    private val releaseCache = ConcurrentHashMap<String, ReleaseRef>()
    private val assetCache = ConcurrentHashMap<String, Map<String, AssetRef>>()
    private val manifestCache = ConcurrentHashMap<String, StorageManifest>()

    fun loadStorageManifest(primaryReleaseTag: String): StorageManifest =
        manifestCache[primaryReleaseTag] ?: synchronized(manifestCache) {
            manifestCache[primaryReleaseTag] ?: loadStorageManifestUncached(primaryReleaseTag).also {
                manifestCache[primaryReleaseTag] = it
            }
        }

    fun logicalFile(primaryReleaseTag: String, logicalName: String): LogicalFile =
        loadStorageManifest(primaryReleaseTag).file(logicalName)

    fun openPartSlice(part: LogicalPart, assetOffset: Long, requestedLength: Long): OpenedSlice {
        require(assetOffset >= 0 && requestedLength > 0 && assetOffset + requestedLength <= part.sizeBytes) {
            "Geçersiz chunk byte aralığı"
        }

        val release = release(part.releaseTag)
        val asset = assets(release).getValue(part.assetName)
        val end = assetOffset + requestedLength - 1
        val request = apiRequest("https://api.github.com/repos/$owner/$repo/releases/assets/${asset.id}")
            .header("Accept", "application/octet-stream")
            .header("Range", "bytes=$assetOffset-$end")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            val detail = response.body.string().take(300)
            response.close()
            error("GitHub asset okunamadı (${response.code}): $detail")
        }

        val input = response.body.byteStream()
        // GitHub normally returns 206 for a Range request. If an upstream redirect
        // ever returns the full asset as 200, skip locally so Media3 still sees the
        // exact logical byte range it requested.
        if (response.code == 200 && assetOffset > 0) skipFully(input, assetOffset)
        return OpenedSlice(response, input, requestedLength)
    }

    fun readLogicalText(primaryReleaseTag: String, logicalName: String, maxBytes: Long = 8L * 1024L * 1024L): String {
        val file = logicalFile(primaryReleaseTag, logicalName)
        require(file.sizeBytes <= maxBytes) { "$logicalName beklenenden büyük (${file.sizeBytes} byte)" }
        val out = ByteArray(file.sizeBytes.toInt())
        var outputOffset = 0
        file.parts.sortedBy { it.offset }.forEach { part ->
            openPartSlice(part, 0L, part.sizeBytes).use { slice ->
                while (outputOffset < part.offset + part.sizeBytes) {
                    val remainingPart = (part.offset + part.sizeBytes - outputOffset).toInt()
                    val read = slice.input.read(out, outputOffset, remainingPart)
                    if (read < 0) error("GitHub asset beklenenden erken bitti: ${part.assetName}")
                    outputOffset += read
                }
            }
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun loadStorageManifestUncached(primaryReleaseTag: String): StorageManifest {
        val primary = release(primaryReleaseTag)
        val manifestAsset = assets(primary)[STORAGE_MANIFEST]
            ?: error("$STORAGE_MANIFEST Release içinde bulunamadı")

        val request = apiRequest("https://api.github.com/repos/$owner/$repo/releases/assets/${manifestAsset.id}")
            .header("Accept", "application/octet-stream")
            .build()
        val json = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("Storage manifest indirilemedi (${response.code}): ${text.take(300)}")
            JSONObject(text)
        }

        val filesJson = json.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (fileIndex in 0 until filesJson.length()) {
                val fileJson = filesJson.getJSONObject(fileIndex)
                val partsJson = fileJson.optJSONArray("parts") ?: JSONArray()
                val parts = buildList {
                    for (partIndex in 0 until partsJson.length()) {
                        val part = partsJson.getJSONObject(partIndex)
                        add(
                            LogicalPart(
                                releaseTag = part.optString("releaseTag", primaryReleaseTag),
                                assetName = part.getString("name"),
                                offset = part.getLong("offset"),
                                sizeBytes = part.getLong("sizeBytes"),
                            ),
                        )
                    }
                }.sortedBy { it.offset }
                add(
                    LogicalFile(
                        logicalName = fileJson.getString("logicalName"),
                        sizeBytes = fileJson.getLong("sizeBytes"),
                        parts = parts,
                    ),
                )
            }
        }

        return StorageManifest(
            videoId = json.optString("videoId"),
            primaryReleaseTag = json.optString("primaryReleaseTag", primaryReleaseTag),
            files = files,
        )
    }

    private fun release(tag: String): ReleaseRef = releaseCache[tag] ?: synchronized(releaseCache) {
        releaseCache[tag] ?: run {
            val request = apiRequest(
                "https://api.github.com/repos/$owner/$repo/releases/tags/${encodePath(tag)}",
            ).build()
            val json = client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) error("Release bulunamadı $tag (${response.code}): ${text.take(300)}")
                JSONObject(text)
            }
            ReleaseRef(json.getLong("id"), json.getString("tag_name")).also { releaseCache[tag] = it }
        }
    }

    private fun assets(release: ReleaseRef): Map<String, AssetRef> = assetCache[release.tag] ?: synchronized(assetCache) {
        assetCache[release.tag] ?: run {
            val result = LinkedHashMap<String, AssetRef>()
            for (page in 1..10) {
                val request = apiRequest(
                    "https://api.github.com/repos/$owner/$repo/releases/${release.id}/assets?per_page=100&page=$page",
                ).build()
                val array = client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    if (!response.isSuccessful) error("Release asset listesi okunamadı (${response.code}): ${text.take(300)}")
                    JSONArray(text)
                }
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val asset = AssetRef(item.getLong("id"), item.getString("name"), item.getLong("size"))
                    result[asset.name] = asset
                }
                if (array.length() < 100) break
            }
            result.toMap().also { assetCache[release.tag] = it }
        }
    }

    private fun apiRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ytclone-android")

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (input.read() < 0) error("Asset skip sırasında beklenenden erken bitti")
                remaining -= 1
            }
        }
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val STORAGE_MANIFEST = "storage-manifest.json"
    }
}
