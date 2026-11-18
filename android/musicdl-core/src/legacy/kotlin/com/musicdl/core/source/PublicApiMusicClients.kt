package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

private fun sha1Hex(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun jamCall(path: String): String {
    val rand = Random.nextDouble().toString()
    return "$${sha1Hex(path + rand)}*$rand~"
}

/** Port of `musicdl/modules/sources/jamendo.py::JamendoMusicClient`. */
class JamendoMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "JamendoMusicClient"

    private fun headers(path: String) = mapOf(
        "referer" to "https://www.jamendo.com/search?q=musicdl",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "x-jam-version" to "4rkl5f",
        "x-jam-call" to jamCall(path),
        "x-requested-with" to "XMLHttpRequest",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://www.jamendo.com/api/search".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("query", keyword)
                setQueryParameter("type", "track")
                setQueryParameter("limit", searchSizePerPage.toString())
                setQueryParameter("identities", "www")
                setQueryParameter("offset", count.toString())
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += searchSizePerPage
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val payload = http.get(searchUrl, extraHeaders = headers("/api/search"))?.use { JsonUtil.parse(it.textOrNull()) } as? JsonArray ?: return
        for (element in payload) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val track = element as? JsonObject ?: continue
            val songId = JsonUtil.stringify(track["id"]) ?: continue
            parseTrack(track, songId)?.let { results += it }
        }
    }

    private fun parseTrack(search: JsonObject, songId: String): SongInfo? {
        val detail = http.get(
            "https://www.jamendo.com/api/tracks",
            query = mapOf("id[]" to songId),
            extraHeaders = headers("/api/tracks"),
        )?.use { JsonUtil.parse(it.textOrNull()) }?.let { (it as? JsonArray)?.firstOrNull() as? JsonObject }

        val albumId = JsonUtil.extract(search, listOf("album", "id")) ?: JsonUtil.str(search, "albumId")
        val candidates = mutableListOf("https://prod-1.storage.jamendo.com/download/track/$songId/flac/")
        for (path in listOf(listOf("stream", "flac"), listOf("download", "flac"), listOf("stream", "mp3"), listOf("download", "mp3"), listOf("stream", "ogg"), listOf("download", "ogg"))) {
            val url = JsonUtil.extract(detail, path) ?: JsonUtil.extract(search, path) ?: continue
            if (url.startsWith("http")) candidates += url
        }
        for (candidate in candidates) {
            val status = audioLinkTester.test(candidate, downloadHeaders)
            if (!status.ok) continue
            val seconds = (JsonUtil.double(search, "duration") ?: JsonUtil.double(detail, "duration") ?: 0.0).toLong()
            val lyric = TextUtils.cleanLrc(JsonUtil.str(detail, "lyrics")?.replace("<br />", "\n"))
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to search, "download" to detail),
                songName = PathUtils.legalizeString(JsonUtil.str(search, "name") ?: JsonUtil.str(detail, "name")),
                singers = PathUtils.legalizeString(JsonUtil.extract(search, listOf("artist", "name")) ?: JsonUtil.extract(detail, listOf("artist", "name"))),
                album = PathUtils.legalizeString(JsonUtil.extract(search, listOf("album", "name")) ?: JsonUtil.extract(detail, listOf("album", "name"))),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = lyric,
                coverUrl = "https://usercontent.jamendo.com?type=album&id=$albumId&width=300&trackid=$songId",
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }
}

