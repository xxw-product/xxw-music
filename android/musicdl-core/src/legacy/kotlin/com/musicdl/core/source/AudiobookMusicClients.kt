package com.musicdl.core.source

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** Port of `musicdl/modules/audiobooks/itunes.py::ITunesMusicClient`. */
class ITunesMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedSearchTypes: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("podcast"))

    override val source = "ITunesMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        if ("podcast" !in allowedSearchTypes) return emptyList()
        val base = "https://itunes.apple.com/search".toHttpUrlOrNull() ?: return emptyList()
        val url = base.newBuilder().apply {
            setQueryParameter("term", keyword)
            setQueryParameter("limit", maxOf(searchSizePerSource, 200).toString())
            setQueryParameter("media", "podcast")
            rule.forEach { (k, v) -> setQueryParameter(k, v) }
        }.build()
        return listOf(url.toString())
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val payload = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val collections = payload.arr("results") ?: return
        for (item in collections) {
            if (results.size >= searchSizePerPage && strictLimitSearchSizePerPage) return
            val album = item as? JsonObject ?: continue
            val albumId = JsonUtil.stringify(album["collectionId"]) ?: continue
            val feedUrl = JsonUtil.str(album, "feedUrl") ?: continue
            val feed = http.get(feedUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: continue
            results += parsePodcastFeed(album, albumId, feed)
        }
    }

    private fun parsePodcastFeed(album: JsonObject, albumId: String, feed: String): SongInfo {
        val channel = feed.substringBefore("<item>").ifEmpty { feed }
        val albumTitle = tagText(channel, "title") ?: JsonUtil.str(album, "collectionName")
        val albumAuthor = tagText(channel, "itunes:author") ?: JsonUtil.str(album, "artistName")
        val albumCover = tagAttr(channel, "itunes:image", "href")

        val episodes = mutableListOf<SongInfo>()
        for ((index, block) in ITEM_BLOCK.findAll(feed).withIndex()) {
            val item = block.groupValues[1]
            val enclosure = tagAttr(item, "enclosure", "url") ?: continue
            val status = audioLinkTester.test(enclosure, downloadHeaders)
            if (!status.ok || status.ext !in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) continue
            val seconds = TextUtils.parseDurationText(tagText(item, "itunes:duration")).let {
                if (it > 0) it else TextUtils.parseDurationText(tagText(item, "itunes:duration"))
            }
            val title = tagText(item, "title") ?: "Episode ${index + 1}"
            episodes += SongInfo(
                source = source,
                identifier = "${albumId}ep${index + 1}",
                rawData = mapOf("search" to album, "download" to item),
                songName = com.musicdl.core.model.PathUtils.legalizeString(title),
                singers = com.musicdl.core.model.PathUtils.legalizeString(
                    tagText(item, "dc:creator") ?: tagText(item, "itunes:author") ?: albumAuthor,
                ),
                album = com.musicdl.core.model.PathUtils.legalizeString(albumTitle),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                coverUrl = tagAttr(item, "itunes:image", "href") ?: albumCover,
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
        }

        val totalBytes = episodes.sumOf { it.fileSizeBytes }
        val totalSeconds = episodes.sumOf { it.durationSeconds }
        return SongInfo(
            source = source,
            identifier = albumId,
            rawData = mapOf("search" to album, "episodes" to episodes.size),
            songName = com.musicdl.core.model.PathUtils.legalizeString(albumTitle),
            singers = com.musicdl.core.model.PathUtils.legalizeString(albumAuthor),
            album = "${episodes.size} Episodes",
            fileSizeBytes = totalBytes,
            fileSize = com.musicdl.core.model.PathUtils.byteToMb(totalBytes),
            durationSeconds = totalSeconds,
            duration = SongInfo.formatDuration(totalSeconds),
            coverUrl = albumCover,
            episodes = episodes,
        )
    }

    private companion object {
        val ITEM_BLOCK = Regex("""<item>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

        fun tagText(xml: String, name: String): String? =
            Regex("""<$name(?:\s[^>]*)?>(.*?)</$name>""", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim()
                ?.takeIf { it.isNotEmpty() }

        fun tagAttr(xml: String, name: String, attribute: String): String? =
            Regex("""<$name\b[^>]*\b$attribute="([^"]*)"""")
                .find(xml)?.groupValues?.get(1)?.replace("\\/", "/")?.takeIf { it.isNotEmpty() }
    }
}

/** Port of `musicdl/modules/audiobooks/ximalaya.py::XimalayaMusicClient`. */
class XimalayaMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedSearchTypes: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("album", "track"))

    override val source = "XimalayaMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://www.ximalaya.com/revision/search".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        for (type in ALLOWED_SEARCH_TYPES) {
            if (type !in allowedSearchTypes) continue
            var count = 0
            while (count < searchSizePerSource) {
                urls += base.newBuilder().apply {
                    setQueryParameter("core", type)
                    setQueryParameter("kw", keyword)
                    setQueryParameter("page", (count / searchSizePerPage + 1).toString())
                    setQueryParameter("rows", searchSizePerPage.toString())
                    setQueryParameter("spellchecker", "true")
                    setQueryParameter("condition", "relation")
                    setQueryParameter("device", "web")
                    rule.forEach { (k, v) -> setQueryParameter(k, v) }
                }.build().toString()
                count += searchSizePerPage
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val type = searchUrl.toHttpUrlOrNull()?.queryParameter("core") ?: "track"
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val response = JsonUtil.extractElement(body, listOf("data", "result", "response")) as? JsonObject ?: return
        when (type) {
            "track" -> parseTracks(response, results)
            "album" -> parseAlbums(response, results)
        }
    }

    private fun parseTracks(response: JsonObject, results: MutableList<SongInfo>) {
        for (element in response.arr("docs") ?: return) {
            val doc = element as? JsonObject ?: continue
            val id = JsonUtil.stringify(doc["id"] ?: doc["trackId"]) ?: continue
            val parsers = buildList {
                if (searchCookies.isNotEmpty()) add(::parseWithOfficialApiV1)
                add(::parseWithTelecomApi)
                add(::parseWithMobileJson)
            }
            var best = SongInfo(source = source, identifier = id)
            for (parser in parsers) {
                val candidate = runCatching { parser(doc, id) }.getOrNull()
                if (candidate != null && candidate.hasValidDownloadUrl) best = candidate
                if (best.hasValidDownloadUrl && best.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
            }
            if (!best.hasValidDownloadUrl) continue
            results += best
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseAlbums(response: JsonObject, results: MutableList<SongInfo>) {
        for (element in response.arr("docs") ?: return) {
            val doc = element as? JsonObject ?: continue
            val albumId = JsonUtil.stringify(doc["id"]) ?: continue
            val totalTracks = JsonUtil.int(doc, "tracks") ?: 0
            val pageSize = 50
            val pages = maxOf(1, (totalTracks + pageSize - 1) / pageSize)
            val tracks = mutableListOf<JsonObject>()
            val seen = mutableSetOf<String>()
            var page = 1
            while (page <= pages) {
                val pageTracks = fetchAlbumPage(albumId, page, pageSize) ?: break
                if (pageTracks.isEmpty()) break
                for (track in pageTracks) {
                    val trackId = JsonUtil.stringify(track["trackId"] ?: track["id"]) ?: continue
                    if (seen.add(trackId)) tracks += track
                }
                page++
            }
            val episodes = mutableListOf<SongInfo>()
            for (track in tracks) {
                val trackId = JsonUtil.stringify(track["trackId"] ?: track["id"]) ?: continue
                val parsers = buildList {
                    if (searchCookies.isNotEmpty()) add(::parseWithOfficialApiV1)
                    add(::parseWithTelecomApi)
                    add(::parseWithMobileJson)
                }
                var episode: SongInfo? = null
                for (parser in parsers) {
                    val candidate = runCatching { parser(track, trackId) }.getOrNull()
                    if (candidate != null && candidate.hasValidDownloadUrl) episode = candidate
                    if (episode != null && episode.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
                }
                if (episode != null && episode.hasValidDownloadUrl) episodes += episode
            }
            if (episodes.isEmpty()) continue
            val totalSeconds = episodes.sumOf { it.durationSeconds }
            val totalBytes = episodes.sumOf { it.fileSizeBytes }
            results += SongInfo(
                source = source,
                identifier = albumId,
                rawData = mapOf("search" to doc, "episodes" to episodes.size),
                songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(doc, "title")),
                singers = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(doc, "nickname")),
                album = "${episodes.size} Episodes",
                durationSeconds = totalSeconds,
                duration = SongInfo.formatDuration(totalSeconds),
                fileSizeBytes = totalBytes,
                fileSize = com.musicdl.core.model.PathUtils.byteToMb(totalBytes),
                coverUrl = JsonUtil.str(doc, "cover_path"),
                episodes = episodes,
            )
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun fetchAlbumPage(albumId: String, page: Int, pageSize: Int): List<JsonObject>? {
        val mobile = http.get(
            "https://mobile.ximalaya.com/mobile/v1/album/track/?albumId=$albumId&pageSize=$pageSize&pageId=$page",
            extraHeaders = mapOf("Referer" to "https://www.ximalaya.com/album/$albumId") + searchHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) }
        if (mobile != null && JsonUtil.long(mobile, "ret") == 0L) {
            val list = (mobile.obj("data")?.arr("list"))?.filterIsInstance<JsonObject>()
            if (!list.isNullOrEmpty()) return list
        }
        val revision = http.get(
            "https://www.ximalaya.com/revision/play/v1/show?id=$albumId&num=$page&sort=0&size=$pageSize&ptype=0",
            extraHeaders = mapOf("Referer" to "https://www.ximalaya.com/album/$albumId", "xm-sign" to xmSign()) + searchHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        if (JsonUtil.long(revision, "ret") != 200L) return null
        return revision.obj("data")?.arr("tracksAudioPlay")?.filterIsInstance<JsonObject>()
    }

    private fun parseWithTelecomApi(doc: JsonObject, id: String): SongInfo? {
        val payload = http.get("https://api.telecom.ac.cn/ximalaya?all=0&trackid=$id", extraHeaders = searchHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val candidates = payload.arr("AudioUrls")?.filterIsInstance<JsonObject>().orEmpty()
        var best: SongInfo? = null
        for (candidate in candidates) {
            val status = audioLinkTester.test(JsonUtil.str(candidate, "url"), downloadHeaders)
            best = SongInfo(
                source = source,
                identifier = id,
                rawData = mapOf("search" to doc, "download" to payload),
                songName = com.musicdl.core.model.PathUtils.legalizeString(
                    JsonUtil.str(payload, "AudioName") ?: JsonUtil.str(doc, "title") ?: JsonUtil.str(doc, "trackName"),
                ),
                singers = com.musicdl.core.model.PathUtils.legalizeString(
                    JsonUtil.str(doc, "nickname") ?: JsonUtil.str(doc, "anchorName"),
                ),
                album = com.musicdl.core.model.PathUtils.legalizeString(
                    com.musicdl.core.util.TextUtils.firstNonBlank(
                        JsonUtil.str(doc, "album_title"), JsonUtil.str(doc, "albumTitle"), JsonUtil.str(doc, "albumName"),
                    ),
                ),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds(JsonUtil.double(payload, "AudioLen") ?: JsonUtil.double(doc, "duration")),
                coverUrl = coverOf(doc),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
            if (best.hasValidDownloadUrl && best.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
        }
        return best?.let { it.copy(duration = SongInfo.formatDuration(it.durationSeconds)) }
    }

    private fun parseWithOfficialApiV1(doc: JsonObject, id: String): SongInfo? {
        val headers = mapOf(
            "User-Agent" to "ting_6.7.9(GM1900,Android29)",
            "Referer" to "https://www.ximalaya.com/sound/$id",
            "xm-sign" to xmSign(),
        )
        val payload = http.get(
            "https://www.ximalaya.com/mobile-playpage/track/v3/baseInfo/${System.currentTimeMillis()}",
            query = mapOf("device" to "www2", "trackQualityLevel" to "2", "trackId" to id),
            extraHeaders = headers + searchHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        if (JsonUtil.long(payload, "ret") != 0L) return null
        val trackInfo = payload.obj("trackInfo") ?: return null
        if (JsonUtil.bool(trackInfo, "isAuthorized") != true) return null
        val playUrls = trackInfo.arr("playUrlList")?.filterIsInstance<JsonObject>()
            ?.filter { !JsonUtil.str(it, "url").isNullOrBlank() }
            ?.sortedBy { QUALITY_ORDER.indexOf(JsonUtil.str(it, "type")).let { i -> if (i < 0) QUALITY_ORDER.size else i } }
            .orEmpty()
        var best: SongInfo? = null
        for (entry in playUrls) {
            val decrypted = decryptPlayUrl(JsonUtil.str(entry, "url")) ?: continue
            if (!decrypted.startsWith("http")) continue
            val status = audioLinkTester.test(decrypted, downloadHeaders)
            best = SongInfo(
                source = source,
                identifier = id,
                rawData = mapOf("search" to doc, "download" to payload),
                songName = com.musicdl.core.model.PathUtils.legalizeString(
                    JsonUtil.str(doc, "title") ?: JsonUtil.str(doc, "trackName") ?: JsonUtil.str(trackInfo, "title"),
                ),
                singers = com.musicdl.core.model.PathUtils.legalizeString(
                    JsonUtil.str(doc, "nickname") ?: JsonUtil.str(doc, "anchorName"),
                ),
                album = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(doc, "album_title")),
                ext = status.ext,
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds(JsonUtil.double(doc, "duration") ?: JsonUtil.double(trackInfo, "duration")),
                coverUrl = coverOf(doc),
                downloadUrl = status.downloadUrl,
                downloadUrlStatus = status,
                downloadHeaders = downloadHeaders,
            )
            if (best.hasValidDownloadUrl && best.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
        }
        return best?.let { it.copy(duration = SongInfo.formatDuration(it.durationSeconds)) }
    }

    private fun parseWithMobileJson(doc: JsonObject, id: String): SongInfo? {
        val headers = mapOf(
            "Referer" to "https://m.ximalaya.com/sound/$id",
            "User-Agent" to USER_AGENT,
        )
        val payload = http.get("https://m.ximalaya.com/tracks/$id.json", extraHeaders = headers + searchHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val ret = JsonUtil.long(payload, "ret")
        if (ret != null && ret != 0L && ret != 200L) return null
        val url = com.musicdl.core.util.TextUtils.firstNonBlank(
            JsonUtil.str(payload, "play_path_64"), JsonUtil.str(payload, "play_path_32"), JsonUtil.str(payload, "play_path"),
        ) ?: return null
        val status = audioLinkTester.test(url, downloadHeaders)
        val secs = seconds(JsonUtil.double(doc, "duration") ?: JsonUtil.double(payload, "duration"))
        return SongInfo(
            source = source,
            identifier = id,
            rawData = mapOf("search" to doc, "download" to payload),
            songName = com.musicdl.core.model.PathUtils.legalizeString(
                JsonUtil.str(doc, "title") ?: JsonUtil.str(doc, "trackName") ?: JsonUtil.str(payload, "title"),
            ),
            singers = com.musicdl.core.model.PathUtils.legalizeString(
                JsonUtil.str(doc, "nickname") ?: JsonUtil.str(doc, "anchorName") ?: JsonUtil.str(payload, "nickname"),
            ),
            album = com.musicdl.core.model.PathUtils.legalizeString(
                com.musicdl.core.util.TextUtils.firstNonBlank(
                    JsonUtil.str(doc, "album_title"), JsonUtil.str(doc, "albumTitle"), JsonUtil.str(payload, "album_title"),
                ),
            ),
            ext = status.ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            coverUrl = coverOf(doc) ?: JsonUtil.str(payload, "coverLarge"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    private fun coverOf(doc: JsonObject): String? = com.musicdl.core.util.TextUtils.firstNonBlank(
        JsonUtil.str(doc, "cover_path"), JsonUtil.str(doc, "coverMiddle"), JsonUtil.str(doc, "coverLarge"),
        JsonUtil.str(doc, "coverSmall"), JsonUtil.str(doc, "trackCoverPath"),
    )

    private fun seconds(value: Double?): Long = (value ?: 0.0).toLong()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        val ALLOWED_SEARCH_TYPES = listOf("album", "track")
        val QUALITY_ORDER = listOf("M4A_128", "MP3_64", "MP3_32")
        val KEY_A = "204,53,135,197,39,73,58,160,79,24,12,83,180,250,101,60,206,30,10,227,36,95,161,16,135,150,235,116,242,116,165,171"
            .split(',').map(String::toInt).toIntArray()
        val SBOX_O = (
            "183,174,108,16,131,159,250,5,239,110,193,202,153,137,251,176,119,150,47,204,97,237,1,71,177,42,88,218,166,82,87,94,14,195,69,127,215,240,225,197,238,142,123,44,219,50,190,29," +
                "181,186,169,98,139,185,152,13,141,76,6,157,200,132,182,49,20,116,136,43,155,194,101,231,162,242,151,213,53,60,26,134,211,56,28,223,107,161,199,15,229,61,96,41,66,158,254,21,165," +
                "253,103,89,3,168,40,246,81,95,58,31,172,78,99,45,148,187,222,124,55,203,235,64,68,149,180,35,113,207,118,111,91,38,247,214,7,212,209,189,241,18,115,173,25,236,121,249,75,57," +
                "216,10,175,112,234,164,70,206,198,255,140,230,12,32,83,46,245,0,62,227,72,191,156,138,248,114,220,90,84,170,128,19,24,122,146,80,39,37,8,34,22,11,93,130,63,154,244,160,144,79," +
                "23,133,92,54,102,210,65,67,27,196,201,106,143,52,74,100,217,179,48,233,126,117,184,226,85,171,167,86,2,147,17,135,228,252,105,30,192,129,178,120,36,145,51,163,77,205,73,4,188," +
                "125,232,33,243,109,224,104,208,221,59,9"
            ).split(',').map(String::toInt).toIntArray()

        fun base62(num: Long): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var value = num
            val out = StringBuilder()
            while (true) {
                out.insert(0, chars[(value % 62).toInt()])
                value /= 62
                if (value <= 0) break
            }
            return out.toString().padStart(8, '0')
        }

        fun xmSign(now: Long = System.currentTimeMillis()): String {
            val random = java.security.SecureRandom()
            val head = StringBuilder()
            repeat(4) { head.append("%02x".format(random.nextInt(256))) }
            repeat(2) { head.append("%02x".format(random.nextInt(256))) }
            head.append(base62(now)).append("0202")
            val crc = CRC32().apply { update(head.toString().toByteArray()) }.value.let { "%08x".format(it) }
            val raw = (head.toString() + crc).toByteArray()
            val padding = 16 - raw.size % 16
            val padded = raw + ByteArray(padding) { padding.toByte() }
            val key = "y3hbnr8d4s2ztjbc".toByteArray()
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            val sid = Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(padded))
            return "&&${sid}_2"
        }

        fun decryptPlayUrl(encrypted: String?): String? {
            if (encrypted.isNullOrBlank()) return null
            val data = TextUtils.decodeBase64(encrypted) ?: return null
            if (data.size < 16) return encrypted
            val payload = data.copyOfRange(0, data.size - 16)
            val iv = data.copyOfRange(data.size - 16, data.size)
            val out = ByteArray(payload.size)
            for (i in payload.indices) {
                var value = SBOX_O[payload[i].toInt() and 0xFF].toByte().toInt() and 0xFF
                value = value xor (iv[i % 16].toInt() and 0xFF)
                value = value xor (KEY_A[i % 32] and 0xFF)
                out[i] = value.toByte()
            }
            return String(out, Charsets.UTF_8)
        }
    }
}

/** Port of `musicdl/modules/audiobooks/qingting.py::QingtingMusicClient`. */
class QingtingMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedSearchTypes: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("album", "track"))

    override val source = "QingtingMusicClient"

    private var auth: Map<String, String> = searchCookies.ifEmpty { downloadCookies }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        if (rule["access_token"] != null) auth = auth + ("access_token" to rule.getValue("access_token"))
        rule["refresh_token"]?.let { auth = auth + ("refresh_token" to it) }
        rule["qingting_id"]?.let { auth = auth + ("qingting_id" to it) }
        if (auth["access_token"] == null) refreshAuth()
        if (auth["access_token"] == null) {
            logger.warning("$source needs \"qingting_id\" plus \"access_token\"/\"refresh_token\" in search cookies or the rule map.")
            return emptyList()
        }
        val base = "https://app.qtfm.cn/m-bff/v1/search/result".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        for (type in ALLOWED_SEARCH_TYPES) {
            if (type !in allowedSearchTypes) continue
            val include = if (type == "album") "channel_ondemand" else "program_ondemand"
            var count = 0
            while (count < searchSizePerSource) {
                urls += base.newBuilder().apply {
                    setQueryParameter("k", keyword)
                    setQueryParameter("sort_type", rule["sort_type"] ?: "0")
                    setQueryParameter("page", (count / searchSizePerPage + 1).toString())
                    setQueryParameter("include", include)
                    setQueryParameter("pagesize", searchSizePerPage.toString())
                    setQueryParameter("k_src", "direct")
                }.build().toString()
                count += searchSizePerPage
            }
        }
        return urls
    }

    private fun refreshAuth() {
        val qingtingId = auth["qingting_id"] ?: return
        val refreshToken = auth["refresh_token"] ?: return
        val response = http.postForm(
            "https://user.qtfm.cn/u2/api/v4/auth",
            mapOf(
                "refresh_token" to refreshToken, "qingting_id" to qingtingId,
                "device_id" to DEVICE_ID, "grant_type" to "refresh_token",
            ),
            extraHeaders = mapOf("Content-Type" to "application/x-www-form-urlencoded") + searchHeaders,
        ) ?: return
        val payload = response.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val data = payload.obj("data") ?: return
        auth = data.mapValues { JsonUtil.stringify(it.value).orEmpty() }
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val include = searchUrl.toHttpUrlOrNull()?.queryParameter("include") ?: return
        val payload = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val items = JsonUtil.extractElement(payload, listOf("data", "data")) as? JsonArray ?: return
        for (element in items) {
            val item = element as? JsonObject ?: continue
            when (include) {
                "program_ondemand" -> {
                    if (JsonUtil.str(item, "type") != "program") continue
                    parseProgram(item)?.let { results += it }
                }
                "channel_ondemand" -> parseChannel(item)?.let { results += it }
            }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseProgram(item: JsonObject): SongInfo? {
        val id = JsonUtil.stringify(item["id"]) ?: return null
        val appUrl = JsonUtil.str(item, "url") ?: return null
        val query = appUrl.toHttpUrlOrNull() ?: return null
        val channelId = query.queryParameter("channel_id") ?: return null
        val programId = query.queryParameter("program_id") ?: id
        val pathQuery = "/m-bff/v1/audiostreams/channel/$channelId/program/$programId" +
            "?access_token=${auth["access_token"].orEmpty()}&device_id=$DEVICE_ID" +
            "&qingting_id=${auth["qingting_id"].orEmpty()}&type=play"
        val sign = hmacMd5(HMAC_KEY, pathQuery)
        val payload = http.get("https://app.qtfm.cn$pathQuery&sign=$sign", extraHeaders = searchHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        val editions = buildList {
            addAll((JsonUtil.extractElement(payload, listOf("data", "editions")) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty())
            addAll((JsonUtil.extractElement(payload, listOf("data", "backup_editions")) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty())
        }.sortedWith(
            compareByDescending<JsonObject> { JsonUtil.long(it, "size") ?: 0L }
                .thenByDescending { JsonUtil.long(it, "bitrate") ?: 0L },
        )

        for (edition in editions) {
            val rawUrls = edition["urls"] ?: continue
            val urls = when (rawUrls) {
                is JsonArray -> rawUrls.mapNotNull { JsonUtil.stringify(it) }
                else -> listOfNotNull(JsonUtil.stringify(rawUrls))
            }.filter { it.startsWith("http") }
            for (url in urls) {
                val status = audioLinkTester.test(url, downloadHeaders)
                if (!status.ok) continue
                val channelInfo = http.get("https://app.qtfm.cn/m-bff/v2/channel/$channelId", extraHeaders = searchHeaders)
                    ?.use { JsonUtil.parseObject(it.textOrNull()) }
                val secs = (JsonUtil.double(item, "duration") ?: 0.0).toLong()
                return SongInfo(
                    source = source,
                    identifier = id,
                    rawData = mapOf("search" to item, "download" to payload),
                    songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(item, "title")),
                    singers = com.musicdl.core.model.PathUtils.legalizeString(
                        (JsonUtil.extractElement(channelInfo, listOf("data", "podcasters")) as? JsonArray)
                            ?.filterIsInstance<JsonObject>()
                            ?.mapNotNull { JsonUtil.str(it, "nick_name") }
                            ?.joinToString(", "),
                    ),
                    album = com.musicdl.core.model.PathUtils.legalizeString(
                        JsonUtil.extract(channelInfo, listOf("data", "title")) ?: JsonUtil.str(item, "desc"),
                    ),
                    ext = status.ext,
                    fileSizeBytes = status.fileSizeBytes,
                    fileSize = status.fileSize,
                    durationSeconds = secs,
                    duration = SongInfo.formatDuration(secs),
                    coverUrl = JsonUtil.str(item, "cover"),
                    downloadUrl = status.downloadUrl,
                    downloadUrlStatus = status,
                    downloadHeaders = downloadHeaders,
                )
            }
        }
        return null
    }

    private fun parseChannel(item: JsonObject): SongInfo? {
        val channelId = JsonUtil.stringify(item["id"]) ?: return null
        if (JsonUtil.str(item, "type") != "channel_ondemand") return null
        val info = http.get("https://app.qtfm.cn/m-bff/v2/channel/$channelId", extraHeaders = searchHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) }
        val programCount = JsonUtil.extract(info, listOf("data", "program_count"))?.toIntOrNull() ?: 0
        val pageSize = 100
        val pages = maxOf(0, (programCount + pageSize - 1) / pageSize)
        val episodes = mutableListOf<SongInfo>()
        val seen = mutableSetOf<String>()
        for (page in 1..pages) {
            val pagePayload = http.get(
                "https://app.qtfm.cn/m-bff/v2/channel/$channelId/programs",
                query = mapOf("order" to "asc", "pagesize" to pageSize.toString(), "curpage" to page.toString()),
                extraHeaders = searchHeaders,
            )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: continue
            val programs = JsonUtil.extractElement(pagePayload, listOf("data", "programs")) as? JsonArray ?: continue
            for (element in programs) {
                val program = element as? JsonObject ?: continue
                val programId = JsonUtil.stringify(program["id"]) ?: continue
                if (!seen.add(programId)) continue
                val patched = JsonObject(
                    program + mapOf(
                        "url" to kotlinx.serialization.json.JsonPrimitive(
                            "qingtingfm://app.qingting.fm/playingview?type=ondemand&channel_id=$channelId&program_id=$programId",
                        ),
                    ),
                )
                parseProgram(patched)?.let { episodes += it }
            }
        }
        if (episodes.isEmpty()) return null
        val secs = episodes.sumOf { it.durationSeconds }
        return SongInfo(
            source = source,
            identifier = channelId,
            rawData = mapOf("search" to item, "episodes" to episodes.size),
            songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(item, "title")),
            singers = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.extract(item, listOf("podcaster", "name"))),
            album = "$programCount Episodes",
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            coverUrl = JsonUtil.str(item, "cover"),
            episodes = episodes,
        )
    }

    private companion object {
        const val HMAC_KEY = "99@b8#571(bb38_b"
        const val DEVICE_ID = "66f6e3b560ad8876e52e6e67ee535c5c"
        val ALLOWED_SEARCH_TYPES = listOf("album", "track")

        fun hmacMd5(key: String, message: String): String {
            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacMD5"))
            return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

/** Port of `musicdl/modules/audiobooks/lizhi.py::LizhiMusicClient`. */
class LizhiMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedSearchTypes: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("track", "album"))

    override val source = "LizhiMusicClient"

    private val searchTypes = mutableMapOf<String, String>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://m.lizhi.fm/vodapi/search/voice".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        for (type in SEARCH_TYPES) {
            if (type !in allowedSearchTypes) continue
            var count = 0
            while (count < searchSizePerSource) {
                val url = base.newBuilder().apply {
                    setQueryParameter("deviceId", "h5-b6ef91a9-3dbb-c716-1fdd-43ba08851150")
                    setQueryParameter("keywords", keyword)
                    setQueryParameter("page", (count / searchSizePerPage + 1).toString())
                    setQueryParameter("receiptData", "")
                    rule.forEach { (k, v) -> setQueryParameter(k, v) }
                }.build().toString()
                searchTypes[url] = type
                urls += url
                count += searchSizePerPage
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val type = searchTypes[searchUrl] ?: "track"
        val payload = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        val data = payload["data"] as? JsonArray ?: return
        for (element in data) {
            val item = element as? JsonObject ?: continue
            when (type) {
                "track" -> parseVoice(item)?.let { results += it }
                "album" -> parseAlbum(item)?.let { results += it }
                "user" -> parseUser(item)?.let { results += it }
            }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseVoice(voice: JsonObject): SongInfo? {
        val voiceId = JsonUtil.extract(voice, listOf("voiceInfo", "voiceId")) ?: return null
        val info = http.get("https://m.lizhi.fm/vodapi/voice/info/$voiceId", extraHeaders = searchHeaders)
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        return buildEpisode(
            info, voiceId,
            search = voice,
            name = JsonUtil.extract(info, listOf("data", "userVoice", "voiceInfo", "name")),
            singer = JsonUtil.extract(info, listOf("data", "userVoice", "userInfo", "name")),
            album = JsonUtil.extract(info, listOf("data", "userVoice", "userInfo", "name")),
            cover = JsonUtil.extract(info, listOf("data", "userVoice", "voiceInfo", "imageUrl")),
            seconds = (JsonUtil.extract(info, listOf("data", "userVoice", "voiceInfo", "duration"))?.toLongOrNull() ?: 0L),
            rawUrl = JsonUtil.extract(info, listOf("data", "userVoice", "voicePlayProperty", "trackUrl")),
        )
    }

    private fun parseAlbum(item: JsonObject): SongInfo? {
        val albumId = JsonUtil.extract(item, listOf("voiceExProperty", "payPlaySheet", "payPlaySheetId")) ?: return null
        val pageSize = 1000
        val voices = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val payload = http.get(
                "https://m.lizhi.fm/vodapi/playsheet/data",
                query = mapOf("id" to albumId, "page" to page.toString(), "count" to pageSize.toString()),
                extraHeaders = searchHeaders,
            )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: break
            val pageVoices = payload["voices"] as? JsonArray ?: break
            voices += pageVoices.filterIsInstance<JsonObject>()
            if (pageVoices.isEmpty()) break
            page++
        }
        if (voices.isEmpty()) return null
        val episodes = mutableListOf<SongInfo>()
        val seen = mutableSetOf<String>()
        for (voice in voices) {
            val voiceId = JsonUtil.str(voice, "voiceId") ?: continue
            if (!seen.add(voiceId)) continue
            val episode = buildEpisode(
                voice, voiceId,
                search = item,
                name = JsonUtil.str(voice, "name"),
                singer = JsonUtil.extract(voice, listOf("userInfo", "name")),
                album = JsonUtil.str(voice, "name"),
                cover = JsonUtil.str(voice, "cover"),
                seconds = JsonUtil.long(voice, "duration") ?: 0L,
                rawUrl = JsonUtil.str(voice, "voiceTrack"),
            ) ?: continue
            episodes += episode
        }
        if (episodes.isEmpty()) return null
        val secs = episodes.sumOf { it.durationSeconds }
        val bytes = episodes.sumOf { it.fileSizeBytes }
        return SongInfo(
            source = source,
            identifier = albumId,
            rawData = mapOf("search" to item, "episodes" to episodes.size),
            songName = albumId,
            singers = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.extract(item, listOf("userInfo", "name"))),
            album = "${JsonUtil.extract(item, listOf("userInfo", "audioNum")) ?: 0} Episodes",
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            fileSizeBytes = bytes,
            fileSize = com.musicdl.core.model.PathUtils.byteToMb(bytes),
            coverUrl = JsonUtil.extract(item, listOf("userInfo", "photo")),
            episodes = episodes,
        )
    }

    private fun parseUser(item: JsonObject): SongInfo? {
        val userId = JsonUtil.extract(item, listOf("userInfo", "id")) ?: return null
        val voices = mutableListOf<JsonObject>()
        var page = 1
        while (true) {
            val payload = http.get(
                "https://m.lizhi.fm/vodapi/user/voice",
                query = mapOf("id" to userId, "page" to page.toString(), "count" to "1000"),
                extraHeaders = searchHeaders,
            )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: break
            val pageVoices = payload["data"] as? JsonArray ?: break
            voices += pageVoices.filterIsInstance<JsonObject>()
            if (pageVoices.isEmpty()) break
            page++
        }
        if (voices.isEmpty()) return null
        val episodes = mutableListOf<SongInfo>()
        val seen = mutableSetOf<String>()
        for (voice in voices) {
            val voiceId = JsonUtil.extract(voice, listOf("voiceInfo", "voiceId")) ?: continue
            if (!seen.add(voiceId)) continue
            buildEpisode(
                voice, voiceId,
                search = item,
                name = JsonUtil.extract(voice, listOf("voiceInfo", "name")),
                singer = JsonUtil.extract(voice, listOf("userInfo", "name")),
                album = JsonUtil.extract(voice, listOf("userInfo", "name")),
                cover = JsonUtil.extract(voice, listOf("voiceInfo", "imageUrl")),
                seconds = (JsonUtil.extract(voice, listOf("voiceInfo", "duration"))?.toLongOrNull() ?: 0L),
                rawUrl = JsonUtil.extract(voice, listOf("voicePlayProperty", "trackUrl")),
            )?.let { episodes += it }
        }
        if (episodes.isEmpty()) return null
        val secs = episodes.sumOf { it.durationSeconds }
        return SongInfo(
            source = source,
            identifier = userId,
            rawData = mapOf("search" to item, "episodes" to episodes.size),
            songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.extract(item, listOf("userInfo", "name"))),
            singers = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.extract(item, listOf("userInfo", "name"))),
            album = "${episodes.size} Episodes",
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            coverUrl = JsonUtil.extract(item, listOf("userInfo", "photo")),
            episodes = episodes,
        )
    }

    private fun buildEpisode(
        download: JsonObject,
        voiceId: String,
        search: JsonObject,
        name: String?,
        singer: String?,
        album: String?,
        cover: String?,
        seconds: Long,
        rawUrl: String?,
    ): SongInfo? {
        var base = rawUrl?.takeIf { it.startsWith("http") }
        if (base == null) {
            val date = Regex("""/(\d{4}/\d{2}/\d{2})(?:/|$)""").find(cover.orEmpty())?.groupValues?.get(1) ?: return null
            base = "https://cdn101.lizhi.fm/audio/$date/${voiceId}_sd.m4a"
        }
        var status = com.musicdl.core.model.DownloadUrlStatus()
        var candidate = base
        for (quality in QUALITIES) {
            candidate = base.dropLast(7) + quality
            candidate = candidate.replace("//cdn5.lizhi.fm/audio/", "//cdn101.lizhi.fm/audio/")
            status = audioLinkTester.test(candidate, downloadHeaders)
            if (status.ok && status.ext in com.musicdl.core.net.AudioLinkTester.VALID_AUDIO_EXTS) break
        }
        if (!status.ok) {
            status = audioLinkTester.test(candidate.replace("//cdn101.lizhi.fm/audio/", "//cdn5.lizhi.fm/audio/"), downloadHeaders)
        }
        return SongInfo(
            source = source,
            identifier = voiceId,
            rawData = mapOf("search" to search, "download" to download),
            songName = com.musicdl.core.model.PathUtils.legalizeString(name),
            singers = com.musicdl.core.model.PathUtils.legalizeString(singer),
            album = com.musicdl.core.model.PathUtils.legalizeString(album),
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

    private companion object {
        val SEARCH_TYPES = listOf("track", "album", "user")
        val QUALITIES = listOf("_ud.mp3", "_hd.mp3", "_sd.m4a")
    }
}

/** Port of `musicdl/modules/audiobooks/lrts.py::LRTSMusicClient`. */
class LRTSMusicClient internal constructor(
    config: MusicClientConfig,
    private val allowedSearchTypes: Set<String>,
) : BaseMusicClient(config) {
    constructor(config: MusicClientConfig) : this(config, setOf("album", "book"))

    override val source = "LRTSMusicClient"

    private val searchTypes = mutableMapOf<String, String>()

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = "https://m.lrts.me/ajax/search".toHttpUrlOrNull() ?: return emptyList()
        val urls = mutableListOf<String>()
        val pageSize = maxOf(searchSizePerPage, 40)
        for (type in SEARCH_TYPES) {
            if (type !in allowedSearchTypes) continue
            var count = 0
            while (count < searchSizePerSource) {
                val url = base.newBuilder().apply {
                    setQueryParameter("keyWord", keyword)
                    setQueryParameter("pageSize", pageSize.toString())
                    setQueryParameter("pageNum", (count / pageSize + 1).toString())
                    setQueryParameter("searchOption", "1")
                    rule.forEach { (k, v) -> setQueryParameter(k, v) }
                }.build().toString()
                searchTypes[url] = type
                urls += url
                count += pageSize
            }
        }
        return urls
    }

    override suspend fun searchOne(keyword: String, searchUrl: String, results: MutableList<SongInfo>, progress: SearchProgress) {
        val type = searchTypes[searchUrl] ?: "book"
        val payload = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return
        when (type) {
            "book" -> parseBooks(payload, results)
            "album" -> parseAlbums(payload, results)
        }
    }

    private fun parseBooks(payload: JsonObject, results: MutableList<SongInfo>) {
        val list = JsonUtil.extractElement(payload, listOf("data", "bookResult", "list")) as? JsonArray ?: return
        for (element in list) {
            val book = element as? JsonObject ?: continue
            val bookId = JsonUtil.stringify(book["id"]) ?: continue
            val sections = JsonUtil.int(book, "sections") ?: 0
            val pageSize = 50
            val pages = (sections + pageSize - 1) / pageSize
            val tracks = mutableListOf<JsonObject>()
            val seen = mutableSetOf<String>()
            for (page in 1..pages) {
                val menu = http.get(
                    "https://m.lrts.me/ajax/getBookMenu?bookId=$bookId&pageNum=$page&pageSize=$pageSize&sortType=0",
                    extraHeaders = searchHeaders,
                )?.use { JsonUtil.parseObject(it.textOrNull()) } ?: continue
                for (track in (menu["list"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()) {
                    val trackId = JsonUtil.str(track, "id") ?: continue
                    if (seen.add(trackId)) tracks += track
                }
            }
            val episodes = mutableListOf<SongInfo>()
            for ((index, track) in tracks.withIndex()) {
                val patched = withBook(track, book)
                parseTrack(patched, bookId, index + 1, entityType = 3, idKey = "id")?.let { episodes += it }
            }
            if (episodes.isEmpty()) continue
            results += albumSummary(book, bookId, episodes, "announcer", "cover", "name")
        }
    }

    private fun parseAlbums(payload: JsonObject, results: MutableList<SongInfo>) {
        val list = JsonUtil.extractElement(payload, listOf("data", "albumResult", "list")) as? JsonArray ?: return
        for (element in list) {
            val album = element as? JsonObject ?: continue
            val albumId = JsonUtil.stringify(album["id"]) ?: continue
            val detail = http.get("https://m.lrts.me/ajax/getAlbumAudios?ablumnId=$albumId&sortType=0", extraHeaders = searchHeaders)
                ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: continue
            val tracks = mutableListOf<JsonObject>()
            val seen = mutableSetOf<String>()
            for (track in (detail["list"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()) {
                val audioId = JsonUtil.str(track, "audioId") ?: continue
                if (seen.add(audioId)) tracks += track
            }
            val episodes = mutableListOf<SongInfo>()
            for ((index, track) in tracks.withIndex()) {
                val patched = withAlbum(track, album)
                parseTrack(patched, albumId, index + 1, entityType = 2, idKey = "audioId")?.let { episodes += it }
            }
            if (episodes.isEmpty()) continue
            results += albumSummary(album, albumId, episodes, "nickName", "cover", "name")
        }
    }

    private fun parseTrack(item: JsonObject, entityId: String, sectionIdx: Int, entityType: Int, idKey: String): SongInfo? {
        val songId = JsonUtil.str(item, idKey) ?: JsonUtil.str(item, "sectionId") ?: return null
        var payload = http.get(
            "https://m.lrts.me/ajax/getPlayPath?entityId=$entityId&entityType=$entityType&opType=1&sections=[$songId]&type=0",
            extraHeaders = searchHeaders,
        )?.use { JsonUtil.parseObject(it.textOrNull()) }
        var url = JsonUtil.extract(payload, listOf("list", 0, "path"))
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            payload = http.get(
                "https://m.lrts.me/ajax/getListenPath?entityId=$entityId&entityType=$entityType&opType=1" +
                    "&sections=[$sectionIdx]&type=0&id=$songId&section=$sectionIdx",
                extraHeaders = searchHeaders,
            )?.use { JsonUtil.parseObject(it.textOrNull()) }
            url = JsonUtil.extract(payload, listOf("data", "path"))
        }
        if (url.isNullOrBlank()) return null
        val status = audioLinkTester.test(url, downloadHeaders)
        val secs = (JsonUtil.double(item, "length") ?: 0.0).toLong()
        val info = if (entityType == 3) "book_info" else "album_info"
        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item, "download" to (payload ?: JsonObject(emptyMap()))),
            songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(item, "name")),
            singers = com.musicdl.core.model.PathUtils.legalizeString(
                JsonUtil.extract(item, listOf(info, if (entityType == 3) "announcer" else "nickName")),
            ),
            album = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.extract(item, listOf(info, "name"))),
            ext = status.ext,
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            coverUrl = JsonUtil.extract(item, listOf(info, "cover")),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    private fun albumSummary(
        search: JsonObject,
        albumId: String,
        episodes: List<SongInfo>,
        singerKey: String,
        coverKey: String,
        titleKey: String,
    ): SongInfo {
        val secs = episodes.sumOf { it.durationSeconds }
        val bytes = episodes.sumOf { it.fileSizeBytes }
        return SongInfo(
            source = source,
            identifier = albumId,
            rawData = mapOf("search" to search, "episodes" to episodes.size),
            songName = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(search, "name")),
            singers = com.musicdl.core.model.PathUtils.legalizeString(JsonUtil.str(search, singerKey)),
            album = "${JsonUtil.int(search, "sections") ?: episodes.size} Episodes",
            durationSeconds = secs,
            duration = SongInfo.formatDuration(secs),
            fileSizeBytes = bytes,
            fileSize = com.musicdl.core.model.PathUtils.byteToMb(bytes),
            coverUrl = JsonUtil.str(search, coverKey),
            episodes = episodes,
        )
    }

    private fun withBook(track: JsonObject, book: JsonObject)
        = JsonObject(track + mapOf("book_info" to book))

    private fun withAlbum(track: JsonObject, album: JsonObject)
        = JsonObject(track + mapOf("album_info" to album))

    private companion object {
        val SEARCH_TYPES = listOf("album", "book")
    }
}
