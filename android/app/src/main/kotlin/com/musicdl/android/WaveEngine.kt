package com.musicdl.android

import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.MusicClient
import com.musicdl.core.source.MusicClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown only when every stage, including the random fallback, produced nothing playable. */
class WaveUnavailable(message: String) : Exception(message)

data class WaveBuild(val tracks: List<SongInfo>, val notes: List<String> = emptyList())

/**
 * Builds «Моя волна».
 *
 * The pipeline is typed now: every query knows whether it asks for an artist, a style or simply a
 * musical base, and results are checked against that intent.
 *
 * profile → typed seeds → candidates → intent check → culture filter → enrich → rank → queue
 *
 * Why the checks exist:
 * - an artist query used to accept anything the catalog returned, including songs that merely have
 *   the artist's name in their title, so results and their credits looked unrelated;
 * - anonymous genre queries against a global catalog bring world music (Bollywood, regional pop)
 *   that has nothing to do with the listener, so records from a culture the library never showed
 *   are dropped;
 * - the widest catalog (Qobuz) is only trusted for artist lookups and for artists already in the
 *   taste profile; genre exploration leans on catalogs with a narrower, more relevant selection.
 *
 * Every stage is bounded and a failed provider only contributes nothing.
 */
class WaveEngine(
    private val config: MusicClientConfig,
    private val sources: Set<String>,
    private val accept: (SongInfo) -> Boolean,
    private val enrich: suspend (SongInfo) -> SongInfo,
    private val report: (String) -> Unit,
    /** Recording keys the listener already owns (likes, downloads, playlists): a wave is for discovery. */
    private val owned: Set<String> = emptySet(),
) {
    private val queryTimeoutMs = 9_000L
    private val sourceTimeoutMs = 6_000L
    private val stageBudgetMs = 18_000L
    private val enrichBudgetMs = 18_000L
    private val enrichItemTimeoutMs = 6_000L
    // No hard ten-track wall: the wave grows until the catalogs stop answering or the budget ends.
    private val targetSize = 150

    suspend fun build(profile: TasteProfile, generation: Int, recent: Set<String>): WaveBuild {
        val notes = mutableListOf<String>()
        val client = MusicClient(
            musicSources = sources.toList().ifEmpty { MusicClient.DEFAULT_MUSIC_SOURCES },
            baseConfig = config,
        )
        try {
            val collected = java.util.Collections.synchronizedList(ArrayList<SongInfo>())
            val knownArtists = profile.artists.keys.toList()

            var skippedOwned = 0
            fun addAll(song: SongInfo) {
                synchronized(collected) {
                    if (Wave.recordingKey(song) in owned) { skippedOwned++; return }
                    if (collected.none { Wave.recordingKey(it) == Wave.recordingKey(song) }) collected.add(song)
                }
            }

            fun unique(): List<SongInfo> = synchronized(collected) { collected.toList() }

            val known = profile.artists.isNotEmpty() || profile.history.isNotEmpty() ||
                profile.genres.values.any { it > Genres.DEFAULT_WEIGHT + 0.01f }

            if (!known) {
                report("Волна: мало данных — берём музыкальную основу")
                searchStage(client, Wave.baseSeeds.take(3), profile, knownArtists,
                    allowGlobalCatalog = false, notes = notes, sink = ::addAll)
            }

            val artistSeeds = Wave.artistSeeds(profile, generation)
            if (artistSeeds.isNotEmpty()) {
                report("Волна: ищем исполнителей")
                // An exact artist search is the one place the wide catalog is genuinely useful.
                searchStage(client, artistSeeds, profile, knownArtists,
                    allowGlobalCatalog = true, notes = notes, sink = ::addAll)
            }

            if (unique().size < 40) {
                val genreSeeds = Wave.genreSeeds(profile, generation).take(8)
                if (genreSeeds.isNotEmpty()) {
                    report("Волна: расширяем жанры")
                    searchStage(client, genreSeeds, profile, knownArtists,
                        allowGlobalCatalog = false, notes = notes, sink = ::addAll)
                }
            }

            if (unique().size < 10) {
                report("Волна: запасной подбор")
                searchStage(client, Wave.baseSeeds.take(3), profile, knownArtists,
                    allowGlobalCatalog = false, notes = notes, sink = ::addAll)
            }

            // The very last network attempt: any source, any non-foreign recording. It runs only if
            // the strict stages found almost nothing, so it cannot pollute a normal wave.
            if (unique().size < 6) {
                report("Волна: широкий подбор")
                searchStage(client, Wave.baseSeeds.take(2), profile, knownArtists,
                    allowGlobalCatalog = true, notes = notes, sink = ::addAll, relaxed = true)
            }

            val candidates = unique()
            if (candidates.isEmpty()) {
                val why = if (skippedOwned > 0) "все найденные треки уже есть в вашей библиотеке ($skippedOwned)"
                else "источники не ответили"
                throw WaveUnavailable("Волна пуста: $why. ${notes.joinToString("; ")}".trim())
            }

            report("Волна: уточняем жанры · ${candidates.size}")
            val enriched = enrichCandidates(candidates.take(150))
                // Enrichment may add catalog genres; a record that turned out to be foreign is not
                // a recommendation, so the filter runs again before ranking.
                .filterNot { Wave.isForeignCulture(it, profile) || Wave.recordingKey(it) in owned }
            val ranked = Wave.rank(
                results = enriched,
                coefficients = profile.genres,
                artists = profile.artists,
                history = profile.history,
                recent = recent,
                penalties = profile.penalties,
            )
            // Random fallback: if ranking rejected everything, a playable assortment still beats an
            // empty wave. Order is shuffled so consecutive tries differ.
            val playable = ranked.ifEmpty {
                enriched.shuffled().take(targetSize).also {
                    notes += "Ранжирование пустое — включён случайный подбор"
                }
            }.take(targetSize)
            if (playable.isEmpty()) throw WaveUnavailable("Не удалось подобрать ни одного трека. Попробуйте ещё раз.")
            return WaveBuild(playable, notes)
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Runs one stage of typed seeds. Results are checked against the intent of their own seed:
     * an artist query must return that artist, a genre query must return that genre (or an artist
     * the user already listens to), and the widest catalog is limited to known artists.
     */
    private suspend fun searchStage(
        client: MusicClient,
        seeds: List<WaveSeed>,
        profile: TasteProfile,
        knownArtists: List<String>,
        allowGlobalCatalog: Boolean,
        notes: MutableList<String>,
        sink: (SongInfo) -> Unit,
        relaxed: Boolean = false,
    ) {
        if (seeds.isEmpty()) return
        // Detached on purpose: a timeout around `coroutineScope` still waits for children that are
        // blocked in HTTP. Here the budget only controls how long we listen; whatever is still
        // running afterwards is cancelled and forgotten.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val slots = Semaphore(3)
        var answered = 0
        var kept = 0
        val jobs = seeds.map { seed ->
            scope.async {
                slots.withPermit {
                    val results = try {
                        withTimeoutOrNull(queryTimeoutMs) {
                            client.search(seed.query, sourceTimeoutMs = sourceTimeoutMs).values.flatten()
                        }.orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val relevant = results.filter { song ->
                        keep(song, seed, profile, knownArtists, allowGlobalCatalog, relaxed)
                    }
                    if (results.isNotEmpty()) synchronized(notes) { answered++ }
                    synchronized(notes) { kept += relevant.size }
                    relevant.forEach(sink)
                    report(progressLabel(seed))
                }
            }
        }
        withTimeoutOrNull(stageBudgetMs) { jobs.awaitAll() }
        jobs.forEach { it.cancel() }
        scope.cancel()
        notes += "${stageName(seeds.first().kind)}: ${seeds.size} запросов, ответов $answered, подошло $kept"
        report(stageName(seeds.first().kind))
    }

    private fun keep(
        song: SongInfo,
        seed: WaveSeed,
        profile: TasteProfile,
        knownArtists: List<String>,
        allowGlobalCatalog: Boolean,
        relaxed: Boolean,
    ): Boolean {
        if (!accept(song)) return false
        return Wave.acceptsSeedResult(
            song = song,
            seed = seed,
            profile = profile,
            knownArtists = knownArtists,
            allowGlobalCatalog = allowGlobalCatalog,
            relaxed = relaxed,
            globalCatalog = GLOBAL_CATALOG,
        )
    }

    private fun progressLabel(seed: WaveSeed): String = when (seed.kind) {
        WaveSeedKind.ARTIST -> "Волна: исполнитель ${seed.artist}"
        WaveSeedKind.GENRE -> "Волна: жанр ${seed.genre?.label ?: seed.query}"
        WaveSeedKind.BASE -> "Волна: основа"
    }

    private fun stageName(kind: WaveSeedKind): String = when (kind) {
        WaveSeedKind.ARTIST -> "Волна: исполнители"
        WaveSeedKind.GENRE -> "Волна: жанры"
        WaveSeedKind.BASE -> "Волна: основа"
    }

    private suspend fun enrichCandidates(candidates: List<SongInfo>): List<SongInfo> {
        val updated = java.util.Collections.synchronizedMap(LinkedHashMap<String, SongInfo>())
        val slots = Semaphore(3)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = candidates.map { song ->
            scope.async {
                slots.withPermit {
                    val fresh = try {
                        withTimeoutOrNull(enrichItemTimeoutMs) { enrich(song) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    updated[song.key()] = fresh ?: song
                }
            }
        }
        withTimeoutOrNull(enrichBudgetMs) { jobs.awaitAll() }
        jobs.forEach { it.cancel() }
        scope.cancel()
        return candidates.map { updated[it.key()] ?: it }
    }

    private fun SongInfo.key(): String = "$source:${identifier ?: songName}"

    private companion object {
        /** The widest catalog: excellent for a named artist, too broad for anonymous browsing. */
        const val GLOBAL_CATALOG = "QobuzMusicClient"
    }
}
