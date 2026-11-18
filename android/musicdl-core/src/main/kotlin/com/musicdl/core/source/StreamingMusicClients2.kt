package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Port of `musicdl/modules/sources/deezer.py::DeezerMusicClient` (anonymous branch).
 *
 * A premium `arl` cookie unlocks `media.deezer.com` lossless streaming, which this port does not
 * handle. Without it the Python client falls back to the public converter endpoints and finally
 * to the 30 second preview, and that is exactly what is implemented here.
 *
 * Two things differ from the Python flow, both because a phone is not a desktop:
 *
 * 1. The search response already carries `preview`, so the common case needs no extra request.
 * 2. The converter mirrors are tried once, in parallel, and remembered. The Python client walks
 *    them per track, which costs about a second each; a Deezer-only search of eight tracks then
 *    ran past the per-source timeout and returned nothing at all.
 */
open class DeezerMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "DeezerMusicClient"

    /** Mirrors that answered with a usable link before; tried first, and only once per client. */
    private val workingMirrors = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux i686; rv:135.0) Gecko/20100101 Firefox/135.0",
            "Accept" to "*/*",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://api.deezer.com/search/track".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("q", keyword)
                setQueryParameter("index", offset.toString())
                setQueryParameter("limit", pageSize.toString())
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
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).objects()
        if (items.isEmpty()) return

        // Tracks are independent, so resolve them together instead of one after another. The cap
        // keeps one search from occupying every coroutine thread with blocking HTTP probes.
        val parsed = coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    probeLimit.withPermit {
                        progress.onSourceTask(
                            source,
                            "$source._search >>> ${index + 1}/${items.size} results",
                            index + 1,
                            items.size,
                        )
                        runCatching { parseSong(item) }
                            .onFailure { logger.error("$source._parse >>> ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }

        for (song in parsed) {
            if (song == null) continue
            results += song
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private suspend fun parseSong(item: JsonObject): SongInfo? {
        val songId = item["id"]?.toString()?.trim('"') ?: return null
        val seconds = JsonUtil.long(item, "duration") ?: 0L
        val trackUrl = "https://www.deezer.com/en/track/$songId"

        // The search payload already has the preview link, so try it first and only pay for the
        // converter mirrors when it is missing or dead. Building the whole candidate list up front
        // would hit the mirrors on every track, which is what made a Deezer-only search time out.
        val inlinePreview = JsonUtil.str(item, "preview")?.takeIf { it.startsWith("http") }

        val ordered = mutableListOf<String>()
        inlinePreview?.let(ordered::add)

        fun buildSong(status: com.musicdl.core.model.DownloadUrlStatus): SongInfo = SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(JsonUtil.str(item, "title")),
            singers = PathUtils.legalizeString(JsonUtil.extract(item, listOf("artist", "name"))),
            album = PathUtils.legalizeString(JsonUtil.extract(item, listOf("album", "title"))),
            genre = TrackGenres.fromCatalog(item).first,
            tags = TrackGenres.fromCatalog(item).second,
            durationSeconds = if (status.originalDownloadUrl == inlinePreview || status.originalDownloadUrl.contains("preview", true)) minOf(seconds, 30L) else seconds,
            duration = SongInfo.formatDuration(if (status.originalDownloadUrl == inlinePreview || status.originalDownloadUrl.contains("preview", true)) minOf(seconds, 30L) else seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = JsonUtil.extract(item, listOf("album", "cover_xl")) ?: inlinePreview,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )

        // Full MP3 first. Public previews are only an explicitly labelled fallback.
        for (candidate in resolveMirrors(songId, trackUrl)) {
            val status = audioLinkTester.testFast(candidate, downloadHeaders)
            if (status.ok && status.ext.equals("mp3", true)) return buildSong(status)
        }

        for (candidate in ordered) {
            val status = audioLinkTester.testFast(candidate, downloadHeaders)
            if (status.ok && AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return buildSong(status)
        }

        // Preview missing or dead: ask the track endpoint, then the mirrors.
        if (inlinePreview == null) {
            http.get(trackEndpoint(songId), extraHeaders = searchHeaders)
                ?.use { it.textOrNull() }
                ?.let { runCatching { JsonUtil.str(JsonUtil.parseObject(it), "preview") }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
                ?.let { candidate ->
                    val status = audioLinkTester.testFast(candidate, downloadHeaders)
                    if (status.ok && AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return buildSong(status)
                }
        }

        return null
    }

    /** Overridable so tests can point the track lookup at a local server. */
    protected open fun trackEndpoint(songId: String): String = "https://api.deezer.com/track/$songId"

    /**
     * Converter mirrors. Only the ones that already worked are retried, and a mirror that fails
     * is dropped for the rest of the session instead of being re-probed for every track.
     */
    protected open suspend fun resolveMirrors(songId: String, trackUrl: String): List<String> {
        val found = mutableListOf<String>()
        val resolved = coroutineScope {
            MIRRORS.map { mirror -> async {
                mirror to runCatching { mirror.resolve(this@DeezerMusicClient, songId, trackUrl) }.getOrNull()
            } }.awaitAll()
        }
        for ((mirror, link) in resolved) {
            if (link != null && link.startsWith("http")) {
                if (mirror.id !in workingMirrors) workingMirrors += mirror.id
                found += link
            } else if (mirror.id in workingMirrors) {
                workingMirrors -= mirror.id
            }
        }
        return found
    }

    private interface Mirror {
        val id: String
        fun resolve(client: DeezerMusicClient, songId: String, trackUrl: String): String?
    }

    private companion object {
        val MIRRORS: List<Mirror> = listOf(
            object : Mirror {
                override val id = "deezdownloaders"
                override fun resolve(client: DeezerMusicClient, songId: String, trackUrl: String): String? {
                    val headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "https://deezdownloaders.com/",
                    )
                    val info = client.http.get(
                        "https://deezdownloaders.com/api/track.php",
                        query = mapOf("url" to trackUrl),
                        extraHeaders = headers,
                    )?.use { it.textOrNull() }?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }
                        ?: return null
                    val name = JsonUtil.str(info, "name") ?: return null
                    val artist = JsonUtil.str(info, "artist") ?: return null
                    val body = client.http.postForm(
                        "https://deezdownloaders.com/api/downloader.php",
                        mapOf(
                            "name" to name, "artist" to artist, "url" to trackUrl, "token" to "none",
                            "zip_download" to "false", "quality" to "128",
                        ),
                        extraHeaders = headers + mapOf("Origin" to "https://deezdownloaders.com"),
                    )?.use { it.textOrNull() } ?: return null
                    return runCatching { JsonUtil.extract(JsonUtil.parseObject(body), listOf("dlink")) }.getOrNull()
                }
            },
            object : Mirror {
                override val id = "musicfab"
                override fun resolve(client: DeezerMusicClient, songId: String, trackUrl: String): String? {
                    val body = client.http.postJson(
                        "https://musicfab.io/api/deezer",
                        buildJsonObject { put("url", JsonPrimitive(trackUrl)) }.toString(),
                        extraHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                            "Content-Type" to "application/json",
                            "Referer" to "https://musicfab.io/deezer-to-mp3/",
                            "Origin" to "https://musicfab.io",
                        ),
                    )?.use { it.textOrNull() } ?: return null
                    return runCatching {
                        JsonUtil.extract(JsonUtil.parseObject(body), listOf("data", "metadata", "download"))
                    }.getOrNull()
                }
            },
        )
    }
}

/**
 * Port of `musicdl/modules/sources/soundcloud.py::SoundCloudMusicClient` (progressive branch).
 *
 * The Python client also handles `ctr-`/`cbc-` HLS streams behind Widevine/FairPlay; those need a
 * licence exchange and an external downloader, so this port keeps the public `progressive`
 * transcodings (and the original file when the uploader enabled downloads).
 */
class SoundCloudMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "SoundCloudMusicClient"

    private var clientId: String? = config.defaultSearchCookies["client_id"] ?: cachedClientId

    override suspend fun searchArtist(name: String, progress: SearchProgress): List<SongInfo> {
        val cid = ensureClientId() ?: return emptyList()
        val body = http.get("https://api-v2.soundcloud.com/search/users",
            query = mapOf("q" to name, "client_id" to cid, "limit" to "10"), extraHeaders = searchHeaders)
            ?.use { it.textOrNull() }
        val users = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("collection")).objects().filter { user ->
            listOf("username", "full_name").any { field ->
                com.musicdl.core.model.ArtistIdentity.matches(SongInfo(singers = JsonUtil.str(user, field)), name)
            }
        }
        val tracks = mutableListOf<SongInfo>()
        for (user in users.take(2)) {
            val id = JsonUtil.str(user, "id") ?: continue
            val url = "https://api-v2.soundcloud.com/users/$id/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", cid).addQueryParameter("limit", searchSizePerSource.toString()).build()
            searchOne(name, url.toString(), tracks, progress)
        }
        val matches = tracks.filter { com.musicdl.core.model.ArtistIdentity.matches(it, name) }
            .distinctBy { it.identifier }.map { it.copy(workDir = config.workDir) }
        return matches.ifEmpty { super.searchArtist(name, progress) }
    }

    init {
        searchHeaders = searchHeaders + mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        )
    }

    /**
     * Reads the anonymous `client_id` out of the site's bundles.
     *
     * The old version fetched *every* script in reverse and only then gave up, and a fresh
     * `MusicClient` (and therefore a fresh instance) is built for each search — so the scraping cost
     * was paid on every query and regularly blew past the 20 s per-source budget. Now the id is
     * cached process-wide and only the last few bundles are read, because the id lives in the
     * trailing ones. The pinned fallback id used to hide all of this by silently returning 401s.
     */
    private fun ensureClientId(): String? {
        clientId?.let { return it }
        val resolved = runCatching {
            val home = http.get("https://soundcloud.com/", extraHeaders = searchHeaders)?.use { it.textOrNull() }
                ?: return@runCatching null
            val scripts = Regex("""<script[^>]+src="([^"]+)"""").findAll(home)
                .map { it.groupValues[1] }
                .toList()
                .reversed()
                .take(MAX_SCRIPT_PROBES)
            for (src in scripts) {
                val script = http.get(src, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: continue
                Regex("""client_id\s*[:=]\s*"([0-9a-zA-Z]{32})"""")
                    .find(script)
                    ?.let { return@runCatching it.groupValues[1] }
            }
            null
        }.getOrNull()
        if (resolved != null) cachedClientId = resolved
        clientId = resolved
        return resolved
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val id = ensureClientId() ?: run {
            logger.error("$source >>> client_id was not found on soundcloud.com, skipping search")
            return emptyList()
        }
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://api-v2.soundcloud.com/search/tracks".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("q", keyword)
                setQueryParameter("client_id", id)
                setQueryParameter("limit", pageSize.toString())
                setQueryParameter("offset", offset.toString())
                setQueryParameter("linked_partitioning", "1")
                setQueryParameter("app_version", "1769771069")
                setQueryParameter("app_locale", "en")
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
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("collection")).objects()
        if (items.isEmpty()) return
        // Each hit costs a track lookup plus a stream resolve. Doing that one after another for
        // thirty hits burned the whole per-source budget before anything was returned, which is
        // why SoundCloud looked like it found nothing.
        val parsed = coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    probeLimit.withPermit {
                        progress.onSourceTask(
                            source,
                            "$source._search >>> ${index + 1}/${items.size} results",
                            index + 1,
                            items.size,
                        )
                        runCatching { parseSong(item) }
                            .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }
        for (song in parsed) {
            if (song == null) continue
            results += song
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private companion object {
        /** Process-wide: a fresh client (and instance) is built for every search. */
        @Volatile
        var cachedClientId: String? = null

        /** Only the trailing bundles carry the id; reading all of them cost more than the timeout. */
        const val MAX_SCRIPT_PROBES = 5

        /** Progressive audio is preferred, HLS is what most modern uploads expose at all. */
        val PLAYABLE_PROTOCOLS = setOf("progressive", "hls")
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val cid = ensureClientId() ?: return null
        val id = item["id"]?.toString()?.trim('"') ?: return null
        var track = item
        if (JsonUtil.extractElement(track, listOf("media", "transcodings")).objects().isEmpty()) {
            val body = http.get(
                "https://api-v2.soundcloud.com/tracks/$id",
                query = mapOf("client_id" to cid),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: return null
            track = runCatching { JsonUtil.parseObject(body) }.getOrNull() ?: return null
        }

        val transcodings = JsonUtil.extractElement(track, listOf("media", "transcodings")).objects()
        val playable = transcodings
            .filter { JsonUtil.extract(it, listOf("format", "protocol"))?.lowercase() in PLAYABLE_PROTOCOLS }
            .sortedWith(
                compareByDescending<JsonObject> {
                    JsonUtil.extract(it, listOf("format", "protocol"))?.lowercase() == "progressive"
                }.thenByDescending { JsonUtil.long(it, "preset") ?: 0L },
            )

        val trackAuth = JsonUtil.str(track, "track_authorization")
        for (transcoding in playable) {
            val resolveUrl = JsonUtil.str(transcoding, "url") ?: continue
            val protocol = JsonUtil.extract(transcoding, listOf("format", "protocol"))?.lowercase().orEmpty()
            val query = mutableMapOf("client_id" to cid)
            if (!trackAuth.isNullOrBlank()) query["track_authorization"] = trackAuth
            val resolved = http.get(resolveUrl, query = query, extraHeaders = searchHeaders)
                ?.use { it.textOrNull() } ?: continue
            val redirect = runCatching { JsonUtil.str(JsonUtil.parseObject(resolved), "url") }.getOrNull()
                ?.takeIf { it.startsWith("http") } ?: continue

            val status = audioLinkTester.testFast(redirect, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue

            val millis = JsonUtil.long(track, "duration") ?: 0L
            val seconds = millis / 1000
            return SongInfo(
                source = source,
                identifier = id,
                rawData = mapOf("search" to track),
                genre = JsonUtil.str(track, "genre"),
                tags = JsonUtil.str(track, "tag_list"),
                description = JsonUtil.str(track, "description"),
                songName = PathUtils.legalizeString(JsonUtil.str(track, "title")),
                singers = PathUtils.legalizeString(
                    JsonUtil.extract(track, listOf("publisher_metadata", "artist"))
                        ?: JsonUtil.extract(track, listOf("user", "username")),
                ),
                album = PathUtils.legalizeString(JsonUtil.extract(track, listOf("publisher_metadata", "album_title"))),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = JsonUtil.str(track, "artwork_url"),
                protocol = if (protocol == "hls") "HLS" else "HTTP",
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }
}
