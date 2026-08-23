package com.thedrowned925.ytclone.storage

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class GitHubCatalogPublisher(
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class CatalogResult(
        val releaseTag: String,
        val videoCount: Int,
        val channelCount: Int,
    )

    private data class ReleaseRef(val id: Long, val tag: String)
    private data class AssetRef(val id: Long, val name: String, val size: Long)

    fun update(
        repoValue: String,
        token: String,
        mediaManifest: JSONObject,
        channelSnapshot: JSONObject,
        videoReleaseTag: String,
        onProgress: (String) -> Unit = {},
    ): CatalogResult {
        val (owner, repo) = parseRepo(repoValue)
        onProgress("Katalog Release'i hazırlanıyor")
        val release = getOrCreateCatalogRelease(owner, repo, token)
        val assets = listAssets(owner, repo, release.id, token).associateBy { it.name }.toMutableMap()

        val catalog = assets[VIDEO_CATALOG]?.let { downloadJson(owner, repo, it.id, token) }
            ?: JSONObject().put("schemaVersion", 1).put("videos", JSONArray())
        val channels = assets[CHANNEL_CATALOG]?.let { downloadJson(owner, repo, it.id, token) }
            ?: JSONObject().put("schemaVersion", 1).put("channels", JSONArray())

        val videoId = mediaManifest.optString("sourceId").ifBlank { videoReleaseTag.removePrefix("ytclone-") }
        val channelId = channelSnapshot.optString("channelId").ifBlank { mediaManifest.optString("channelId") }
        val videoEntry = buildVideoEntry(mediaManifest, videoReleaseTag, videoId, channelId)
        upsertById(catalog.getOrCreateArray("videos"), "id", videoId, videoEntry)

        val channelEntry = buildChannelEntry(channelSnapshot, videoReleaseTag, videoId)
        upsertChannel(channels.getOrCreateArray("channels"), channelId, channelEntry, videoId)

        catalog.put("updatedAtEpochMs", System.currentTimeMillis())
        channels.put("updatedAtEpochMs", System.currentTimeMillis())

        onProgress("catalog.json güncelleniyor")
        replaceJsonAsset(owner, repo, release.id, VIDEO_CATALOG, catalog, assets[VIDEO_CATALOG], token)
        onProgress("channels.json güncelleniyor")
        replaceJsonAsset(owner, repo, release.id, CHANNEL_CATALOG, channels, assets[CHANNEL_CATALOG], token)

        return CatalogResult(
            releaseTag = CATALOG_TAG,
            videoCount = catalog.optJSONArray("videos")?.length() ?: 0,
            channelCount = channels.optJSONArray("channels")?.length() ?: 0,
        )
    }

    private fun buildVideoEntry(
        manifest: JSONObject,
        releaseTag: String,
        videoId: String,
        channelId: String,
    ): JSONObject {
        val qualities = JSONArray()
        val qualityJson = manifest.optJSONObject("qualities") ?: JSONObject()
        for (key in qualityJson.keys()) {
            val item = qualityJson.optJSONObject(key) ?: continue
            qualities.put(JSONObject()
                .put("id", key)
                .put("height", item.optInt("height"))
                .put("fps", item.optInt("fps", 30))
                .put("codec", item.optString("codec"))
                .put("container", item.optString("container"))
                .put("logicalName", item.optString("logicalName")))
        }

        val audio = JSONArray()
        val sourceAudio = manifest.optJSONArray("audioTracks") ?: JSONArray()
        for (index in 0 until sourceAudio.length()) {
            val item = sourceAudio.optJSONObject(index) ?: continue
            audio.put(JSONObject()
                .put("language", item.optString("language", "und"))
                .put("label", item.optString("label"))
                .put("codec", item.optString("codec"))
                .put("logicalName", item.optString("logicalName"))
                .put("default", item.optBoolean("default", index == 0)))
        }

        val subtitles = JSONArray()
        val sourceSubs = manifest.optJSONArray("subtitles") ?: JSONArray()
        for (index in 0 until sourceSubs.length()) subtitles.put(sourceSubs.getJSONObject(index))

        return JSONObject()
            .put("id", videoId)
            .put("title", manifest.optString("title"))
            .put("description", manifest.optString("description"))
            .put("channelId", channelId)
            .put("channel", manifest.optString("channel"))
            .put("durationSeconds", manifest.optDouble("durationSeconds", 0.0))
            .put("uploadDate", manifest.optString("uploadDate"))
            .put("sourceUrl", manifest.optString("webpageUrl"))
            .put("releaseTag", releaseTag)
            .put("manifestLogicalName", "manifest.json")
            .put("thumbnailLogicalName", manifest.optString("thumbnailLogicalName"))
            .put("qualities", qualities)
            .put("audioTracks", audio)
            .put("subtitles", subtitles)
    }

    private fun buildChannelEntry(snapshot: JSONObject, releaseTag: String, videoId: String): JSONObject =
        JSONObject()
            .put("channelId", snapshot.optString("channelId"))
            .put("name", snapshot.optString("name"))
            .put("url", snapshot.optString("url"))
            .put("handle", snapshot.optString("handle"))
            .put("description", snapshot.optString("description"))
            .put("subscriberCount", snapshot.optLong("subscriberCount", 0L))
            .put("avatar", pointer(releaseTag, snapshot.optString("avatarLogicalName")))
            .put("banner", pointer(releaseTag, snapshot.optString("bannerLogicalName")))
            .put("latestReleaseTag", releaseTag)
            .put("videos", JSONArray().put(videoId))

    private fun pointer(releaseTag: String, logicalName: String): Any =
        if (logicalName.isBlank() || logicalName == "null") JSONObject.NULL
        else JSONObject().put("releaseTag", releaseTag).put("logicalName", logicalName)

    private fun upsertById(array: JSONArray, key: String, id: String, value: JSONObject) {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString(key) == id) {
                array.put(index, value)
                return
            }
        }
        array.put(value)
    }

    private fun upsertChannel(array: JSONArray, channelId: String, value: JSONObject, videoId: String) {
        for (index in 0 until array.length()) {
            val existing = array.optJSONObject(index) ?: continue
            if (existing.optString("channelId") != channelId) continue
            val videos = existing.optJSONArray("videos") ?: JSONArray()
            val merged = linkedSetOf<String>()
            for (i in 0 until videos.length()) videos.optString(i).takeIf(String::isNotBlank)?.let(merged::add)
            merged += videoId
            value.put("videos", JSONArray(merged.toList()))
            array.put(index, value)
            return
        }
        array.put(value)
    }

    private fun JSONObject.getOrCreateArray(name: String): JSONArray {
        optJSONArray(name)?.let { return it }
        return JSONArray().also { put(name, it) }
    }

    private fun getOrCreateCatalogRelease(owner: String, repo: String, token: String): ReleaseRef {
        findRelease(owner, repo, CATALOG_TAG, token)?.let { return it }
        val body = JSONObject()
            .put("tag_name", CATALOG_TAG)
            .put("name", "YTClone Catalog")
            .put("body", "Machine-readable YTClone video and channel catalogs.")
            .put("draft", false)
            .put("prerelease", false)
        val json = jsonRequest("POST", "https://api.github.com/repos/$owner/$repo/releases", token, body)
        return ReleaseRef(json.getLong("id"), json.getString("tag_name"))
    }

    private fun findRelease(owner: String, repo: String, tag: String, token: String): ReleaseRef? {
        for (page in 1..10) {
            val array = jsonArrayRequest("https://api.github.com/repos/$owner/$repo/releases?per_page=100&page=$page", token)
            if (array.length() == 0) return null
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
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
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                result += AssetRef(item.getLong("id"), item.getString("name"), item.getLong("size"))
            }
            if (array.length() < 100) break
        }
        return result
    }

    private fun downloadJson(owner: String, repo: String, assetId: Long, token: String): JSONObject {
        val request = baseRequest("https://api.github.com/repos/$owner/$repo/releases/assets/$assetId", token)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) error("Katalog indirilemedi: GitHub ${response.code}")
            JSONObject(body)
        }
    }

    private fun replaceJsonAsset(
        owner: String,
        repo: String,
        releaseId: Long,
        name: String,
        json: JSONObject,
        existing: AssetRef?,
        token: String,
    ) {
        existing?.let { deleteAsset(owner, repo, it.id, token) }
        val url = "https://uploads.github.com/repos/$owner/$repo/releases/$releaseId/assets?name=$name"
        val request = baseRequest(url, token)
            .post(json.toString(2).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("Katalog yüklenemedi ${response.code}: $text")
        }
    }

    private fun deleteAsset(owner: String, repo: String, assetId: Long, token: String) {
        val request = baseRequest("https://api.github.com/repos/$owner/$repo/releases/assets/$assetId", token)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) error("Eski katalog asset'i silinemedi: ${response.code}")
        }
    }

    private fun jsonRequest(method: String, url: String, token: String, body: JSONObject): JSONObject {
        val requestBody = body.toString().toRequestBody(JSON)
        val builder = baseRequest(url, token)
        when (method) {
            "POST" -> builder.post(requestBody)
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

    private fun parseRepo(value: String): Pair<String, String> {
        val parts = value.trim().split('/')
        require(parts.size == 2 && parts.all { it.isNotBlank() }) { "Repo owner/name biçiminde olmalı" }
        return parts[0] to parts[1]
    }

    companion object {
        const val CATALOG_TAG = "ytclone-catalog"
        private const val VIDEO_CATALOG = "catalog.json"
        private const val CHANNEL_CATALOG = "channels.json"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
