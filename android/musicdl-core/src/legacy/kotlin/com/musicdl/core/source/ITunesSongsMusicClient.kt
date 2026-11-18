package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Songs from the public iTunes Search API.
 *
 * The engine's `ITunesMusicClient` is the audiobook port: it hard-codes `media=podcast` and can
 * therefore never return a track. This client asks the same free, key-less endpoint with
 * `entity=song` and walks two storefronts, because a release can exist in one country catalog and
 * be absent from another — `Miura — BATO` is in `ru` and missing from `us`.
 *
 * Every hit resolves to the catalog `previewUrl` (a 30-second m4a preview), which is what the
 * anonymous Apple endpoint exposes without a media-user-token.
 */
class ITunesSongsMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "ITunesSongsMusicClient"

    init {
        searchHeaders = searchHeaders + mapOf(
            "Accept" to "application/json",
            "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        )
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        return STOREFRONTS.flatMap { storefront ->
            (0 until searchSizePerSource step pageSize).map { offset ->
                "https://itunes.apple.com/$storefront/search".toHttpUrl().newBuilder().apply {
                    setQueryParameter("term", keyword)
                    setQueryParameter("entity", "song")
                    setQueryParameter("limit", pageSize.toString())
                    setQueryParameter("offset", offset.toString())
                    rule.forEach { (key, value) -> setQueryParameter(key, value) }
                }.build().toString()
            }
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val payload = http.get(searchUrl, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) }
            ?: return
        val items = JsonUtil.array(payload, "results")?.filterIsInstance<JsonObject>() ?: return
        items.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${items.size} results", index + 1, items.size)
            val song = runCatching { parseSong(item) }
                .onFailure { logger.error("$source._parse >>> ${it.message}") }
                .getOrNull() ?: return@forEachIndexed
            // The same release is listed in both storefronts under different track ids.
            if (results.none { it.songName == song.songName && it.singers == song.singers }) results += song
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val trackId = JsonUtil.long(item, "trackId")?.toString() ?: return null
        val preview = JsonUtil.str(item, "previewUrl")?.takeIf { it.startsWith("http") } ?: return null
        val status = audioLinkTester.testFast(preview, downloadHeaders)
        if (!status.ok || !AudioLinkTester.isValidAudioExt(status.ext.orEmpty())) return null

        val seconds = (JsonUtil.long(item, "trackTimeMillis") ?: 0L) / 1000
        val cover = JsonUtil.str(item, "artworkUrl100")?.replace(ARTWORK_STAMP, ARTWORK_STAMP_HIRES)
        return SongInfo(
            source = source,
            identifier = trackId,
            rawData = mapOf("search" to item),
            songName = PathUtils.legalizeString(JsonUtil.str(item, "trackName")),
            singers = PathUtils.legalizeString(JsonUtil.str(item, "artistName")),
            album = PathUtils.legalizeString(JsonUtil.str(item, "collectionName")),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = status.ext.orEmpty(),
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = cover,
            downloadUrl = status.downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
        )
    }

    private companion object {
        /** Russian catalog first: local releases are the ones the global store is missing. */
        val STOREFRONTS = listOf("ru", "us")
        const val ARTWORK_STAMP = "100x100bb"
        const val ARTWORK_STAMP_HIRES = "600x600bb"
    }
}
