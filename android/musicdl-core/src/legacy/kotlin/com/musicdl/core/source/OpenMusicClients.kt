package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.URLEncoder

/** Recursive lookup used by the scraped pages that bury their payload in `_ROUTER_DATA`. */
private fun JsonElement?.findObject(key: String): JsonObject? {
    when (this) {
        is JsonObject -> {
            (this[key] as? JsonObject)?.let { return it }
            values.forEach { value -> value.findObject(key)?.let { return it } }
        }
        is JsonArray -> forEach { value -> value.findObject(key)?.let { return it } }
        else -> Unit
    }
    return null
}

private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { name ->
    JsonUtil.str(this, name)?.takeIf { it.isNotBlank() }
}

/**
 * Port of `musicdl/modules/sources/bodian.py::BodianMusicClient`.
 *
 * The signed `bd-api` playback endpoints need a `uid`/`token` pair, so without an account the
 * client resolves audio exactly like the Python one does for anonymous users: try the two open
 * Kuwo mobile gateways (`nmobi` / `mobi`) and keep the first URL that answers as audio.
 */
class BodianMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "BodianMusicClient"

    init {
        val headers = mapOf(
            "user-agent" to "Dart/3.3 (dart:io)",
            "plat" to "win",
            "channel" to "W1",
            "ver" to "1.1.5",
            "svrver" to "13",
        )
        searchHeaders = searchHeaders + headers
        downloadHeaders = downloadHeaders + headers
        parseHeaders = parseHeaders + headers
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://bd-api.kuwo.cn/api/search/music/list".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("pn", (offset / pageSize).toString())
                setQueryParameter("rn", pageSize.toString())
                setQueryParameter("keyword", keyword)
                setQueryParameter("correct", "1")
                setQueryParameter("uid", "-1")
                setQueryParameter("token", "")
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
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data", "resultList")).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.text("id", "musicId", "rid") ?: return null
        val resolved = resolveOpenGateway(songId) ?: return null
        val status = audioLinkTester.test(resolved.first, downloadHeaders)
        val ext = status.ext.orEmpty()
        if (!status.ok || !AudioLinkTester.isValidAudioExt(ext)) return null

        val seconds = JsonUtil.long(resolved.second, "duration")?.toDouble()?.toLong() ?: 0L
        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(item.text("name", "songName", "SONGNAME")),
            singers = PathUtils.legalizeString(item.text("artist", "singer")),
            album = PathUtils.legalizeString(item.text("album")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = item.text("albumPic", "pic"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    /** Port of `_parsewithjiakongapi` then `_parsewithtianbaoapi`. */
    private fun resolveOpenGateway(songId: String): Pair<String, JsonObject>? {
        val candidates = listOf(
            "https://nmobi.kuwo.cn/mobi.s".toHttpUrl().newBuilder().apply {
                setQueryParameter("f", "web")
                setQueryParameter("source", "kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk")
                setQueryParameter("type", "convert_url_with_sign")
                setQueryParameter("rid", songId)
                setQueryParameter("br", "2000kflac")
                setQueryParameter("user", (0..1000000).random().toString())
                setQueryParameter("loginUid", (0..1000000).random().toString())
            }.build().toString(),
            "https://mobi.kuwo.cn/mobi.s".toHttpUrl().newBuilder().apply {
                setQueryParameter("f", "web")
                setQueryParameter("user", (1000000..10000000).random().toString())
                setQueryParameter("source", "kwplayerhd_ar_4.3.0.8_tianbao_T1A_qirui.apk")
                setQueryParameter("type", "convert_url_with_sign")
                setQueryParameter("br", "2000kflac")
                setQueryParameter("rid", songId)
            }.build().toString(),
        )
        for (candidate in candidates) {
            val body = http.get(candidate, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: continue
            val data = runCatching { JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")) as? JsonObject }
                .getOrNull() ?: continue
            val url = JsonUtil.str(data, "url")?.takeIf { it.startsWith("http") } ?: continue
            return url to data
        }
        return null
    }
}

/**
 * Port of `musicdl/modules/sources/fivesing.py::FiveSingMusicClient`.
 * Search JSON from `search.5sing.kugou.com`, playback from `getSongUrl` (sq -> hq -> lq,
 * each with its `_backup` twin), then `newget` for album cover and dynamic words.
 */
class FiveSingMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "FiveSingMusicClient"

    init {
        val headers = mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36",
            "Referer" to "https://5sing.kugou.com/",
        )
        searchHeaders = searchHeaders + headers
        parseHeaders = parseHeaders + headers
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "http://search.5sing.kugou.com/home/json".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("keyword", keyword)
                setQueryParameter("sort", "1")
                setQueryParameter("page", (offset / pageSize + 1).toString())
                setQueryParameter("filter", "0")
                setQueryParameter("type", "0")
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
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("list")).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.text("songId") ?: return null
        val songType = item.text("typeEname") ?: return null
        val urlBody = http.get(
            "http://mobileapi.5sing.kugou.com/song/getSongUrl",
            query = mapOf("songid" to songId, "songtype" to songType),
            extraHeaders = parseHeaders,
        )?.use { it.textOrNull() } ?: return null
        val data = runCatching { JsonUtil.extractElement(JsonUtil.parseObject(urlBody), listOf("data")) as? JsonObject }
            .getOrNull() ?: return null

        for (quality in listOf("sq", "hq", "lq")) {
            val candidate = listOf("${quality}url", "${quality}url_backup")
                .firstNotNullOfOrNull { JsonUtil.str(data, it) }
                ?.takeIf { it.startsWith("http") } ?: continue
            val status = audioLinkTester.test(candidate, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue

            val detail = http.get(
                "http://mobileapi.5sing.kugou.com/song/newget",
                query = mapOf("songid" to songId, "songtype" to songType, "songfields" to "", "userfields" to ""),
                extraHeaders = parseHeaders,
            )?.use { it.textOrNull() }?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }
            val lyric = TextUtils.cleanLrc(JsonUtil.extract(detail, listOf("data", "dynamicWords")))
            val seconds = if (lyric != null) TextUtils.extractDurationSecondsFromLrc(lyric) else 0L

            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to item),
                songName = PathUtils.legalizeString(item.text("songName")),
                singers = PathUtils.legalizeString(item.text("singer")),
                album = PathUtils.legalizeString(JsonUtil.extract(detail, listOf("data", "albumName"))),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = JsonUtil.extract(detail, listOf("data", "user", "I")) ?: JsonUtil.extract(detail, listOf("data", "user", "I")),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                lyric = lyric,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }
}

/**
 * Port of `musicdl/modules/sources/soda.py::SodaMusicClient` (anonymous branch).
 *
 * `api.qishui.com` returns the search rows; without a Luna account the playable asset is the
 * audition URL published on the `music.douyin.com/qishui/share/track` page, whose payload sits
 * in `_ROUTER_DATA`. Lyrics come from that page's `sentences` list, same as the Python code.
 */
class SodaMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "SodaMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "LunaPC/3.4.0(388267242)",
            "Content-Type" to "application/json; charset=utf-8",
            "X-Luna-Background-Type" to "foreground",
            "X-Luna-Is-Background-Req" to "0",
            "X-Luna-Is-Local-User" to "1",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(20).coerceAtLeast(1)
        val endpoint = "https://api.qishui.com/luna/search/track".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("device_platform", "android")
                setQueryParameter("os", "android")
                setQueryParameter("aid", "386088")
                setQueryParameter("app_name", "luna")
                setQueryParameter("version_name", "19.8.0")
                setQueryParameter("device_type", "ABR-AL80")
                setQueryParameter("device_brand", "HUAWEI")
                setQueryParameter("language", "zh")
                setQueryParameter("package", "com.luna.music")
                setQueryParameter("q", keyword)
                setQueryParameter("cursor", offset.toString())
                setQueryParameter("count", pageSize.toString())
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
        val groups = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("result_groups")).objects()
        val items = groups.firstOrNull()?.let { JsonUtil.extractElement(it, listOf("data")).objects() }.orEmpty()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithofficialnonvipapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = JsonUtil.extract(item, listOf("entity", "track", "id")) ?: return null
        val page = http.get(
            "https://music.douyin.com/qishui/share/track",
            query = mapOf("track_id" to songId),
            extraHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            ),
        )?.use { it.textOrNull() } ?: return null

        val payload = Regex("""_ROUTER_DATA\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.getOrNull(1) ?: return null
        val routerData = runCatching { JsonUtil.parse(payload) }.getOrNull() ?: return null
        val audioOption = routerData.findObject("audioWithLyricsOption") ?: return null
        val audition = JsonUtil.str(audioOption, "url")
            ?.replace("\\u002F", "/")?.replace("%7C", "|")?.replace("%3D", "=")
            ?.takeIf { it.startsWith("http") } ?: return null
        val status = audioLinkTester.test(audition, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        val sentences = JsonUtil.extractElement(audioOption, listOf("lyrics", "sentences")).objects()
        val lyric = TextUtils.cleanLrc(
            sentences.joinToString("\n") { sentence ->
                val start = JsonUtil.long(sentence, "startMs") ?: 0L
                val words = JsonUtil.extractElement(sentence, listOf("words")).objects()
                    .joinToString("") { JsonUtil.str(it, "text").orEmpty() }
                "[%02d:%02d.%03d]%s".format(start / 60000, (start % 60000) / 1000, start % 1000, words)
            },
        )
        val track = routerData.findObject("trackInfo")
        val millis = track?.let { JsonUtil.long(it, "duration") } ?: 0L
        val seconds = millis / 1000
        val artists = JsonUtil.extractElement(track, listOf("artists")).objects()
            .mapNotNull { JsonUtil.str(it, "name") }.joinToString(", ")

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(JsonUtil.str(track, "name")),
            singers = PathUtils.legalizeString(artists.ifBlank { null }),
            album = PathUtils.legalizeString(JsonUtil.extract(track, listOf("album", "name"))),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = JsonUtil.str(track, "cover"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `musicdl/modules/thirdpartysites/htqyy.py::HTQYYMusicClient`.
 * One HTML search page, then the play page's `var fileHost` + `var mp3` script pair.
 */
class HTQYYMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "HTQYYMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            "referer" to "http://www.htqyy.com/",
        )
        downloadHeaders = downloadHeaders + mapOf(
            "referer" to "http://www.htqyy.com/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "http://www.htqyy.com/home/search".toHttpUrl().newBuilder()
            .setQueryParameter("wd", keyword)
            .build().toString(),
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val doc = Jsoup.parse(html, "http://www.htqyy.com")
        val rows = doc.select("ul#musicList li.musicItem")
        rows.forEachIndexed { index, li ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(li.attr("data-id"), li)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(@Suppress("UNUSED_PARAMETER") ignored: String, li: org.jsoup.nodes.Element): SongInfo? {
        val titleAnchor = li.selectFirst("span.title a") ?: return null
        val songId = li.selectFirst("input[name=checked]")?.attr("value")
            ?: titleAnchor.attr("sid").takeIf { it.isNotBlank() }
            ?: return null
        val playUrl = titleAnchor.absUrl("href").ifBlank { "http://www.htqyy.com${titleAnchor.attr("href")}" }
        val page = http.get(playUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return null

        val fileHost = Regex("""var\s+fileHost\s*=\s*["']([^"']*)["']""").find(page)?.groupValues?.getOrNull(1)
        val mp3 = Regex("""var\s+mp3\s*=\s*["']([^"']*)["']""").find(page)?.groupValues?.getOrNull(1)
        val downloadUrl = listOfNotNull(fileHost, mp3).joinToString("").takeIf { it.startsWith("http") } ?: return null
        val imgUrl = Regex("""var\s+imgUrl\s*=\s*["']([^"']*)["']""").find(page)?.groupValues?.getOrNull(1)

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(titleAnchor.text()),
            singers = PathUtils.legalizeString(li.selectFirst("span.artistName a")?.text()),
            album = PathUtils.legalizeString(li.selectFirst("span.albumName a")?.text()),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = imgUrl,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `musicdl/modules/thirdpartysites/yinyueku.py::YinyuekuMusicClient`.
 * `api.php` answers a JSON array for `types=search`, then `url` / `pic` / `lyric` per song.
 */
class YinyuekuMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "YinyuekuMusicClient"

    private val endpoint = "http://yinyueku.cn/api.php"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
            "Origin" to "http://yinyueku.cn",
            "Referer" to "http://yinyueku.cn/",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        // The page number travels in the form body, so it is encoded into the URL the base class carries.
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.toHttpUrl().newBuilder()
                .setQueryParameter("keyword", keyword)
                .setQueryParameter("count", pageSize.toString())
                .setQueryParameter("pages", (offset / pageSize + 1).toString())
                .build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val page = searchUrl.toHttpUrl().queryParameter("pages") ?: "1"
        val count = searchUrl.toHttpUrl().queryParameter("count") ?: searchSizePerPage.toString()
        val body = http.postForm(
            endpoint,
            mapOf("types" to "search", "count" to count, "pages" to page, "name" to keyword),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return
        val items = JsonUtil.parse(body).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.text("id") ?: return null
        val songSource = item.text("source") ?: return null
        val sign = item.text("sign") ?: return null
        val urlBody = http.postForm(
            endpoint,
            mapOf("types" to "url", "id" to songId, "source" to songSource, "sign" to sign),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return null
        val download = runCatching { JsonUtil.parseObject(urlBody) }.getOrNull() ?: return null
        val downloadUrl = JsonUtil.str(download, "url")?.takeIf { it.startsWith("http") } ?: return null
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        val cover = runCatching {
            http.postForm(
                endpoint,
                mapOf(
                    "types" to "pic", "id" to JsonUtil.str(item, "pic_id").orEmpty(),
                    "source" to songSource, "song" to songId, "sign" to sign,
                ),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }?.let { JsonUtil.str(JsonUtil.parseObject(it), "url") }
        }.getOrNull()

        var lyric: String? = null
        var seconds = 0L
        runCatching {
            val lyricBody = http.postForm(
                endpoint,
                mapOf(
                    "types" to "lyric", "id" to JsonUtil.str(item, "lyric_id").orEmpty(),
                    "source" to songSource, "song" to songId, "sign" to sign,
                ),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: return@runCatching
            lyric = TextUtils.cleanLrc(JsonUtil.extract(JsonUtil.parseObject(lyricBody), listOf("lyric")))
            seconds = TextUtils.extractDurationSecondsFromLrc(lyric)
        }

        val artists = JsonUtil.extractElement(item, listOf("artist")).objects()
            .mapNotNull { JsonUtil.str(it, "name") }
            .joinToString(", ")

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(item.text("name")),
            singers = PathUtils.legalizeString(artists.ifBlank { null }),
            album = PathUtils.legalizeString(item.text("album")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = cover,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/** Kept for symmetry with the Python helpers the clients above inline. */
internal fun encoder(value: String): String = URLEncoder.encode(value, "UTF-8")