/** Port of `musicdl/modules/sources/fma.py::FMAMusicClient`. */
class FMAMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "FMAMusicClient"

    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
        "referer" to "https://freemusicarchive.org/",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://freemusicarchive.org/search/".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("quicksearch", keyword)
                setQueryParameter("pageSize", searchSizePerPage.toString())
                setQueryParameter("page", (count / searchSizePerPage + 1).toString())
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += searchSizePerPage
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val html = http.get(searchUrl, extraHeaders = headers)?.use { it.textOrNull() } ?: return
        val doc = Jsoup.parse(html)
        for (item in doc.select(".play-item[data-track-info]")) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val track = JsonUtil.parseObject(item.attr("data-track-info")) ?: continue
            val songId = JsonUtil.stringify(track["id"]) ?: continue
            val album = item.selectFirst(".ptxt-album a")?.text()?.trim()
            val duration = item.selectFirst("span.inline-flex.items-center")?.text()?.trim()
            track.toMutableMap().let {
                val enriched = JsonObject(
                    it + mapOf(
                        "albumTitle" to JsonPrimitive(album ?: ""),
                        "duration" to JsonPrimitive(duration ?: ""),
                    ),
                )
                parseTrack(enriched, songId)?.let { song -> results += song }
            }
        }
    }

    private fun parseTrack(search: JsonObject, songId: String): SongInfo? {
        val pageUrl = JsonUtil.str(search, "url") ?: return null
        val html = http.get(pageUrl, extraHeaders = headers)?.use { it.textOrNull() }
        val doc = html?.let(Jsoup::parse)
        val detail = doc?.selectFirst("[data-track-info]")?.attr("data-track-info")?.let(JsonUtil::parseObject)
        val cover = doc?.selectFirst("img[src*='/image/'][src*='type=track']")?.absUrl("src")
        val candidates = listOf(
            detail?.let { JsonUtil.str(it, "fileUrl") },
            JsonUtil.str(search, "fileUrl"),
            detail?.let { JsonUtil.str(it, "playbackUrl") },
            JsonUtil.str(search, "playbackUrl"),
            detail?.let { JsonUtil.str(it, "downloadUrl") },
            JsonUtil.str(search, "downloadUrl"),
        ).filterNotNull().filter { it.startsWith("http") }
        val seconds = TextUtils.parseDurationText(JsonUtil.str(search, "duration"))
        for (candidate in candidates) {
            val status = audioLinkTester.test(candidate, downloadHeaders)
            if (!status.ok) continue
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to search, "download" to detail),
                songName = PathUtils.legalizeString(JsonUtil.str(search, "title") ?: JsonUtil.str(detail, "title")),
                singers = PathUtils.legalizeString(JsonUtil.str(search, "artistName") ?: JsonUtil.str(detail, "artistName")),
                album = PathUtils.legalizeString(JsonUtil.str(search, "albumTitle") ?: JsonUtil.str(detail, "albumTitle")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                coverUrl = cover,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }
}

