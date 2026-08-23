package com.thedrowned925.ytclone.storage

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.min

class GitHubReleaseUploader(
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class PublishResult(
        val videoId: String,
        val primaryReleaseTag: String,
        val releaseTags: List<String>,
        val storageManifest: File,
    )

    private data class ReleaseRef(val id: Long, val tag: String)
    private data class AssetRef(val id: Long, val name: String, val size: Long)
    private data class PlannedPart(
        val index: Int,
        val start: Long,
        val length: Long,
        val assetName: String,
        var releaseTag: String = "",
    )
    private data class PlannedFile(
        val file: File,
        val logicalName: String,
        val size: Long,
        val parts: MutableList<PlannedPart>,
    )

    fun publishJob(
        jobDir: File,
        mediaManifest: JSONObject,
        repoValue: String,
        token: String,
        excludedLogicalNames: Set<String> = emptySet(),
        onProgress: (percent: Int, detail: String) -> Unit,
    ): PublishResult {
        val (owner, repo) = parseRepo(repoValue)
        val videoId = mediaManifest.optString("sourceId").takeIf { it.isNotBlank() }
            ?: sha256(mediaManifest.optString("webpageUrl") + mediaManifest.optString("title")).take(16)
        val baseTag = "ytclone-${sanitizeTag(videoId)}"

        val files = planFiles(jobDir, excludedLogicalNames)
        val allParts = files.flatMap { it.parts }
        require(allParts.isNotEmpty()) { "Yüklenecek dosya yok" }

        val releaseCount = ceil(allParts.size / MAX_DATA_ASSETS_PER_RELEASE.toDouble()).toInt().coerceAtLeast(1)
        val releaseTags = (0 until releaseCount).map { index ->
            "$baseTag-r${(index + 1).toString().padStart(3, '0')}"
        }

        allParts.forEachIndexed { index, part ->
            part.releaseTag = releaseTags[index / MAX_DATA_ASSETS_PER_RELEASE]
        }

        val title = mediaManifest.optString("title", videoId)
        val releases = releaseTags.associateWith { tag ->
            getOrCreateDraftRelease(owner, repo, tag, title, releaseTags.indexOf(tag) + 1, releaseCount, token)
        }

        val assetMaps = releases.mapValues { (_, release) ->
            listAssets(owner, repo, release.id, token).associateBy { it.name }.toMutableMap()
        }.toMutableMap()

        val totalBytes = files.sumOf { it.size }.coerceAtLeast(1L)
        var completedBytes = 0L

        for (plannedFile in files) {
            for (part in plannedFile.parts) {
                val release = releases.getValue(part.releaseTag)
                val assets = assetMaps.getValue(part.releaseTag)
                val existing = assets[part.assetName]

                if (existing != null && existing.size == part.length) {
                    completedBytes += part.length
                    onProgress(percent(completedBytes, totalBytes), "Zaten yüklü: ${plannedFile.logicalName} #${part.index + 1}")
                    continue
                }

                if (existing != null) {
                    deleteAsset(owner, repo, existing.id, token)
                    assets.remove(existing.name)
                }

                val uploaded = uploadSlice(
                    owner = owner,
                    repo = repo,
                    releaseId = release.id,
                    file = plannedFile.file,
                    start = part.start,
                    length = part.length,
                    assetName = part.assetName,
                    token = token,
                    onBytes = { sent ->
                        val now = completedBytes + sent
                        onProgress(percent(now, totalBytes), "Yükleniyor: ${plannedFile.logicalName} #${part.index + 1}/${plannedFile.parts.size}")
                    },
                )
                assets[uploaded.name] = uploaded
                completedBytes += part.length
            }
        }

        val storageManifestJson = buildStorageManifest(videoId, releaseTags.first(), files)
        val storageManifestFile = File(jobDir, STORAGE_MANIFEST)
        storageManifestFile.writeText(storageManifestJson.toString(2))

        val firstRelease = releases.getValue(releaseTags.first())
        val firstAssets = assetMaps.getValue(releaseTags.first())
        firstAssets[STORAGE_MANIFEST]?.let { deleteAsset(owner, repo, it.id, token) }
        uploadWholeFile(owner, repo, firstRelease.id, storageManifestFile, STORAGE_MANIFEST, token)

        releaseTags.forEachIndexed { index, tag ->
            onProgress(99, "Release yayınlanıyor ${index + 1}/${releaseTags.size}")
            publishRelease(owner, repo, releases.getValue(tag).id, token)
        }

        File(jobDir, "publish.json").writeText(
            JSONObject()
                .put("videoId", videoId)
                .put("primaryReleaseTag", releaseTags.first())
                .put("releaseTags", JSONArray(releaseTags))
                .toString(2),
        )
        onProgress(100, "GitHub arşivi yayınlandı")

        return PublishResult(videoId, releaseTags.first(), releaseTags, storageManifestFile)
    }

    private fun planFiles(root: File, excludedLogicalNames: Set<String>): List<PlannedFile> = root.walkTopDown()
        .filter { it.isFile && it.length() > 0L }
        .filterNot { it.name.endsWith(".part") || it.name == STORAGE_MANIFEST || it.name == "publish.json" }
        .map { file -> file to file.relativeTo(root).invariantSeparatorsPath }
        .filterNot { (_, logical) -> logical in excludedLogicalNames }
        .map { (file, logical) ->
            val size = file.length()
            val totalParts = maxOf(1, ceil(size / CHUNK_SIZE_BYTES.toDouble()).toInt())
            val parts = MutableList(totalParts) { index ->
                val start = index * CHUNK_SIZE_BYTES
                val length = min(CHUNK_SIZE_BYTES, size - start)
                PlannedPart(
                    index = index,
                    start = start,
                    length = length,
                    assetName = assetName(logical, index, totalParts),
                )
            }
            PlannedFile(file, logical, size, parts)
        }
        .sortedBy { it.logicalName }
        .toList()

    private fun buildStorageManifest(videoId: String, primaryTag: String, files: List<PlannedFile>): JSONObject =
        JSONObject()
            .put("schemaVersion", 2)
            .put("videoId", videoId)
            .put("primaryReleaseTag", primaryTag)
            .put("chunkSizeBytes", CHUNK_SIZE_BYTES)
            .put("files", JSONArray().apply {
                files.forEach { planned ->
                    put(JSONObject()
                        .put("logicalName", planned.logicalName)
                        .put("sizeBytes", planned.size)
                        .put("chunked", planned.parts.size > 1)
                        .put("parts", JSONArray().apply {
                            planned.parts.forEach { part ->
                                put(JSONObject()
                                    .put("releaseTag", part.releaseTag)
                                    .put("name", part.assetName)
                                    .put("offset", part.start)
                                    .put("sizeBytes", part.length))
                            }
                        }))
                }
            })

    private fun getOrCreateDraftRelease(
        owner: String,
        repo: String,
        tag: String,
        title: String,
        index: Int,
        total: Int,
        token: String,
    ): ReleaseRef {
        findRelease(owner, repo, tag, token)?.let { return it }
        val body = JSONObject()
            .put("tag_name", tag)
            .put("name", if (total == 1) title else "$title [$index/$total]")
            .put("body", "Published by YTClone Android. Chunked media storage.")
            .put("draft", true)
            .put("prerelease", false)
        val json = jsonRequest("POST", "https://api.github.com/repos/$owner/$repo/releases", token, body)
        return ReleaseRef(json.getLong("id"), json.getString("tag_name"))
    }

    private fun findRelease(owner: String, repo: String, tag: String, token: String): ReleaseRef? {
        for (page in 1..10) {
            val array = jsonArrayRequest("https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page", token)
            if (array.length() == 0) return null
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optString("tag_name") == tag) return ReleaseRef(item.getLong("id"), tag)
            }
            if (array.length() < 100) return null
        }
        return null
    }

    private fun listAssets(owner: String, repo: String, releaseId: Long, token: String): List<AssetRef> {
        val result = mutableListOf<AssetRef>()
        for (page in 1..10) {
            val array = jsonArrayRequest(
                "https://api.github.com/repos/$owner/$repo/releases/$releaseId/assets?per_page=100&page=$page",
                token,
            )
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                result += AssetRef(item.getLong("id"), item.getString("name"), item.getLong("size"))
            }
            if (array.length() < 100) break
        }
        return result
    }

    private fun uploadSlice(
        owner: String,
        repo: String,
        releaseId: Long,
        file: File,
        start: Long,
        length: Long,
        assetName: String,
        token: String,
        onBytes: (Long) -> Unit,
    ): AssetRef {
        require(length < GITHUB_ASSET_LIMIT_BYTES) { "GitHub asset 2 GiB sınırını aşıyor" }
        val url = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=${urlEncode(assetName)}"
        val request = baseRequest(url, token)
            .post(FileSliceRequestBody(file, start, length, onBytes))
            .build()
        return executeAssetRequest(request)
    }

    private fun uploadWholeFile(owner: String, repo: String, releaseId: Long, file: File, name: String, token: String): AssetRef =
        uploadSlice(owner, repo, releaseId, file, 0L, file.length(), name, token) { }

    private fun deleteAsset(owner: String, repo: String, assetId: Long, token: String) {
        val request = baseRequest("https://api.github.com/repos/$owner/$repo/releases/assets/$assetId", token)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) error("GitHub asset silinemedi: ${response.code}")
        }
    }

    private fun publishRelease(owner: String, repo: String, releaseId: Long, token: String) {
        jsonRequest(
            "PATCH",
            "https://api.github.com/repos/$owner/$repo/releases/$releaseId",
            token,
            JSONObject().put("draft", false),
        )
    }

    private fun executeAssetRequest(request: Request): AssetRef = client.newCall(request).execute().use { response ->
        val text = response.body.string()
        if (!response.isSuccessful) error("GitHub upload hatası ${response.code}: $text")
        val json = JSONObject(text)
        AssetRef(json.getLong("id"), json.getString("name"), json.getLong("size"))
    }

    private fun jsonRequest(method: String, url: String, token: String, body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody(JSON)
        val builder = baseRequest(url, token)
        when (method) {
            "POST" -> builder.post(requestBody)
            "PATCH" -> builder.patch(requestBody)
            else -> error("Unsupported method: $method")
        }
        return client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("GitHub API ${response.code}: $text")
            JSONObject(text)
        }
    }

    private fun jsonArrayRequest(url: String, token: String): JSONArray {
        val request = baseRequest(url, token).get().build()
        return client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("GitHub API ${response.code}: $text")
            JSONArray(text)
        }
    }

    private fun baseRequest(url: String, token: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ytclone-android")

    private class FileSliceRequestBody(
        private val file: File,
        private val start: Long,
        private val length: Long,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { input ->
                input.seek(start)
                var remaining = length
                var sent = 0L
                val buffer = ByteArray(1024 * 1024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Dosya beklenenden erken bitti: ${file.name}")
                    sink.write(buffer, 0, read)
                    remaining -= read
                    sent += read
                    onBytes(sent)
                }
            }
        }
    }

    private fun parseRepo(value: String): Pair<String, String> {
        val parts = value.trim().split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Repo owner/name biçiminde olmalı" }
        return parts[0] to parts[1]
    }

    private fun assetName(logicalName: String, index: Int, total: Int): String {
        val hash = sha256(logicalName).take(10)
        val safe = logicalName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
        val suffix = if (total == 1) "" else ".part${(index + 1).toString().padStart(4, '0')}"
        return "f-$hash-$safe$suffix"
    }

    private fun sanitizeTag(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "-").take(80)
    private fun percent(done: Long, total: Long): Int = ((done.toDouble() / total) * 100.0).toInt().coerceIn(0, 98)
    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CHUNK_SIZE_BYTES: Long = 1_932_735_283L // floor(1.8 GiB)
        private const val GITHUB_ASSET_LIMIT_BYTES: Long = 2L * 1024L * 1024L * 1024L
        private const val MAX_DATA_ASSETS_PER_RELEASE = 990
        private const val STORAGE_MANIFEST = "storage-manifest.json"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
