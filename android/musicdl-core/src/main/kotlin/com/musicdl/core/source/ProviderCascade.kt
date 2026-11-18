package com.musicdl.core.source

import com.musicdl.core.model.ArtistIdentity
import com.musicdl.core.model.SongInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Result of one provider inside a cascade; kept so the UI can explain what failed. */
enum class ProviderOutcome { OK, EMPTY, TIMEOUT, ERROR }

data class ProviderAttempt(
    val source: String,
    val outcome: ProviderOutcome,
    val received: Int = 0,
    val elapsedMs: Long = 0L,
)

data class CascadeResult<T>(
    val value: T?,
    val provider: String? = null,
    val attempts: List<ProviderAttempt> = emptyList(),
) {
    val failed: Boolean get() = value == null
    fun summary(): String = if (attempts.isEmpty()) "нет источников" else attempts.joinToString(" · ") {
        "${it.source.removeSuffix("MusicClient")}: ${when (it.outcome) {
            ProviderOutcome.OK -> "ок"
            ProviderOutcome.EMPTY -> "пусто"
            ProviderOutcome.TIMEOUT -> "таймаут"
            ProviderOutcome.ERROR -> "ошибка"
        }}"
    }
}

/**
 * A → B → C → fallback resolution.
 *
 * Every provider is tried in a fixed order with its own hard timeout and one retry; an error, an
 * empty answer or a timeout only moves the chain forward. This is what keeps one dead catalog from
 * blocking playback or a wave build, which the flat fan-out in [MusicClient.search] cannot do: that
 * one waits for everyone and loses the order needed to prefer a good source over a rescue one.
 */
class ProviderCascade(
    order: List<String>,
    private val baseConfig: MusicClientConfig,
    private val perProviderTimeoutMs: Long = 8_000L,
    private val attemptsPerProvider: Int = 2,
) {
    val sources: List<String> = order.distinct().filter { it in MusicClientRegistry.registeredModules }

    /** First provider that answers with a track accepted by [accept]. */
    suspend fun search(keyword: String, accept: (SongInfo) -> Boolean): CascadeResult<SongInfo> =
        withContext(Dispatchers.IO) {
            val log = ArrayList<ProviderAttempt>()
            if (keyword.isBlank()) return@withContext CascadeResult(null, attempts = log)
            for (source in sources) {
                val client = MusicClientRegistry.build(
                    source,
                    MusicClientRegistry.configFor(source, baseConfig),
                ) ?: continue
                try {
                    for (attempt in 1..attemptsPerProvider.coerceAtLeast(1)) {
                        val started = System.currentTimeMillis()
                        var match: SongInfo? = null
                        var received = 0
                        val outcome = try {
                            val results = withTimeoutOrNull(perProviderTimeoutMs) { client.search(keyword) }
                            when {
                                results == null -> ProviderOutcome.TIMEOUT
                                else -> {
                                    received = results.size
                                    match = results.filter(accept).maxByOrNull(::closeness)
                                    if (match == null) ProviderOutcome.EMPTY else ProviderOutcome.OK
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            baseConfig.logger.warning("Cascade.$source >>> $keyword (${error.message})")
                            ProviderOutcome.ERROR
                        }
                        log += ProviderAttempt(source, outcome, received, System.currentTimeMillis() - started)
                        if (match != null) return@withContext CascadeResult(match, source, log)
                        // A timeout is already the provider's full budget; retrying doubles the wait.
                        if (outcome == ProviderOutcome.TIMEOUT) break
                    }
                } finally {
                    runCatching { client.close() }
                }
            }
            CascadeResult(null, attempts = log)
        }

    /** Search by track identity, preferring the recording that matches title and artist. */
    suspend fun find(title: String?, artist: String?, fullTracksOnly: Boolean = false): CascadeResult<SongInfo> {
        val query = listOfNotNull(
            artist?.takeUnless { it.isBlank() || it.equals("NULL", true) },
            title?.takeUnless { it.isBlank() },
        ).joinToString(" ").trim()
        return search(query) { candidate ->
            val wanted = title.orEmpty().lowercase(Locale.ROOT)
            val candidateTitle = candidate.songName.orEmpty().lowercase(Locale.ROOT)
            val titleMatches = wanted.isBlank() || candidateTitle.contains(wanted) || wanted.contains(candidateTitle)
            val artistMatches = artist.isNullOrBlank() || ArtistIdentity.matches(candidate, artist)
            val longEnough = !fullTracksOnly || candidate.durationSeconds == 0L || candidate.durationSeconds > 35L
            titleMatches && artistMatches && longEnough
        }
    }

    /**
     * A streamable copy of [song]: refresh it at its own source first, then let the chain look for
     * the same recording elsewhere. Used when a stored link expires, which is normal for signed
     * catalog URLs.
     */
    suspend fun streamable(song: SongInfo): CascadeResult<SongInfo> = withContext(Dispatchers.IO) {
        val log = ArrayList<ProviderAttempt>()
        val own = MusicClientRegistry.build(
            song.source,
            MusicClientRegistry.configFor(song.source, baseConfig),
        )
        if (own != null) {
            val started = System.currentTimeMillis()
            try {
                val client = MusicClient(listOf(song.source), baseConfig)
                val fresh = withTimeoutOrNull(perProviderTimeoutMs) { client.refreshTrack(song) }
                log += ProviderAttempt(song.source, if (fresh != null) ProviderOutcome.OK else ProviderOutcome.EMPTY,
                    0, System.currentTimeMillis() - started)
                if (fresh != null) return@withContext CascadeResult(fresh, song.source, log)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                log += ProviderAttempt(song.source, ProviderOutcome.ERROR, 0, System.currentTimeMillis() - started)
            } finally {
                runCatching { own.close() }
            }
        }
        val rest = sources.filterNot { it == song.source }
        if (rest.isEmpty()) return@withContext CascadeResult(null, attempts = log)
        val fallback = ProviderCascade(rest, baseConfig, perProviderTimeoutMs, attemptsPerProvider)
            .find(song.songName, song.singers, fullTracksOnly = song.durationSeconds > 35L)
        CascadeResult(fallback.value, fallback.provider, log + fallback.attempts)
    }

    /** Bigger files usually mean a fuller encode; only used to break a tie between accepted hits. */
    private fun closeness(song: SongInfo): Long = song.fileSizeBytes + if (song.hasValidDownloadUrl) 1L else 0L
}
