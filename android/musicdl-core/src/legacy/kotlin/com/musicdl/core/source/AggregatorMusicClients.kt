package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Port of `musicdl/modules/common/gdstudio.py::GDStudioMusicClient`. */
class GDStudioMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedMusicSources: Set<String>,
    private val host: String,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("netease", "joox", "kuwo", "tidal", "qobuz", "apple", "bilibili"), "music.gdstudio.xyz")

    override val source = "GDStudioMusicClient"

    private val baseUrl = "https://$host/"
    private val apiUrl = "https://$host/api.php"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer" to baseUrl,
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Origin" to "https://$host",
    )

    private val searchRules = mutableMapOf<String, Pair<String, Map<String, String>>>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val encoded = jsEncodeUriComponent(keyword)
        val urls = mutableListOf<String>()
        for (site in SUPPORTED_SITES) {
            if (site !in allowedMusicSources) continue
            var count = 0
            while (count < searchSizePerSource) {
                val params = mapOf(
                    "types" to "search",
                    "count" to searchSizePerPage.toString(),
                    "pages" to (count / searchSizePerPage + 1).toString(),
                    "name" to keyword,
                    "source" to site,
                    "s" to sign(encoded),
                ) + rule
                val url = "$apiUrl?" + params.entries.joinToString("&") { "${it.key}=${quote(it.value)}" }
                searchRules[url] = site to params
                urls += url
                count += searchSizePerPage
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val (_, params) = searchRules[searchUrl] ?: return
        val payload = http.get(apiUrl, query = params, extraHeaders = headers)?.use { JsonUtil.parse(it.textOrNull()) } ?: return
        val items = payload as? JsonArray ?: return
        for (element in items) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val item = element as? JsonObject ?: continue
            val songId = JsonUtil.stringify(item["id"]) ?: continue
            val urlId = JsonUtil.str(item, "url_id") ?: continue
            val rootSource = JsonUtil.str(item, "source") ?: continue
            val download = getSongUrl(urlId, rootSource, MUSIC_QUALITIES.first()) ?: continue
            val rawUrl = JsonUtil.str(download, "url") ?: continue
            if (JsonUtil.long(download, "size") == 0L || JsonUtil.long(download, "br") == -1L) continue
            val resolved = if (rawUrl.startsWith("http")) rawUrl else baseUrl + rawUrl.removePrefix("/")
            var status = audioLinkTester.test(resolved, downloadHeaders)
            if (!status.ok) status = audioLinkTester.test("https://music-proxy.gdstudio.org/$resolved", downloadHeaders)
            if (!status.ok) continue
            val seconds = (JsonUtil.extract(item, listOf("extra_data", "duration")) ?: "0").toDoubleOrNull()?.toLong() ?: 0L
            val cover = getPic(JsonUtil.str(item, "pic_id"), rootSource)?.let { JsonUtil.str(it, "url") }
            val lyric = TextUtils.cleanLrc(getLyric(JsonUtil.str(item, "lyric_id"), rootSource)?.let { JsonUtil.str(it, "lyric") })
            val artists = (item["artist"] as? JsonArray)?.mapNotNull { JsonUtil.stringify(it) }
            val detectedExt = if (status.ext == "m4s" || status.ext == "mp4") "m4a" else status.ext
            results += SongInfo(
                source = source,
                rootSource = rootSource,
                identifier = songId,
                rawData = mapOf("search" to item, "download" to download),
                songName = PathUtils.legalizeString(JsonUtil.str(item, "name")),
                singers = PathUtils.legalizeString(artists?.joinToString(", ")),
                album = PathUtils.legalizeString(JsonUtil.str(item, "album")),
                ext = detectedExt,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = lyric,
                coverUrl = cover,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = if (rootSource == "bilibili") downloadHeaders + ("Referer" to "https://www.bilibili.com/") else downloadHeaders,
            )
        }
    }

    private fun getSongUrl(songId: String, source: String, br: Int): JsonObject? = http.get(
        apiUrl,
        query = mapOf(
            "types" to "url", "id" to songId, "source" to source, "br" to br.toString(),
            "s" to sign(jsEncodeUriComponent(songId)),
        ),
        extraHeaders = headers,
    )?.use { JsonUtil.parseObject(it.textOrNull()) }

    private fun getPic(picId: String?, source: String): JsonObject? {
        if (picId.isNullOrBlank()) return null
        if (source == "kuwo") {
            val path = if (picId.startsWith("120/")) "300/" + picId.drop(4) else picId
            return JsonObject(mapOf("url" to JsonPrimitive("http://img1.kwcdn.kuwo.cn/star/albumcover/$path")))
        }
        if (source == "bilibili") {
            return JsonObject(mapOf("url" to JsonPrimitive(if (picId.startsWith("http")) picId else "https:$picId")))
        }
        return http.get(
            apiUrl,
            query = mapOf("types" to "pic", "id" to picId, "source" to source, "size" to "300", "s" to sign(jsEncodeUriComponent(picId))),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) }
    }

    private fun getLyric(lyricId: String?, source: String): JsonObject? {
        if (lyricId.isNullOrBlank()) return null
        return http.get(
            apiUrl,
            query = mapOf("types" to "lyric", "id" to lyricId, "source" to source, "s" to sign(jsEncodeUriComponent(lyricId))),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) }
    }

    private fun serverTime(): String =
        http.get("https://$host/time", extraHeaders = headers)?.use { it.textOrNull() }?.trim()
            ?.takeIf { it.isNotEmpty() } ?: (System.currentTimeMillis() / 1000).toString()

    private fun sign(payload: String): String = makeSign(payload, serverTime(), host, VERSION)

    private fun quote(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val VERSION = "2026.09.25"
        val MUSIC_QUALITIES = listOf(999, 740, 320, 192, 128)
        val SUPPORTED_SITES = listOf("netease", "joox", "kuwo", "tidal", "qobuz", "apple", "bilibili", "ytmusic", "embeat", "tencent", "spotify")

        fun jsEncodeUriComponent(value: String): String = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("(", "%28").replace(")", "%29").replace("*", "%2A")
            .replace("'", "%27").replace("!", "%21")
            .replace("%7E", "~")

        fun normalizeVersion(version: String): String =
            version.split('.').joinToString("") { if (it.length == 1) it.padStart(2, '0') else it }

        fun makeSign(payload: String, serverTime: String, host: String, version: String): String {
            val timePrefix = serverTime.take(9)
            val digest = md5Hex("$timePrefix|$host|${normalizeVersion(version)}|$payload")
            return digest.takeLast(8).uppercase(Locale.ROOT)
        }
    }
}

