package com.musicdl.core.source

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.HttpClient
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/** Parse only explicit catalog fields. Descriptions, names and titles are never genre evidence. */
object TrackGenres {
    private fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
        ?.trim()?.takeIf { it.isNotBlank() && it != "null" && it != "NULL" }

    fun fromCatalog(item: JsonObject): Pair<String?, String?> {
        val album = item["album"] as? JsonObject
        val primary = text(item["genre"]) ?: (item["genre"] as? JsonObject)?.let { text(it["name"]) }
            ?: (album?.get("genre") as? JsonObject)?.let { text(it["name"]) }
        fun names(owner: JsonObject?): List<String> {
            if (owner == null) return emptyList()
            val genres = owner["genres"] as? JsonObject
            val entries = genres?.get("data") as? JsonArray ?: owner["genres_list"] as? JsonArray ?: JsonArray(emptyList())
            return entries.mapNotNull { entry ->
                if (entry is JsonObject) text(entry["name"]) else text(entry)
            }.flatMap { it.split('→', '›', '/').map(String::trim) }.filter(String::isNotBlank)
        }
        val all = (names(item) + names(album)).distinct()
        val tags = (listOfNotNull(text(item["tag_list"]), text(item["tags"])) + all).distinct().joinToString("; ")
        return (primary ?: all.firstOrNull()) to tags.takeIf(String::isNotBlank)
    }

    fun merge(song: SongInfo, item: JsonObject): SongInfo {
        val (genre, tags) = fromCatalog(item)
        return song.copy(genre = genre ?: song.genre, tags = listOfNotNull(song.tags, tags)
            .filter(String::isNotBlank).distinct().joinToString("; ").takeIf(String::isNotBlank))
    }
}

/** Lazy, bounded catalog enrichment for played/saved tracks; album responses are shared. */
class TrackGenreClient(
    private val deezerBase: String = "https://api.deezer.com",
    private val qobuzBase: String = "https://www.qobuz.com/api.json/0.2",
) : Closeable {
    private val http = HttpClient(maxRetries = 1, timeoutSeconds = 6, maintainSession = true)
    private val cache = ConcurrentHashMap<String, JsonObject>()
    private val failures = ConcurrentHashMap<String, Long>()

    private fun get(url: String, query: Map<String, String> = emptyMap()): JsonObject? {
        val key = url + query.toSortedMap().toString()
        cache[key]?.let { return it }
        if (System.currentTimeMillis() - (failures[key] ?: 0L) < 600_000L) return null
        val body = http.get(url, query = query)?.use { it.textOrNull() }
        val parsed = body?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }
        if (parsed == null || parsed["error"] != null) {
            if (failures.size >= 256) failures.clear()
            failures[key] = System.currentTimeMillis()
            return null
        }
        if (cache.size >= 256) cache.clear()
        cache[key] = parsed
        return parsed
    }

    suspend fun enrich(song: SongInfo): SongInfo = try {
        enrichCatalog(song)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { song }

    private suspend fun enrichCatalog(song: SongInfo): SongInfo = withContext(Dispatchers.IO) {
        val id = song.identifier?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return@withContext song
        val inline = song.rawData["search"] as? JsonObject
        val merged = inline?.let { TrackGenres.merge(song, it) } ?: song
        if (!merged.genre.isNullOrBlank() && !merged.tags.isNullOrBlank()) return@withContext merged
        when (song.source) {
            "DeezerMusicClient" -> {
                val track = get("$deezerBase/track/$id") ?: return@withContext merged
                if (track["id"]?.jsonPrimitive?.contentOrNull != id) return@withContext merged
                val albumId = (track["album"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() }
                val album = albumId?.let { get("$deezerBase/album/$it") }
                TrackGenres.merge(TrackGenres.merge(merged, track), album ?: buildJsonObject {})
            }
            "QobuzMusicClient" -> get("$qobuzBase/track/get", mapOf("track_id" to id, "app_id" to "712109809"))
                ?.takeIf { it["id"]?.jsonPrimitive?.contentOrNull == id }
                ?.let { TrackGenres.merge(merged, it) } ?: merged
            // SoundCloud resolves full track metadata during search, including genre and tag_list.
            else -> merged
        }
    }

    override fun close() = http.close()
}