/** Port of `musicdl/modules/sources/ccmixter.py::CCMixterMusicClient`. */
class CCMixterMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "CCMixterMusicClient"

    private val headers = mapOf(
        "accept" to "application/xspf+xml, application/xml, text/xml, */*",
        "referer" to "https://ccmixter.org/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://ccmixter.org/api/query".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("f", "xspf")
                setQueryParameter("t", "search_uploads")
                setQueryParameter("search_type", "any")
                setQueryParameter("search", keyword)
                setQueryParameter("limit", searchSizePerPage.toString())
                setQueryParameter("offset", count.toString())
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += searchSizePerPage
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val xspf = http.get(searchUrl, extraHeaders = headers)?.use { it.textOrNull() } ?: return
        val parsed = parseXspf(xspf)
        if (parsed.isEmpty()) return
        val infoUrl = "https://ccmixter.org/api/query".toHttpUrlOrNull()?.newBuilder()
            ?.setQueryParameter("f", "json")
            ?.setQueryParameter("dataview", "info")
            ?.setQueryParameter("ids", parsed.joinToString(",") { it.second })
            ?.build()?.toString()
        val uploads = infoUrl?.let { url ->
            http.get(url, extraHeaders = headers)?.use { JsonUtil.parse(it.textOrNull()) } as? JsonArray
        }?.filterIsInstance<JsonObject>().orEmpty().associateBy { JsonUtil.stringify(it["upload_id"]).orEmpty() }

        for ((track, identifier) in parsed) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val upload = uploads[identifier]
            val duration = (upload?.get("files") as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.mapNotNull { JsonUtil.extract(it, listOf("file_format_info", "ps")) }
                ?.firstOrNull { it.isNotEmpty() }
            val seconds = if (duration != null) duration.split(':').reversed()
                .foldIndexed(0.0) { index, acc, part -> acc + (part.toDoubleOrNull() ?: 0.0) * Math.pow(60.0, index.toDouble()) }
                .toLong() else 0L
            val status = audioLinkTester.test(JsonUtil.str(track, "download_url"), downloadHeaders)
            if (!status.ok) continue
            results += SongInfo(
                source = source,
                identifier = identifier,
                rawData = mapOf("search" to track, "download" to upload),
                songName = PathUtils.legalizeString(JsonUtil.str(track, "title")),
                singers = PathUtils.legalizeString(JsonUtil.str(track, "creator")),
                album = PathUtils.legalizeString(JsonUtil.str(track, "album")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                coverUrl = JsonUtil.str(track, "cover_url"),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
    }

    /** Returns (record, identifier) pairs from the XSPF payload. */
    private fun parseXspf(xspf: String): List<Pair<JsonObject, String>> {
        val out = mutableListOf<Pair<JsonObject, String>>()
        for (match in TRACK.findAll(xspf)) {
            val block = match.groupValues[1]
            val location = LOCATION.findAll(block)
                .map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim() }
                .firstOrNull { it.startsWith("http") } ?: continue
            val fields = buildMap {
                for (tag in listOf("title", "creator", "album", "duration", "image", "identifier", "annotation")) {
                    val value = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                        .find(block)?.groupValues?.get(1)
                        ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim() ?: ""
                    put(tag, value)
                }
            }
            val record = JsonObject(
                mapOf(
                    "download_url" to JsonPrimitive(location),
                    "title" to JsonPrimitive(fields.getValue("title")),
                    "creator" to JsonPrimitive(fields.getValue("creator")),
                    "album" to JsonPrimitive(fields.getValue("album")),
                    "cover_url" to JsonPrimitive(fields.getValue("image")),
                    "annotation" to JsonPrimitive(fields.getValue("annotation")),
                ),
            )
            out += record to (fields.getValue("identifier").ifEmpty { location })
        }
        return out
    }

    private companion object {
        val TRACK = Regex("<track>(.*?)</track>", RegexOption.DOT_MATCHES_ALL)
        val LOCATION = Regex("<location[^>]*>(.*?)</location>", RegexOption.DOT_MATCHES_ALL)
    }
}

/** Port of `musicdl/modules/sources/opengameart.py::OpenGameArtMusicClient`. */
class OpenGameArtMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "OpenGameArtMusicClient"

    private val headers = mapOf("User-Agent" to "Mozilla/5.0 OGA-Music-Search/1.0")

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "$BASE_URL/art-search-advanced".toHttpUrlOrNull() ?: return emptyList()
        val pageSize = maxOf(searchSizePerPage, 24)
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("keys", keyword)
                setQueryParameter("field_art_type_tid[0]", MUSIC_TYPE_ID)
                setQueryParameter("items_per_page", pageSize.toString())
                setQueryParameter("page", (count / pageSize).toString())
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += pageSize
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val html = http.get(searchUrl, extraHeaders = headers)?.use { it.textOrNull() } ?: return
        val doc = Jsoup.parse(html)
        val heading = doc.select("h1, h2, h3").firstOrNull { it.text().trim().equals("Search Art", true) } ?: return
        val seen = mutableSetOf<String>()
        for (anchor in heading.parents().firstOrNull()?.let { doc.select("a[href]") } ?: doc.select("a[href]")) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val href = anchor.attr("href")
            val title = anchor.text().trim()
            if (title.isEmpty() || title.lowercase(Locale.ROOT) in SKIP_TITLES) continue
            val resolved = anchor.absUrl("href").ifEmpty { BASE_URL + href }
            if (!resolved.startsWith("$BASE_URL/content/")) continue
            if (!seen.add(resolved)) continue
            parsePage(resolved, title)?.let { results += it }
        }
    }

    private fun parsePage(pageUrl: String, title: String): SongInfo? {
        val html = http.get(pageUrl, extraHeaders = headers)?.use { it.textOrNull() } ?: return null
        val doc = Jsoup.parse(html)
        val files = doc.select("a[href]")
            .mapNotNull { link ->
                val url = link.absUrl("href")
                if (!url.contains("/sites/default/files/") || url.contains("/styles/")) return@mapNotNull null
                val filename = url.substringAfterLast('/').substringBefore('?')
                val ext = "." + filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext !in AUDIO_EXTS && ext !in ARCHIVE_EXTS) return@mapNotNull null
                Triple(url, filename, ext)
            }
            .distinctBy { it.first }
        val best = files.filter { it.third in AUDIO_EXTS }.ifEmpty { files }
            .maxByOrNull { QUALITY_SCORE[it.third] ?: 0 }
            ?: return null
        val status = audioLinkTester.test(best.first, downloadHeaders)
        if (!status.ok) return null
        val lines = doc.text().split('\n').map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
        val author = lines.indexOfFirst { it.equals("Author:", true) }
            .takeIf { it >= 0 && it + 1 < lines.size }?.let { lines[it + 1] }
        val identifier = sha1Hex(
            (best.first.toHttpUrlOrNull()?.let { "${it.host}${it.encodedPath}" } ?: best.first),
        ).take(12)
        return SongInfo(
            source = source,
            identifier = identifier,
            rawData = mapOf("search" to mapOf("song_name" to title, "page_url" to pageUrl)),
            songName = PathUtils.legalizeString(title),
            singers = PathUtils.legalizeString(author),
            ext = status.ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    private companion object {
        const val BASE_URL = "https://opengameart.org"
        const val MUSIC_TYPE_ID = "12"
        val ARCHIVE_EXTS = setOf(".zip", ".7z", ".rar")
        val AUDIO_EXTS = setOf(".flac", ".wav", ".aiff", ".aif", ".opus", ".ogg", ".oga", ".m4a", ".mp3", ".mid", ".midi", ".mod", ".xm", ".it", ".s3m")
        val QUALITY_SCORE = mapOf(
            ".flac" to 100, ".wav" to 95, ".aiff" to 95, ".aif" to 95, ".opus" to 82, ".ogg" to 78,
            ".oga" to 78, ".m4a" to 74, ".mp3" to 70, ".xm" to 45, ".it" to 43, ".mod" to 40,
            ".s3m" to 40, ".mid" to 25, ".midi" to 25, ".zip" to 10, ".7z" to 10, ".rar" to 10,
        )
        val SKIP_TITLES = setOf("image", "preview", "first", "previous", "next", "last", "log in", "register", "read more")
    }
}

