package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.Crypto
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Port of `musicdl/modules/sources/netease.py::NeteaseMusicClient`.
 *
 * Kept: the official `cloudsearch/pc` search, the eapi `song/enhance/player/url/v1`
 * download resolution across the quality ladder, and the lyric endpoint.
 * Dropped: the ~30 third-party mirror parsers the Python version chains through -
 * they are unauthenticated scrapers whose hosts rotate; the official path is the
 * stable one and the code that matters.
 */
class NeteaseMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source: String = "NeteaseMusicClient"

    private val neteaseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
        "Referer" to "https://music.163.com/",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage
        val urls = mutableListOf<String>()
        var count = 0
        while (searchSizePerSource > count) {
            val offset = (count / pageSize) * pageSize
            urls += "https://music.163.com/api/cloudsearch/pc?s=${encode(keyword)}&type=1&limit=$pageSize&offset=$offset"
            count += pageSize
        }
        return urls
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val response = http.postForm(searchUrl, extraHeaders = neteaseHeaders, tag = "$source.search") ?: return
        val body = response.use { it.textOrNull() } ?: return
        val root = JsonUtil.parseObject(body) ?: return
        val songs = JsonUtil.array(JsonUtil.obj(root, "result"), "songs") ?: return

        for (element in songs) {
            val song = element as? JsonObject ?: continue
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) break
            val parsed = runCatching { parseSong(song) }.getOrNull() ?: continue
            if (parsed.hasValidDownloadUrl) results += parsed
        }
    }

    private fun parseSong(song: JsonObject): SongInfo {
        val songId = JsonUtil.str(song, "id") ?: return SongInfo(source = source)
        val name = PathUtils.legalizeString(JsonUtil.str(song, "name"))
        val singers = PathUtils.legalizeString(
            JsonUtil.array(song, "ar")
                ?.mapNotNull { (it as? JsonObject)?.let { artist -> JsonUtil.str(artist, "name") } }
                ?.joinToString(", "),
        )
        val albumObj = JsonUtil.obj(song, "al")
        val album = PathUtils.legalizeString(JsonUtil.str(albumObj, "name"))
        val cover = JsonUtil.str(albumObj, "picUrl")
        val durationSeconds = (JsonUtil.long(song, "dt") ?: 0L) / 1000

        var best: SongInfo? = null
        for (quality in QUALITIES) {
            val resolved = resolveDownloadUrl(songId, quality) ?: continue
            val status = audioLinkTester.test(resolved)
            if (!status.ok) continue
            val candidate = SongInfo(
                rawData = mapOf("search" to song.toString(), "quality" to quality),
                source = source,
                songName = name,
                singers = singers,
                album = album,
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = durationSeconds,
                duration = SongInfo.formatDuration(durationSeconds),
                coverUrl = cover,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                identifier = songId,
            )
            if (best == null || candidate.largerThan(best)) best = candidate
            if (best.hasValidDownloadUrl) break
        }

        val result = best ?: return SongInfo(source = source, identifier = songId)
        return result.copy(lyric = fetchLyric(songId))
    }

    /** Port of the eapi branch of `_parsewithofficialapiv1`. */
    private fun resolveDownloadUrl(songId: String, quality: String): String? {
        val payload = buildJsonObject {
            put("ids", "[$songId]")
            put("level", quality)
            put("encodeType", if (quality == "dolby") "mp4" else "flac")
            put("header", """{"os":"pc","appver":"","osver":"","deviceId":"pyncm!","requestId":"${(20000000..30000000).random()}"}""")
            if (quality == "sky") put("immerseType", "c51")
        }
        val encrypted = Crypto.neteaseEapiEncrypt(
            "/eapi/song/enhance/player/url/v1",
            payload.toString(),
        )
        val response = http.postForm(
            "https://interface3.music.163.com/eapi/song/enhance/player/url/v1",
            mapOf("params" to encrypted),
            extraHeaders = mapOf("Cookie" to "os=pc; appver=; osver=; deviceId=pyncm!"),
            tag = "$source.url",
        ) ?: return null
        val body = response.use { it.textOrNull() } ?: return null
        val root = JsonUtil.parseObject(body) ?: return null
        val url = JsonUtil.extract(root, listOf("data", 0, "url"))
        return url?.takeIf { it.startsWith("http") }
    }

    private fun fetchLyric(songId: String): String? {
        val response = http.postForm(
            "https://interface3.music.163.com/api/song/lyric",
            mapOf("id" to songId, "cp" to "false", "tv" to "0", "lv" to "0", "rv" to "0", "kv" to "0", "yv" to "0", "ytv" to "0", "yrv" to "0"),
            tag = "$source.lyric",
        ) ?: return null
        val body = response.use { it.textOrNull() } ?: return null
        val root = JsonUtil.parseObject(body) ?: return null
        return JsonUtil.extract(root, listOf("lrc", "lyric"))?.takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Port of `neteaseutils.MUSIC_QUALITIES`, best first. */
        val QUALITIES = listOf("jymaster", "jyeffect", "sky", "hires", "lossless", "dolby", "exhigh", "standard")
    }
}
