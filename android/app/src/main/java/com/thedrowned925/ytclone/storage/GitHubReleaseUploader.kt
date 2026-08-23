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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
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
        val verified: Boolean,
    )

    data class UploadProgress(
        val stage: String,
        val percent: Int,
        val detail: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long,
        val etaSeconds: Long,
    )

    private data class ReleaseRef(val id: Long, val tag: String, val draft: Boolean)
    private data class AssetRef(val id: Long, val name: String, val size: Long)
    private data class PlannedPart(
        val index: Int,
        val start: Long,
        val length: Long,
        val assetName: String,
        val releaseTag: String,
    )
    private data class PlannedFile(
        val file: File,
        val logicalName: String,
        val size: Long,
        val parts: List<PlannedPart>,
    )
    private data class UploadTask(val file: PlannedFile, val part: PlannedPart)

    fun publishJob(
        jobDir: File,
        mediaManifest: JSONObject,
        repoValue: String,
        token: String,
        excludedLogicalNames: Set<String> = emptySet(),
        onProgress: (UploadProgress) -> Unit,
    ): PublishResult {
        val (owner, repo) = parseRepo(repoValue)
        val videoId = mediaManifest.optString("sourceId").takeIf { it.isNotBlank() }
            ?: sha256(mediaManifest.optString("webpageUrl") + mediaManifest.optString("title")).take(16)
        val releaseTag = "ytclone-${sanitizeTag(videoId)}"

        mediaManifest.put("status", "uploading")
        mediaManifest.put(
            "storage",
            JSONObject()
                .put("provider", "github-release")
                .put("primaryReleaseTag", releaseTag)
                .put("releaseTags", JSONArray().put(releaseTag))
                .put("singleRelease", true),
        )
        File(jobDir, MEDIA_MANIFEST).writeText(mediaManifest.toString(2))

        val files = planFiles(jobDir, excludedLogicalNames, releaseTag)
        val allParts = files.flatMap { it.parts }
        require(allParts.isNotEmpty()) { "Yüklenecek dosya yok" }
        require(allParts.size + RESERVED_ASSETS <= MAX_RELEASE_ASSETS) {
            "Bu video ${allParts.size + RESERVED_ASSETS} Release asset'i gerektiriyor. Tek video = tek Release kuralı nedeniyle 1000 asset sınırı aşılamaz."
        }

        val title = mediaManifest.optString("title", videoId)
        val release = getOrCreateRelease(owner, repo, releaseTag, title, token)
        val assets = listAssets(owner, repo, release.id, token).associateBy { it.name }.toMutableMap()

        val totalBytes = files.sumOf { it.size }.coerceAtLeast(1L)
        val completedBytes = AtomicLong(0L)
        val sessionUploadedBytes = AtomicLong(0L)
        val activeBytes = ConcurrentHashMap<String, Long>()
        val tasks = mutableListOf<UploadTask>()
        val progressLock = Any()
        val startedAt = System.nanoTime()

        for (plannedFile in files) {
            for (part in plannedFile.parts) {
                val existing = assets[part.assetName]
                if (existing != null && existing.size == part.length) {
                    completedBytes.addAndGet(part.length)
                    continue
                }
                if (existing != null) {
                    deleteAsset(owner, repo, existing.id, token)
                    assets.remove(existing.name)
                }
                tasks += UploadTask(plannedFile, part)
            }
        }

        reportProgress(
            stage = "upload",
            detail = if (tasks.isEmpty()) "Tüm medya asset'leri zaten GitHub'da" else "GitHub'a paralel yükleme hazırlanıyor",
            completed = completedBytes.get(),
            active = 0L,
            sessionUploaded = 0L,
            total = totalBytes,
            startedAt = startedAt,
            onProgress = onProgress,
        )

        val executor = Executors.newFixedThreadPool(UPLOAD_CONCURRENCY)
        try {
            val futures = tasks.map { task ->
                executor.submit {
                    val part = task.part
                    activeBytes[part.assetName] = 0L
                    uploadSlice(
                        owner = owner,
                        repo = repo,
                        releaseId = release.id,
                        file = task.file.file,
                        start = part.start,
                        length = part.length,
                        assetName = part.assetName,
                        token = token,
                    ) { sent ->
                        activeBytes[part.assetName] = sent
                        synchronized(progressLock) {
                            val active = activeBytes.values.sum()
                            reportProgress(
                                stage = "upload",
                                detail = "${task.file.logicalName} • parça ${part.index + 1}/${task.file.parts.size}",
                                completed = completedBytes.get(),
                                active = active,
                                sessionUploaded = sessionUploadedBytes.get(),
                                total = totalBytes,
                                startedAt = startedAt,
                                onProgress = onProgress,
                            )
                        }
                    }
                    activeBytes.remove(part.assetName)
                    completedBytes.addAndGet(part.length)
                    sessionUploadedBytes.addAndGet(part.length)
                }
            }
            futures.forEach { it.get() }
        } catch (error: Throwable) {
            executor.shutdownNow()
            throw (error.cause ?: error)
        } finally {
            executor.shutdown()
        }

        val storageManifestJson = buildStorageManifest(videoId, releaseTag, files)
        val storageManifestFile = File(jobDir, STORAGE_MANIFEST)
        storageManifestFile.writeText(storageManifestJson.toString(2))

        listAssets(owner, repo, release.id, token)
            .firstOrNull { it.name == STORAGE_MANIFEST }
            ?.let { deleteAsset(owner, repo, it.id, token) }
        uploadWholeFile(owner, repo, release.id, storageManifestFile, STORAGE_MANIFEST, token)

        onProgress(
            UploadProgress(
                stage = "verify",
                percent = 99,
                detail = "GitHub asset'leri doğrulanıyor",
                doneBytes = totalBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = currentSpeed(sessionUploadedBytes.get(), startedAt),
                etaSeconds = 0,
            ),
        )
        verifyAssets(owner, repo, release.id, files, storageManifestFile, token)

        mediaManifest.put("status", "published")
        File(jobDir, MEDIA_MANIFEST).writeText(mediaManifest.toString(2))
        publishRelease(owner, repo, release.id, token)
        verifyReleasePublished(owner, repo, releaseTag, token)

        File(jobDir, "publish.json").writeText(
            JSONObject()
                .put("videoId", videoId)
                .put("primaryReleaseTag", releaseTag)
                .put("releaseTags", JSONArray().put(releaseTag))
                .put("verified", true)
                .toString(2),
        )

        onProgress(
            UploadProgress(
                stage = "published",
                percent = 100,
                detail = "GitHub Release yayınlandı ve doğrulandı",
                doneBytes = totalBytes,
                totalBytes = totalBytes,
                speedBytesPerSecond = currentSpeed(sessionUploadedBytes.get(), startedAt),
                etaSeconds = 0,
            ),
        )
        return PublishResult(videoId, releaseTag, listOf(releaseTag), storageManifestFile, true)
    }

    private fun planFiles(root: File, excludedLogicalNames: Set<String>, releaseTag: String): List<PlannedFile> = root.walkTopDown()
        .filter { it.isFile && it.length() > 0L }
        .filterNot { it.name.endsWith(".part") || it.name == STORAGE_MANIFEST || it.name == "publish.json" }
        .map { file -> file to file.relativeTo(root).invariantSeparatorsPath }
        .filterNot { (_, logical) -> logical in excludedLogicalNames }
        .map { (file, logical) ->
            val size = file.length()
            val totalParts = maxOf(1, ceil(size / CHUNK_SIZE_BYTES.toDouble()).toInt())
            val parts = List(totalParts) { index ->
                val start = index * CHUNK_SIZE_BYTES
                val length = min(CHUNK_SIZE_BYTES, size - start)
                PlannedPart(
                    index = index,
                    start = start,
                    length = length,
                    assetName = assetName(logical, index, totalParts),
                    releaseTag = releaseTag,
                )
            }
            PlannedFile(file, logical, size, parts)
        }
        .sortedBy { it.logicalName }
        .toList()

    private fun buildStorageManifest(videoId: String, releaseTag: String, files: List<PlannedFile>): JSONObject =
        JSONObject()
            .put("schemaVersion", 3)
            .put("videoId", videoId)
            .put("primaryReleaseTag", releaseTag)
            .put("releaseTags", JSONArray().put(releaseTag))
            .put("singleRelease", true)
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
                                    .put("releaseTag", releaseTag)
                                    .put("name", part.assetName)
                                    .put("offset", part.start)
                                    .put("sizeBytes", part.length))
                            }
                        }))
                }
            })

    private fun verifyAssets(
        owner: String,
        repo: String,
        releaseId: Long,
        files: List<PlannedFile>,
        storageManifest: File,
        token: String,
    ) {
        val remote = listAssets(owner, repo, releaseId, token).associateBy { it.name }
        files.flatMap { it.parts }.forEach { part ->
            val asset = remote[part.assetName] ?: error("GitHub doğrulama hatası: eksik asset ${part.assetName}")
            check(asset.size == part.length) {
                "GitHub doğrulama hatası: ${part.assetName} boyutu ${asset.size}, beklenen ${part.length}"
            }
        }
        val storage = remote[STORAGE_MANIFEST] ?: error("GitHub doğrulama hatası: storage-manifest.json eksik")
        check(storage.size == storageManifest.length()) { "storage-manifest.json boyutu doğrulanamadı" }
    }

    private fun getOrCreateRelease(owner: String, repo: String, tag: String, title: String, token: String): ReleaseRef {
        findRelease(owner, repo, tag, token)?.let { return it }
        val body = JSONObject()
            .put("tag_name", tag)
            .put("name", title)
            .put("body", "Published by YTClone Android. One video, one Release; 1.8 GiB chunk storage.")
            .put("draft", true)
            .put("prerelease", false)
        val json = jsonRequest("POST", "https://api.github.com/repos/$owner/$repo/releases", token, body)
        return ReleaseRef(json.getLong("id"), json.getString("tag_name"), json.optBoolean("draft", true))
    }

    private fun findRelease(owner: String, repo: String, tag: String, token: String): ReleaseRef? {
        for (page in 1..10) {
            val array = jsonArrayRequest("https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page", token)
            if (array.length() == 0) return null
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.optString("tag_name") == tag) {
                    return ReleaseRef(item.getLong("id"), tag, item.optBoolean("draft", false))
                }
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

    private fun verifyReleasePublished(owner: String, repo: String, tag: String, token: String) {
        val release = findRelease(owner, repo, tag, token) ?: error("Release yayınlandıktan sonra bulunamadı")
        check(!release.draft) { "Release hâlâ draft durumda; yerel dosyalar silinmedi" }
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

    private fun reportProgress(
        stage: String,
        detail: String,
        completed: Long,
        active: Long,
        sessionUploaded: Long,
        total: Long,
        startedAt: Long,
        onProgress: (UploadProgress) -> Unit,
    ) {
        val done = (completed + active).coerceAtMost(total)
        val speed = currentSpeed(sessionUploaded + active, startedAt)
        val remaining = (total - done).coerceAtLeast(0L)
        val eta = if (speed > 0) remaining / speed else 0L
        val percent = ((done.toDouble() / total.coerceAtLeast(1L)) * 100.0).toInt().coerceIn(0, 98)
        onProgress(
            UploadProgress(
                stage = stage,
                percent = percent,
                detail = "$detail • ${formatBytes(done)} / ${formatBytes(total)} • ${formatSpeed(speed)}${if (eta > 0) " • ETA ${formatEta(eta)}" else ""}",
                doneBytes = done,
                totalBytes = total,
                speedBytesPerSecond = speed,
                etaSeconds = eta,
            ),
        )
    }

    private fun currentSpeed(uploaded: Long, startedAt: Long): Long {
        val seconds = (System.nanoTime() - startedAt).coerceAtLeast(1L) / 1_000_000_000.0
        return (uploaded / seconds).toLong().coerceAtLeast(0L)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> "%.2f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f KiB".format(bytes / 1024.0)
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> "%.1f MiB/s".format(bytesPerSecond / (1024.0 * 1024.0))
        else -> "%.1f KiB/s".format(bytesPerSecond / 1024.0)
    }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
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
    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CHUNK_SIZE_BYTES: Long = 1_932_735_283L // floor(1.8 GiB)
        private const val GITHUB_ASSET_LIMIT_BYTES: Long = 2L * 1024L * 1024L * 1024L
        private const val MAX_RELEASE_ASSETS = 1000
        private const val RESERVED_ASSETS = 1 // storage-manifest.json
        private const val UPLOAD_CONCURRENCY = 3
        private const val MEDIA_MANIFEST = "manifest.json"
        private const val STORAGE_MANIFEST = "storage-manifest.json"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
