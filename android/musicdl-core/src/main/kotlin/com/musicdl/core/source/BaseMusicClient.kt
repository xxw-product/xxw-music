package com.musicdl.core.source

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.AudioLinkTester
import com.musicdl.core.net.HttpClient
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Port of `musicdl/modules/sources/base.py::BaseMusicClientKwargs`. */
data class MusicClientConfig(
    val searchSizePerSource: Int = 5,
    val searchSizePerPage: Int = 10,
    val strictLimitSearchSizePerPage: Boolean = true,
    val maxRetries: Int = 3,
    val maintainSession: Boolean = false,
    val workDir: String = "musicdl_outputs",
    val logger: LoggerHandle = LoggerHandle.SILENT,
    val defaultSearchHeaders: Map<String, String> = emptyMap(),
    val defaultDownloadHeaders: Map<String, String> = emptyMap(),
    val defaultParseHeaders: Map<String, String> = emptyMap(),
    val defaultSearchCookies: Map<String, String> = emptyMap(),
    val defaultDownloadCookies: Map<String, String> = emptyMap(),
    val defaultParseCookies: Map<String, String> = emptyMap(),
)

/** Progress callback so the Android UI can render the same counters the CLI did. */
interface SearchProgress {
    fun onSourceTask(source: String, message: String, completed: Int, total: Int)
    fun onOverall(completed: Int, total: Int, message: String)
}

object NoSearchProgress : SearchProgress {
    override fun onSourceTask(source: String, message: String, completed: Int, total: Int) = Unit
    override fun onOverall(completed: Int, total: Int, message: String) = Unit
}

/**
 * Port of `BaseMusicClient`.
 *
 * Differences that matter for Android: no `rich` progress bars (a [SearchProgress]
 * callback replaces them), no `pickle` result dumps (the caller owns persistence),
 * and no curl_cffi impersonation (OkHttp is the transport).
 */
abstract class BaseMusicClient(protected val config: MusicClientConfig) {

    abstract val source: String

    protected val logger: LoggerHandle get() = config.logger

    val searchSizePerSource: Int = config.searchSizePerSource
    val searchSizePerPage: Int = minOf(config.searchSizePerSource, config.searchSizePerPage)
    val strictLimitSearchSizePerPage: Boolean = config.strictLimitSearchSizePerPage

    protected var searchHeaders: Map<String, String> = config.defaultSearchHeaders
    protected var downloadHeaders: Map<String, String> = config.defaultDownloadHeaders
    protected var parseHeaders: Map<String, String> = config.defaultParseHeaders

    protected var searchCookies: Map<String, String> = config.defaultSearchCookies
    protected var downloadCookies: Map<String, String> = config.defaultDownloadCookies
    protected var parseCookies: Map<String, String> = config.defaultParseCookies

    protected val http: HttpClient = HttpClient(
        logger = config.logger,
        maxRetries = config.maxRetries,
        maintainSession = config.maintainSession,
        defaultHeaders = config.defaultSearchHeaders,
        defaultCookies = config.defaultSearchCookies,
    )

    protected val audioLinkTester = AudioLinkTester()

    /**
     * Probes (stream checks, per-track lookups) hold a thread each because the HTTP stack is
     * blocking. A wave search starts several of these at once, so an uncapped fan-out used to take
     * every coroutine thread and stall the whole app. Eight probes per source is plenty.
     */
    protected val probeLimit = Semaphore(8)

    /** Port of `_constructsearchurls`; each source decides its own page shape. */
    protected abstract fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String>

    /** Port of `_search` for a single page/URL. */
    protected abstract suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    )

    /**
     * Port of `BaseMusicClient.search`: fan out over the constructed URLs, collect,
     * de-duplicate by identifier, keep only records with a usable download URL,
     * and stamp each with its work directory.
     */
    suspend fun search(
        keyword: String,
        rule: Map<String, String> = emptyMap(),
        progress: SearchProgress = NoSearchProgress,
    ): List<SongInfo> {
        logger.info("Start to search music files using $source.")
        val searchUrls = constructSearchUrls(keyword, rule)
        progress.onSourceTask(source, "$source.search >>> 0/${searchUrls.size} URLs", 0, searchUrls.size)
        progress.onOverall(0, searchUrls.size, "Search From Sources")

        val collected = mutableListOf<SongInfo>()
        searchUrls.forEachIndexed { index, url ->
            runCatching { searchOne(keyword, url, collected, progress) }
                .onFailure { logger.error("$source._search >>> $keyword (Error: ${it.message})") }
            progress.onSourceTask(
                source,
                "$source.search >>> ${index + 1}/${searchUrls.size} URLs",
                index + 1,
                searchUrls.size,
            )
            progress.onOverall(index + 1, searchUrls.size, "Search From Sources")
        }

        val unique = removeDuplicates(collected)
        val workDir = constructUniqueWorkDir(keyword)
        val usable = unique.filter { it.hasValidDownloadUrl }.map { it.copy(workDir = workDir) }
        logger.info("Finished searching from $source. valid items: ${usable.size}.")
        return usable
    }

    /** Port of `_constructuniqueworkdir`. */
    fun constructUniqueWorkDir(keyword: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(Date())
        val safeKeyword = com.musicdl.core.model.PathUtils.sanitizeFileName(keyword)
        return "${config.workDir}/$source/$stamp $safeKeyword"
    }

    /** Port of `_removeduplicates`. */
    protected fun removeDuplicates(items: List<SongInfo>): List<SongInfo> =
        items.distinctBy { it.identifier ?: "${it.source}:${it.songName}:${it.singers}" }

    /** Port of `parseplaylist`; sources that support it override this. */
    open suspend fun searchArtist(name: String, progress: SearchProgress = NoSearchProgress): List<SongInfo> =
        search(name, progress = progress).filter { com.musicdl.core.model.ArtistIdentity.matches(it, name) }

    open fun parsePlaylist(playlistUrl: String): List<SongInfo> =
        throw UnsupportedOperationException("Not supported now to parse playlist from $source")

    /** Port of `_autosetproxies`: Android has no freeproxy lib, so this is a no-op hook. */
    protected open fun autoSetProxies(): Map<String, String> = emptyMap()

    open fun close() {
        http.close()
        audioLinkTester.close()
    }
}
