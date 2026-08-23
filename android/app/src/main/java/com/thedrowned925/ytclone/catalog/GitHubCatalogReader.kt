package com.thedrowned925.ytclone.catalog

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Reads the lightweight machine catalog from the fixed ytclone-catalog Release. */
class GitHubCatalogReader(
    repoValue: String,
    private val token: String,
    private val client: OkHttpClient = OkHttpClient(),
) {
    data class Payload(val catalog: JSONObject, val channels: JSONObject)
    private data class Asset(val id: Long, val name: String)

    private val parts = repoValue.trim().split('/').also {
        require(it.size == 2 && it.all(String::isNotBlank)) { "Repo owner/name biçiminde olmalı" }
    }
    private val owner = parts[0]
    private val repo = parts[1]

    fun load(): Payload {
        val release = getJson("https://api.github.com/repos/$owner/$repo/releases/tags/$CATALOG_TAG")
        val releaseId = release.getLong("id")
        val assets = listAssets(releaseId).associateBy { it.name }
        val catalog = assets[VIDEO_CATALOG]?.let(::downloadJson)
            ?: JSONObject().put("schemaVersion", 1).put("videos", JSONArray())
        val channels = assets[CHANNEL_CATALOG]?.let(::downloadJson)
            ?: JSONObject().put("schemaVersion", 1).put("channels", JSONArray())
        return Payload(catalog, channels)
    }

    private fun listAssets(releaseId: Long): List<Asset> = buildList {
        for (page in 1..10) {
            val request = request("https://api.github.com/repos/$owner/$repo/releases/$releaseId/assets?per_page=100&page=$page").build()
            val array = client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) error("Katalog asset listesi okunamadı (${response.code}): ${text.take(200)}")
                JSONArray(text)
            }
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(Asset(item.getLong("id"), item.getString("name")))
            }
            if (array.length() < 100) break
        }
    }

    private fun downloadJson(asset: Asset): JSONObject {
        val req = request("https://api.github.com/repos/$owner/$repo/releases/assets/${asset.id}")
            .header("Accept", "application/octet-stream")
            .build()
        return client.newCall(req).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("${asset.name} indirilemedi (${response.code})")
            JSONObject(text)
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = request(url).build()
        return client.newCall(req).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) error("GitHub katalog Release'i okunamadı (${response.code}): ${text.take(200)}")
            JSONObject(text)
        }
    }

    private fun request(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "ytclone-android")

    companion object {
        const val CATALOG_TAG = "ytclone-catalog"
        private const val VIDEO_CATALOG = "catalog.json"
        private const val CHANNEL_CATALOG = "channels.json"
    }
}
