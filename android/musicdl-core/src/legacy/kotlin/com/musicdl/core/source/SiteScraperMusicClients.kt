package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.QuarkParser
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import java.net.URLEncoder

private fun JsonObject.siteText(vararg keys: String): String? = keys.firstNotNullOfOrNull { name ->
    JsonUtil.str(this, name)?.takeIf { it.isNotBlank() }
}

private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")

/**
 * Shared plumbing for the `thirdpartysites` scrapers.
 *
 * They all end at a Quark share link: without the caller's own Quark cookies there is no way to
 * turn that share into a file, so [resolveQuark] returns nothing and the client logs instead of
 * fabricating a URL.
 */
abstract class QuarkBackedMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    protected val quarkCookies: Map<String, String> by lazy {
        val joined = config.defaultSearchCookies["quark_cookies"]
            ?: config.defaultSearchCookies["quark"]
            ?: config.defaultSearchCookies["quarkParserCookies"]
        if (!joined.isNullOrBlank()) {
            joined.split(';').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
            }.toMap()
        } else {
            config.defaultSearchCookies.filterKeys { it == "b-user-id" || it == "__pus" || it == "__puus" }
        }
    }

    protected val hasQuarkCookies: Boolean get() = quarkCookies.isNotEmpty()

    protected fun resolveQuark(link: String): String? {
        if (!hasQuarkCookies) return null
        return QuarkParser.parseFromUrl(link, quarkCookies).second
    }

    protected fun lrcSeconds(lyric: String?): Long = TextUtils.extractDurationSecondsFromLrc(lyric)
}

/**
 * Port of `thirdpartysites/buguyy.py::BuguyyMusicClient`.
 * `api/search` -> per-song `api/getdown` (the MP3# mirror) or `api/geturl` for the direct file,
 * plus `api/geturl`'s `lrc`.
 */
class BuguyyMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "BuguyyMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
            "Referer" to "https://buguyy.top/",
            "Content-Type" to "application/json",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://buguyy.top/api/search".toHttpUrl().newBuilder()
            .setQueryParameter("keyword", keyword)
            .build().toString(),
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).siteObjects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.siteText("id") ?: return null
        val lyricBody = http.get(
            "https://buguyy.top/api/geturl",
            query = mapOf("id" to songId),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() }?.let { runCatching { JsonUtil.parseObject(it) }.getOrNull() }

        val lyric = TextUtils.cleanLrc(
            JsonUtil.str(lyricBody, "lrc")?.replace(Regex("(?i)<br\\s*/?>"), "\n"),
        )
        var downloadUrl: String? = null

        if (hasQuarkCookies) {
            val quarkBody = http.get(
                "https://buguyy.top/api/getdown",
                query = mapOf("id" to songId),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
            val raw = runCatching { JsonUtil.str(JsonUtil.parseObject(quarkBody.orEmpty()), "kuakedownurl") }.getOrNull()
            val match = raw?.let { Regex("(?i)(?:RAR|WAV|FLAC|MP3)#(https?://[^#]+)").find(it) }?.groupValues?.getOrNull(1)
            if (match != null) downloadUrl = resolveQuark(match)
        }
        if (downloadUrl == null) {
            downloadUrl = JsonUtil.str(lyricBody, "url")?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = lrcSeconds(lyric)

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(item.siteText("title")),
            singers = PathUtils.legalizeString(item.siteText("singer")),
            album = PathUtils.legalizeString(item.siteText("album")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = item.siteText("picurl"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/mitu.py::MituMusicClient` (`qqmp3.vip`).
 * `api/songs.php?type=search` for rows, `api/kw.php?level=exhigh&lrc=true` for the file and lyric.
 */
class MituMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "MituMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "accept" to "*/*",
            "origin" to "https://www.qqmp3.vip",
            "referer" to "https://www.qqmp3.vip/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://api.qqmp3.vip/api/songs.php".toHttpUrl().newBuilder()
            .setQueryParameter("keyword", keyword)
            .setQueryParameter("type", "search")
            .build().toString(),
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).siteObjects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val rid = item.siteText("rid") ?: return null
        val body = http.get(
            "https://api.qqmp3.vip/api/kw.php",
            query = mapOf("rid" to rid, "type" to "json", "level" to "exhigh", "lrc" to "true"),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return null
        val download = runCatching { JsonUtil.parseObject(body) }.getOrNull() ?: return null

        val lyric = TextUtils.cleanLrc(JsonUtil.extract(download, listOf("data", "lrc")))
        var downloadUrl: String? = null

        if (hasQuarkCookies) {
            val quarkLinks = JsonUtil.extractElement(item, listOf("downurl")).siteObjects()
                .mapNotNull { JsonUtil.str(it, "url") ?: (it as? JsonPrimitive)?.content }
            for (link in quarkLinks) {
                val resolved = link.substringAfter("http", "").let { if (it.isEmpty()) null else "http$it" } ?: continue
                resolveQuark(resolved)?.let { downloadUrl = it }
                if (downloadUrl != null) break
            }
        }
        if (downloadUrl == null) {
            downloadUrl = JsonUtil.extract(download, listOf("data", "url"))?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = lrcSeconds(lyric)

        return SongInfo(
            source = source,
            identifier = rid,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(item.siteText("name")),
            singers = PathUtils.legalizeString(item.siteText("artist")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = item.siteText("pic"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/xiageba.py::XiagebaMusicClient`.
 * `api/music/search` -> `so.liumingye.cn/api/music/<id>` for the share links and lyrics.
 */
class XiagebaMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "XiagebaMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://xiageba.liumingye.cn/api/music/search".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("q", keyword)
                setQueryParameter("page", (offset / pageSize + 1).toString())
                setQueryParameter("pageSize", pageSize.toString())
            }.build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        if (!hasQuarkCookies) {
            logger.warning("$source._search >>> quark_cookies is not configured, skipping share links.")
            return
        }
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).siteObjects()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = item.siteText("id") ?: return null
        val detailBody = http.get(
            "https://so.liumingye.cn/api/music/$songId",
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return null
        val detail = runCatching { JsonUtil.parseObject(detailBody) }.getOrNull() ?: return null
        val lyric = TextUtils.cleanLrc(JsonUtil.str(detail, "lyrics"))

        val links = JsonUtil.extractElement(detail, listOf("downloads")).siteObjects()
            .mapNotNull { JsonUtil.str(it, "url") }
            .filter { it.startsWith("https://pan.quark.cn/") }
        for (link in links) {
            val downloadUrl = resolveQuark(link) ?: continue
            val status = audioLinkTester.test(downloadUrl, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue
            val seconds = lrcSeconds(lyric)
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to item),
                songName = PathUtils.legalizeString(item.siteText("title")),
                singers = PathUtils.legalizeString(item.siteText("artist")),
                album = PathUtils.legalizeString(item.siteText("album")),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = item.siteText("cover"),
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
 * Port of `thirdpartysites/kkws.py::KKWSMusicClient`.
 * `search.html` listing -> detail page `openModel(...)` buttons -> `getdown` resolution, then Quark.
 */
class KKWSMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "KKWSMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(15).coerceAtLeast(1)
        val endpoint = "https://www.kkws.cc/search.html".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                setQueryParameter("key", keyword)
                setQueryParameter("page", (offset / pageSize + 1).toString())
            }.build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        if (!hasQuarkCookies) {
            logger.warning("$source._search >>> quark_cookies is not configured, skipping share links.")
            return
        }
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val rows = Jsoup.parse(html, "https://www.kkws.cc/").select("ul.listbox > li")
        rows.forEachIndexed { index, li ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(li)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(li: org.jsoup.nodes.Element): SongInfo? {
        val anchor = li.selectFirst("h2 a[href]") ?: return null
        val detailUrl = anchor.absUrl("href").ifBlank { "https://www.kkws.cc${anchor.attr("href")}" }
        val songId = detailUrl.substringAfterLast('/')
        val title = anchor.attr("title").ifBlank { anchor.text() }
            .replace("免费下载", "")
            .replace(Regex("""\s*\[[^]]+]\s*"""), " ")
            .replace(Regex("""\s*-\s*\d+(\.\d+)?[KMG]?\s*$"""), "")
            .trim()
        val singer = li.select("small em").lastOrNull()?.text()?.replace("演唱：", "")?.trim()

        val page = http.get(detailUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return null
        val doc = Jsoup.parse(page, "https://www.kkws.cc/")
        val lyric = TextUtils.cleanLrc(
            doc.selectFirst("#textbox")?.text()?.replace(" / ", "\n"),
        )
        for (button in doc.select("""div.downbox a[onclick^="openModel"]""")) {
            val args = Regex("""'([^']*)'""").findAll(button.attr("onclick")).map { it.groupValues[1] }.toList()
            if (args.size < 4) continue
            val (downloadId, nameFormat, rawUrl, _) = args
            val resolved = http.get(
                "https://www.kkws.cc/getdown",
                query = mapOf("url" to rawUrl, "j" to "1", "id" to downloadId, "title" to nameFormat),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
                ?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "decrypted_url")) }.getOrNull() }
                ?: rawUrl.takeIf { it.startsWith("http") }
                ?: continue
            if (!resolved.contains("pan.quark.cn")) continue

            val downloadUrl = resolveQuark(resolved) ?: continue
            val status = audioLinkTester.test(downloadUrl, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue
            val seconds = lrcSeconds(lyric)
            return SongInfo(
                source = source,
                identifier = songId.ifBlank { downloadId },
                rawData = mapOf("search" to JsonObject(emptyMap())),
                songName = PathUtils.legalizeString(title),
                singers = PathUtils.legalizeString(singer),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
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
 * Port of `thirdpartysites/fivesong.py::FiveSongMusicClient` (`5song.xyz`).
 * Search and detail are HTML; the Quark link comes back from `play/songdown.php`, lyrics from `play/lrc.php`.
 */
class FiveSongMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "FiveSongMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(10).coerceAtLeast(1)
        val endpoint = "https://www.5song.xyz/search.html".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                if (offset > 0) setQueryParameter("page", (offset / pageSize + 1).toString())
                setQueryParameter("keyword", keyword)
            }.build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        if (!hasQuarkCookies) {
            logger.warning("$source._search >>> quark_cookies is not configured, skipping share links.")
            return
        }
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val doc = Jsoup.parse(html, "https://www.5song.xyz/")
        val rows = doc.select("div.list ul > li")
        rows.forEachIndexed { index, li ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(li)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(li: org.jsoup.nodes.Element): SongInfo? {
        val anchor = li.selectFirst("a[href]") ?: return null
        val detailUrl = anchor.absUrl("href").ifBlank { "https://www.5song.xyz${anchor.attr("href")}" }
        val songId = detailUrl.substringAfterLast('/').substringBefore('.')
        val title = anchor.selectFirst("div.con div.t h3")?.text()
        val singer = anchor.selectFirst("div.singerNum div.singer")?.text()
        val cover = anchor.selectFirst("div.pic img")?.absUrl("src")

        val page = http.get(detailUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return null
        val doc = Jsoup.parse(page, "https://www.5song.xyz/")

        var downloadUrl: String? = null
        for (entry in doc.select("div.download li.mobileDown[data-t]")) {
            if (!entry.text().contains("夸克")) continue
            val payload = buildJsonObject {
                put("id", JsonPrimitive(songId))
                put("terminal", JsonPrimitive("pc"))
                put("t", JsonPrimitive(entry.attr("data-t")))
                put("visitor", JsonPrimitive(""))
            }
            val body = http.postJson(
                "https://www.5song.xyz/play/songdown.php",
                payload.toString(),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: continue
            val link = runCatching { JsonUtil.str(JsonUtil.parseObject(body), "url") }.getOrNull() ?: continue
            if (!link.startsWith("https://pan.quark.cn/")) continue
            downloadUrl = resolveQuark(link)
            if (downloadUrl != null) break
        }
        downloadUrl ?: return null

        val lyricRaw = http.get(
            "https://www.5song.xyz/play/lrc.php",
            query = mapOf("id" to songId),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() }
        val lyric = TextUtils.cleanLrc(lyricRaw?.takeIf { !it.contains("歌词获取失败") })

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = lrcSeconds(lyric)

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(title),
            singers = PathUtils.legalizeString(singer),
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

private fun kotlinx.serialization.json.JsonElement?.siteObjects(): List<JsonObject> =
    (this as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
