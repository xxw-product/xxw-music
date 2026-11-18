package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Port of `thirdpartysites/gequbao.py::GequbaoMusicClient`.
 * `/s/<kw>` listing -> `/music/<id>` page with `window.appData` -> `common-play-url` (or the
 * Quark share when the caller configured Quark cookies).
 */
class GequbaoMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "GequbaoMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://www.gequbao.com/s/${keyword.replace(" ", "%20")}",
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val doc = Jsoup.parse(html, "https://www.gequbao.com")
        val card = doc.select("div.card")
            .firstOrNull { it.text().contains("搜索结果") && it.selectFirst("h1.mark") != null }
            ?: return
        val rows = card.select("div.row").filter { row ->
            row.selectFirst("""a[href^="/music/"][title]""") != null && row.selectFirst("""a.btn[href^="/music/"]""") != null
        }
        rows.forEachIndexed { index, row ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(row)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(row: Element): SongInfo? {
        val detail = row.selectFirst("""a[href^="/music/"][title]""") ?: return null
        val musicId = detail.attr("href").substringAfterLast('/')
        val page = http.get("https://www.gequbao.com${detail.attr("href")}", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        val meta = Regex("""window\.appData""").find(page)?.let { page } ?: page

        fun field(name: String): String? = Regex("\"$name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(meta)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() }

        val title = field("mp3_title") ?: field("title")
        val author = field("mp3_author") ?: field("author")
        val durationText = field("mp3_duration")
        val cover = field("mp3_cover")
        val playId = field("play_id")
        val lyric = TextUtils.cleanLrc(Jsoup.parse(page).selectFirst("div#content-lrc")?.text())

        var downloadUrl: String? = null
        if (hasQuarkCookies) {
            val shares = Regex("\"share_link\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .findAll(meta).map { it.groupValues[1].replace("\\/", "/") }.toList()
            for (share in shares) {
                if (!share.contains("pan.quark.cn")) continue
                downloadUrl = resolveQuark(share)
                if (downloadUrl != null) break
            }
        }
        if (downloadUrl == null && !playId.isNullOrBlank()) {
            val body = http.postForm(
                "https://www.gequbao.com/member/common-play-url",
                mapOf("id" to playId, "purpose" to "play"),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
            downloadUrl = body?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "url")) }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        var seconds = TextUtils.parseDurationText(durationText)
        if (seconds == 0L) seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = musicId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(title),
            singers = PathUtils.legalizeString(author ?: row.selectFirst("small.text-jade")?.text()),
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

/**
 * Port of `thirdpartysites/twot58.py::TwoT58MusicClient`.
 * `/so/<kw>.html` listing, then the `plug/down.php` redirect chain per quality (flac -> wav -> 320).
 * The site's human-check page needs a captcha, so it is detected and reported instead of retried.
 */
class TwoT58MusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "TwoT58MusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36",
            "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(68).coerceAtLeast(1)
        return (0 until searchSizePerSource step pageSize).map { offset ->
            val page = offset / pageSize + 1
            val encoded = keyword.replace(" ", "%20")
            if (page == 1) "https://www.2t58.com/so/$encoded.html" else "https://www.2t58.com/so/$encoded/$page.html"
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        if (html.contains("安全人机验证") && html.contains("human_check")) {
            logger.warning("$source._search >>> human verification page, stopping this page.")
            return
        }
        val doc = Jsoup.parse(html, "https://www.2t58.com/")
        val links = doc.select(".play_list ul li .name a")
        links.forEachIndexed { index, anchor ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${links.size} results", index + 1, links.size)
            runCatching { parse(anchor, "$source")?.let(results::add) }.onFailure {
                logger.error("$source._search >>> ${it.message}")
            }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parse(anchor: Element, @Suppress("UNUSED_PARAMETER") unused: String): SongInfo? {
        val href = anchor.attr("href")
        val songId = href.substringAfterLast('/').substringBefore('.')
        if (songId.isBlank()) return null
        val detailUrl = "https://www.2t58.com$href"

        for (quality in listOf("flac", "wav", "320")) {
            val probe = "https://www.2t58.com/plug/down.php?ac=music&id=$songId&k=$quality"
            val redirected = http.head(probe, extraHeaders = downloadHeaders)?.request?.url?.toString() ?: probe
            val status = audioLinkTester.test(redirected, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue

            val lyric = http.get(
                "https://www.2t58.com/plug/down.php",
                query = mapOf("ac" to "music", "lk" to "lrc", "id" to songId),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
                ?.replace("[00:00.00]欢迎来访爱听音乐网 www.2t58.com\r\n", "")
                ?.let(TextUtils::cleanLrc)

            val cover = http.get(detailUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() }
                ?.let { Jsoup.parse(it).selectFirst("#mcover")?.attr("src") }
            val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to JsonObject(emptyMap())),
                songName = PathUtils.legalizeString(anchor.text()),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                coverUrl = cover,
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
 * Port of `thirdpartysites/heishan.py::HeishanMusicClient` (`163cn.cc`).
 * Listing -> song page lyric -> `/download/<id>.html?t=0|1` Quark links.
 */
class HeishanMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "HeishanMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(20).coerceAtLeast(1)
        return (0 until searchSizePerSource step pageSize).map { offset ->
            "https://www.163cn.cc/search.html".toHttpUrl().newBuilder()
                .setQueryParameter("page", (offset / pageSize + 1).toString())
                .setQueryParameter("keyword", keyword)
                .build().toString()
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
        val rows = Jsoup.parse(html, "https://www.163cn.cc/").select(".songslist ul li")
        rows.forEachIndexed { index, li ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(li)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(li: Element): SongInfo? {
        val anchor = li.selectFirst("""a[href^="/songs/"]""") ?: return null
        val songId = Regex("""/songs/(\d+)\.html""").find(anchor.attr("href"))?.groupValues?.getOrNull(1) ?: return null
        val songUrl = "https://www.163cn.cc${anchor.attr("href")}"

        val lyric = http.get(songUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() }
            ?.let { page ->
                val box = Jsoup.parse(page).selectFirst(".lyrics") ?: return@let null
                TextUtils.cleanLrc(box.select("p").joinToString("\n") { it.text() })
            }

        val links = mutableListOf<String>()
        for (t in listOf("0", "1")) {
            val page = http.get(
                "https://www.163cn.cc/download/$songId.html",
                query = mapOf("t" to t),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() } ?: continue
            links += Jsoup.parse(page).select("""a[href^="https://pan.quark.cn/s/"]""").map { it.attr("href") }
        }
        for (link in links.distinct()) {
            val downloadUrl = resolveQuark(link) ?: continue
            val status = audioLinkTester.test(downloadUrl, downloadHeaders)
            if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue
            val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)
            return SongInfo(
                source = source,
                identifier = songId,
                rawData = mapOf("search" to JsonObject(emptyMap())),
                songName = PathUtils.legalizeString(li.selectFirst(".t h3")?.text()),
                singers = PathUtils.legalizeString(li.selectFirst(".s")?.text()),
                durationSeconds = seconds,
                duration = SongInfo.formatDuration(seconds),
                ext = status.ext.orEmpty(),
                fileSizeBytes = status.fileSizeBytes,
                fileSize = status.fileSize,
                coverUrl = li.selectFirst(".pic img[src]")?.attr("src"),
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
 * Port of `thirdpartysites/yinyuedao.py::YinyuedaoMusicClient` (`1mp3.top`).
 * Base64-encoded detail ids, `data-url` download buttons ranked by format, and the
 * `geturl?id=&quality=exhigh` fallback.
 */
class YinyuedaoMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "YinyuedaoMusicClient"

    private val qualityRank = mapOf(
        "DSD" to 100, "DSF" to 100, "DFF" to 100, "WAV" to 95, "AIFF" to 95, "FLAC" to 90,
        "ALAC" to 90, "APE" to 88, "WV" to 88, "OPUS" to 70, "AAC" to 65, "M4A" to 65,
        "OGG" to 60, "VORBIS" to 60, "MP3" to 50, "WMA" to 45,
    )

    init {
        searchHeaders = searchHeaders + mapOf(
            "referer" to "https://1mp3.top/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://1mp3.top/search.html".toHttpUrl().newBuilder()
            .setQueryParameter("keyword", keyword)
            .build().toString(),
    )

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val rows = Jsoup.parse(html, "https://www.1mp3.top/").select("""a[href^="/mdetail/"]""")
            .filter { it.select("div.row > div").size >= 2 }
        rows.forEachIndexed { index, anchor ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(anchor)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(anchor: Element): SongInfo? {
        val href = anchor.attr("href")
        val token = href.substringAfterLast('/')
        val musicId = TextUtils.decodeBase64(token)?.let { String(it) }?.substringBefore("|") ?: token
        val cols = anchor.select("div.row > div")
        val title = cols.getOrNull(0)?.text()
        val singer = cols.getOrNull(1)?.text()
        val detailUrl = "https://www.1mp3.top$href"

        val page = http.get(detailUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return null
        val doc = Jsoup.parse(page, "https://www.1mp3.top/")
        val lyric = TextUtils.cleanLrc(doc.selectFirst("section#demo article")?.text())
        val cover = doc.selectFirst("#album-cover")?.let { it.absUrl("src").ifBlank { it.attr("src") } }

        if (hasQuarkCookies) {
            val links = doc.select("a.download-link[data-url]")
                .mapNotNull { it.attr("data-url").takeIf(String::isNotBlank)?.let { url -> it.attr("data-format").uppercase().trim() to url } }
                .sortedByDescending { (format, _) -> qualityRank[format] ?: -1 }
            for ((format, raw) in links) {
                if (format.isBlank()) continue
                val downloadUrl = resolveQuark(raw) ?: continue
                val status = audioLinkTester.test(downloadUrl, downloadHeaders)
                if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) continue
                return build(musicId, title, singer, lyric, cover, status)
            }
        }

        val body = http.get(
            "https://1mp3.top/geturl",
            query = mapOf("id" to token, "quality" to "exhigh", "type" to "json"),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() }
        val downloadUrl = body?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "url")) }.getOrNull() }
            ?: return null
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        return build(musicId, title, singer, lyric, cover, status)
    }

    private fun build(
        musicId: String,
        title: String?,
        singer: String?,
        lyric: String?,
        cover: String?,
        status: com.musicdl.core.model.DownloadUrlStatus,
    ): SongInfo {
        val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)
        return SongInfo(
            source = source,
            identifier = musicId,
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