/** Port of `musicdl/modules/sources/jiosaavn.py::JioSaavnMusicClient`. */
class JioSaavnMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "JioSaavnMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://www.jiosaavn.com/api.php".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("p", (count / searchSizePerPage + 1).toString())
                setQueryParameter("q", keyword)
                setQueryParameter("_format", "json")
                setQueryParameter("_marker", "0")
                setQueryParameter("api_version", "4")
                setQueryParameter("ctx", "web6dot0")
                setQueryParameter("n", searchSizePerPage.toString())
                setQueryParameter("__call", "search.getResults")
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += searchSizePerPage
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val payload = http.get(searchUrl, extraHeaders = headers)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val ids = (payload["results"] as? JsonArray ?: return).mapNotNull { element ->
            (element as? JsonObject)?.let { JsonUtil.str(it, "id") }
        }
        if (ids.isEmpty()) return
        // Song details plus a stream probe per hit: one after another this alone outlived the
        // per-source budget, which is why JioSaavn kept answering "timeout" and contributing zero.
        val parsed = runBlocking(Dispatchers.IO) {
            ids.mapIndexed { index, songId ->
                async {
                    progress.onSourceTask(
                        source,
                        "$source._search >>> ${index + 1}/${ids.size} results",
                        index + 1,
                        ids.size,
                    )
                    runCatching { parseTrack(songId) }.getOrNull()
                }
            }.awaitAll()
        }
        for (song in parsed) {
            if (song == null) continue
            results += song
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseTrack(songId: String): SongInfo? {
        val payload = http.get(
            "https://www.jiosaavn.com/api.php",
            query = mapOf(
                "__call" to "song.getDetails", "cc" to "in", "_format" to "json",
                "_marker" to "0", "pids" to songId,
            ),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val detail = payload[songId] as? JsonObject ?: return null
        val base = JsonUtil.str(detail, "media_url")
            ?: decryptUrl(JsonUtil.str(detail, "encrypted_media_url"))
            ?: decryptUrl(JsonUtil.str(detail, "encrypted_drm_media_url"))
            ?: JsonUtil.str(detail, "media_preview_url")
            ?: return null
        for (quality in QUALITIES) {
            val url = normalizeMediaUrl(base, quality) ?: continue
            val status = audioLinkTester.test(url, downloadHeaders)
            if (!status.ok) continue
            val seconds = (JsonUtil.double(detail, "duration") ?: 0.0).toLong()
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to detail),
                songName = PathUtils.legalizeString(JsonUtil.str(detail, "song")),
                singers = PathUtils.legalizeString(JsonUtil.str(detail, "singers") ?: JsonUtil.str(detail, "primary_artists")),
                album = PathUtils.legalizeString(JsonUtil.str(detail, "album")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                coverUrl = JsonUtil.str(detail, "image"),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }

    private fun fetchLyric(songId: String): String? {
        val html = http.get(
            "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&ctx=web6dot0&api_version=4" +
                "&_format=json&_marker=0&lyrics_id=$songId",
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val raw = JsonUtil.str(html, "lyrics") ?: return null
        return TextUtils.cleanLrc(Jsoup.parse(raw).text().replace(". ", "\n"))
    }

    private fun decryptUrl(encUrl: String?): String? {
        if (encUrl.isNullOrBlank()) return null
        val bytes = TextUtils.decodeBase64(encUrl.trim()) ?: return null
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY.toByteArray(), "DES"))
        var out = cipher.doFinal(bytes)
        // strip PKCS#5 padding when it is present, else trailing NULs (as the original does)
        val pad = out.lastOrNull()?.toInt() ?: 0
        out = if (pad in 1..8 && out.takeLast(pad).all { it.toInt() == pad }) {
            out.dropLast(pad).toByteArray()
        } else {
            out.dropLastWhile { it == 0.toByte() }.toByteArray()
        }
        return String(out, Charsets.UTF_8).trim().replace("http://", "https://")
    }

    private fun normalizeMediaUrl(url: String, quality: String): String? {
        if (url.isBlank()) return null
        return Regex("_(12|48|96|160|320)\\.(mp4|mp3|m4a)").replace(url.replace("http://", "https://")) {
            "_$quality.${it.groupValues[2]}"
        }
    }

    private companion object {
        const val KEY = "38346591"
        val QUALITIES = listOf("320", "160", "96", "48", "12")
    }
}

/** Port of `musicdl/modules/sources/streetvoice.py::StreetVoiceMusicClient`. */
class StreetVoiceMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "StreetVoiceMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
        "Referer" to "https://www.streetvoice.cn/",
        "x-requested-with" to "XMLHttpRequest",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://www.streetvoice.cn/search/".toHttpUrlOrNull() ?: return emptyList()
        val pageSize = minOf(10, searchSizePerPage)
        val urls = mutableListOf<String>()
        var count = 0
        while (count < searchSizePerSource) {
            urls += base.newBuilder().apply {
                setQueryParameter("page", (count / pageSize + 1).toString())
                setQueryParameter("q", keyword)
                setQueryParameter("type", "song")
                setQueryParameter("_pjax", "#pjax-container")
                rule.forEach { (k, v) -> setQueryParameter(k, v) }
            }.build().toString()
            count += pageSize
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val html = http.get(searchUrl, extraHeaders = headers)?.use { it.textOrNull() } ?: return
        for (item in Jsoup.parse(html).select("ul.list-group-song li.work-item.item_box")) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val songId = item.selectFirst("button.js-search[data-id]")?.attr("data-id") ?: continue
            val title = item.selectFirst(".work-item-info h4 a")?.text()?.trim()
            val artist = item.selectFirst(".work-item-info h5 a")?.text()?.trim()
            parseSong(songId, title, artist)?.let { results += it }
        }
    }

    private fun parseSong(songId: String, title: String?, artist: String?): SongInfo? {
        val detail = http.get(
            "https://www.streetvoice.cn/api/v5/song/$songId/?_=${System.currentTimeMillis()}",
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val hls = http.postForm(
            "https://www.streetvoice.cn/api/v5/song/$songId/hls/file/",
            emptyMap(),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val url = JsonUtil.str(hls, "file") ?: return null
        if (!url.startsWith("http")) return null
        val ext = url.toHttpUrlOrNull()?.pathSegments?.let { segments ->
            if (segments.size >= 2) segments[segments.size - 2].substringAfterLast('.', "") else ""
        }?.takeIf { it.isNotEmpty() } ?: "m3u8"
        val reachable = http.get(url, extraHeaders = downloadHeaders)?.use { it.isSuccessful } ?: false
        if (!reachable) return null
        val status = DownloadUrlStatus(
            ok = true,
            ext = ext,
            downloadUrl = url,
            originalDownloadUrl = url,
            method = "streetvoice hls",
        )
        val seconds = (JsonUtil.double(detail, "length") ?: 0.0).toLong()
        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to detail, "download" to hls),
            songName = PathUtils.legalizeString(JsonUtil.str(detail, "name") ?: title),
            singers = PathUtils.legalizeString(JsonUtil.extract(detail, listOf("user", "profile", "nickname")) ?: artist),
            album = PathUtils.legalizeString(JsonUtil.extract(detail, listOf("album", "name"))),
            ext = status.ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            lyric = TextUtils.cleanLrc(JsonUtil.str(detail, "lyrics")),
            coverUrl = JsonUtil.str(detail, "image"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
            protocol = "HLS",
        )
    }
}

