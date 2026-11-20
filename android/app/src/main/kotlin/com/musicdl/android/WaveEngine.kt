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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val similar: SimilarArtists? = null,
) {
    private val queryTimeoutMs = 7_000L
    private val sourceTimeoutMs = 5_000L
    private val stageBudgetMs = 12_000L
    private val enrichBudgetMs = 8_000L
    private val enrichItemTimeoutMs = 4_000L
    // No hard ten-track wall: the wave grows until the catalogs stop answering or the budget ends.
    private val targetSize = 150

    /**
     * The wave plays what the listener already owns or likes, plus more by the same artists:
     *  1. the personal pool: liked, saved, downloaded and well-listened tracks (no network, instant);
     *  2. other tracks by the favourite artists, found by an exact artist search.
     * It never goes looking for unknown artists, genres or generic "hits" queries: when the catalogs
     * are silent the wave simply plays from the personal pool.
     */
    suspend fun build(
        profile: TasteProfile,
        generation: Int,
        recent: Set<String>,
        fast: Boolean = false,
        limit: Int = targetSize,
        owned: List<SongInfo> = emptyList(),
    ): WaveBuild {
        val notes = mutableListOf<String>()
        val favorites = Wave.favoriteArtists(profile, generation, if (fast) 6 else 12)
        val ownedKeys = owned.map { Wave.recordingKey(it) }.toSet()
        val personal = personalPool(owned, profile, recent, generation)
        if (favorites.isEmpty() && personal.isEmpty()) {
            throw WaveUnavailable("Волне пока не на что опереться: поставьте лайки трекам или добавьте музыку в библиотеку.")
        }
        val found = java.util.Collections.synchronizedList(ArrayList<SongInfo>())
        if (favorites.isNotEmpty()) {
            val client = MusicClient(
                musicSources = sources.toList().ifEmpty { MusicClient.DEFAULT_MUSIC_SOURCES },
                baseConfig = config,
            )
            try {
                report("Волна: ищем ещё у ваших исполнителей")
                val knownArtists = profile.artists.keys.toList()
                searchStage(
                    client, artistSeeds(favorites), profile, knownArtists,
                    allowGlobalCatalog = true, notes = notes,
                    sink = { song ->
                        // Only tracks the listener does not have yet: the library is covered by the personal pool.
                        if (Wave.recordingKey(song) !in recent && Wave.recordingKey(song) !in ownedKeys) synchronized(found) {
                            if (found.none { Wave.recordingKey(it) == Wave.recordingKey(song) }) found.add(song)
                        }
                    },
                    budget = if (fast) 6_000L else stageBudgetMs,
                    enough = { fast && found.size >= limit * 3 },
                )
            } finally {
                // Closing may wait on sockets stuck in the network; never let that delay the result.
                CoroutineScope(Dispatchers.IO).launch { runCatching { client.close() } }
            }
        }
        val candidates = (found.toList() + personal).distinctBy { Wave.recordingKey(it) }
            .filterNot { Wave.isForeignCulture(it, profile) }
        notes += "Своих: ${personal.size}, у любимых исполнителей: ${found.size}"
        val ranked = Wave.rank(
            results = candidates,
            coefficients = profile.genres,
            artists = profile.artists,
            history = profile.history,
            recent = recent,
            penalties = profile.penalties,
        )
        // Two new tracks by favourite artists for every one from the library; the library fills in
        // when the catalogs have fewer new tracks than needed.
        val ordered = ranked.ifEmpty { candidates.shuffled() }
        val fresh = ordered.filter { Wave.recordingKey(it) !in ownedKeys }.toMutableList()
        val mine = ordered.filter { Wave.recordingKey(it) in ownedKeys }.toMutableList()
        val playable = buildList {
            while (size < limit && (fresh.isNotEmpty() || mine.isNotEmpty())) {
                repeat(2) { if (size < limit && fresh.isNotEmpty()) add(fresh.removeAt(0)) }
                if (size < limit && mine.isNotEmpty()) add(mine.removeAt(0))
                if (fresh.isEmpty() && mine.isEmpty()) break
            }
        }
        if (playable.isEmpty()) throw WaveUnavailable("Не удалось подобрать ни одного трека. Попробуйте ещё раз.")
        return WaveBuild(playable, notes)
    }

    /**
     * A plain artist query mostly returns the artist's best-known tracks, which the listener has
     * usually liked already. Query variants reach further into the same artist's catalog; results
     * are still required to be by exactly that artist. Plain queries for every artist go first.
     */
    private fun artistSeeds(artists: List<String>): List<WaveSeed> =
        listOf("", " feat", " remix", " album").flatMap { suffix ->
            artists.map { WaveSeed(WaveSeedKind.ARTIST, it + suffix, artist = it) }
        }

    /** Tracks the listener owns or approved, minus anything rejected; fresh ones first, sampled for variety. */
    private fun personalPool(owned: List<SongInfo>, profile: TasteProfile, recent: Set<String>, generation: Int): List<SongInfo> {
        val usable = owned.filter { song ->
            !song.songName.isNullOrBlank() && !song.singers.isNullOrBlank() && !Wave.isJunk(song) &&
                Wave.artistNames(song.singers).none { (profile.penalties.artists[Wave.key(it)] ?: 0f) >= 3f }
        }.distinctBy { Wave.recordingKey(it) }
        val fresh = usable.filter { Wave.recordingKey(it) !in recent }
        val pool = fresh.ifEmpty { usable }
        return pool.shuffled(java.util.Random(generation * 7919L + pool.size)).take(120)
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
        budget: Long = stageBudgetMs,
        enough: () -> Boolean = { false },
    ) {
        if (seeds.isEmpty()) return
        // Detached on purpose: a timeout around `coroutineScope` still waits for children that are
        // blocked in HTTP. Here the budget only controls how long we listen; whatever is still
        // running afterwards is cancelled and forgotten. Default, not IO: these jobs only await.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val slots = Semaphore(6)
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
        withTimeoutOrNull(budget) {
            while (jobs.any { it.isActive } && !enough()) delay(100)
        }
        jobs.forEach { it.cancel() }
        scope.cancel()
        synchronized(notes) { notes += "${stageName(seeds.first().kind)}: ${seeds.size} запросов, ответов $answered, подошло $kept" }
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

    /** Related artists for a few favourites, looked up in parallel within a short budget. */
    private suspend fun relatedArtists(sources: List<String>): Map<String, List<String>> {
        val lookup = similar ?: return emptyMap()
        if (sources.isEmpty()) return emptyMap()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val jobs = sources.associateWith { name -> scope.async { lookup.related(name) } }
        withTimeoutOrNull(3_500L) {
            while (jobs.values.any { it.isActive }) delay(50)
        }
        val answered = jobs.mapNotNull { (name, job) ->
            if (job.isCompleted && !job.isCancelled) runCatching { job.getCompleted() }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { name to it } else null
        }.toMap()
        jobs.values.forEach { it.cancel() }
        scope.cancel()
        return answered
    }

    private suspend fun enrichCandidates(candidates: List<SongInfo>): List<SongInfo> {
        val updated = java.util.Collections.synchronizedMap(LinkedHashMap<String, SongInfo>())
        val slots = Semaphore(12)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val jobs = candidates.map { song ->
            scope.async {
                // A track that already carries a genre needs no catalog round trip.
                if (!song.genre.isNullOrBlank()) {
                    updated[song.key()] = song
                    return@async
                }
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
