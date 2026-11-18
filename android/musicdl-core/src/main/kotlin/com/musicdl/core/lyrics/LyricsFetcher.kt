package com.musicdl.core.lyrics

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.HttpClient
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One timed line of a song; [timeMs] is the moment it starts. */
data class LyricLine(val timeMs: Long, val text: String)

/** A lyrics lookup result: timed lines when the provider has timings, untouched text otherwise. */
data class Lyrics(
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
    val provider: String = "",
) {
    val hasTimings: Boolean get() = lines.isNotEmpty()
    val isBlank: Boolean get() = lines.isEmpty() && plain.isNullOrBlank()
}

/**
 * Lyrics for a track, looked up by title/artist rather than by source id — the same song is
 * indexed by lrclib no matter which catalog returned it.
 *
 * Order: lrclib `/api/get` (exact match, carries LRC timings), then lrclib `/api/search`
 * (fuzzy index, still timed), then lyrics.ovh for plain text. All three are free and key-less.
 */
object LyricsFetcher {

    private const val LRCLIB = "https://lrclib.net/api"

    private val http = HttpClient(
        maxRetries = 1,
        timeoutSeconds = 5,
        defaultHeaders = mapOf("User-Agent" to "XXWMusic/1.0 (Android lyrics client)"),
    )

    private fun request(url: String, query: Map<String, String>): String? =
        http.get(url, query = query)?.use { it.textOrNull() }
    private val additional = AdditionalLyricsSources(::request)
    private val syncedSources = SyncedLyricsSources(::request)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Lyrics>()

