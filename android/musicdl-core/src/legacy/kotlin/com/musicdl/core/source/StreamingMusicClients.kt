package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

/**
 * Port of `musicdl/modules/sources/bilibili.py::BilibiliMusicClient`.
 *
 * Search is the public web-interface endpoint; playback resolves per-part DASH audio
 * from `x/player/playurl`, preferring FLAC -> Dolby -> plain audio by bandwidth.
 */
class BilibiliMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "BilibiliMusicClient"

    private val browserHeaders = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36 Edg/121.0.0.0",
        "Referer" to "https://www.bilibili.com/",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
    )

    init {
        searchHeaders = searchHeaders + browserHeaders
        downloadHeaders = downloadHeaders + browserHeaders
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://api.bilibili.com/x/web-interface/search/type".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("__refresh__", "true")
                setQueryParameter("page", (offset / pageSize + 1).toString())
                setQueryParameter("page_size", pageSize.toString())
                setQueryParameter("platform", "pc")
                setQueryParameter("highlight", "1")
                setQueryParameter("keyword", keyword)
                setQueryParameter("search_type", "video")
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
        val data = JsonUtil.parseObject(body)
        val items = JsonUtil.extractElement(data, listOf("data", "result")).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(
                source,
                "$source._search >>> ${index + 1}/${items.size} results",
                index + 1,
                items.size,
            )
            runCatching { results += parseVideo(item) }
                .onFailure { logger.error("$source._parsewithofficialapiv1 >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    /** Port of `_parsewithofficialapiv1`: one [SongInfo] per video part (episode). */
    private fun parseVideo(searchResult: JsonObject): List<SongInfo> {
        val bvid = JsonUtil.str(searchResult, "bvid") ?: return emptyList()
        val viewBody = http.get(
            "https://api.bilibili.com/x/web-interface/view",
            query = mapOf("bvid" to bvid),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return emptyList()
        val view = JsonUtil.parseObject(viewBody)
        val rootTitle = JsonUtil.extract(view, listOf("data", "title"))
        val pages = JsonUtil.extractElement(view, listOf("data", "pages")).objects()
        val author = JsonUtil.str(searchResult, "author")
        val pic = JsonUtil.str(searchResult, "pic")?.let { if (it.startsWith("http")) it else "https:$it" }

        val parsed = mutableListOf<SongInfo>()
        for (page in pages) {
            val cid = JsonUtil.long(page, "cid") ?: continue
            val part = JsonUtil.str(page, "part") ?: continue
            val playBody = http.get(
                "https://api.bilibili.com/x/player/playurl",
                query = mapOf("fnval" to "16", "bvid" to bvid, "cid" to cid.toString()),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: continue
            val play = JsonUtil.parseObject(playBody)
            val audios = AUDIO_PATHS
                .asSequence()
                .map { JsonUtil.extractElement(play, it).objects() }
                .firstOrNull { it.isNotEmpty() }
                ?: continue
            // Python sorts by (bandwidth, filesize) descending and takes the first usable URL.
            val best = audios.maxWithOrNull(
                compareBy<JsonObject>({ JsonUtil.long(it, "bandwidth") ?: 0L }, { JsonUtil.long(it, "filesize") ?: 0L }),
            ) ?: continue
            val candidate = listOf("baseUrl", "base_url", "backupUrl", "backup_url")
                .firstNotNullOfOrNull { JsonUtil.str(best, it) }
                ?.takeIf { it.startsWith("http") }
                ?: continue

            val status = audioLinkTester.test(candidate, downloadHeaders)
            val ext = when (val raw = status.ext.orEmpty()) {
                "m4s", "mp4" -> "m4a"
                else -> raw
            }
            if (!status.ok || !AudioLinkTester.isValidAudioExt(ext)) continue

            val dash = JsonUtil.extractElement(play, listOf("data", "dash")) as? JsonObject
            val seconds = dash?.let { JsonUtil.long(it, "duration") } ?: 0L
            parsed += SongInfo(
                source = source,
                identifier = cid.toString(),
                rawData = mapOf("search" to searchResult, "download" to play),
                songName = PathUtils.legalizeString(if (part == rootTitle) part else "$rootTitle - $part"),
                singers = PathUtils.legalizeString(author),
                album = PathUtils.legalizeString(bvid),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = pic,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
            if (strictLimitSearchSizePerPage && parsed.size >= searchSizePerPage) break
        }
        return parsed
    }

    private companion object {
        val AUDIO_PATHS = listOf(
            listOf("data", "dash", "flac", "audio"),
            listOf("data", "dash", "dolby", "audio"),
            listOf("data", "dash", "audio"),
        )
    }
}

/**
 * Port of `musicdl/modules/sources/kugou.py::KugouMusicClient`.
 *
 * Kugou's own song URL API needs a signed `mid`/`userid` pair, so the Python client resolves
 * playback through the same public mirror APIs it uses (haitangw, chksz, lzmhhh, cocodownloader).
 * The mirror ladder is tried from lossless downwards; the first URL that survives the audio
 * probe becomes the song's download URL.
 */
class KugouMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "KugouMusicClient"

    init {
        val headers = mapOf("User-Agent" to BROWSER_UA)
        searchHeaders = searchHeaders + headers
        downloadHeaders = downloadHeaders + headers
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://songsearch.kugou.com/song_search_v2".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("format", "json")
                setQueryParameter("keyword", keyword)
                setQueryParameter("platform", "WebFilter")
                setQueryParameter("page", (offset / pageSize + 1).toString())
                setQueryParameter("pagesize", pageSize.toString())
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
        val data = JsonUtil.parseObject(body)
        val items = JsonUtil.extractElement(data, listOf("data", "lists")).objects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(
                source,
                "$source._search >>> ${index + 1}/${items.size} results",
                index + 1,
                items.size,
            )
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._parsewiththirdpartapis >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val hash = firstNonBlank(JsonUtil.str(item, "hash"), JsonUtil.str(item, "FileHash")) ?: return null
        val downloadUrl = resolveDownloadUrl(hash) ?: return null
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        val ext = status.ext.orEmpty()
        if (!status.ok || !AudioLinkTester.isValidAudioExt(ext)) return null

        // `duration` is seconds; `timelen` is milliseconds (the audiobook-style rows use it).
        val seconds = JsonUtil.long(item, "duration")
            ?: ((JsonUtil.long(item, "Duration") ?: 0L))
                .takeIf { it > 0L }
            ?: ((JsonUtil.long(item, "timelen") ?: 0L) / 1000L)

        val songName = firstNonBlank(
            JsonUtil.str(item, "songname"),
            JsonUtil.str(item, "SongName"),
            JsonUtil.str(item, "songname_original"),
            JsonUtil.str(item, "OriSongName"),
            JsonUtil.str(item, "filename"),
            JsonUtil.str(item, "FileName"),
        )
        val singers = firstNonBlank(
            JsonUtil.str(item, "singername"),
            JsonUtil.str(item, "SingerName"),
            JsonUtil.extractElement(item, listOf("singerinfo")).objects()
                .mapNotNull { JsonUtil.str(it, "name") }
                .joinToString(", ")
                .ifBlank { null },
            JsonUtil.extractElement(item, listOf("Singers")).objects()
                .mapNotNull { JsonUtil.str(it, "name") }
                .joinToString(", ")
                .ifBlank { null },
        )
        val album = firstNonBlank(
            JsonUtil.str(item, "album_name"),
            JsonUtil.str(item, "AlbumName"),
            JsonUtil.extract(item, listOf("albuminfo", "name")),
        )
        val cover = firstNonBlank(
            JsonUtil.extract(item, listOf("trans_param", "union_cover")),
            JsonUtil.str(item, "cover_url"),
            JsonUtil.str(item, "Image"),
        )?.replace("{size}", "300")

        return SongInfo(
            source = source,
            identifier = hash,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(songName),
            singers = PathUtils.legalizeString(singers),
            album = PathUtils.legalizeString(album),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = cover,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    /** Port of the mirror ladder in `_parsewiththirdpartapis` (baka.plus is a redirect chain, skipped). */
    private fun resolveDownloadUrl(hash: String): String? {
        for (level in QUALITY_LEVELS) {
            urlFrom(getJson("https://musicapi.haitangw.net/kgqq/kg.php?type=json&id=$hash&level=$level"), "data", "url")
                ?.let { return it }
            urlFrom(getJson("https://music.haitangw.cc/kgqq/kg.php?type=json&id=$hash&level=$level"), "data", "url")
                ?.let { return it }
        }
        urlFrom(getJson("https://api.chksz.com/api/kugou_music?id=$hash&size=master"), "url")?.let { return it }
        urlFrom(getJson("https://cocodownloader.markqq.com/api/url?id=$hash&provider=kugou"), "url")?.let { return it }
        return urlFrom(postLzmhhh(hash), "data")
    }

    private fun postLzmhhh(hash: String): JsonObject? = runCatching {
        val headers = mapOf(
            "User-Agent" to BROWSER_UA,
            "Referer" to "https://music.lzmhhh.com/",
            "Origin" to "https://music.lzmhhh.com",
        )
        http.postForm(
            "https://music.lzmhhh.com/api/music/url",
            mapOf("id" to hash, "type" to "kg"),
            extraHeaders = headers,
        )?.use { it.textOrNull() }?.let { JsonUtil.parseObject(it) }
    }.getOrNull()

    private fun getJson(url: String): JsonObject? = runCatching {
        http.get(url, extraHeaders = searchHeaders)?.use { it.textOrNull() }?.let { JsonUtil.parseObject(it) }
    }.getOrNull()

    private fun urlFrom(root: JsonObject?, vararg path: String): String? =
        root?.let { JsonUtil.extract(it, path.toList()) }?.takeIf { it.startsWith("http") }

    private companion object {
        val QUALITY_LEVELS = listOf("hires", "lossless", "exhigh")
    }
}
