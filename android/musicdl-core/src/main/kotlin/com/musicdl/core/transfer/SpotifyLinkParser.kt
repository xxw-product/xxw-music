package com.musicdl.core.transfer

import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.LoggerHandle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One track of a public Spotify link, reduced to the metadata another catalog can be searched for. */
data class SpotifyLinkTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationSeconds: Long,
    val coverUrl: String?,
)

data class SpotifyLink(
    val kind: String,
    val id: String,
    val name: String,
    val tracks: List<SpotifyLinkTrack>,
)

/** Outcome of a Spotify link read; a failure always carries a sentence the UI can show as is. */
sealed interface SpotifyParseResult {
    data class Success(val link: SpotifyLink) : SpotifyParseResult
    data class Failure(val message: String) : SpotifyParseResult
}

/**
 * Public Spotify links without an account, using the endpoint the Telegram plugin relies on:
 * `https://open.spotify.com/embed/{playlist|album|track}/{id}` serves a `__NEXT_DATA__` payload
 * that contains the whole track list.
 *
 * It yields metadata only — Spotify's own audio is DRM-protected — so the caller matches every
 * track against catalogs it can actually download from, which is exactly what the plugin did
 * before it downloaded the audio through yt-dlp.
 *
 * The HTTP status is checked explicitly because the failure modes look identical in the page body:
 * a geo-block answers 451, a private or deleted playlist 404, and a blocked region still returns a
 * valid Next.js payload with an empty page. Each of those becomes its own message instead of a
 * silent empty list.
 */
object SpotifyLinkParser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-z]+/)?|spotify:)(playlist|album|track)[/:]([A-Za-z0-9]+)""")
    private val NEXT_DATA = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(url: String, logger: LoggerHandle = LoggerHandle.SILENT): SpotifyParseResult {
        val match = LINK.find(url.trim())
            ?: return SpotifyParseResult.Failure("Spotify: нужна ссылка на плейлист, альбом или трек")
        val kind = match.groupValues[1]
        val id = match.groupValues[2]
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$kind/$id")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 451 ->
                        SpotifyParseResult.Failure("Spotify: ссылка недоступна в этом регионе (HTTP 451)")
                    response.code == 404 ->
                        SpotifyParseResult.Failure("Spotify: плейлист не найден или он приватный")
                    !response.isSuccessful ->
                        SpotifyParseResult.Failure("Spotify ответил HTTP ${response.code}")
                    else -> readLink(kind, id, body)
                }
            }
        } catch (error: Exception) {
            logger.warning("SpotifyLinkParser >>> $id (${error.message})")
            SpotifyParseResult.Failure("Spotify недоступен: ${error.message?.take(80) ?: "сетевая ошибка"}")
        }
    }

    private fun readLink(kind: String, id: String, html: String): SpotifyParseResult {
        val payload = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: return SpotifyParseResult.Failure("Spotify: страница без данных — ссылка не плейлист?")
        val data = JsonUtil.parseObject(payload)
            ?: return SpotifyParseResult.Failure("Spotify: не разобрал ответ")
        val entity = findEntity(data)
            ?: return SpotifyParseResult.Failure("Spotify не отдал список треков (пустая страница)")
        val name = JsonUtil.str(entity, "title") ?: JsonUtil.str(entity, "name") ?: "Spotify · $id"
        val tracks = tracks(entity, kind, id, name)
        if (tracks.isEmpty()) return SpotifyParseResult.Failure("Spotify: в ссылке нет треков")
        return SpotifyParseResult.Success(SpotifyLink(kind, id, name, tracks))
    }

    private fun tracks(entity: JsonObject, kind: String, linkId: String, name: String): List<SpotifyLinkTrack> {
        val cover = coverOf(entity)
        val trackList = JsonUtil.array(entity, "trackList")
        if (trackList != null) {
            return trackList.filterIsInstance<JsonObject>().mapNotNull { item ->
                val title = JsonUtil.str(item, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                SpotifyLinkTrack(
                    id = JsonUtil.str(item, "uri")?.split(":")?.lastOrNull() ?: "item-${title.hashCode()}",
                    title = title,
                    artists = JsonUtil.str(item, "subtitle").orEmpty(),
                    album = if (kind == "album") name else "",
                    durationSeconds = (JsonUtil.long(item, "duration") ?: 0L) / 1000L,
                    coverUrl = cover,
                )
            }
        }
        val title = JsonUtil.str(entity, "title") ?: JsonUtil.str(entity, "name") ?: return emptyList()
        val artists = JsonUtil.array(entity, "artists").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { JsonUtil.str(it, "name") }
        return listOf(
            SpotifyLinkTrack(
                id = JsonUtil.str(entity, "uri")?.split(":")?.lastOrNull() ?: linkId,
                title = title,
                artists = artists.joinToString(", ").ifBlank { JsonUtil.str(entity, "subtitle").orEmpty() },
                album = "",
                durationSeconds = (JsonUtil.long(entity, "duration") ?: 0L) / 1000L,
                coverUrl = cover,
            ),
        )
    }

    /** Port of the plugin's `find_entity`: the payload nests the entity differently per page type. */
    private fun findEntity(node: Any?, depth: Int = 0): JsonObject? {
        if (depth > 8) return null
        when (node) {
            is JsonObject -> {
                val entity = node["entity"] as? JsonObject
                if (entity != null && (
                    entity["trackList"] != null || entity["artists"] != null ||
                        entity["title"] != null || entity["name"] != null
                        )
                ) return entity
                node.values.forEach { value -> findEntity(value, depth + 1)?.let { return it } }
            }
            is JsonArray -> node.forEach { value -> findEntity(value, depth + 1)?.let { return it } }
        }
        return null
    }

    private fun coverOf(entity: JsonObject): String? {
        val sources = JsonUtil.array(JsonUtil.obj(entity, "coverArt"), "sources")
            .orEmpty().filterIsInstance<JsonObject>()
        sources.maxByOrNull { (JsonUtil.int(it, "width") ?: 0) * (JsonUtil.int(it, "height") ?: 0) }
            ?.let { best -> JsonUtil.str(best, "url")?.let { return it } }
        return JsonUtil.array(JsonUtil.obj(entity, "visualIdentity"), "image")
            .orEmpty().filterIsInstance<JsonObject>()
            .firstNotNullOfOrNull { JsonUtil.str(it, "url") }
    }
}
