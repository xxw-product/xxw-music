package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/**
 * Yandex Music as a source, built on the same public endpoints the Telegram
 * «Playlist to Profile» plugin uses:
 *
 * * search — `GET /search?type=track`;
 * * shared playlist — `GET /users/{owner}/playlists/{kind}`;
 * * playlist by uuid — `GET /playlist/{uuid}`;
 * * album — `GET /albums/{id}/with-tracks`;
 * * id-only playlist entries — `POST /tracks` with `track-ids`, exactly like the plugin's
 *   `_ya_fetch_tracks_by_ids`.
 *
 * Audio comes from `GET /tracks/{id}/download-info`: the API answers with a signed request URL
 * whose XML descriptor (`host`, `path`, `ts`, `s`) is turned into the `/get-mp3/...` link the
 * official Android client streams.
 *
 * Quality rule, and the reason search and playlist parsing differ: anonymously the API marks most
 * offers `preview: true`, and the file it hands out is a low-bitrate encode of the whole track.
 * [searchOne] therefore keeps only tracks that have a full-file offer, so a preview never reaches
 * the feed as a normal row. [parsePlaylist] keeps every track, with the link only when one exists;
 * the caller resolves the rest against its own catalogs, which is what the plugin did with yt-dlp.
 *
 * A personal token (`yandex_token` in the client cookies) is optional and only widens what the
 * account is allowed to stream.
 */
class YandexMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "YandexMusicClient"

    private val token: String? = listOf("yandex_token", "yandex_oauth", "access_token")
        .firstNotNullOfOrNull { key -> config.defaultSearchCookies[key]?.trim()?.takeIf(String::isNotEmpty) }

    private val apiHeaders: Map<String, String> = buildMap {
        put("X-Yandex-Music-Client", CLIENT_HEADER)
        put("Accept", "application/json")
        put("Accept-Language", "ru")
        token?.let { put("Authorization", "OAuth $it") }
    }

    private val fileHeaders: Map<String, String> = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

    /** Signed links are stable for their `ts` window; one lookup per track is enough. */
    private val links = HashMap<String, DownloadUrlStatus?>()

    init {
        searchHeaders = apiHeaders
        parseHeaders = apiHeaders
        downloadHeaders = fileHeaders
    }

    // ------------------------------------------------------------------ search

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        // Anonymous accounts are only offered the preview encode of a track, and a preview must not
        // reach the feed as a normal row. Playlist links still work without a token: they carry
        // metadata, and the caller resolves the audio in catalogs that hand out full files.
        if (token == null) {
            logger.warning(
                "$source >>> anonymous search has preview-only offers, so no rows are kept; " +
                    "add yandex_token for full files (playlist links work without it)",
            )
        }
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "$API/search".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("text", keyword)
                setQueryParameter("type", "track")
                setQueryParameter("page", (offset / pageSize).toString())
                setQueryParameter("nocorrect", "false")
                rule.forEach { (key, value) -> setQueryParameter(key, value) }
            }.build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val root = getJson(searchUrl) ?: return
        JsonUtil.obj(root, "error")?.let { error ->
            logger.error("$source >>> ${JsonUtil.str(error, "message") ?: "API error"}")
            return
        }
        val items = JsonUtil.array(JsonUtil.obj(JsonUtil.obj(root, "result"), "tracks"), "results")
            .orEmpty().filterIsInstance<JsonObject>()
        if (items.isEmpty()) return
        val wanted = if (strictLimitSearchSizePerPage) (searchSizePerPage - results.size).coerceAtLeast(0) else items.size
        val slice = items.take(wanted)
        if (slice.isEmpty()) return
        progress.onSourceTask(source, "$source._search >>> resolving ${slice.size} tracks", 0, slice.size)
        // One download-info round-trip per track is what decides whether a row is playable at all,
        // so the probes run in parallel under the shared permit budget instead of one after another.
        val parsed = coroutineScope {
            slice.map { item -> async(Dispatchers.IO) { probeLimit.withPermit { parseTrack(item) } } }.awaitAll()
        }
        results += parsed.filterNotNull()
    }

    // ---------------------------------------------------------------- playlist

    override fun parsePlaylist(playlistUrl: String): List<SongInfo> {
        val link = parseYandexLink(playlistUrl.trim()) ?: return emptyList()
        val (name, entries) = when (link.kind) {
            "playlist" -> playlistEntries(link.first, link.second)
            "playlist_uuid" -> playlistByUuid(link.first)
            "album" -> albumEntries(link.first)
            "track" -> "Яндекс · трек" to listOf(JsonObject(mapOf("id" to kotlinx.serialization.json.JsonPrimitive(link.second))))
            else -> return emptyList()
        }
        val resolved = resolveEntries(entries)
        if (resolved.isEmpty()) return emptyList()
        // Metadata rows keep no link: the caller matches them in its own catalogs, like the plugin
        // did before it downloaded the audio from YouTube.
        val songs = runBlocking(Dispatchers.IO) {
            coroutineScope {
                resolved.map { entry -> async { probeLimit.withPermit { parseTrack(entry, requireFullAudio = false) } } }.awaitAll()
            }
        }.filterNotNull()
        val workDir = constructUniqueWorkDir(name)
        // The playlist title travels on every row so the caller can name the imported collection.
        return removeDuplicates(songs).map { song ->
            song.copy(workDir = workDir, rawData = song.rawData + ("playlistTitle" to name))
        }
    }

    /** Public profile playlists only; private likes still require the account-library import. */
    fun profilePlaylistUrls(url: String): List<String> {
        val uri = url.toHttpUrl()
        require(uri.host.startsWith("music.yandex.")) { "Нужна ссылка на профиль Яндекс Музыки" }
        val segments = uri.pathSegments.filter { it.isNotEmpty() }
        require(segments.size == 2 && segments[0] == "users") { "Нужна ссылка music.yandex.ru/users/имя" }
        val owner = segments[1]
        val endpoint = "$API/users/$owner/playlists/list"
        val root = getJson(endpoint) ?: error("Яндекс не ответил при чтении профиля")
        return JsonUtil.array(root, "result").orEmpty().filterIsInstance<JsonObject>().mapNotNull {
            val kind = JsonUtil.str(it, "kind") ?: return@mapNotNull null
            "https://music.yandex.ru/users/$owner/playlists/$kind"
        }.distinct()
    }

    private data class YandexLink(val kind: String, val first: String, val second: String = "")

    /** Port of the plugin's `_parse_yandex_url`: every share-link shape Yandex hands out. */
    private fun parseYandexLink(url: String): YandexLink? {
        Regex("""music\.yandex\.[a-z]+/users/([^/?#]+)/playlists/(\d+)""").find(url)?.let {
            return YandexLink("playlist", it.groupValues[1], it.groupValues[2])
        }
        // New Yandex share links for “Мне нравится” use /playlists/lk.<uuid>.
        // The dot is part of the playlist UUID and must not be rejected by the URL parser.
        Regex("""music\.yandex\.[a-z]+/playlists?/([^/?#]+)""").find(url)?.let {
            return YandexLink("playlist_uuid", it.groupValues[1])
        }
        Regex("""music\.yandex\.[a-z]+/album/(\d+)/track/(\d+)""").find(url)?.let {
            return YandexLink("track", it.groupValues[1], it.groupValues[2])
        }
        Regex("""music\.yandex\.[a-z]+/album/(\d+)""").find(url)?.let {
            return YandexLink("album", it.groupValues[1])
        }
        Regex("""music\.yandex\.[a-z]+/track/(\d+)""").find(url)?.let {
            return YandexLink("track", "", it.groupValues[1])
        }
        return null
    }

    private fun playlistEntries(owner: String, kind: String): Pair<String, List<JsonObject>> {
        val root = getJson("$API/users/$owner/playlists/$kind") ?: return "Яндекс · плейлист" to emptyList()
        val result = JsonUtil.obj(root, "result")
            ?: (root["result"] as? JsonArray)?.firstOrNull() as? JsonObject
        return (JsonUtil.str(result, "title") ?: "Яндекс · плейлист") to tracksNode(result)
    }

    private fun playlistByUuid(uuid: String): Pair<String, List<JsonObject>> {
        val root = getJson("$API/playlist/$uuid") ?: return "Яндекс · плейлист" to emptyList()
        val result = JsonUtil.obj(root, "result")
        return (JsonUtil.str(result, "title") ?: "Яндекс · плейлист") to tracksNode(result)
    }

    private fun albumEntries(albumId: String): Pair<String, List<JsonObject>> {
        val root = getJson("$API/albums/$albumId/with-tracks") ?: return "Яндекс · альбом" to emptyList()
        val result = JsonUtil.obj(root, "result") ?: return "Яндекс · альбом" to emptyList()
        val tracks = JsonUtil.array(result, "volumes").orEmpty()
            .flatMap { volume -> (volume as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
        return (JsonUtil.str(result, "title") ?: "Яндекс · альбом") to tracks
    }

    private fun tracksNode(result: JsonObject?): List<JsonObject> =
        JsonUtil.array(result, "tracks").orEmpty().filterIsInstance<JsonObject>()

    /**
     * Playlist entries are either `{"track": {...}}`, a full track, or an id that has to be looked
     * up in bulk — the plugin's `_full()` plus `_ya_fetch_tracks_by_ids` in one pass.
     */
    private fun resolveEntries(entries: List<JsonObject>): List<JsonObject> {
        val full = ArrayList<JsonObject>(entries.size)
        val missing = ArrayList<String>()
        for (entry in entries) {
            val nested = JsonUtil.obj(entry, "track")
            if (nested != null) {
                full += nested
                continue
            }
            if (JsonUtil.str(entry, "title") != null && entry["artists"] != null) {
                full += entry
                continue
            }
            JsonUtil.str(entry, "id")?.substringBefore(':')?.takeIf { it.isNotEmpty() }?.let(missing::add)
        }
        if (missing.isNotEmpty()) full += fetchTracksByIds(missing)
        return full
    }

    private fun fetchTracksByIds(ids: List<String>): List<JsonObject> =
        ids.chunked(MAX_TRACK_IDS_PER_REQUEST).flatMap { chunk ->
            val response = http.postForm(
                "$API/tracks",
                form = mapOf("track-ids" to chunk.joinToString(","), "with-positions" to "False"),
                extraHeaders = parseHeaders,
            )?.use { JsonUtil.parseObject(it.textOrNull()) }
            JsonUtil.array(response, "result").orEmpty().filterIsInstance<JsonObject>()
        }

    // ------------------------------------------------------------------ tracks

    private fun parseTrack(track: JsonObject, requireFullAudio: Boolean = true): SongInfo? {
        val rawId = JsonUtil.str(track, "realId") ?: JsonUtil.str(track, "id") ?: return null
        val trackId = rawId.substringBefore(':')
        if (trackId.isEmpty()) return null
        val baseTitle = JsonUtil.str(track, "title")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val version = JsonUtil.str(track, "version")?.trim().orEmpty()
        val title = if (version.isEmpty()) baseTitle else "$baseTitle ($version)"
        val artists = JsonUtil.array(track, "artists").orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { JsonUtil.str(it, "name")?.trim()?.takeIf(String::isNotEmpty) }
        val albumNode = JsonUtil.array(track, "albums").orEmpty().filterIsInstance<JsonObject>().firstOrNull()
        val seconds = (JsonUtil.long(track, "durationMs") ?: 0L) / 1000L
        // The track record itself says whether a full file is possible without a subscription, so
        // a playlist of anonymous tracks costs no download-info request at all.
        val fullFilePossible = token != null || JsonUtil.bool(track, "availableFullWithoutPermission") == true
        // Playlist-link imports are metadata-first: probing download-info for every liked track
        // can turn a large “Мне нравится” list into hundreds/thousands of network requests.
        // resolvePlayable() in the app resolves metadata against the selected catalogs afterwards.
        val status = if (requireFullAudio && fullFilePossible) downloadStatus(trackId) else null
        if (status == null && requireFullAudio) return null
        val song = SongInfo(
            source = source,
            identifier = trackId,
            rawData = mapOf("search" to track),
            songName = PathUtils.legalizeString(title),
            singers = PathUtils.legalizeString(artists.joinToString(", ").takeIf { it.isNotBlank() }),
            album = PathUtils.legalizeString(albumNode?.let { JsonUtil.str(it, "title") }),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            coverUrl = coverUrl(track),
            downloadUrl = status?.downloadUrl,
            downloadUrlStatus = status ?: DownloadUrlStatus(),
            downloadHeaders = downloadHeaders,
            protocol = "HTTP",
        )
        if (status == null) return song
        return song.copy(ext = status.ext, fileSize = status.fileSize, fileSizeBytes = status.fileSizeBytes)
    }

    /** `coverUri` ships as `avatars.yandex.net/...%%`; `%%` is the size placeholder. */
    private fun coverUrl(track: JsonObject): String? {
        val raw = JsonUtil.str(track, "coverUri") ?: JsonUtil.str(track, "ogImage") ?: return null
        val sized = raw.replace("%%", "1000x1000")
        return if (sized.startsWith("http")) sized else "https://$sized"
    }

    private fun downloadStatus(trackId: String): DownloadUrlStatus? {
        synchronized(links) { links[trackId] }?.let { return it }
        synchronized(links) { if (links.containsKey(trackId)) return null }
        val status = resolveDownloadStatus(trackId)
        synchronized(links) { links[trackId] = status }
        return status
    }

    private fun resolveDownloadStatus(trackId: String): DownloadUrlStatus? {
        if (!trackId.all(Char::isDigit)) return null
        val root = getJson("$API/tracks/$trackId/download-info") ?: return null
        val offers = JsonUtil.array(root, "result").orEmpty().filterIsInstance<JsonObject>()
        // Only the mp3 route is signed here. Prefer a full file over the preview encode; without
        // one the track stays metadata-only so a 30-second teaser is never presented as the song.
        val best = offers
            .filter { JsonUtil.str(it, "codec")?.lowercase() == "mp3" && JsonUtil.str(it, "downloadInfoUrl") != null }
            .filterNot { JsonUtil.bool(it, "preview") == true }
            .maxByOrNull { JsonUtil.int(it, "bitrate") ?: 0 }
            ?: return null
        val descriptorUrl = JsonUtil.str(best, "downloadInfoUrl") ?: return null
        val xml = http.get(descriptorUrl, extraHeaders = fileHeaders)?.use { it.textOrNull() } ?: return null
        val host = xmlTag(xml, "host") ?: return null
        val path = xmlTag(xml, "path") ?: return null
        val ts = xmlTag(xml, "ts") ?: return null
        val salt = xmlTag(xml, "s") ?: return null
        // The storage path is signed with the descriptor's own `s` value; this is the same
        // /get-mp3/{sign}/{ts}{path} route the official Android client uses.
        val digest = md5(SIGN_SALT + path.removePrefix("/") + salt)
        val link = "https://$host/get-mp3/$digest/$ts$path"
        return DownloadUrlStatus(
            ok = true,
            contentType = "audio/mpeg",
            ext = "mp3",
            originalDownloadUrl = link,
            downloadUrl = link,
            fileSize = "NULL",
            fileSizeBytes = 0L,
            method = "yandex download-info",
            statusCode = 200,
        )
    }

    private fun getJson(url: String, headers: Map<String, String> = parseHeaders): JsonObject? =
        http.get(url, extraHeaders = headers)?.use { JsonUtil.parseObject(it.textOrNull()) }

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val API = "https://api.music.yandex.net"
        const val CLIENT_HEADER = "YandexMusicAndroid/24023231"
        const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
        const val MAX_TRACK_IDS_PER_REQUEST = 100
    }
}
