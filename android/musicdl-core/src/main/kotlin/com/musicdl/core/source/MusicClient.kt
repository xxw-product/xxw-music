package com.musicdl.core.source

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.SongDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors

/** Port of `MusicClientBuilder.REGISTERED_MODULES` plus `BuildMusicClient`. */
object MusicClientRegistry {

    private val builders: Map<String, (MusicClientConfig) -> BaseMusicClient> = linkedMapOf(
        "QobuzMusicClient" to ::QobuzMusicClient,
        "DeezerMusicClient" to ::DeezerMusicClient,
        "SoundCloudMusicClient" to ::SoundCloudMusicClient,
        // Public api.music.yandex.net endpoints; a token only widens what the account may stream.
        "YandexMusicClient" to ::YandexMusicClient,
        // Optional: requires a VK access token with audio permission. See VKMusicClient.
        "VKMusicClient" to ::VKMusicClient,
    )

    fun configFor(source: String, base: MusicClientConfig): MusicClientConfig = base

    val registeredModules: Set<String> get() = builders.keys

    fun build(source: String, config: MusicClientConfig): BaseMusicClient? =
        builders[source]?.invoke(config)

    fun require(source: String, config: MusicClientConfig): BaseMusicClient =
        build(source, config) ?: error(
            "Invalid music source \"$source\"; expected one of (${builders.keys.joinToString(", ")})",
        )
}

/**
 * Port of `musicdl/musicdl.py::MusicClient`.
 *
 * The CLI's interactive table picker becomes the caller's job (the Android UI owns
 * selection); search fan-out, per-source grouping and de-duplication stay here.
 */
class MusicClient(
    musicSources: List<String> = DEFAULT_MUSIC_SOURCES,
    private val baseConfig: MusicClientConfig = MusicClientConfig(),
) {
    val musicSources: List<String> = musicSources.distinct().filter { it in MusicClientRegistry.registeredModules }
        .ifEmpty { DEFAULT_MUSIC_SOURCES.filter { it in MusicClientRegistry.registeredModules } }

    private val clients = this.musicSources.associateWith {
        MusicClientRegistry.require(it, MusicClientRegistry.configFor(it, baseConfig))
    }

    /**
     * Port of `MusicClient.search`: every source in parallel, results keyed by source.
     * A failing source yields an empty list instead of killing the whole search.
     */
    suspend fun search(
        keyword: String,
        progress: SearchProgress = NoSearchProgress,
        sourceTimeoutMs: Long = DEFAULT_SOURCE_TIMEOUT_MS,
        artistOnly: Boolean = false,
        /** Called once per source, as soon as it answers or times out, so the UI can stream in. */
        onSourceResult: ((String, List<SongInfo>) -> Unit)? = null,
    ): Map<String, List<SongInfo>> = withContext(Dispatchers.Default) {
        // Sources talk to the network through blocking OkHttp calls, so a hung server cannot be
        // interrupted by cancellation alone. Each source therefore runs in its own supervisor scope
        // and the fan-out only waits [sourceTimeoutMs] for it; whatever is late is abandoned.
        // Own elastic pool: abandoned blocking calls must never starve Dispatchers.IO (64 threads),
        // otherwise timeouts and other work stop firing on time.
        val work = CoroutineScope(SupervisorJob() + BLOCKING)
        val tasks = musicSources.associateWith { source ->
            work.async {
                val client = clients[source] ?: return@async emptyList()
                runCatching { if (artistOnly) client.searchArtist(keyword, progress) else client.search(keyword, progress = progress) }
                    .onFailure { err ->
                        baseConfig.logger.error("MusicClient.$source.search >>> $keyword (Error: ${err.message})")
                    }
                    .getOrDefault(emptyList())
            }
        }
        try {
            // Wait for every source concurrently; waiting in sequence would multiply the timeout.
            tasks.map { (source, task) ->
                work.async {
                    val results = withTimeoutOrNull(sourceTimeoutMs) { task.await() }
                        ?: run {
                            baseConfig.logger.error("MusicClient.$source.search >>> $keyword (Error: timeout after ${sourceTimeoutMs}ms)")
                            emptyList()
                        }
                    onSourceResult?.invoke(source, results)
                    source to results
                }
            }.awaitAll().toMap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            tasks.values.forEach { it.cancel() }
            work.cancel()
        }
    }

    /** Port of `MusicClient.parseplaylist`: first source that returns tracks wins. */
    suspend fun parsePlaylist(playlistUrl: String): List<SongInfo> = withContext(Dispatchers.IO) {
        for (source in musicSources) {
            val client = clients[source] ?: continue
            val tracks = runCatching { client.parsePlaylist(playlistUrl) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        emptyList()
    }

    /** Saves a direct audio file or a supported HLS playlist and returns the actual output file. */
    suspend fun download(song: SongInfo): File = withContext(Dispatchers.IO) {
        // Restored library snapshots may carry '.' or an obsolete path from another device.
        SongDownloader(baseConfig).download(song.copy(workDir = baseConfig.workDir))
    }

    fun close() = clients.values.forEach { it.close() }

    companion object {
        /** Upper bound for a single source inside [search]; slow sites are simply skipped. */
        const val DEFAULT_SOURCE_TIMEOUT_MS = 30_000L

        private val BLOCKING = Executors.newCachedThreadPool { r ->
            Thread(r, "musicdl-search").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /** Port of `DEFAULT_MUSIC_SOURCES`. */
        val DEFAULT_MUSIC_SOURCES = MusicClientRegistry.registeredModules.toList()

        /** Port of `LOSSLESS_QUALITY_DEFINITIONS`, used by the UI to flag lossless rows. */
        val LOSSLESS_QUALITY_DEFINITIONS = setOf("flac", "wav", "alac", "ape", "wv", "tta", "dsf", "dff")
    }
}
