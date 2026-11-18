package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.Crypto
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject

/**
 * Port of `musicdl/modules/sources/qianqian.py::QianqianMusicClient`.
 *
 * Kept: the signed `v1/search` search (`_addsignandtstoparams`) and the
 * `v1/song/tracklink` download resolution across the rate ladder, plus lyrics.
 */
class QianqianMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source: String = "QianqianMusicClient"

    private val headers = mapOf(
        "accept" to "*/*",
        "accept-language" to "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
        "referer" to "https://music.91q.com/player",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
        "from" to "web",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage
        val urls = mutableListOf<String>()
        var count = 0
        while (searchSizePerSource > count) {
            val pageNo = count / pageSize + 1
            val params = mutableMapOf(
                "word" to keyword,
                "type" to "1",
                "pageNo" to pageNo.toString(),
                "pageSize" to pageSize.toString(),
                "appid" to APPID,
            )
            val signed = Crypto.qianqianSign(params)
            urls += "https://music.91q.com/v1/search?" + signed.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
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
        val response = http.get(searchUrl, extraHeaders = headers, tag = "$source.search") ?: return
        val body = response.use { it.textOrNull() } ?: return
        val root = JsonUtil.parseObject(body) ?: return
        val tracks = JsonUtil.array(JsonUtil.obj(root, "data"), "typeTrack") ?: return

        for (element in tracks) {
            val item = element as? JsonObject ?: continue
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) break
            val parsed = runCatching { parseSong(item) }.getOrNull() ?: continue
            if (parsed.hasValidDownloadUrl) results += parsed
        }
    }

    private fun parseSong(item: JsonObject): SongInfo {
        val songId = JsonUtil.str(item, "TSID") ?: return SongInfo(source = source)
        val name = PathUtils.legalizeString(JsonUtil.str(item, "title"))
        val singers = PathUtils.legalizeString(
            JsonUtil.array(item, "artist")
                ?.mapNotNull { (it as? JsonObject)?.let { a -> JsonUtil.str(a, "name") } }
                ?.joinToString(", "),
        )
        val album = PathUtils.legalizeString(JsonUtil.str(item, "albumTitle"))
        val cover = JsonUtil.str(item, "pic")

        var best: SongInfo? = null
        for (rate in RATES) {
            val params = mutableMapOf("TSID" to songId, "appid" to APPID, "rate" to rate)
            val signed = Crypto.qianqianSign(params)
            val query = signed.entries.joinToString("&") { "${it.key}=${encode(it.value)}" }
            val response = http.get(
                "https://music.91q.com/v1/song/tracklink?$query",
                extraHeaders = headers,
                tag = "$source.url",
            ) ?: continue
            val body = response.use { it.textOrNull() } ?: continue
            val root = JsonUtil.parseObject(body) ?: continue
            val url = JsonUtil.extract(root, listOf("data", "path"))
                ?: JsonUtil.extract(root, listOf("data", "trail_audio_info", "path"))
            if (url == null || !url.startsWith("http")) continue

            val status = audioLinkTester.test(url)
            if (!status.ok) continue
            val durationSeconds = JsonUtil.long(JsonUtil.obj(root, "data"), "duration") ?: 0L
            val candidate = SongInfo(
                rawData = mapOf("search" to item.toString(), "rate" to rate),
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
        return result.copy(lyric = fetchLyric(item))
    }

    private fun fetchLyric(item: JsonObject): String? {
        val lyricUrl = JsonUtil.str(item, "lyric") ?: return null
        val response = http.get(lyricUrl, extraHeaders = headers, tag = "$source.lyric") ?: return null
        return response.use { it.textOrNull() }?.takeIf { it.isNotBlank() }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        const val APPID = "16073360"

        /** Port of `QianqianMusicClient.MUSIC_QUALITIES`, best first. */
        val RATES = listOf("3000", "320", "128", "64")
    }
}
