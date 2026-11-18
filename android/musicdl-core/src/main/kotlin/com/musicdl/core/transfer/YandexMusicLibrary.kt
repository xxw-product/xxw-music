package com.musicdl.core.transfer

import com.musicdl.core.net.HttpClient
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One track from a Yandex Music account, reduced to what catalog matching needs. */
data class YandexTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationSeconds: Long = 0L,
) {
    val query: String get() = (artists.take(2) + title).joinToString(" ").trim()
}

data class YandexAlbum(
    val id: String,
    val title: String,
    val artists: List<String>,
    val tracks: List<YandexTrack> = emptyList(),
) {
    val label: String get() = listOfNotNull(artists.firstOrNull(), title).joinToString(" — ")
}
data class YandexPlaylist(val kind: Int, val title: String, val tracks: List<YandexTrack>)

data class YandexLibrary(
    val account: String,
    val tracks: List<YandexTrack>,
    val artists: List<String>,
    val albums: List<YandexAlbum>,
    val playlists: List<YandexPlaylist>,
)

class YandexAuthException(message: String) : Exception(message)

/**
 * Read-only access to a user's own Yandex Music library.
 *
 * Uses the public mobile endpoints with the user's OAuth token (`Authorization: OAuth …`), the
 * same token the official clients send; nothing is scraped, no session is shared and no content is
 * republished. Without a token [isConfigured] is false and every call returns an empty library —
 * importing is an explicit, user-startable action, never a background sync.
 *
 * Legal note: the token grants access to the user's subscription. Importing likes into another
 * player for personal use is what the UI offers; the resulting playlist is a list of references
 * resolved through the app's own catalogs, not a copy of Yandex files.
 */
class YandexMusicLibrary(private val token: String, private val logger: LoggerHandle = LoggerHandle.SILENT) {

    val isConfigured: Boolean get() = token.isNotBlank()

    private val http = HttpClient(maxRetries = 2, timeoutSeconds = 12, maintainSession = true)

    private fun headers(): Map<String, String> = mapOf(
        "Authorization" to "OAuth $token",
        "X-Yandex-Music-Client" to "YandexMusicAndroid/24023621",
        "Accept-Language" to "ru",
        "User-Agent" to "Yandex-Music-API",
    )

    private suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonObject? =
        withContext(Dispatchers.IO) {
            val body = http.get(API + path, query = query, extraHeaders = headers())?.use { it.textOrNull() }
                ?: return@withContext null
            val root = JsonUtil.parseObject(body) ?: return@withContext null
            JsonUtil.obj(root, "error")?.let { error ->
                val name = JsonUtil.str(error, "name").orEmpty()
                if (name.equals("unauthorized", true) || name.equals("invalid_token", true)) {
                    throw YandexAuthException("Токен Яндекса не принят. Получите новый токен.")
                }
                logger.warning("YandexMusic.$path >>> ${JsonUtil.str(error, "message") ?: name}")
                return@withContext null
            }
            root
        }

    suspend fun account(): String? {
        if (!isConfigured) return null
        val root = get("/account/status") ?: return null
        val account = JsonUtil.obj(JsonUtil.obj(root, "result"), "account") ?: return null
        return JsonUtil.str(account, "displayName") ?: JsonUtil.str(account, "login") ?: JsonUtil.str(account, "uid")
    }

    private suspend fun uid(): String? {
        val root = get("/account/status") ?: return null
        return JsonUtil.str(JsonUtil.obj(JsonUtil.obj(root, "result"), "account"), "uid")
    }

    suspend fun library(): YandexLibrary {
        if (!isConfigured) return YandexLibrary("", emptyList(), emptyList(), emptyList(), emptyList())
        val uid = uid() ?: throw YandexAuthException("Не удалось определить аккаунт Яндекса по токену")
        val name = account() ?: uid

        val likedTracks = get("/users/$uid/likes/tracks")?.let { root ->
            JsonUtil.array(JsonUtil.obj(JsonUtil.obj(root, "result"), "library"), "tracks").orEmpty()
                .mapNotNull { parseTrack(it as? JsonObject) }
        }.orEmpty()

        val artists = get("/users/$uid/likes/artists")?.let { root ->
            entries(JsonUtil.obj(JsonUtil.obj(root, "result"), "library"), "artists")
                .mapNotNull { entry ->
                    JsonUtil.str(entry, "name") ?: JsonUtil.str(JsonUtil.obj(entry, "artist"), "name")
                }
        }.orEmpty()

        // Liked albums are imported as playlists, so their track lists are fetched too. The cap
        // keeps a big library from turning one import into hundreds of requests.
        val albums = get("/users/$uid/likes/albums")?.let { root ->
            entries(JsonUtil.obj(JsonUtil.obj(root, "result"), "library"), "albums").take(24).mapNotNull { entry ->
                val album = JsonUtil.obj(entry, "album") ?: entry
                val id = JsonUtil.str(album, "id") ?: return@mapNotNull null
                val title = JsonUtil.str(album, "title") ?: return@mapNotNull null
                YandexAlbum(id, title, artistNames(album), runCatching { albumTracks(id) }.getOrDefault(emptyList()))
            }
        }.orEmpty()

        val playlistHeaders = get("/users/$uid/playlists/list")?.let { root ->
            (root["result"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        }.orEmpty()

        val playlists = playlistHeaders.mapNotNull { header ->
            val kind = JsonUtil.int(header, "kind") ?: return@mapNotNull null
            val title = JsonUtil.str(header, "title") ?: "Плейлист $kind"
            val detail = get("/users/$uid/playlists/$kind")
            val tracks = JsonUtil.array(JsonUtil.obj(detail, "result"), "tracks").orEmpty()
                .mapNotNull { parseTrack(it as? JsonObject) }
            YandexPlaylist(kind, title, tracks)
        }

        return YandexLibrary(name, likedTracks.distinctBy { it.id }, artists.distinct(), albums.distinctBy { it.id }, playlists)
    }

    /** Tracks of one album, flattened from its volumes. Used only for liked albums. */
    private suspend fun albumTracks(albumId: String): List<YandexTrack> {
        if (!albumId.all(Char::isDigit)) return emptyList()
        val root = get("/albums/$albumId/with-tracks") ?: return emptyList()
        val result = JsonUtil.obj(root, "result") ?: return emptyList()
        return JsonUtil.array(result, "volumes").orEmpty()
            .flatMap { volume -> (volume as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
            .mapNotNull(::parseTrack)
            .distinctBy { it.id }
    }

    private fun entries(owner: JsonObject?, key: String): List<JsonObject> =
        JsonUtil.array(owner, key).orEmpty().filterIsInstance<JsonObject>()

    private fun artistNames(node: JsonObject): List<String> =
        JsonUtil.array(node, "artists").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { JsonUtil.str(it, "name") }
            .ifEmpty { listOfNotNull(JsonUtil.str(node, "artist")) }

    private fun parseTrack(item: JsonObject?): YandexTrack? {
        if (item == null) return null
        val track = JsonUtil.obj(item, "track") ?: item
        val id = JsonUtil.str(track, "id") ?: return null
        val title = JsonUtil.str(track, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val album = JsonUtil.array(track, "albums").orEmpty().firstOrNull()
            ?.let { (it as? JsonObject)?.let { node -> JsonUtil.str(node, "title") } }
        return YandexTrack(
            id = id,
            title = title,
            artists = artistNames(track),
            album = album,
            durationSeconds = (JsonUtil.long(track, "durationMs") ?: 0L) / 1000L,
        )
    }

    fun close() = http.close()

    private companion object {
        const val API = "https://api.music.yandex.net"
    }
}