    /** Race bounded, independent providers; a failed provider cannot abort the other lookups. */
    internal suspend fun firstAvailable(tasks: List<() -> Lyrics?>): Lyrics? = supervisorScope {
        val results = Channel<Lyrics?>(Channel.UNLIMITED)
        val jobs = tasks.map { task -> async(Dispatchers.IO) {
            val result = try { task() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { null }
            results.send(result)
        } }
        var plain: Lyrics? = null
        try {
            withTimeoutOrNull(12_000L) {
                repeat(tasks.size) {
                    val candidate = results.receive()?.takeUnless { it.isBlank }
                    if (candidate?.hasTimings == true) return@withTimeoutOrNull candidate
                    if (plain == null) plain = candidate
                }
                plain
            } ?: plain
        } finally {
            jobs.forEach { it.cancel() }
            results.close()
        }
    }

    suspend fun fetch(song: SongInfo, logger: LoggerHandle = LoggerHandle.SILENT): Lyrics? =
        withContext(Dispatchers.IO) {
            val title = song.songName?.trim().orEmpty()
            val artist = song.singers?.trim().orEmpty()
            if (title.isEmpty()) return@withContext null

            val embeddedLyrics = if (song.source !in setOf("SoundCloudMusicClient", "JioSaavnMusicClient", "SpotifyMusicClient", "AppleMusicClient", "ITunesSongsMusicClient", "DeezerMusicClient", "QobuzMusicClient", "JamendoMusicClient", "FMAMusicClient", "CCMixterMusicClient", "TestMusicClient")) null
                else AdditionalLyricsSources.textLyrics(song.lyric, song.source.removeSuffix("MusicClient"))
            val embedded = embeddedLyrics ?: descriptionLyrics(song)
            if (embedded?.hasTimings == true) return@withContext embedded
            val queries = LyricQueries.forSong(song)
            if (queries.isEmpty()) return@withContext embedded
            val cacheKey = "${queries.first()}:${song.durationSeconds}"
            cache[cacheKey]?.let { return@withContext it }
            firstAvailable(listOf(
                { queries.firstNotNullOfOrNull { syncedSources.bini(it, song) } },
                { queries.take(2).firstNotNullOfOrNull { lrcLibExact(it.title, it.artist, song) } },
                { queries.firstNotNullOfOrNull { lrcLibSearch(it.title, it.artist, song) } },
                { embedded },
                { queries.take(2).firstNotNullOfOrNull { lyricsOvh(it.title, it.artist) } },
                { additional.jioSaavn(song) },
            ))?.let {
                if (it.hasTimings) {
                    if (cache.size >= 128) cache.clear()
                    cache[cacheKey] = it
                }
                return@withContext it
            }
            logger.info("LyricsFetcher >>> nothing found for $artist - $title")
            null
        }

    private fun descriptionLyrics(song: SongInfo): Lyrics? {
        if (!song.source.equals("SoundCloudMusicClient", ignoreCase = true)) return null
        val desc = (song.description ?: (song.rawData["search"] as? JsonObject)?.let { JsonUtil.str(it, "description") })?.trim().orEmpty()
        val timed = parseLrc(desc)
        if (timed.isNotEmpty()) return Lyrics(lines = timed, provider = "SoundCloud")
        if (desc.length < 40) return null
        val lines = desc.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val marker = lines.indexOfFirst {
            it.matches(Regex("""(?i)(lyrics|текст(?: песни)?|слова)(?:\s*:)?"""))
        }
        if (marker < 0) return null
        val verse = lines.drop(marker + 1).takeWhile { !it.contains("http", ignoreCase = true) }
        if (verse.size < 4) return null
        val joined = verse.joinToString("\n")
        // Heuristic: avoid generic promo descriptions.
        val lower = joined.lowercase()
        val promoHits = listOf("follow", "subscribe", "http", "www.", "instagram", "spotify", "buy", "free download").count { lower.contains(it) }
        if (promoHits >= 3) return null
        return AdditionalLyricsSources.textLyrics(joined, "SoundCloud description")
    }

    /** `/api/get` wants the exact triple; a duration mismatch there is treated as "not found". */
    private fun lrcLibExact(title: String, artist: String, song: SongInfo): Lyrics? {
        val query = mutableMapOf("track_name" to title)
        if (artist.isNotBlank()) query["artist_name"] = artist
        song.album?.takeIf { it.isNotBlank() && it != "NULL" }?.let { query["album_name"] = it }
        if (song.durationSeconds > 0L) query["duration"] = song.durationSeconds.toString()
        val body = http.get("$LRCLIB/get", query = query)?.use { it.textOrNull() } ?: return null
        val payload = JsonUtil.parseObject(body) ?: return null
        return fromLrcLib(payload, "lrclib")
    }

    /** Fuzzy index: only accept a hit whose title/artist actually match the request. */
    private fun lrcLibSearch(title: String, artist: String, song: SongInfo): Lyrics? {
        val body = http.get("$LRCLIB/search", query = mapOf("track_name" to title, "artist_name" to artist))?.use { it.textOrNull() } ?: return null
        val items = (JsonUtil.parse(body) as? JsonArray)?.filterIsInstance<JsonObject>() ?: return null
        val related = items.filter { relatedHit(it, title, artist) }.filter {
            val duration = JsonUtil.long(it, "duration") ?: 0L
            duration == 0L || song.durationSeconds == 0L || kotlin.math.abs(duration - song.durationSeconds) <= 8
        }
        // A record with timings beats a longer plain one.
        val ordered = related.sortedByDescending { parseLrc(JsonUtil.str(it, "syncedLyrics")).size }
        for (item in ordered) fromLrcLib(item, "lrclib search")?.let { return it }
        return null
    }

    private fun relatedHit(item: JsonObject, title: String, artist: String): Boolean {
        return AdditionalLyricsSources.matches(title, artist,
            JsonUtil.str(item, "trackName").orEmpty(), JsonUtil.str(item, "artistName").orEmpty())
    }

    private fun fromLrcLib(item: JsonObject, provider: String): Lyrics? {
        if (JsonUtil.bool(item, "instrumental") == true) {
            return Lyrics(plain = "Инструментал — текста нет", provider = provider)
        }
        val synced = JsonUtil.str(item, "syncedLyrics")
        val plain = JsonUtil.str(item, "plainLyrics")
        val lines = parseLrc(synced)
        if (lines.isNotEmpty()) return Lyrics(lines = lines, plain = plain, provider = provider)
        if (!plain.isNullOrBlank()) return Lyrics(plain = plain.trim(), provider = provider)
        return null
    }

    /** Plain-text fallback: no timings, but better than an empty sheet for older/rarer tracks. */
    private fun lyricsOvh(title: String, artist: String): Lyrics? {
        if (artist.isBlank()) return null
        val url = runCatching {
            "https://api.lyrics.ovh/v1".toHttpUrl().newBuilder()
                .addPathSegment(artist)
                .addPathSegment(title)
                .build()
                .toString()
        }.getOrNull() ?: return null
        val body = http.get(url)?.use { it.textOrNull() } ?: return null
        val payload = JsonUtil.parseObject(body) ?: return null
        val text = JsonUtil.str(payload, "lyrics")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cleaned = text.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("Paroles de la chanson", ignoreCase = true) }
            .joinToString("\n")
            .trim()
        return if (cleaned.isEmpty()) null else Lyrics(plain = cleaned, provider = "lyrics.ovh")
    }

    /**
     * LRC parser: one line may carry several `[mm:ss.xx]` stamps, and `[ar:]`/`[by:]` metadata is
     * ignored because only timestamp-shaped tags match.
     */
    fun parseLrc(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (line in raw.lineSequence()) {
            val stamps = TIME_TAG.findAll(line).toList()
            if (stamps.isEmpty()) continue
            val text = line.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (stamp in stamps) {
                val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                val seconds = stamp.groupValues[2].toLongOrNull() ?: 0L
                val fraction = when (val raw = stamp.groupValues[3]) {
                    "" -> 0L
                    else -> raw.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
                }
                out += LyricLine(minutes * 60_000L + seconds * 1_000L + fraction, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
}
