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
 * Port of `musicdl/modules/sources/qq.py::QQMusicClient`.
 *
 * Kept: the `musicu.fcg` search with the mobile `DoSearchForQQMusicMobile` module,
 * and the `vkeys` download/lyric endpoints the Python client uses as its primary path.
 * Dropped: the encrypted `musics.fcg` endpoint (needs a full qimei device handshake)
 * and the ~15 rotating mirror scrapers.
 */
class QQMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source: String = "QQMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Referer" to "https://y.qq.com/",
        "Origin" to "https://y.qq.com/",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage
        val urls = mutableListOf<String>()
        var count = 0
        while (searchSizePerSource > count) {
            val pageNum = count / pageSize + 1
            val payload = buildJsonObject {
                putJsonObject("comm") {
                    put("ct", "11")
                    put("cv", "0")
                    put("v", "0")
                    put("tmeAppID", "qqmusic")
                    put("format", "json")
                    put("inCharset", "utf-8")
                    put("outCharset", "utf-8")
                    put("uid", "3931641530")
                }
                putJsonObject("music.search.SearchCgiService.DoSearchForQQMusicMobile") {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicMobile")
                    putJsonObject("param") {
                        put("searchid", Crypto.qqRandomSearchId())
                        put("query", keyword)
                        put("search_type", 0)
                        put("num_per_page", pageSize)
                        put("page_num", pageNum)
                        put("highlight", 1)
                        put("grp", 1)
                    }
                }
            }
            urls += payload.toString()
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
        val response = http.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            searchUrl,
            extraHeaders = headers,
            tag = "$source.search",
        ) ?: return
        val body = response.use { it.textOrNull() } ?: return
        val root = JsonUtil.parseObject(body) ?: return
        val songs = JsonUtil.extractElement(
            root,
            listOf("music.search.SearchCgiService.DoSearchForQQMusicMobile", "data", "body", "song", "list"),
        ) as? kotlinx.serialization.json.JsonArray ?: return

        for (element in songs) {
            val item = element as? JsonObject ?: continue
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) break
            val parsed = runCatching { parseSong(item) }.getOrNull() ?: continue
            if (parsed.hasValidDownloadUrl) results += parsed
        }
    }

    private fun parseSong(item: JsonObject): SongInfo {
        val mid = JsonUtil.str(item, "mid") ?: JsonUtil.str(item, "songmid") ?: return SongInfo(source = source)
        val name = PathUtils.legalizeString(JsonUtil.str(item, "title") ?: JsonUtil.str(item, "name"))
        val singers = PathUtils.legalizeString(
            JsonUtil.array(item, "singer")
                ?.mapNotNull { (it as? JsonObject)?.let { s -> JsonUtil.str(s, "name") } }
                ?.joinToString(", "),
        )
        val albumObj = JsonUtil.obj(item, "album")
        val album = PathUtils.legalizeString(JsonUtil.str(albumObj, "title") ?: JsonUtil.str(albumObj, "name"))
        val albumMid = JsonUtil.str(albumObj, "mid") ?: JsonUtil.str(item, "albummid")
        val cover = if (albumMid != null) "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg" else null
        val durationSeconds = JsonUtil.long(item, "interval") ?: 0L

        var best: SongInfo? = null
        for (quality in QUALITIES) {
            val url = resolveDownloadUrl(mid, quality) ?: continue
            val status = audioLinkTester.test(url)
            if (!status.ok) continue
            val candidate = SongInfo(
                rawData = mapOf("search" to item.toString(), "quality" to quality),
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
                identifier = mid,
            )
            if (best == null || candidate.largerThan(best)) best = candidate
            if (best.hasValidDownloadUrl) break
        }

        val result = best ?: return SongInfo(source = source, identifier = mid)
        return result.copy(lyric = fetchLyric(mid))
    }

    /** Port of `_parsewithvkeysapi` download branch. */
    private fun resolveDownloadUrl(mid: String, quality: String): String? {
        val response = http.get(
            "https://api.vkeys.cn/music/tencent/song/link?mid=$mid&quality=$quality",
            extraHeaders = headers,
            tag = "$source.url",
        ) ?: return null
        val body = response.use { it.textOrNull() } ?: return null
        val root = JsonUtil.parseObject(body) ?: return null
        return JsonUtil.extract(root, listOf("data", "url"))?.takeIf { it.startsWith("http") }
    }

    private fun fetchLyric(mid: String): String? {
        val response = http.get(
            "https://api.vkeys.cn/v2/music/tencent/lyric?mid=$mid",
            extraHeaders = headers,
            tag = "$source.lyric",
        ) ?: return null
        val body = response.use { it.textOrNull() } ?: return null
        val root = JsonUtil.parseObject(body) ?: return null
        return JsonUtil.extract(root, listOf("data", "lrc"))?.takeIf { it.isNotBlank() }
    }

    companion object {
        /** Best quality first, mirroring `ThirdPartVKeysAPISongFileType` ordering. */
        val QUALITIES = listOf("master", "atmos", "flac", "320", "128")
    }
}
