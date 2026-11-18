package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WikimediaCommonsMusicClient internal constructor(
    config: MusicClientConfig,
    private val endpoint: String,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, "https://commons.wikimedia.org/w/api.php")

    override val source = "WikimediaCommonsMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> =
        pagedSearchUrls(
            endpoint,
            mapOf(
                "action" to "query", "generator" to "search", "gsrsearch" to "$keyword filetype:audio",
                "gsrnamespace" to "6", "prop" to "imageinfo", "iiprop" to "url|size|mime|extmetadata",
                "iiurlwidth" to "500", "format" to "json", "formatversion" to "2",
            ) + rule,
            searchSizePerSource, searchSizePerPage.coerceAtMost(50), "gsrlimit", "gsroffset",
        )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val headers = mapOf("User-Agent" to "MusicDL-Android/1.0") + searchHeaders
        val data = JsonUtil.parseObject(http.get(searchUrl, extraHeaders = headers)?.use { it.textOrNull() })
        val pages = JsonUtil.extractElement(data, listOf("query", "pages")).objects()
        results += pages.sortedBy { JsonUtil.int(it, "index") ?: Int.MAX_VALUE }.mapNotNull(::parseFile)
    }

    private fun parseFile(page: JsonObject): SongInfo? {
        val info = page["imageinfo"].objects().firstOrNull() ?: return null
        val url = JsonUtil.str(info, "url")?.toHttpUrlOrNull() ?: return null
        val mime = JsonUtil.str(info, "mime").orEmpty()
        val ext = AudioLinkTester.normalizeExt(url.pathSegments.last().substringAfterLast('.', ""))
        if (!mime.startsWith("audio/") || !AudioLinkTester.isValidAudioExt(ext)) return null
        val id = JsonUtil.str(page, "pageid") ?: return null
        val fileTitle = JsonUtil.str(page, "title")?.removePrefix("File:")?.substringBeforeLast('.')
        fun metadata(key: String) = JsonUtil.extract(info, listOf("extmetadata", key, "value"))
            ?.replace(Regex("<[^>]+>"), " ")?.trim()?.takeIf(String::isNotEmpty)

        val bytes = JsonUtil.long(info, "size") ?: 0L
        val seconds = JsonUtil.long(info, "duration") ?: 0L
        val status = DownloadUrlStatus(
            ok = true, downloadUrl = url.toString(), originalDownloadUrl = url.toString(),
            ext = ext.orEmpty(), contentType = mime, fileSizeBytes = bytes, fileSize = PathUtils.byteToMb(bytes),
            method = "Wikimedia imageinfo",
        )
        return SongInfo(
            source = source, identifier = id, rawData = mapOf("search" to page),
            songName = PathUtils.legalizeString(metadata("ObjectName") ?: fileTitle),
            singers = PathUtils.legalizeString(metadata("Artist") ?: metadata("Credit")),
            album = PathUtils.legalizeString(metadata("Collection") ?: "Wikimedia Commons"),
            durationSeconds = seconds, duration = SongInfo.formatDuration(seconds),
            ext = ext, fileSizeBytes = bytes, fileSize = status.fileSize,
            coverUrl = JsonUtil.str(info, "thumburl"), downloadUrl = status.downloadUrl,
            downloadUrlStatus = status, downloadHeaders = downloadHeaders,
        )
    }
}

class AudiusMusicClient internal constructor(
    config: MusicClientConfig,
    endpoint: String,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, "https://api.audius.co/v1/")

    override val source = "AudiusMusicClient"
    private val api = endpoint.toHttpUrl()
    private val app = mapOf("app_name" to "musicdl")

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> =
        pagedSearchUrls(
            api.resolve("tracks/search").toString(), app + ("query" to keyword) + rule,
            searchSizePerSource, searchSizePerPage.coerceAtMost(50),
        )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val page = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) }
        val limit = searchUrl.toHttpUrl().queryParameter("limit")?.toIntOrNull() ?: searchSizePerPage
        val tracks = page?.get("data").objects().asSequence().mapNotNull(::parseTrack)
        results += (if (strictLimitSearchSizePerPage) tracks.take(limit) else tracks).toList()
    }

    override fun parsePlaylist(playlistUrl: String): List<SongInfo> {
        val url = playlistUrl.toHttpUrlOrNull() ?: return emptyList()
        if (url.host != "audius.co" && url.host != "www.audius.co") return emptyList()
        val payload = get("resolve", parseHeaders, app + ("url" to url.toString()))?.get("data")
        val playlist = (payload as? JsonObject) ?: payload.objects().firstOrNull() ?: return emptyList()
        val id = JsonUtil.str(playlist, "id")?.takeIf(::validId) ?: return emptyList()
        val workDir = constructUniqueWorkDir(JsonUtil.str(playlist, "playlist_name") ?: "playlist-$id")
        val tracks = get("playlists/$id/tracks", parseHeaders, app)?.get("data").objects().mapNotNull(::parseTrack)
        return removeDuplicates(tracks).map { it.copy(workDir = workDir) }
    }

    private fun get(path: String, headers: Map<String, String>, query: Map<String, String>): JsonObject? =
        http.get(api.resolve(path).toString(), query = query, extraHeaders = headers)
            ?.use { JsonUtil.parseObject(it.textOrNull()) }

    private fun validId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9_-]+"))

    private fun parseTrack(track: JsonObject): SongInfo? {
        val id = JsonUtil.str(track, "id")?.takeIf(::validId) ?: return null
        val url = requireNotNull(api.resolve("tracks/$id/stream")).newBuilder()
            .addQueryParameter("app_name", "musicdl").build()
        val status = audioLinkTester.test(url.toString(), downloadHeaders)
        if (!status.ok) return null
        val user = JsonUtil.obj(track, "user")
        val artwork = JsonUtil.obj(track, "artwork")
        val seconds = JsonUtil.long(track, "duration") ?: 0L
        return SongInfo(
            source = source, identifier = id, rawData = mapOf("search" to track),
            songName = PathUtils.legalizeString(JsonUtil.str(track, "title")),
            singers = PathUtils.legalizeString(JsonUtil.str(user, "name") ?: JsonUtil.str(user, "handle")),
            album = JsonUtil.str(track, "album_name") ?: JsonUtil.str(track, "playlist_name"),
            coverUrl = listOf("1000x1000", "480x480", "150x150").firstNotNullOfOrNull { JsonUtil.str(artwork, it) },
            durationSeconds = seconds, duration = SongInfo.formatDuration(seconds),
            ext = status.ext, fileSizeBytes = status.fileSizeBytes, fileSize = status.fileSize,
            downloadUrl = status.downloadUrl, downloadUrlStatus = status, downloadHeaders = downloadHeaders,
            downloadCookies = downloadCookies, protocol = if (status.ext == "m3u8") "HLS" else "HTTP",
        )
    }
}
