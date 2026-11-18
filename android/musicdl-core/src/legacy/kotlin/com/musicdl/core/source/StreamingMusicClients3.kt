package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** JS `encodeURIComponent` on a UTF-8 byte stream. */
private fun jsQuote(value: String): String {
    val safe = "-_.!~*'()"
    return buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (code in 0x30..0x39 || code in 0x41..0x5A || code in 0x61..0x7A || safe.contains(ch)) {
                append(ch)
            } else {
                append('%').append("%02X".format(code))
            }
        }
    }
}

private fun JsonObject.firstText(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { JsonUtil.str(this, it)?.takeIf(String::isNotBlank) }

/**
 * Port of `musicdl/modules/sources/joox.py::JooxMusicClient`.
 *
 * Search and track metadata come from `cache.api.joox.com`; the same 8-character MD5 fragment the
 * Python client computes for the `gdstudio` mirrors is reproduced for the fallback URL resolver.
 */
class JooxMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "JooxMusicClient"

    private val salt = "Jo0x@t3Nc3nT"
    private val country = config.defaultSearchCookies["country"] ?: "hk"
    private val lang = "zh_TW"
    private val uid = config.defaultSearchCookies["wmid"] ?: "142420656"
    private val usk = config.defaultSearchCookies["session_key"] ?: "2a5d97d05dc8fe238150184eaf3519ad"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://www.joox.com",
            "Referer" to "https://www.joox.com/",
        )
    }

    /** Port of `makesecret`. */
    private fun makeSecret(params: Map<String, String>): String =
        md5Hex(salt + params.entries.joinToString("&") { "${it.key}=${jsQuote(it.value)}" })

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://cache.api.joox.com/openjoox/v2/search_type".toHttpUrl().newBuilder()
            .setQueryParameter("country", country)
            .setQueryParameter("lang", lang)
            .setQueryParameter("key", keyword)
            .setQueryParameter("type", "0")
            .build().toString(),
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val tracks = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("tracks")).objects()
        // Joox nests each hit in a single-element array.
        val items = tracks.map { element -> (element as? JsonArray)?.firstOrNull() as? JsonObject ?: element }
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.firstText("id", "songid") ?: return null
        val meta = trackMeta(songId)
        val candidates = buildList {
            JsonUtil.extractElement(meta, listOf("play_url_list")).objects()
                .mapNotNullTo(this) { JsonUtil.str(it, "url") }
            JsonUtil.str(meta, "refrain_url")?.let(::add)
            if (isEmpty()) gdstudioUrl(songId, "music.gdstudio.xyz")?.let(::add)
            if (isEmpty()) gdstudioUrl(songId, "music.gdstudio.org")?.let(::add)
        }

        for (candidate in candidates) {
            if (!candidate.startsWith("http")) continue
            val status = audioLinkTester.test(candidate, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue
            val seconds = (JsonUtil.long(item, "play_duration")
                ?: JsonUtil.long(meta, "play_duration") ?: 0L)
            val artists = JsonUtil.extractElement(meta, listOf("artist_list")).objects()
                .mapNotNull { JsonUtil.str(it, "name") }.joinToString(", ")
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to item),
                songName = PathUtils.legalizeString(JsonUtil.str(meta, "name")),
                singers = PathUtils.legalizeString(artists.ifBlank { null }),
                album = PathUtils.legalizeString(JsonUtil.str(meta, "album_name")),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = JsonUtil.extract(meta, listOf("images", "0", "url")),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                lyric = TextUtils.cleanLrc(
                    TextUtils.decodeBase64(JsonUtil.str(meta, "lrc_content"))?.let { String(it) },
                ),
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }

    /** Port of `_getsongmetainfo`. */
    private fun trackMeta(songId: String): JsonObject {
        val params = linkedMapOf(
            "country" to country, "lang" to lang, "lyric" to "1", "fs" to "1", "im" to "0",
            "uid" to uid, "usk" to usk, "id" to songId,
        )
        val secret = makeSecret(params)
        val query = params - "id" + ("secret" to secret)
        val url = "https://cache.api.joox.com/openjoox2/v1/track/${songId.replace("/", "_")}"
        val body = http.get(url, query = query, extraHeaders = searchHeaders)?.use { it.textOrNull() }
        return body?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() } ?: JsonObject(emptyMap())
    }

    /**
     * Port of `_parsewithgdstudioxyzapi` / `_parsewithgdstudioorgapi`: the sign is the tail of an
     * MD5 over `time|host|version|encodedId`, exactly what the Python MD5 hand-roll produces.
     */
    private fun gdstudioUrl(songId: String, host: String): String? {
        val serverTime = http.get("https://$host/time", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
            ?: (System.currentTimeMillis() / 1000).toString()
        val version = "2026.08.01".split('.').joinToString("") { if (it.length == 1) "0$it" else it }
        val signSource = "${serverTime.take(9)}|$host|$version|${jsQuote(songId)}"
        val sign = md5Hex(signSource).takeLast(8).uppercase()
        val body = http.postForm(
            "https://$host/api.php",
            mapOf("types" to "url", "id" to songId, "source" to "joox", "br" to "999", "s" to sign),
            extraHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
                "Origin" to "https://$host",
                "Referer" to "https://$host/",
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )?.use { it.textOrNull() }
        return body?.let { runCatching { JsonUtil.str(JsonUtil.parseObject(it), "url") }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
    }
}

/**
 * Port of `musicdl/modules/sources/moov.py::MOOVMusicClient`.
 *
 * MOOV only serves HLS: `checkout` returns a playlist URL per quality, and the segment layout is
 * validated by the shared audio probe. Playback normally needs a `MOOVUUID` device cookie, so the
 * client reports nothing instead of a broken `.m3u8` when the probe rejects the URL.
 */
class MOOVMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "MOOVMusicClient"

    private val deviceId = config.defaultSearchCookies["MOOVUUID"]
        ?: md5Hex("musicdl-android-${System.currentTimeMillis()}").uppercase()

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Origin" to "https://moov.hk",
            "Referer" to "https://moov.hk/",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://search-hk.moov-music.com/search/api/search/search".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("value", keyword)
                setQueryParameter("type", "product")
                setQueryParameter("from", offset.toString())
                setQueryParameter("count", pageSize.toString())
                setQueryParameter("_", System.currentTimeMillis().toString())
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
        val root = JsonUtil.parseObject(body)
        val items = JsonUtil.extractElement(root, listOf("dataObject", "primarySearch")).objects() +
            JsonUtil.extractElement(root, listOf("dataObject", "secondarySearch")).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.firstText("productId") ?: return null
        val detail = http.get(
            "https://mtg.now.com/moov/api/content/getProductDetail",
            query = mapOf("productId" to songId),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() }?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("dataObject")) }.getOrNull() }
        val meta = item
        val qualities = (JsonUtil.str(meta, "qualities") ?: "HD").split(',').map(String::trim).reversed()

        for (quality in qualities) {
            val body = http.get(
                "https://mtg.now.com/moov/api/content/checkout",
                query = mapOf(
                    "deviceid" to deviceId, "devicetype" to "web", "cat" to "playlist", "refid" to "",
                    "reftype" to "", "pid" to songId, "preview" to "F", "connect" to "web",
                    "streamtype" to "stdHlsSgl", "quality" to quality, "application" to "moovnext",
                    "clientver" to "2.0.6", "carrier" to "csl",
                ),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: continue
            val checkout = runCatching { JsonUtil.parseObject(body) }.getOrNull() ?: continue
            val playUrl = JsonUtil.extract(checkout, listOf("result", "dataObject", "playUrl"))
                ?: JsonUtil.extract(checkout, listOf("result", "dataObject", "playUrlValidate"))
            if (playUrl.isNullOrBlank() || !playUrl.startsWith("http")) continue

            val status = audioLinkTester.test(playUrl, downloadHeaders)
            if (!status.ok) continue
            val format = (JsonUtil.extract(checkout, listOf("result", "dataObject", "format")) ?: "aac").lowercase()
            val seconds = JsonUtil.long(meta, "productLength") ?: 0L
            val artists = JsonUtil.extractElement(meta, listOf("artists")).objects()
                .mapNotNull { JsonUtil.str(it, "name") }.joinToString(", ")

            val lyric = http.get(
                "https://mtg.now.com/moov/api/lyric/getLyric",
                query = mapOf("pid" to songId, "_" to System.currentTimeMillis().toString()),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
                ?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("dataObject", "lyric")) }.getOrNull() }
                ?.let(TextUtils::cleanLrc)

            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to item, "detail" to (detail ?: JsonObject(emptyMap()))),
                songName = PathUtils.legalizeString(JsonUtil.str(meta, "productTitle")),
                singers = PathUtils.legalizeString(artists.ifBlank { null }),
                album = PathUtils.legalizeString(JsonUtil.str(meta, "albumTitle")),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = format,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = JsonUtil.str(meta, "image")
                    ?: JsonUtil.str(meta, "albumCover")
                    ?: JsonUtil.str(meta, "thumbnail"),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                lyric = lyric,
                downloadHeaders = downloadHeaders,
            )
        }
        return null
    }
}
