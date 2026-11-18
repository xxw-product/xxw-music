package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import com.musicdl.core.util.TextUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private fun md5(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun qobuzSig(method: String, args: Map<String, String>, timestamp: String, secret: String): String {
    val ignored = setOf("app_id", "request_ts", "request_sig")
    val sorted = args.toSortedMap().entries.filter { it.key !in ignored }
        .joinToString("") { "${it.key}${it.value}" }
    return md5(method.trim('/').replace("/", "") + sorted + timestamp + secret)
}


class QobuzMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "QobuzMusicClient"

    // The bundled pair is only a fallback: Settings lets the user paste their own app id/secret.
    private val appId = config.defaultSearchCookies["qobuz_app_id"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: "712109809"
    private val appSecret = config.defaultSearchCookies["qobuz_app_secret"]?.trim()?.takeIf { it.isNotEmpty() }
        ?: "589be88e4538daea11f509d29e4a23b1"
    private val userToken = config.defaultSearchCookies["user_auth_token"]
        ?: config.defaultSearchCookies["qobuz_user_token"]
        ?: config.defaultSearchCookies["X-User-Auth-Token"]
    private val mirrorHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36",
        "Accept" to "audio/flac, audio/*, */*",
        "Accept-Encoding" to "identity",
        "Referer" to "https://open.qobuz.com/",
        "Origin" to "https://open.qobuz.com",
        "X-API-Key" to "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f",
        "api-key" to "ak_8e3f1a7c2b5d9e4f0a6c3b8d1e5f2a9c7b4d0e6f",
    )

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
            "Accept" to "application/json",
        )
        userToken?.let { searchHeaders = searchHeaders + ("X-User-Auth-Token" to it) }
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        val base = mapOf("query" to keyword, "offset" to "0", "limit" to "10") + rule
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val pageSize = searchSizePerPage.coerceAtLeast(1)
        val endpoint = "https://www.qobuz.com/api.json/0.2/catalog/search".toHttpUrl()
        return (0 until searchSizePerSource step pageSize).map { offset ->
            endpoint.newBuilder().apply {
                base.forEach { (key, value) -> setQueryParameter(key, value) }
                setQueryParameter("offset", offset.toString())
                setQueryParameter("limit", pageSize.toString())
                setQueryParameter("app_id", appId)
                setQueryParameter("request_ts", timestamp)
                setQueryParameter("request_sig", qobuzSig("catalog/search",
                    base + mapOf("offset" to offset.toString(), "limit" to pageSize.toString()), timestamp, appSecret))
            }.build().toString()
        }
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val body = http.get(searchUrl, extraHeaders = searchHeaders)?.use { it.textOrNull() } ?: return
        val items = JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("tracks", "items")).objects()
        if (items.isEmpty()) return
        // Two stream probes per hit (24-bit, then plain) add up fast when done one by one. They run
        // as suspending coroutines under [probeLimit]; `runBlocking` here used to block a worker
        // while its children needed another one, which deadlocked the whole dispatcher.
        val parsed = coroutineScope {
            items.mapIndexed { index, item ->
                async {
                    probeLimit.withPermit {
                        progress.onSourceTask(
                            source,
                            "$source._search >>> ${index + 1}/${items.size} results",
                            index + 1,
                            items.size,
                        )
                        runCatching { parseSong(item) }
                            .onFailure { logger.error("$source._parsewithanandserverapi >>> ${it.message}") }
                            .getOrNull()
                    }
                }
            }.awaitAll()
        }
        for (song in parsed) {
            if (song == null) continue
            results += song
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val songId = JsonUtil.str(item, "id") ?: return null
        val seconds = (JsonUtil.double(item, "duration") ?: 0.0).toLong()
        // The mirror answers 405 to HEAD and ignores Range, so probing a link here downloads the
        // whole ~25 MB FLAC — ten of those blew the per-source budget and Qobuz contributed zero.
        // The stream URL is deterministic, so it is built directly; the player and the downloader
        // both send mirrorHeaders (X-API-Key included), which the mirror requires.
        val downloadUrl = "https://qobuz.anandserver.cfd/api/stream/$songId"
        val status = DownloadUrlStatus(
            ok = true,
            contentType = "audio/flac",
            ext = "flac",
            originalDownloadUrl = downloadUrl,
            downloadUrl = downloadUrl,
            method = "GET",
            statusCode = 200,
        )
        return SongInfo(
            source = source,
            identifier = songId,
            rawData = mapOf("search" to item, "quality" to "27"),
            songName = PathUtils.legalizeString(JsonUtil.str(item, "title")),
            singers = PathUtils.legalizeString(JsonUtil.extract(item, listOf("performer", "name"))),
            album = PathUtils.legalizeString(JsonUtil.extract(item, listOf("album", "title"))),
            genre = TrackGenres.fromCatalog(item).first,
            tags = TrackGenres.fromCatalog(item).second,
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = "flac",
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = JsonUtil.extract(item, listOf("album", "image", "large")),
            downloadUrl = downloadUrl,
            downloadUrlStatus = status,
            downloadHeaders = mirrorHeaders,
            protocol = "HTTP",
        )
    }
}
