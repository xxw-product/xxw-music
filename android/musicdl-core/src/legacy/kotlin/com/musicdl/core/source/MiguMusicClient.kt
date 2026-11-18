package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.Crypto
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject

/**
 * Port of `musicdl/modules/sources/migu.py::MiguMusicClient`.
 *
 * Kept: the `search_all.do` search, the signature-1 magic-prefixed response decryption,
 * and the `listen-url/h5/v2.4` quality ladder. Dropped: the third-party fallbacks.
 */
class MiguMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source: String = "MiguMusicClient"

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Origin" to "https://h5.nf.migu.cn",
        "Referer" to "https://h5.nf.migu.cn/",
        "ua" to "Android_migu",
        "version" to "6.8.8",
        "channel" to "014021I",
        "subchannel" to "014021I",
    )

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage
        val urls = mutableListOf<String>()
        var count = 0
        while (searchSizePerSource > count) {
            val pageNo = count / pageSize + 1
            val query = buildString {
                append("text=").append(encode(keyword))
                append("&pageNo=").append(pageNo)
                append("&pageSize=").append(pageSize)
                append("&isCopyright=1&sort=1")
                append("&searchSwitch=").append(encode("""{"song":1,"album":0,"singer":0,"tagSong":1,"mvSong":0,"bestShow":1}"""))
            }
            urls += "https://c.musicapp.migu.cn/v1.0/content/search_all.do?$query"
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
        val bytes = response.use { runCatching { it.body?.bytes() }.getOrNull() } ?: return
        val decrypted = decryptResponse(bytes)
        val root = JsonUtil.parseObject(decrypted) ?: return
        val songs = JsonUtil.array(JsonUtil.obj(root, "songResultData"), "result") ?: return

        for (element in songs) {
            val item = element as? JsonObject ?: continue
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) break
            val parsed = runCatching { parseSong(item) }.getOrNull() ?: continue
            if (parsed.hasValidDownloadUrl) results += parsed
        }
    }

    /** Port of `_decryptresp`: magic-prefixed bodies are XOR-streamed, plain ones are JSON. */
    private fun decryptResponse(bytes: ByteArray): String {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        return if (Crypto.miguHasMagic(bytes)) {
            runCatching { String(Crypto.miguDecrypt(bytes), Charsets.UTF_8) }.getOrDefault(text)
        } else {
            text
        }
    }

    private fun parseSong(item: JsonObject): SongInfo {
        val contentId = JsonUtil.str(item, "contentId") ?: return SongInfo(source = source)
        val copyrightId = JsonUtil.str(item, "copyrightId") ?: return SongInfo(source = source)
        val name = PathUtils.legalizeString(JsonUtil.str(item, "name") ?: JsonUtil.str(item, "songName"))
        val singers = PathUtils.legalizeString(
            JsonUtil.array(item, "singers")
                ?.mapNotNull { (it as? JsonObject)?.let { s -> JsonUtil.str(s, "name") } }
                ?.joinToString(", "),
        )
        val album = PathUtils.legalizeString(JsonUtil.str(item, "album"))
        val cover = JsonUtil.str(item, "img3") ?: JsonUtil.str(item, "img2") ?: JsonUtil.str(item, "img1")

        val formats = buildList {
            JsonUtil.array(item, "rateFormats")?.let { addAll(it) }
            JsonUtil.array(item, "newRateFormats")?.let { addAll(it) }
            JsonUtil.array(item, "audioFormats")?.let { addAll(it) }
        }

        var best: SongInfo? = null
        for (format in formats) {
            val meta = format as? JsonObject ?: continue
            val formatType = JsonUtil.str(meta, "formatType") ?: continue
            val resourceType = JsonUtil.str(meta, "resourceType") ?: continue
            if (formatType == "Z3D") continue // encrypted audio, unplayable

            val url = resolveDownloadUrl(contentId, copyrightId, resourceType, formatType) ?: continue
            val status = audioLinkTester.test(url)
            if (!status.ok) continue
            val candidate = SongInfo(
                rawData = mapOf("search" to item.toString(), "quality" to formatType),
                source = source,
                songName = name,
                singers = singers,
                album = album,
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = cover,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                identifier = contentId,
            )
            if (best == null || candidate.largerThan(best)) best = candidate
            if (best.hasValidDownloadUrl) break
        }

        val result = best ?: return SongInfo(source = source, identifier = contentId)
        val durationSeconds = runCatching {
            (result.rawData["duration"] as? String)?.toDoubleOrNull()?.toLong() ?: 0L
        }.getOrDefault(0L)
        return result.copy(
            durationSeconds = durationSeconds,
            duration = SongInfo.formatDuration(durationSeconds),
        )
    }

    private fun resolveDownloadUrl(
        contentId: String,
        copyrightId: String,
        resourceType: String,
        formatType: String,
    ): String? {
        val query = buildString {
            append("contentId=").append(contentId)
            append("&copyrightId=").append(copyrightId)
            append("&resourceType=").append(resourceType)
            append("&netType=01")
            append("&toneFlag=").append(formatType)
            append("&scene=")
            append("&lowerQualityContentId=").append(contentId)
        }
        val response = http.get(
            "https://c.musicapp.migu.cn/strategy/listen-url/h5/v2.4?$query",
            extraHeaders = headers + mapOf("Content-Type" to "application/json;charset=UTF-8", "birth" to "h5page", "signature" to "1"),
            tag = "$source.url",
        ) ?: return null
        val bytes = response.use { runCatching { it.body?.bytes() }.getOrNull() } ?: return null
        val root = JsonUtil.parseObject(decryptResponse(bytes)) ?: return null
        return JsonUtil.extract(root, listOf("data", "url"))?.takeIf { it.startsWith("http") }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