/** Port of `musicdl/modules/common/tunehub.py::TuneHubMusicClient`. */
class TuneHubMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedMusicSources: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("netease"))

    override val source = "TuneHubMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
    )

    private data class SearchSpec(val site: String, val page: Int)

    private val specs = mutableMapOf<String, SearchSpec>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val urls = mutableListOf<String>()
        if ("netease" in allowedMusicSources) {
            val url = "https://api.qijieya.cn/meting/?server=netease&type=search&id=" + URLEncoder.encode(keyword, "UTF-8")
            specs[url] = SearchSpec("netease", 1)
            urls += url
        }
        for (site in listOf("kuwo", "qq")) {
            if (site !in allowedMusicSources) continue
            var count = 0
            while (count < searchSizePerSource) {
                val page = count / searchSizePerPage + 1
                val url = "tunehub://$site/$page/" + URLEncoder.encode(keyword, "UTF-8") + "?limit=$searchSizePerPage"
                specs[url] = SearchSpec(site, page)
                urls += url
                count += searchSizePerPage
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val spec = specs[searchUrl] ?: return
        val candidates = when (spec.site) {
            "netease" -> searchNetease(searchUrl)
            "kuwo" -> searchKuwo(keyword, spec.page)
            "qq" -> searchQQ(keyword, spec.page)
            else -> emptyList()
        }
        for (candidate in candidates) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val songId = JsonUtil.str(candidate, "id") ?: continue
            val song = when (spec.site) {
                "netease" -> resolveNetease(candidate, songId)
                else -> resolveTuneHub(candidate, songId, spec.site)
            } ?: continue
            if (!song.hasValidDownloadUrl || song.ext !in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) continue
            results += song
        }
    }

    private fun searchNetease(url: String): List<JsonObject> {
        val payload = http.get(url, extraHeaders = headers)?.use { JsonUtil.parse(it.textOrNull()) } ?: return emptyList()
        return (payload as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
    }

    private fun searchKuwo(keyword: String, page: Int): List<JsonObject> {
        val payload = http.get(
            "http://search.kuwo.cn/r.s",
            query = mapOf(
                "client" to "kt", "all" to keyword, "pn" to (page - 1).toString(), "rn" to "20",
                "uid" to "794762570", "ver" to "kwplayer_ar_9.2.2.1", "vipver" to "1",
                "show_copyright_off" to "1", "newver" to "1", "ft" to "music", "cluster" to "0",
                "strategy" to "2012", "encoding" to "utf8", "rformat" to "json", "vermerge" to "1",
                "mobi" to "1", "issubtitle" to "1",
            ),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return emptyList()
        val items = (payload["abslist"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        return items.mapNotNull { item ->
            val raw = JsonUtil.str(item, "MUSICRID") ?: return@mapNotNull null
            buildJsonObject {
                put("id", raw.replace("MUSIC_", ""))
                put("name", JsonUtil.str(item, "SONGNAME").orEmpty())
                put("artist", JsonUtil.str(item, "ARTIST").orEmpty().replace("&", ", "))
                put("album", JsonUtil.str(item, "ALBUM").orEmpty())
            }
        }
    }

    private fun searchQQ(keyword: String, page: Int): List<JsonObject> {
        val body = buildJsonObject {
            put("req_1", buildJsonObject {
                put("method", "DoSearchForQQMusicDesktop")
                put("module", "music.search.SearchCgiService")
                put("param", buildJsonObject {
                    put("num_per_page", 20)
                    put("page_num", page)
                    put("query", keyword)
                    put("search_type", "0")
                })
            })
        }.toString()
        val payload = http.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body,
            extraHeaders = headers + mapOf("Referer" to "https://y.qq.com/", "Cookie" to "uin="),
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return emptyList()
        val list = (JsonUtil.extractElement(payload, listOf("req_1", "data", "body", "song", "list")) as? JsonArray)
            ?.filterIsInstance<JsonObject>().orEmpty()
        return list.mapNotNull { item ->
            val mid = JsonUtil.str(item, "mid") ?: return@mapNotNull null
            val singers = (item["singer"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.mapNotNull { JsonUtil.str(it, "name") }?.joinToString(", ")
            buildJsonObject {
                put("id", mid)
                put("name", JsonUtil.str(item, "name").orEmpty())
                put("artist", singers.orEmpty())
                put("album", JsonUtil.extract(item, listOf("album", "name")).orEmpty())
            }
        }
    }

    private fun resolveNetease(candidate: JsonObject, songId: String): SongInfo? {
        var status = DownloadUrlStatus()
        var resolved = ""
        for (br in METING_QUALITIES) {
            val url = "https://api.qijieya.cn/meting/?server=netease&type=url&id=$songId&br=$br"
            resolved = http.head(url, extraHeaders = headers)?.use { it.request.url.toString() } ?: url
            status = audioLinkTester.test(resolved, downloadHeaders)
            if (status.ok && status.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
        }
        if (!status.ok) return null
        val cover = JsonUtil.str(candidate, "pic")
            ?: "https://api.qijieya.cn/meting/?server=netease&type=pic&id=$songId"
        return SongInfo(
            source = source,
            rootSource = "netease",
            identifier = songId,
            rawData = mapOf("search" to candidate),
            songName = PathUtils.legalizeString(JsonUtil.str(candidate, "name")),
            singers = PathUtils.legalizeString(JsonUtil.str(candidate, "artist")),
            album = PathUtils.legalizeString(JsonUtil.str(candidate, "album")),
            ext = status.ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = cover,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    private fun resolveTuneHub(candidate: JsonObject, songId: String, site: String): SongInfo? {
        for (quality in TUNE_HUB_QUALITIES) {
            val body = buildJsonObject {
                put("quality", quality)
                put("ids", songId)
                put("platform", site)
            }.toString()
            var payload = http.postJson("https://tunehub.sayqz.com/api/v1/parse?", body, extraHeaders = headers)
                ?.use { JsonUtil.parseObject(it.textOrNull()) }
            var url = JsonUtil.extract(payload, listOf("data", "data", 0, "url"))
            if (url.isNullOrBlank()) {
                payload = http.postJson("https://api.yexin.de5.net/api/tunehub/parse?", body, extraHeaders = headers)
                    ?.use { JsonUtil.parseObject(it.textOrNull()) }
                url = JsonUtil.extract(payload, listOf("data", "data", 0, "url"))
            }
            if (url.isNullOrBlank() || !url.startsWith("http")) continue
            val status = audioLinkTester.test(url, downloadHeaders)
            if (!status.ok) continue
            val seconds = JsonUtil.extract(payload, listOf("data", "data", 0, "info", "duration"))?.toDoubleOrNull()?.toLong() ?: 0L
            return SongInfo(
                source = source,
                rootSource = site,
                identifier = songId,
                rawData = mapOf("search" to candidate, "download" to (payload ?: JsonObject(emptyMap()))),
                songName = PathUtils.legalizeString(JsonUtil.str(candidate, "name")),
                singers = PathUtils.legalizeString(JsonUtil.str(candidate, "artist")),
                album = PathUtils.legalizeString(JsonUtil.str(candidate, "album")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = TextUtils.cleanLrc(JsonUtil.extract(payload, listOf("data", "data", 0, "lyrics"))),
                coverUrl = JsonUtil.extract(payload, listOf("data", "data", 0, "cover")),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }

    private companion object {
        val METING_QUALITIES = listOf("400", "380", "320", "128")
        val TUNE_HUB_QUALITIES = listOf("flac24bit", "flac", "320k", "128k")
    }
}

/** Port of `musicdl/modules/common/mp3juice.py::MP3JuiceMusicClient`. */
class MP3JuiceMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "MP3JuiceMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://mp3juice.sc/",
        "Origin" to "https://mp3juice.sc",
    )

    private var apiKey: String = ""

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        if (apiKey.isEmpty()) apiKey = scrapeApiKey() ?: return emptyList()
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(URLEncoder.encode(keyword, "UTF-8").toByteArray())
        val params = mapOf(
            "api_key" to apiKey,
            "y" to "s",
            "q" to encoded,
            "_" to System.currentTimeMillis().toString(),
        ) + rule
        return listOf("https://mp3juice.sc/api/v1/search?" + params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" })
    }

    private fun scrapeApiKey(): String? {
        val html = http.get("https://mp3juice.sc/", extraHeaders = headers)?.use { it.textOrNull() } ?: return null
        return html.substringAfter("var apiKey='", "").substringBefore("'").takeIf { it.isNotEmpty() }
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val payload = http.get(searchUrl, extraHeaders = headers)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val youtube = (payload["yt"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val soundcloud = (payload["sc"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val interleaved = mutableListOf<Pair<JsonObject, String>>()
        for (index in 0 until maxOf(youtube.size, soundcloud.size)) {
            youtube.getOrNull(index)?.let { interleaved += it to "YouTube" }
            soundcloud.getOrNull(index)?.let { interleaved += it to "SoundCloud" }
        }
        for ((candidate, rootSource) in interleaved) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val songId = JsonUtil.str(candidate, "id") ?: continue
            val url = if (rootSource == "SoundCloud") {
                val idBase64 = JsonUtil.str(candidate, "id_base64") ?: continue
                val titleBase64 = JsonUtil.str(candidate, "title_base64") ?: continue
                "https://thetacloud.org/s/$idBase64/$titleBase64/"
            } else {
                resolveYouTube(candidate, songId) ?: continue
            }
            val status = audioLinkTester.test(url, downloadHeaders)
            if (!status.ok || status.ext !in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) continue
            results += SongInfo(
                source = source,
                rootSource = rootSource,
                identifier = songId,
                rawData = mapOf("search" to candidate),
                songName = PathUtils.legalizeString(JsonUtil.str(candidate, "title")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
    }

    private fun resolveYouTube(candidate: JsonObject, songId: String): String? {
        val auth = http.get(
            "https://theta.thetacloud.org/api/v1/auth",
            query = mapOf("api_key" to apiKey, "_" to System.currentTimeMillis().toString()),
            extraHeaders = headers,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val key = JsonUtil.str(auth, "key") ?: return null
        val authHeaders = headers + ("Authorization" to "Bearer $key")
        val init = http.get(
            "https://theta.thetacloud.org/api/v1/init",
            query = mapOf("_" to System.currentTimeMillis().toString()),
            extraHeaders = authHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val convertUrl = JsonUtil.str(init, "convertURL") ?: return null
        val convert = http.get(
            "$convertUrl&v=$songId&f=mp3&_=${System.currentTimeMillis() / 1000}",
            extraHeaders = authHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val redirectUrl = JsonUtil.str(convert, "redirectURL") ?: return null
        val redirect = http.get(redirectUrl, extraHeaders = authHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val downloadUrl = JsonUtil.str(redirect, "downloadURL") ?: return null
        return "$downloadUrl&v=$songId&f=mp3&r=mp3juice.sc"
    }
}

/** Port of `musicdl/modules/common/myfreemp3.py::MyFreeMP3MusicClient`. */
class MyFreeMP3MusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "MyFreeMP3MusicClient"

    private val headers = mapOf(
        "accept" to "*/*",
        "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "x-requested-with" to "XMLHttpRequest",
        "origin" to "https://www.myfreemp3.com.cn",
        "referer" to "https://www.myfreemp3.com.cn/",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    )

    private val pageRules = mutableMapOf<String, Map<String, String>>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val urls = mutableListOf<String>()
        val pageSize = minOf(10, searchSizePerPage)
        var count = 0
        while (count < searchSizePerSource) {
            val form = mapOf(
                "type" to "netease",
                "filter" to "name",
                "page" to (count / pageSize + 1).toString(),
                "input" to keyword,
            ) + rule
            val key = "https://www.myfreemp3.com.cn/#${count / pageSize + 1}"
            pageRules[key] = form
            urls += key
            count += pageSize
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val form = pageRules[searchUrl] ?: return
        val payload = http.postForm("https://www.myfreemp3.com.cn/", form, extraHeaders = headers)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val list = JsonUtil.extractElement(payload, listOf("data", "list")) as? JsonArray ?: return
        for (element in list) {
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
            val item = element as? JsonObject ?: continue
            val songId = JsonUtil.stringify(item["id"]) ?: continue
            val outer = "http://music.163.com/song/media/outer/url?id=$songId.mp3"
            val resolved = http.head(outer, extraHeaders = headers)?.use { it.request.url.toString() } ?: outer
            val status = audioLinkTester.test(resolved, downloadHeaders)
            if (!status.ok) continue
            val lyric = TextUtils.cleanLrc(JsonUtil.str(item, "lrc")?.removePrefix("data:text/plain,"))
            val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)
            results += SongInfo(
                source = source,
                rootSource = "netease",
                identifier = songId,
                rawData = mapOf("search" to item),
                songName = PathUtils.legalizeString(JsonUtil.str(item, "title")),
                singers = PathUtils.legalizeString(JsonUtil.str(item, "author")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = lyric,
                coverUrl = JsonUtil.str(item, "pic"),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }
    }
}

/** Port of `musicdl/modules/common/jbsou.py::JBSouMusicClient`. */
class JBSouMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedMusicSources: Set<String>,
) : PostedSiteMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("netease", "qq", "kugou", "kuwo"))

    override val source = "JBSouMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
        "Origin" to "https://www.jbsou.cn",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "https://www.jbsou.cn/",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
    )

    private val pageRules = mutableMapOf<String, Map<String, String>>()
    private var visitCookie: String? = null

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        visitCookie = http.get("https://www.jbsou.cn/", extraHeaders = mapOf("User-Agent" to headers.getValue("User-Agent")))
            ?.use { it.textOrNull() }
            ?.let { html -> Regex("""document\.cookie="mk_visit=([^;"]+)""").find(html)?.groupValues?.get(1) }
        val urls = mutableListOf<String>()
        val pageSize = minOf(searchSizePerPage, 10)
        for (site in SITES) {
            if (site !in allowedMusicSources) continue
            var count = 0
            while (count < searchSizePerSource) {
                val form = mapOf(
                    "input" to keyword,
                    "filter" to "name",
                    "type" to site,
                    "page" to (count / pageSize + 1).toString(),
                ) + rule
                val key = "https://www.jbsou.cn/#$site/${count / pageSize + 1}"
                pageRules[key] = form
                urls += key
                count += pageSize
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val form = pageRules[searchUrl] ?: return
        val extra = headers + listOfNotNull(visitCookie?.let { "Cookie" to "mk_visit=$it" })
        val payload = http.postForm("https://www.jbsou.cn/", form, extraHeaders = extra)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        collectSongs(payload, "https://www.jbsou.cn/", form["type"] ?: "", extra, results)
    }

    private companion object {
        val SITES = listOf("netease", "qq", "kugou", "kuwo", "migu", "qianqian")
    }
}

/** Port of `musicdl/modules/common/xiaobai.py::XiaoBaiMusicClient`. */
class XiaoBaiMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedMusicSources: Set<String>,
) : PostedSiteMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("netease", "qq", "kugou", "kuwo"))

    override val source = "XiaoBaiMusicClient"

    private val headers = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36",
        "origin" to "https://music.90svip.cn",
        "x-requested-with" to "XMLHttpRequest",
        "accept" to "application/json, text/javascript, */*; q=0.01",
        "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "referer" to "https://music.90svip.cn/",
    )

    private val pageRules = mutableMapOf<String, Map<String, String>>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val urls = mutableListOf<String>()
        val pageSize = minOf(searchSizePerPage, 10)
        for (site in SITES) {
            if (site !in allowedMusicSources) continue
            var count = 0
            while (count < searchSizePerSource) {
                val form = mapOf(
                    "input" to keyword,
                    "filter" to "name",
                    "type" to site,
                    "page" to (count / pageSize + 1).toString(),
                ) + rule
                val key = "https://music.90svip.cn/#$site/${count / pageSize + 1}"
                pageRules[key] = form
                urls += key
                count += pageSize
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val form = pageRules[searchUrl] ?: return
        val payload = http.postForm("https://music.90svip.cn/", form, extraHeaders = headers)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        collectSongs(payload, "https://music.90svip.cn/", form["type"] ?: "", headers, results)
    }

    private companion object {
        val SITES = listOf("netease", "qq", "kugou", "kuwo")
    }
}

/**
 * Shared result walker for the two "post a form to the site root" scrapers
 * (`jbsou.py` and `xiaobai.py` share the same response shape).
 */
abstract class PostedSiteMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {
    protected fun collectSongs(
        payload: JsonObject,
        baseUrl: String,
        rootSource: String,
        headers: Map<String, String>,
        results: MutableList<SongInfo>,
    ) {
        val list = payload["data"] as? JsonArray ?: return
        val parsed = runBlocking(Dispatchers.IO) {
        list.filterIsInstance<JsonObject>().map { item -> async {
            val songId = JsonUtil.str(item, "songid") ?: return@async null
            val rawUrl = JsonUtil.str(item, "url") ?: return@async null
            val resolved = link(baseUrl, rawUrl)
            val status = audioLinkTester.testFast(resolved, headers)
            if (!status.ok) return@async null
            val lyricUrl = JsonUtil.str(item, "lrc")?.let { link(baseUrl, it) }
            val lyric = lyricUrl?.let { url ->
                http.get(url, extraHeaders = headers)?.use { it.textOrNull() }
                    ?.substringAfterLast("</script>")
                    ?.let { TextUtils.cleanLrc(it) }
            }
            val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)
            SongInfo(
                source = source,
                rootSource = rootSource,
                identifier = "$rootSource:$songId",
                rawData = mapOf("search" to item),
                songName = PathUtils.legalizeString(JsonUtil.str(item, "name")),
                singers = PathUtils.legalizeString(JsonUtil.str(item, "artist")?.replace("/", ", ")),
                album = PathUtils.legalizeString(JsonUtil.str(item, "album")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                lyric = lyric,
                coverUrl = JsonUtil.str(item, "cover")?.let { link(baseUrl, it) },
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = headers,
            )
        } }.awaitAll()
        }
        results += parsed.filterNotNull().take(searchSizePerPage)
    }
}

private fun link(baseUrl: String, path: String): String =
    if (path.startsWith("http")) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')
