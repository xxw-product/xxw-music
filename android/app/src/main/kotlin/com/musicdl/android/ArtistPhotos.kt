package com.musicdl.android

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Artist portraits from Deezer's anonymous search. Each name is looked up once and the answer is
 * stored on the device (a month for a hit, a day for a miss), so lists show photos instantly and
 * offline afterwards. Only an exact name match is accepted: a namesake's face is worse than a letter.
 */
object ArtistPhotos {
    private val memory = ConcurrentHashMap<String, String>()   // "" = known miss
    private val gate = Semaphore(3)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("artist_photos", Context.MODE_PRIVATE)

    /** What is already known without any I/O: a URL, or null when unknown or missing. */
    fun peek(name: String): String? = memory[Wave.key(name)]?.takeIf { it.isNotEmpty() }

    suspend fun photoUrl(context: Context, name: String): String? {
        val key = Wave.key(name)
        if (key.length < 2) return null
        memory[key]?.let { return it.takeIf(String::isNotEmpty) }
        val stored = stored(context, key)
        if (stored != null) {
            memory[key] = stored
            return stored.takeIf(String::isNotEmpty)
        }
        val url = gate.withPermit {
            memory[key]?.let { return@withPermit it }
            withContext(Dispatchers.IO) { runCatching { fetch(name) }.getOrNull() }
        }
        // A network failure is not a miss: only a completed lookup is remembered.
        if (url != null) {
            memory[key] = url
            prefs(context).edit().putString(key, JSONObject().put("t", System.currentTimeMillis()).put("u", url).toString()).apply()
        }
        return url?.takeIf(String::isNotEmpty)
    }

    private fun stored(context: Context, key: String): String? {
        val raw = prefs(context).getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val url = json.getString("u")
            val ttl = if (url.isEmpty()) 86_400_000L else 30L * 86_400_000L
            if (System.currentTimeMillis() - json.getLong("t") < ttl) url else null
        }.getOrNull()
    }

    /** An artist found by name, for the cards above search results. */
    data class Hit(val name: String, val photo: String?, val fans: Long)

    private val searches = ConcurrentHashMap<String, List<Hit>>()

    /**
     * Artists whose name matches [query] (all query words appear in the name), most popular first.
     * Photos found on the way are remembered, so the avatars on the cards render instantly.
     */
    suspend fun search(query: String, limit: Int = 6): List<Hit> {
        val key = Wave.key(query)
        if (key.length < 2) return emptyList()
        searches[key]?.let { return it }
        val hits = withContext(Dispatchers.IO) {
            runCatching {
                val body = get("https://api.deezer.com/search/artist?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=12") ?: return@runCatching null
                val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptyList<Hit>()
                val words = key.split(' ').filter { it.isNotBlank() }
                (0 until data.length()).map(data::getJSONObject).mapNotNull { item ->
                    val name = item.optString("name").trim()
                    if (name.isEmpty() || !matches(Wave.key(name), words)) return@mapNotNull null
                    val picture = item.optString("picture_big").ifBlank { item.optString("picture_medium") }
                    Hit(name, picture.takeIf { it.isNotBlank() && !it.contains("/images/artist//") }, item.optLong("nb_fan"))
                }.distinctBy { Wave.key(it.name) }.sortedByDescending { it.fans }.take(limit)
            }.getOrNull()
        } ?: return emptyList()   // a failed lookup is not cached
        hits.forEach { hit -> hit.photo?.let { memory.putIfAbsent(Wave.key(hit.name), it) } }
        searches[key] = hits
        return hits
    }

    /** Every query word must occur in the artist name; word order does not matter. */
    internal fun matches(nameKey: String, words: List<String>): Boolean =
        words.isNotEmpty() && words.all { it in nameKey }

    /** Full credit first, then the first name of "A, B" / "A feat. B" style credits. */
    internal fun candidates(name: String): List<String> {
        val first = name.split(Regex("""\s*(?:,|;|/|&|\bfeat\.?\b|\bft\.?\b|\bx\b)\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim().orEmpty()
        return listOf(name.trim(), first).filter { it.length >= 2 }.distinct()
    }

    /** Returns the photo URL, "" for a confirmed miss, or null when the lookup itself failed. */
    private fun fetch(name: String): String? {
        var answered = false
        for (candidate in candidates(name)) {
            val body = get("https://api.deezer.com/search/artist?q=${URLEncoder.encode(candidate, "UTF-8")}&limit=5") ?: continue
            answered = true
            val data = JSONObject(body).optJSONArray("data") ?: continue
            val wanted = Wave.key(candidate)
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                if (Wave.key(item.optString("name")) != wanted) continue
                val picture = item.optString("picture_big").ifBlank { item.optString("picture_medium") }
                // Deezer serves a grey placeholder for artists without a photo; its path has no artist hash.
                if (picture.isNotBlank() && !picture.contains("/images/artist//")) return picture
            }
        }
        return if (answered) "" else null
    }

    private fun get(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 5_000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        try {
            if (connection.responseCode !in 200..299) null else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Round artist picture; falls back to the initial on a colour gradient while loading or when there is none. */
@Composable
fun ArtistAvatar(name: String, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val enabled = Appearance.showArtistPhotos
    val url by produceState(if (enabled) ArtistPhotos.peek(name) else null, name, enabled) {
        value = if (enabled) ArtistPhotos.photoUrl(context, name) else null
    }
    Box(
        modifier.size(size).clip(CircleShape).background(
            Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        CoverImage(url = url, modifier = Modifier.fillMaxSize()) {
            Text(
                text = name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
