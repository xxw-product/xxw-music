package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun md5(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Qobuz signed request: MD5 of `method + sortedArgs + ts + secret`, as in `getrequestsig`. */
private fun qobuzSig(method: String, args: Map<String, String>, timestamp: String, secret: String): String {
    val ignored = setOf("app_id", "request_ts", "request_sig")
    val sorted = args.toSortedMap().entries.filter { it.key !in ignored }
        .joinToString("") { "${it.key}${it.value}" }
    return md5(method.trim('/').replace("/", "") + sorted + timestamp + secret)
}

/**
 * Port of `musicdl/modules/sources/apple.py::AppleMusicClient` (non-VIP branch).
 *
 * The developer token is scraped from the `music.apple.com` bundle exactly like
 * `AppleMusicClientAPIUtils.gettoken`, so search works anonymously; every track then resolves to
 * its catalog preview, which is what the Python client does without a `media-user-token`.
 */
class AppleMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "AppleMusicClient"

    private val storefront = config.defaultSearchCookies["storefront"] ?: "us"
    private val mediaUserToken = config.defaultSearchCookies["media-user-token"]
    private var developerToken: String? = null

    init {
        searchHeaders = searchHeaders + mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US",
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        )
    }

    /** Port of `gettoken`: homepage → `assets/index-*.js` → the embedded `eyJ...` JWT. */
    private fun token(): String? = developerToken ?: run {
        // `https://music.apple.com/?l=en-US` answers 302 to `/us/new` and the redirect response has
        // no bundle in it, so the token was never scraped and Apple silently contributed nothing.
        val home = http.get("https://music.apple.com/us/new", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        val indexJs = Regex("(?:^|/)(assets/index[~-][^/\"]+\\.js)").find(home)?.groupValues?.get(1)
            ?: return null
        val bundle = http.get("https://music.apple.com/$indexJs", query = mapOf("l" to "en-US"), extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        Regex("\"(eyJ[A-Za-z0-9\\-_]+\\.eyJ[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+)\"").find(bundle)?.groupValues?.get(1)
            ?.also { developerToken = it }
    }

    private fun authHeaders(): Map<String, String> {
        val bearer = token() ?: return emptyMap()
        val headers = mutableMapOf("authorization" to "Bearer $bearer")
        mediaUserToken?.let { headers["media-user-token"] = it }
        return headers
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val endpoint = "https://amp-api.music.apple.com/v1/catalog/$storefront/search".toHttpUrl()
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("term", keyword)
                setQueryParameter("types", "songs,albums,artists")
                setQueryParameter("limit", pageSize.toString())
                setQueryParameter("offset", offset.toString())
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
        val headers = authHeaders()
        if (headers.isEmpty()) {
            logger.error("$source >>> developer token was not found on music.apple.com, skipping search")
            return
        }
        val body = http.get(searchUrl, extraHeaders = searchHeaders + headers)?.use { it.textOrNull() } ?: return
        val songs = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("results", "songs", "data")).objects()
        songs.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${songs.size} results", index + 1, songs.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithnonvipofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val attributes = JsonUtil.extractElement(item, listOf("attributes")) as? JsonObject ?: return null
        if (JsonUtil.str(item, "type")?.let { it != "songs" } == true) return null
        val previewUrl = JsonUtil.extract(attributes, listOf("previews", "0", "url")) ?: return null
        if (!previewUrl.startsWith("http")) return null
        val status = audioLinkTester.test(previewUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = (JsonUtil.long(attributes, "durationInMillis") ?: 0L) / 1000
        val cover = JsonUtil.extract(attributes, listOf("artwork", "url"))
            ?.replace("{w}", "600")?.replace("{h}", "600")?.replace("{f}", "jpg")
        return SongInfo(
            source = source,
            identifier = JsonUtil.str(item, "id"),
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(JsonUtil.str(attributes, "name")),
            singers = PathUtils.legalizeString(JsonUtil.str(attributes, "artistName")),
            album = PathUtils.legalizeString(JsonUtil.str(attributes, "albumName")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = cover,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `musicdl/modules/sources/qobuz.py::QobuzMusicClient`.
 *
 * Search uses the anonymous catalog app credentials with the `request_sig` the Python client
 * computes; playback goes through the `qobuz.anandserver.cfd` stream mirror (`_parsewithanandserverapi`),
 * which needs no account. Supplying `user_auth_token` adds the official `X-User-Auth-Token` header.
 */

/**
 * Port of `musicdl/modules/sources/spotify.py::SpotifyMusicClient`.
 *
 * Search reproduces `SpotifyMusicClientSearchUtils`: the TOTP from
 * `SpotifyMusicClientUtils.generatetotp` unlocks `open.spotify.com/api/token`, the client token
 * unlocks `pathfinder/v2/query`, and downloads use the anonymous `spotsaver.net` / `musicfab.io`
 * parsers from `_parsewithspotsaverapi` / `_parsewithmusicfabapi`.
 */
class SpotifyMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "SpotifyMusicClient"

    private val browserVersion = "145"
    private val commonHeaders = mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$browserVersion.0.0.0 Safari/537.36",
    )

    /** TOTP secrets shipped by `getlatesttotpsecret` (version 59–61). */
    private val totpSecrets = mapOf(
        59 to intArrayOf(123, 105, 79, 70, 110, 59, 52, 125, 60, 49, 80, 70, 89, 75, 80, 86, 63, 53, 123, 37, 117, 49, 52, 93, 77, 62, 47, 86, 48, 104, 68, 72),
        60 to intArrayOf(79, 109, 69, 123, 90, 65, 46, 74, 94, 34, 58, 48, 70, 71, 92, 85, 122, 63, 91, 64, 87, 87),
        61 to intArrayOf(44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76, 94, 102, 43, 69, 49, 120, 118, 80, 64, 78),
    )

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$browserVersion.0.0.0 Safari/537.36",
            "Accept" to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "https://open.spotify.com/",
            "Origin" to "https://open.spotify.com/",
        )
    }

    /** Port of `generatetotp`; the base64 step is the identity round-trip Python relies on. */
    private fun generateTotp(secret: IntArray): String {
        val transformed = secret.mapIndexed { index, value -> value xor ((index % 33) + 9) }
        val key = transformed.joinToString("").toByteArray(Charsets.US_ASCII)
        val timeStep = System.currentTimeMillis() / 30_000
        val message = ByteArray(8) { index -> ((timeStep shr (56 - index * 8)) and 0xFF).toByte() }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val digest = mac.doFinal(message)
        val offset = digest[19].toInt() and 0x0F
        val code = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return (code % 1_000_000).toString().padStart(6, '0')
    }

    /** Port of `getsessiondata` + `getaccesstoken` + `getclienttoken`. */
    private fun sessionCredentials(): Triple<String, String, String>? {
        val home = http.get("https://open.spotify.com", extraHeaders = commonHeaders) ?: return null
        val cookie = home.headers.values("set-cookie").firstOrNull { it.startsWith("sp_t=") }
            ?.substringAfter("sp_t=")?.substringBefore(';')
        val html = home.use { it.textOrNull() } ?: return null
        val clientVersion = Regex("\"clientVersion\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
        val totpVersion = totpSecrets.keys.max()
        val totp = generateTotp(totpSecrets.getValue(totpVersion))

        val tokenBody = http.get(
            "https://open.spotify.com/api/token",
            query = mapOf(
                "reason" to "init", "productType" to "web-player", "totp" to totp,
                "totpVer" to totpVersion.toString(), "totpServer" to totp,
            ),
            extraHeaders = commonHeaders,
        )?.use { it.textOrNull() } ?: return null
        val tokenJson = runCatching { JsonUtil.parseObject(tokenBody) }.getOrNull() ?: return null
        val accessToken = JsonUtil.str(tokenJson, "accessToken") ?: return null
        val clientId = JsonUtil.str(tokenJson, "clientId") ?: return null

        val deviceId = cookie?.takeIf(String::isNotBlank) ?: hexRandom(16)
        val payload = """
            {"client_data":{"client_version":"$clientVersion","client_id":"$clientId","js_sdk_data":{"device_brand":"unknown","device_model":"unknown","os":"windows","os_version":"NT 10.0","device_id":"$deviceId","device_type":"computer"}}}
        """.trimIndent()
        val clientTokenBody = http.postJson(
            "https://clienttoken.spotify.com/v1/clienttoken",
            payload,
            extraHeaders = commonHeaders + mapOf("Authority" to "clienttoken.spotify.com", "Accept" to "application/json"),
        )?.use { it.textOrNull() } ?: return null
        val clientToken = runCatching {
            JsonUtil.extract(JsonUtil.parseObject(clientTokenBody), listOf("granted_token", "token"))
        }.getOrNull() ?: return null

        return Triple(accessToken, clientToken, clientVersion)
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        // The Spotify search is a GraphQL call, so the URL only carries the keyword; the real request
        // is built in searchOne once the TOTP-derived tokens are available.
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        return (0 until searchSizePerSource step pageSize).map { offset ->
            "spotify:search".toHttpUrl().newBuilder()
                .setQueryParameter("term", keyword)
                .setQueryParameter("limit", pageSize.toString())
                .setQueryParameter("offset", offset.toString())
                .build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val url = runCatching { searchUrl.toHttpUrl() }.getOrNull() ?: return
        val limit = url.queryParameter("limit") ?: searchSizePerPage.toString()
        val offset = url.queryParameter("offset") ?: "0"
        val term = url.queryParameter("term") ?: keyword
        val (accessToken, clientToken, clientVersion) = sessionCredentials() ?: run {
            logger.error("$source >>> could not obtain Spotify web-player tokens, skipping search")
            return
        }
        val payload = """
            {"variables":{"searchTerm":${jsonQuote(term)},"offset":$offset,"limit":$limit,"numberOfTopResults":5,"includeAudiobooks":true,"includeArtistHasConcertsField":false,"includePreReleases":true,"includeAuthors":false},"operationName":"searchDesktop","extensions":{"persistedQuery":{"version":1,"sha256Hash":"fcad5a3e0d5af727fb76966f06971c19cfa2275e6ff7671196753e008611873c"}}}
        """.trimIndent()
        val body = http.postJson(
            "https://api-partner.spotify.com/pathfinder/v2/query",
            payload,
            extraHeaders = commonHeaders + mapOf(
                "Authorization" to "Bearer $accessToken",
                "Client-Token" to clientToken,
                "Spotify-App-Version" to clientVersion,
                "Content-Type" to "application/json;charset=UTF-8",
            ),
        )?.use { it.textOrNull() } ?: return
        val root = runCatching { JsonUtil.parseObject(body) }.getOrNull() ?: return
        val items = JsonUtil.extractElement(root, listOf("data", "searchV2", "tracksV2", "items")).objects()
            .ifEmpty { JsonUtil.extractElement(root, listOf("data", "searchV2", "tracks", "items")).objects() }

        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewiththirdpartapis >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val track = JsonUtil.extractElement(item, listOf("item", "data")) as? JsonObject ?: return null
        val uri = JsonUtil.str(track, "uri") ?: return null
        val songId = uri.removePrefix("spotify:track:").takeIf { it != uri } ?: JsonUtil.str(track, "id") ?: return null
        val album = JsonUtil.extract(track, listOf("albumOfTrack", "name"))
        val cover = JsonUtil.extract(track, listOf("albumOfTrack", "coverArt", "sources", "0", "url"))
        val durationMs = (JsonUtil.extractElement(track, listOf("duration", "totalMilliseconds")) as? kotlinx.serialization.json.JsonPrimitive)
            ?.let { it.content.toDoubleOrNull()?.toLong() }
            ?: JsonUtil.long(track, "duration")
            ?: JsonUtil.long(track, "trackDuration")
            ?: 0L

        val parsed = runCatching { fetchFromSpotsaver(songId) }.getOrNull()
            ?: runCatching { fetchFromMusicfab(songId) }.getOrNull()
            ?: return null
        val status = parsed.second
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val meta = parsed.first
        val seconds = (JsonUtil.double(meta, "duration")?.toLong() ?: 0L).takeIf { it > 0 } ?: (durationMs / 1000)
        val coverUrl = JsonUtil.str(meta, "thumbnail") ?: cover
        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item, "download" to meta),
            songName = PathUtils.legalizeString(JsonUtil.str(meta, "title") ?: JsonUtil.str(track, "name")),
            singers = PathUtils.legalizeString(
                JsonUtil.str(meta, "artist")
                    ?: JsonUtil.extractElement(track, listOf("artists", "items")).objects()
                        .mapNotNull { JsonUtil.extract(it, listOf("profile", "name")) }.joinToString(", "),
            ),
            album = PathUtils.legalizeString(JsonUtil.str(meta, "album") ?: album),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = coverUrl,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    /** Port of `_parsewithspotsaverapi`: Spotify → YouTube id → mp3, all anonymous JSON. */
    private fun fetchFromSpotsaver(songId: String): Pair<JsonObject, com.musicdl.core.model.DownloadUrlStatus> {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
            "Referer" to "https://spotsaver.net/results/",
            "Accept" to "*/*",
        )
        val trackUrl = "https://open.spotify.com/track/$songId"
        val spotifyBody = http.get(
            "https://spotsaver.net/api/spotify/",
            query = mapOf("url" to trackUrl),
            extraHeaders = headers,
        )?.use { it.textOrNull() } ?: throw IllegalStateException("spotsaver: no track metadata")
        val spotify = JsonUtil.parseObject(spotifyBody)
        val track = JsonUtil.extractElement(spotify, listOf("items", "0")) as? JsonObject
            ?: throw IllegalStateException("spotsaver: empty items")
        val title = JsonUtil.str(track, "title").orEmpty()
        val artist = JsonUtil.str(track, "artist").orEmpty()

        val idBody = http.postJson(
            "https://spotsaver.net/api/get-id/",
            """{"title":${jsonQuote(title)},"artist":${jsonQuote(artist)}}""",
            extraHeaders = headers,
        )?.use { it.textOrNull() } ?: throw IllegalStateException("spotsaver: no video id")
        val idJson = JsonUtil.parseObject(idBody)
        val videoId = JsonUtil.str(idJson, "videoId") ?: throw IllegalStateException("spotsaver: empty videoId")
        val candidateIds = JsonUtil.extractElement(idJson, listOf("candidateIds")).objects()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

        val downloadPayload = buildString {
            append("{\"videoId\":").append(jsonQuote(videoId))
            append(",\"candidateIds\":[").append(candidateIds.joinToString(",") { jsonQuote(it) }).append(']')
            append(",\"format\":\"mp3\",\"title\":")
            append(jsonQuote("$title - $artist".ifBlank { "track" })).append('}')
        }
        val downloadBody = http.postJson("https://spotsaver.net/api/download/", downloadPayload, extraHeaders = headers)
            ?.use { it.textOrNull() } ?: throw IllegalStateException("spotsaver: no download")
        val downloadJson = JsonUtil.parseObject(downloadBody)
        val downloadUrl = JsonUtil.str(downloadJson, "downloadUrl")
            ?: JsonUtil.str(downloadJson, "url")
            ?: throw IllegalStateException("spotsaver: no url")

        val status = audioLinkTester.test(downloadUrl, headers)
        val meta = kotlinx.serialization.json.buildJsonObject {
            JsonUtil.str(track, "title")?.let { put("title", kotlinx.serialization.json.JsonPrimitive(it)) }
            JsonUtil.str(track, "artist")?.let { put("artist", kotlinx.serialization.json.JsonPrimitive(it)) }
            JsonUtil.str(track, "album")?.let { put("album", kotlinx.serialization.json.JsonPrimitive(it)) }
            JsonUtil.str(track, "thumbnail")?.let { put("thumbnail", kotlinx.serialization.json.JsonPrimitive(it)) }
            JsonUtil.double(track, "duration")?.let { put("duration", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        return meta to status
    }

    /** Port of `_parsewithmusicfabapi`. */
    private fun fetchFromMusicfab(songId: String): Pair<JsonObject, com.musicdl.core.model.DownloadUrlStatus> {
        val headers = mapOf(
            "origin" to "https://musicfab.io",
            "referer" to "https://musicfab.io/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        )
        val body = http.postJson(
            "https://musicfab.io/api/spotify",
            """{"url":"https://open.spotify.com/track/$songId"}""",
            extraHeaders = headers,
        )?.use { it.textOrNull() } ?: throw IllegalStateException("musicfab: no response")
        val json = JsonUtil.parseObject(body)
        val downloadUrl = JsonUtil.extract(json, listOf("data", "metadata", "download"))
            ?: throw IllegalStateException("musicfab: no url")
        val status = audioLinkTester.test(downloadUrl, headers)
        val meta = (JsonUtil.extractElement(json, listOf("data", "metadata")) as? JsonObject) ?: JsonObject(emptyMap())
        return meta to status
    }

    private fun hexRandom(bytes: Int): String {
        val buffer = ByteArray(bytes).also(SecureRandom()::nextBytes)
        return buffer.joinToString("") { "%02x".format(it) }
    }

    private fun jsonQuote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Suppress("unused")
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Suppress("unused")
    private fun unusedJsonArray(array: JsonArray): Int = array.size
}