/** Port of `musicdl/modules/sources/suno.py::SunoMusicClient` (requires an `auth_token` cookie). */
class SunoMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "SunoMusicClient"

    private val token: String? = config.defaultSearchCookies["auth_token"]
        ?: config.defaultDownloadCookies["auth_token"]

    private fun headers(): Map<String, String> {
        val browserToken = "{\"token\":\"" + Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"timestamp\":${System.currentTimeMillis()}}".toByteArray(),
        ) + "\"}"
        return mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to "https://suno.com",
            "referer" to "https://suno.com/",
            "browser-token" to browserToken,
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36",
            "device-id" to java.util.UUID.randomUUID().toString(),
        ) + listOfNotNull(token?.let { "authorization" to "Bearer $it" })
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        if (token == null) {
            logger.info("$source needs an \"auth_token\" cookie; skipping.")
            return emptyList()
        }
        val urls = mutableListOf<String>()
        val pages = (searchSizePerSource + searchSizePerPage - 1) / searchSizePerPage
        var cursor: String? = null
        for (page in 1..pages) {
            val body = kotlinx.serialization.json.buildJsonObject {
                put("feed_id", JsonPrimitive("omnisearch_songs"))
                put("cursor", cursor?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                put("page_size", JsonPrimitive(searchSizePerPage))
                put(
                    "request_metadata",
                    kotlinx.serialization.json.buildJsonObject { put("term", JsonPrimitive(keyword)) },
                )
            }.toString()
            val response = http.postJson(FEED_URL, body, extraHeaders = headers())
                ?.use { JsonUtil.parseObject(it.textOrNull()) }
            val responseText = response?.toString() ?: return urls
            urls += "suno://page/$page?payload=" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(responseText.toByteArray())
            cursor = JsonUtil.extract(response, listOf("feed", "next_cursor"))
            if (cursor.isNullOrBlank()) break
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val encoded = searchUrl.substringAfter("payload=", "")
        val payload = TextUtils.decodeBase64(encoded)?.let { JsonUtil.parseObject(String(it)) } ?: return
        val items = JsonUtil.extractElement(payload, listOf("feed", "items")) as? JsonArray ?: return
        for (element in items) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val wrapper = element as? JsonObject ?: continue
            if (JsonUtil.str(wrapper, "content_type") != "clip") continue
            val clip = wrapper["content_item"] as? JsonObject ?: continue
            val songId = JsonUtil.str(clip, "id") ?: continue
            val media = (clip["media_urls"] as? JsonArray)?.filterIsInstance<JsonObject>()
            val url = media?.firstOrNull { JsonUtil.str(it, "content_type") == "m4a-opus" }?.let { JsonUtil.str(it, "url") }
                ?: media?.firstOrNull { JsonUtil.str(it, "content_type") == "mp3" }?.let { JsonUtil.str(it, "url") }
                ?: JsonUtil.str(clip, "audio_url")
                ?: "https://cdn1.suno.ai/$songId.mp3"
            val status = audioLinkTester.test(url, downloadHeaders)
            if (!status.ok) continue
            val seconds = (JsonUtil.extract(clip, listOf("metadata", "duration"))?.toDoubleOrNull() ?: 0.0).toLong()
            results += SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to clip),
                songName = PathUtils.legalizeString(JsonUtil.str(clip, "title")),
                singers = PathUtils.legalizeString(JsonUtil.extract(clip, listOf("persona", "name")) ?: JsonUtil.str(clip, "display_name")),
                album = PathUtils.legalizeString(JsonUtil.str(clip, "model_name")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = TextUtils.cleanLrc(JsonUtil.extract(clip, listOf("metadata", "prompt"))),
                coverUrl = JsonUtil.str(clip, "image_large_url") ?: JsonUtil.str(clip, "image_url"),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
    }

    private companion object {
        const val FEED_URL = "https://studio-api-prod.suno.com/api/unified/feed"
    }
}
