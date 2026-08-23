package com.thedrowned925.ytclone.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class PlaylistStore(context: Context) {
    data class Playlist(val id: String, val name: String, val videoIds: List<String>)

    private val prefs = context.getSharedPreferences("ytclone-playlists", Context.MODE_PRIVATE)

    fun list(): List<Playlist> {
        val parsed = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrElse { JSONArray() }
        val result = mutableListOf<Playlist>()
        for (index in 0 until parsed.length()) {
            val item = parsed.optJSONObject(index) ?: continue
            val ids = mutableListOf<String>()
            val array = item.optJSONArray("videos") ?: JSONArray()
            for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(ids::add)
            result += Playlist(item.optString("id"), item.optString("name"), ids.distinct())
        }
        if (result.none { it.id == WATCH_LATER_ID }) {
            val withDefault = listOf(Playlist(WATCH_LATER_ID, "Sonra izle", emptyList())) + result
            save(withDefault)
            return withDefault
        }
        return result
    }

    fun create(name: String): Playlist {
        val cleaned = name.trim().ifBlank { "Yeni liste" }.take(80)
        val playlist = Playlist(UUID.randomUUID().toString(), cleaned, emptyList())
        save(list() + playlist)
        return playlist
    }

    fun addVideo(playlistId: String, videoId: String) {
        if (videoId.isBlank()) return
        val updated = list().map { playlist ->
            if (playlist.id == playlistId) playlist.copy(videoIds = (playlist.videoIds + videoId).distinct()) else playlist
        }
        save(updated)
    }

    fun removeVideo(playlistId: String, videoId: String) {
        save(list().map { if (it.id == playlistId) it.copy(videoIds = it.videoIds - videoId) else it })
    }

    fun delete(playlistId: String) {
        if (playlistId == WATCH_LATER_ID) return
        save(list().filterNot { it.id == playlistId })
    }

    private fun save(playlists: List<Playlist>) {
        val array = JSONArray()
        playlists.forEach { playlist ->
            array.put(JSONObject()
                .put("id", playlist.id)
                .put("name", playlist.name)
                .put("videos", JSONArray(playlist.videoIds)))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        const val WATCH_LATER_ID = "watch-later"
        private const val KEY = "playlists"
    }
}
