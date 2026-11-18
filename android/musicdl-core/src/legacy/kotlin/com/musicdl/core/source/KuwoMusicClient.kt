package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * Port of `musicdl/modules/sources/kuwo.py::KuwoMusicClient`.
 *
 * Search uses Kuwo's JS-object response; playback resolves Kuwo RIDs through its mobile API.
 * Region-restricted tracks are not exposed as playable results.
 */
class KuwoMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source: String = "KuwoMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage
        val urls = mutableListOf<String>()
        var count = 0
        while (searchSizePerSource > count) {
            val pageNo = count / pageSize
            val query = buildString {
                append("vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1")
                append("&pn=").append(pageNo)
                append("&rn=").append(pageSize)
                append("&all=").append(encode(keyword))
            }
            urls += "https://www.kuwo.cn/search/searchMusicBykeyWord?$query"
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
        val root = parseSearch(body) ?: return
        val lists = JsonUtil.array(root, "abslist") ?: JsonUtil.array(root, "data") ?: return

        val parsed = runBlocking(Dispatchers.IO) {
            lists.filterIsInstance<JsonObject>().map { item ->
                async { parseSong(item) }
            }.awaitAll()
        }
        results += parsed.filter { it.hasValidDownloadUrl }.take(searchSizePerPage)
    }

    private fun parseSong(item: JsonObject): SongInfo {
        val rid = JsonUtil.str(item, "MUSICRID") ?: JsonUtil.str(item, "musicrid") ?: return SongInfo(source = source)
        val songId = rid.removePrefix("MUSIC_")
        val name = PathUtils.legalizeString(JsonUtil.str(item, "SONGNAME") ?: JsonUtil.str(item, "name"))
        val singers = PathUtils.legalizeString(JsonUtil.str(item, "ARTIST") ?: JsonUtil.str(item, "artist"))
        val album = PathUtils.legalizeString(JsonUtil.str(item, "ALBUM") ?: JsonUtil.str(item, "album"))
        val cover = JsonUtil.str(item, "web_albumpic_short") ?: JsonUtil.str(item, "albumpic")
        val durationSeconds = JsonUtil.long(item, "DURATION") ?: JsonUtil.long(item, "duration") ?: 0L

        var best: SongInfo? = null
        for (bitrate in BITRATES) {
            val url = resolveDownloadUrl(songId, bitrate) ?: continue
            val status = audioLinkTester.testFast(url, headers)
            if (!status.ok) continue
            val candidate = SongInfo(
                rawData = mapOf("search" to item.toString(), "bitrate" to bitrate.toString()),
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
                downloadHeaders = headers,
            )
            if (best == null || candidate.largerThan(best)) best = candidate
            if (best.hasValidDownloadUrl) break
        }

        return best ?: SongInfo(source = source, identifier = songId)
    }

    /** Kuwo IDs are numeric RIDs, not Kugou hashes. */
    private fun resolveDownloadUrl(songId: String, bitrate: String): String? {
        val url = "https://nmobi.kuwo.cn/mobi.s?f=web&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk&type=convert_url_with_sign&rid=$songId&br=$bitrate"
        val response = http.get(url, extraHeaders = headers, tag = "$source.url") ?: return null
        val body = response.use { it.textOrNull() } ?: return null
        val root = JsonUtil.parseObject(body) ?: return null
        if (JsonUtil.long(root, "code") == 407L) {
            logger.warning("Kuwo: $songId is unavailable in this region")
            return null
        }
        return JsonUtil.extract(root, listOf("data", "url"))?.takeIf { it.startsWith("http") }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        /** Convert Kuwo's single-quoted JS strings without evaluating script. */
        internal fun parseSearch(body: String): JsonObject? {
            if (body.trimStart().startsWith("{\"")) return JsonUtil.parseObject(body)
            val output = StringBuilder()
            var i = 0
            while (i < body.length) {
                val quote = body[i]
                if (quote != '\'' && quote != '"') { output.append(quote); i++; continue }
                i++
                val value = StringBuilder()
                var closed = false
                while (i < body.length) {
                    val c = body[i++]
                    if (c == quote) { closed = true; break }
                    if (c == '\\' && i < body.length) {
                        when (val next = body[i++]) {
                            '\'', '"', '\\' -> value.append(next)
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            else -> { value.append('\\'); value.append(next) }
                        }
                    } else value.append(c)
                }
                if (!closed) return null
                output.append(JsonPrimitive(value.toString()).toString())
            }
            return JsonUtil.parseObject(output.toString())
        }

        /** Playable tiers only; the Python client's encrypted `.mgg` ladder is intentionally omitted. */
        val BITRATES = listOf("320kmp3", "128kmp3")
    }
}
