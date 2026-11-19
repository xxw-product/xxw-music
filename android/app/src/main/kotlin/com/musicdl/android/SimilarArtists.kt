package com.musicdl.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * "Artists like this one", the backbone of discovery in the wave.
 *
 * Deezer answers anonymously with a ranked list of related artists. Answers are cached on the
 * device (two weeks for a real answer, one hour for an empty one), so after the first wave the
 * lookup costs nothing and works offline. A failed or slow lookup simply yields no suggestions;
 * the wave then relies on the artists the listener already knows.
 */
class SimilarArtists(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("similar_artists", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, List<String>>()

    suspend fun related(artist: String, timeoutMs: Int = 3_000): List<String> = withContext(Dispatchers.IO) {
        val key = Wave.key(artist)
        if (key.length < 2) return@withContext emptyList()
        memory[key]?.let { return@withContext it }
        cached(key)?.let { memory[key] = it; return@withContext it }
        val names = runCatching { fetch(artist, timeoutMs) }.getOrDefault(emptyList())
        prefs.edit().putString(key, JSONObject().put("t", System.currentTimeMillis())
            .put("n", JSONArray(names)).toString()).apply()
        if (names.isNotEmpty()) memory[key] = names
        names
    }

    private fun cached(key: String): List<String>? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val names = json.getJSONArray("n").let { array -> (0 until array.length()).map(array::getString) }
            val ttl = if (names.isEmpty()) 3_600_000L else 14L * 86_400_000L
            if (System.currentTimeMillis() - json.getLong("t") < ttl) names else null
        }.getOrNull()
    }

    private fun fetch(artist: String, timeoutMs: Int): List<String> {
        val query = URLEncoder.encode(artist, "UTF-8")
        val found = JSONObject(get("https://api.deezer.com/search/artist?q=$query&limit=5", timeoutMs))
            .optJSONArray("data") ?: return emptyList()
        val wanted = Wave.key(artist)
        // Only an exact name match is trusted: a wrong artist would poison the whole wave.
        val id = (0 until found.length()).map(found::getJSONObject)
            .firstOrNull { Wave.key(it.optString("name")) == wanted }?.optLong("id") ?: return emptyList()
        val related = JSONObject(get("https://api.deezer.com/artist/$id/related?limit=25", timeoutMs))
            .optJSONArray("data") ?: return emptyList()
        return (0 until related.length()).map { related.getJSONObject(it).optString("name") }
            .filter { it.isNotBlank() }
    }

    private fun get(url: String, timeoutMs: Int): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
