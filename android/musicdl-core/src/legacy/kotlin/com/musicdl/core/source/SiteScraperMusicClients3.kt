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

/** `window.xxx = '...'` block the gequ* sites use to publish the player state. */
private fun windowVars(js: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    Regex("""window\.(\w+)\s*=\s*'([^']*)'\s*;""").findAll(js).forEach { out[it.groupValues[1]] = it.groupValues[2] }
    Regex("""window\.(\w+)\s*=\s*"([^"]*)"\s*;""").findAll(js).forEach { out[it.groupValues[1]] = it.groupValues[2] }
    Regex("""window\.(\w+)\s*=\s*(-?\d+(?:\.\d+)?)\s*;""").findAll(js)
        .forEach { if (!out.containsKey(it.groupValues[1])) out[it.groupValues[1]] = it.groupValues[2] }
    return out
}

/** gequhai/gequke hide share links in a shuffled base64 (`#` -> `H`, `%` -> `S`). */
private fun decodeQuark(raw: String?): String? {
    val normalized = raw?.takeIf { it.isNotBlank() } ?: return null
    if (normalized.startsWith("http")) return normalized
    val bytes = TextUtils.decodeBase64(normalized.replace("#", "H").replace("%", "S")) ?: return null
    return String(bytes).takeIf { it.startsWith("http") }
}

/**
 * Port of `thirdpartysites/fangpi.py::FangpiMusicClient` (`fangpiw.com`).
 * Same markup as Gequbao: result card -> `/music/<id>` -> `common-play-url` or Quark share.
 */
class FangpiMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "FangpiMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
            "Referer" to "https://www.fangpiw.com/",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> =
        listOf("https://www.fangpiw.com/s/${keyword.replace(" ", "%20")}")

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val card = Jsoup.parse(html, "https://www.fangpiw.com/").select("div.card")
            .firstOrNull { it.text().contains("搜索结果") && it.selectFirst("h1.mark") != null } ?: return
        val rows = card.select("div.row").filter {
            it.selectFirst("""a[href^="/music/"][title]""") != null && it.selectFirst("""a.btn[href^="/music/"]""") != null
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
        val page = http.get("https://www.fangpiw.com${detail.attr("href")}", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null

        fun field(name: String): String? = Regex("\"$name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(page)?.groupValues?.getOrNull(1)?.replace("\\/", "/")?.takeIf { it.isNotBlank() }

        val lyric = TextUtils.cleanLrc(Jsoup.parse(page).selectFirst("div#content-lrc")?.text())
        val playId = field("play_id")
        var downloadUrl: String? = null

        if (hasQuarkCookies) {
            for (share in Regex("\"share_link\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").findAll(page)
                .map { it.groupValues[1].replace("\\/", "/") }.filter { it.contains("pan.quark.cn") }) {
                downloadUrl = resolveQuark(share)
                if (downloadUrl != null) break
            }
        }
        if (downloadUrl == null && !playId.isNullOrBlank()) {
            val body = http.postForm(
                "https://www.fangpiw.com/member/common-play-url",
                mapOf("id" to playId, "purpose" to "play"),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
            downloadUrl = body?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "url")) }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        var seconds = TextUtils.parseDurationText(field("mp3_duration"))
        if (seconds == 0L) seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = musicId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(field("mp3_title") ?: detail.attr("title")),
            singers = PathUtils.legalizeString(field("mp3_author") ?: row.selectFirst("small.text-jade")?.text()),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = field("mp3_cover"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/gequhai.py::GequhaiMusicClient`.
 * `table#myTables` listing -> `/play/<id>` page's `window.*` variables -> `api/music`.
 */
class GequhaiMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "GequhaiMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(12).coerceAtLeast(1)
        val encoded = keyword.replace(" ", "%20")
        return (0 until searchSizePerSource step pageSize).map { offset ->
            val page = offset / pageSize + 1
            if (page == 1) "https://www.gequhai.com/s/$encoded" else "https://www.gequhai.com/s/$encoded?page=$page"
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val table = Jsoup.parse(html, "https://www.gequhai.com").selectFirst("table#myTables") ?: return
        val rows = table.select("tbody tr").filter { it.select("td").size >= 3 }
        rows.forEachIndexed { index, row ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(row)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(row: Element): SongInfo? {
        val cells = row.select("td")
        val anchor = cells[1].selectFirst("a")
        val href = anchor?.attr("href").orEmpty()
        val playId = Regex("""/play/(\d+)""").find(href)?.groupValues?.getOrNull(1) ?: return null
        val playUrl = "https://www.gequhai.com$href"
        val page = http.get(playUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return null
        val vars = windowVars(page)
        val doc = Jsoup.parse(page)
        val lyric = TextUtils.cleanLrc(doc.selectFirst("div#content-lrc2")?.text())
        val type = vars["mp3_type"] ?: "0"

        var downloadUrl: String? = null
        if (hasQuarkCookies) {
            val share = decodeQuark(vars["mp3_extra_url2"]) ?: decodeQuark(vars["mp3_extra_url"])
            if (share != null && share.contains("pan.quark.cn")) downloadUrl = resolveQuark(share)
        }
        if (downloadUrl == null) {
            val body = http.postForm(
                "https://www.gequhai.com/api/music",
                mapOf("id" to playId, "type" to type),
                extraHeaders = searchHeaders + mapOf(
                    "X-Requested-With" to "Http",
                    "Origin" to "https://www.gequhai.com",
                ),
            )?.use { it.textOrNull() }
            downloadUrl = body?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "url")) }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = vars["mp3_id"] ?: playId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(vars["mp3_title"] ?: anchor?.text()),
            singers = PathUtils.legalizeString(vars["mp3_author"] ?: cells[2].text()),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = vars["mp3_cover"],
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/gequke.py::GequkeMusicClient`.
 * Identical flow to Gequhai, on `gequke.com` with `/song/<id>` pages and the same `api/music` call.
 */
class GequkeMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "GequkeMusicClient"

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(15).coerceAtLeast(1)
        val encoded = keyword.replace(" ", "%20")
        return (0 until searchSizePerSource step pageSize).map { offset ->
            val page = offset / pageSize + 1
            if (page == 1) "https://www.gequke.com/search?s=$encoded" else "https://www.gequke.com/search?s=$encoded&page=$page"
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val table = Jsoup.parse(html, "https://www.gequke.com").selectFirst("table#myTables") ?: return
        val rows = table.select("tbody tr").filter { it.select("td").size >= 3 }
        rows.forEachIndexed { index, row ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(row)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(row: Element): SongInfo? {
        val cells = row.select("td")
        val anchor = cells[1].selectFirst("a")
        val href = anchor?.attr("href").orEmpty()
        val playId = Regex("""/song/(\d+)""").find(href)?.groupValues?.getOrNull(1) ?: return null
        val page = http.get("https://www.gequke.com$href", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        val vars = windowVars(page)
        val lyric = TextUtils.cleanLrc(Jsoup.parse(page).selectFirst("div#content-lrc2")?.text())

        var downloadUrl: String? = null
        if (hasQuarkCookies) {
            val share = decodeQuark(vars["mp3_extra_url2"]) ?: decodeQuark(vars["mp3_extra_url"])
            if (share != null && share.contains("pan.quark.cn")) downloadUrl = resolveQuark(share)
        }
        if (downloadUrl == null) {
            val body = http.postForm(
                "https://www.gequke.com/api/music",
                mapOf("id" to playId, "type" to (vars["mp3_type"] ?: "0")),
                extraHeaders = searchHeaders + mapOf("X-Requested-With" to "Http", "Origin" to "https://www.gequke.com"),
            )?.use { it.textOrNull() }
            downloadUrl = body?.let { runCatching { JsonUtil.extract(JsonUtil.parseObject(it), listOf("data", "url")) }.getOrNull() }
                ?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = vars["mp3_id"] ?: playId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(vars["mp3_title"] ?: anchor?.text()),
            singers = PathUtils.legalizeString(vars["mp3_author"] ?: cells[2].text()),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = vars["mp3_cover"],
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/leedodo.py::LeedodoMusicClient` (`music.leedodo.com`, WordPress).
 * `?s=` grid (ajax grid for later pages) -> `/song/<id>` -> `/req/mp3/<id>/`.
 */
class LeedodoMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "LeedodoMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36",
            "Referer" to "https://music.leedodo.com/",
            "Host" to "music.leedodo.com",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(10).coerceAtLeast(1)
        val encoded = keyword.replace(" ", "%20")
        return (0 until searchSizePerSource step pageSize).map { offset ->
            val page = offset / pageSize + 1
            val base = "https://music.leedodo.com/?s=$encoded&post_type=post"
            if (page > 1) "$base&paged=$page" else base
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val page = searchUrl.toHttpUrl().queryParameter("paged")?.toIntOrNull() ?: 1
        val html = if (page <= 1) {
            http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() }
        } else {
            // Later pages come from the US Grid ajax endpoint, exactly like the Python client.
            val template = """{"columns":"1","exclude_items":"none","img_size":"default","ignore_items_size":0,"items_layout":"12041","items_offset":"1","load_animation":"none","overriding_link":"none","post_id":8031,"query_args":{"s":"$keyword","post_type":"post","post_status":["publish"],"tax_query":[],"paged":$page},"orderby_query_args":{"orderby":{"date":"DESC"}},"type":"masonry","us_grid_ajax_index":1,"us_grid_filter_params":null,"us_grid_index":1,"_us_grid_post_type":"current_query"}"""
            http.postForm(
                "https://music.leedodo.com/wp-admin/admin-ajax.php",
                mapOf(
                    "action" to "us_ajax_grid",
                    "ajax_url" to "https://music.leedodo.com/wp-admin/admin-ajax.php",
                    "infinite_scroll" to "true",
                    "max_num_pages" to "10",
                    "pagination" to "ajax",
                    "permalink_url" to "https://music.leedodo.com",
                    "template_vars" to template,
                ),
                extraHeaders = searchHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Origin" to "https://music.leedodo.com"),
            )?.use { it.textOrNull() }
        } ?: return

        val rows = Jsoup.parse(html, "https://music.leedodo.com/").select("article.w-grid-item[data-id]")
        rows.forEachIndexed { index, row ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${rows.size} results", index + 1, rows.size)
            runCatching { parseRow(row)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseRow(row: Element): SongInfo? {
        val songNode = row.selectFirst(""".post_title a[href*="/song/"]""") ?: return null
        val songUrl = songNode.absUrl("href").ifBlank { "https://music.leedodo.com${songNode.attr("href")}" }
        val songId = Regex("""/song/(\d+)""").find(songUrl)?.groupValues?.getOrNull(1) ?: return null
        val author = row.selectFirst(".post_artist a")?.text()

        val downloadUrl = "https://music.leedodo.com/req/mp3/$songId/"
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(songNode.text()),
            singers = PathUtils.legalizeString(author),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/mgmp3.py::MGMP3MusicClient`.
 * `api/search?keyword=` rows, `api/geturl?id=` for the file plus lyric/album/duration.
 */
class MGMP3MusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "MGMP3MusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "Referer" to "https://www.mgmp3.top/",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> = listOf(
        "https://www.mgmp3.top/api/search".toHttpUrl().newBuilder()
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
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).siteObjects2()
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseSong(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = JsonUtil.str(item, "id") ?: return null
        val body = http.get(
            "https://www.mgmp3.top/api/geturl",
            query = mapOf("id" to songId),
            extraHeaders = searchHeaders,
        )?.use { it.textOrNull() } ?: return null
        val detail = runCatching { JsonUtil.parseObject(body) }.getOrNull() ?: return null
        val lyric = TextUtils.cleanLrc(JsonUtil.str(detail, "lrc")?.replace(Regex("(?i)<br\\s*/?>"), "\n"))

        var downloadUrl: String? = null
        if (hasQuarkCookies) {
            val quarkBody = http.get(
                "https://www.mgmp3.top/api/getdown",
                query = mapOf("id" to songId),
                extraHeaders = searchHeaders,
            )?.use { it.textOrNull() }
            val raw = quarkBody?.let { runCatching { JsonUtil.str(JsonUtil.parseObject(it), "kuakedownurl") }.getOrNull() }
            for (link in Regex("""https://pan\.quark\.cn/s/[A-Za-z0-9]+""").findAll(raw.orEmpty())) {
                downloadUrl = resolveQuark(link.value)
                if (downloadUrl != null) break
            }
        }
        if (downloadUrl == null) {
            downloadUrl = JsonUtil.str(detail, "url")?.takeIf { it.startsWith("http") }
        }
        downloadUrl ?: return null

        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null
        var seconds = TextUtils.parseDurationText(JsonUtil.str(detail, "duration"))
        if (seconds == 0L) seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(JsonUtil.str(item, "title") ?: JsonUtil.str(detail, "name")),
            singers = PathUtils.legalizeString(JsonUtil.str(item, "singer") ?: JsonUtil.str(detail, "artist")),
            album = PathUtils.legalizeString(JsonUtil.str(detail, "album")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = JsonUtil.str(item, "picurl") ?: JsonUtil.str(detail, "pic"),
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

/**
 * Port of `thirdpartysites/sgogo.py::SgogoMusicClient`.
 * `src/<kw>` listing -> song page whose `new APlayer` script carries `url`/`title`/`pic`.
 */
class SgogoMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "SgogoMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
            "Referer" to "https://www.sgogo.com/",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> =
        listOf("https://www.sgogo.com/src/${keyword.replace(" ", "%20")}")

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = Jsoup.parse(html, "https://www.sgogo.com/").select(".song-list a.srcsong-item[href]")
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            runCatching { parseItem(item)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseItem(item: Element): SongInfo? {
        val href = item.attr("href")
        val songId = Regex("""/song/(\d+)""").find(href)?.groupValues?.getOrNull(1) ?: return null
        val song = item.selectFirst(".srcsong-name")?.text()?.replace(" ", "") ?: return null
        val singer = item.selectFirst(".srcsinger-name")?.text()?.replace(" ", "")

        val page = http.get("https://www.sgogo.com$href", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        val doc = Jsoup.parse(page, "https://www.sgogo.com/")
        val script = doc.select("script").firstOrNull { it.data().contains("new APlayer") }?.data() ?: return null
        fun value(name: String): String? = Regex("""\b$name\s*:\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.getOrNull(2)?.replace("\\/", "/")

        val rawUrl = value("url") ?: return null
        val downloadUrl = if (rawUrl.startsWith("http")) rawUrl else "https://www.sgogo.com/${rawUrl.trimStart('/')}"
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        val lyric = TextUtils.cleanLrc(doc.selectFirst("#songlrc article")?.text())
        val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(song),
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
}

/**
 * Port of `thirdpartysites/xmfwav.py::XMFWAVMusicClient`.
 * `allsrc/<kw>` regex listing -> `/song/<id>` whose `url:` field is the playable file.
 */
class XMFWAVMusicClient(config: MusicClientConfig) : QuarkBackedMusicClient(config) {

    override val source = "XMFWAVMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36",
            "Referer" to "https://www.xmfwav.com/",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtMost(15).coerceAtLeast(1)
        val encoded = keyword.replace(" ", "%20")
        return (0 until searchSizePerSource step pageSize).map { offset ->
            "https://www.xmfwav.com/allsrc/$encoded?kwd=$encoded&page=${offset / pageSize + 1}"
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val html = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val blocks = Regex("""<a\b[^>]*class=['"][^'"]*\bsrcsong-item\b[^'"]*['"][^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).map { it.value }.toList()
        blocks.forEachIndexed { index, block ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${blocks.size} results", index + 1, blocks.size)
            runCatching { parseBlock(block)?.let(results::add) }
                .onFailure { logger.error("$source._search >>> ${it.message}") }
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseBlock(block: String): SongInfo? {
        val songId = Regex("""href=['"]/song/(\d+)""").find(block)?.groupValues?.getOrNull(1) ?: return null
        val info = Regex(
            """<span[^>]*class=['"]srcsong-name['"][^>]*>(.*?)</span>\s*-\s*<span[^>]*class=['"]srcsinger-name['"][^>]*>(.*?)</span>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(block) ?: return null
        val title = Jsoup.parse(info.groupValues[1]).text()
        val singer = Jsoup.parse(info.groupValues[2]).text()

        val page = http.get("https://www.xmfwav.com/song/$songId", extraHeaders = searchHeaders)
            ?.use { it.textOrNull() } ?: return null
        fun field(name: String): String? = Regex("""\b$name\s*:\s*(['"])(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(page)?.groupValues?.getOrNull(2)?.replace("\\/", "/")

        val rawUrl = field("url") ?: return null
        val downloadUrl = if (rawUrl.startsWith("http")) rawUrl else "https://www.xmfwav.com/${rawUrl.trimStart('/')}"
        val status = audioLinkTester.test(downloadUrl, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        val lyric = Regex("""<section[^>]*id=['"]demo['"][^>]*>.*?<article[^>]*>(.*?)</article>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.getOrNull(1)
            ?.replace(Regex("(?i)<br\\s*/?>"), "\n")
            ?.let { Jsoup.parse(it).text() }
            ?.let(TextUtils::cleanLrc)
        val seconds = TextUtils.extractDurationSecondsFromLrc(lyric)

        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to JsonObject(emptyMap())),
            songName = PathUtils.legalizeString(title.ifBlank { field("title") }),
            singers = PathUtils.legalizeString(singer.ifBlank { field("author") }),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = field("pic")?.let { if (it.startsWith("http")) it else "https://www.xmfwav.com/${it.trimStart('/')}" },
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            lyric = lyric,
            downloadHeaders = downloadHeaders,
        )
    }
}

private fun kotlinx.serialization.json.JsonElement?.siteObjects2(): List<JsonObject> =
    (this as? kotlinx.serialization.json.JsonArray).orEmpty().filterIsInstance<JsonObject>()
