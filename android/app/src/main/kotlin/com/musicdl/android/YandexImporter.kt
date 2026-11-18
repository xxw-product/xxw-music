package com.musicdl.android

import com.musicdl.core.model.SongInfo
import com.musicdl.core.transfer.YandexLibrary
import com.musicdl.core.transfer.YandexPlaylist
import com.musicdl.core.transfer.YandexTrack
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

enum class MatchConfidence { EXACT, DOUBTFUL, NONE }

data class ImportMatch(
    val source: YandexTrack,
    val song: SongInfo? = null,
    val confidence: MatchConfidence = MatchConfidence.NONE,
    val note: String = "",
)

data class YandexImportReport(
    val account: String,
    val liked: List<ImportMatch> = emptyList(),
    val playlists: List<Pair<String, List<ImportMatch>>> = emptyList(),
    val artists: List<String> = emptyList(),
    /** Album label → its matched tracks. Imported as a playlist per album. */
    val albums: List<Pair<String, List<ImportMatch>>> = emptyList(),
    val addedTracks: Int = 0,
    val addedPlaylists: Int = 0,
    val followedArtists: Int = 0,
    val skippedDuplicates: Int = 0,
    val error: String? = null,
) {
    val exact: Int get() = allMatches().count { it.confidence == MatchConfidence.EXACT }
    val doubtful: Int get() = allMatches().count { it.confidence == MatchConfidence.DOUBTFUL }
    val unmatched: Int get() = allMatches().count { it.confidence == MatchConfidence.NONE }
    val total: Int get() = allMatches().size

    fun allMatches(): List<ImportMatch> =
        (liked + playlists.flatMap { it.second } + albums.flatMap { it.second })
            .distinctBy { it.source.id }

    fun unmatchedList(): List<ImportMatch> = allMatches().filter { it.confidence == MatchConfidence.NONE }
    fun doubtfulList(): List<ImportMatch> = allMatches().filter { it.confidence == MatchConfidence.DOUBTFUL }
}

/**
 * Matches Yandex tracks to the app's catalogs.
 *
 * Three outcomes instead of a silent best guess:
 * - EXACT — same normalized title and artist, duration within a few seconds;
 * - DOUBTFUL — the title matches but performer or duration differ (a cover, a remix, a live take);
 * - NONE — nothing close, listed for the user instead of being dropped quietly.
 *
 * Duplicates are collapsed by recording key before they reach the library.
 */
object YandexImporter {

    private fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace("ё", "е")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]|feat\\..*$"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    private fun titleOf(song: SongInfo): String = normalize(song.songName)
    private fun artistsOf(song: SongInfo): List<String> = Wave.artistNames(song.singers).map(::normalize)

    fun score(source: YandexTrack, song: SongInfo): MatchConfidence {
        val wantedTitle = normalize(source.title)
        val wantedArtists = source.artists.map(::normalize).filter { it.isNotBlank() }
        val title = titleOf(song)
        val artists = artistsOf(song)
        if (wantedTitle.isBlank() || title.isBlank()) return MatchConfidence.NONE
        val titleEqual = title == wantedTitle
        val titleClose = titleEqual || title.contains(wantedTitle) || wantedTitle.contains(title)
        val artistMatch = wantedArtists.isEmpty() || wantedArtists.any { wanted ->
            artists.any { it == wanted || it.contains(wanted) || wanted.contains(it) }
        }
        val duration = if (source.durationSeconds > 0L && song.durationSeconds > 0L) {
            abs(source.durationSeconds - song.durationSeconds)
        } else 0L
        return when {
            titleEqual && artistMatch && duration <= 8L -> MatchConfidence.EXACT
            titleClose && artistMatch -> MatchConfidence.DOUBTFUL
            titleEqual && duration <= 8L -> MatchConfidence.DOUBTFUL
            titleClose && duration in 1L..40L -> MatchConfidence.DOUBTFUL
            else -> MatchConfidence.NONE
        }
    }

    fun best(source: YandexTrack, candidates: List<SongInfo>): ImportMatch {
        val scored = candidates.map { it to score(source, it) }
            .filter { it.second != MatchConfidence.NONE }
            .sortedWith(compareByDescending<Pair<SongInfo, MatchConfidence>> { it.second == MatchConfidence.EXACT }
                .thenByDescending { (song) ->
                    val duration = if (source.durationSeconds > 0L && song.durationSeconds > 0L)
                        -abs(source.durationSeconds - song.durationSeconds) else 0L
                    duration + if (song.hasValidDownloadUrl) 1L else 0L
                })
        val best = scored.firstOrNull() ?: return ImportMatch(source)
        return ImportMatch(
            source = source,
            song = best.first,
            confidence = best.second,
            note = when (best.second) {
                MatchConfidence.EXACT -> "точное совпадение"
                MatchConfidence.DOUBTFUL -> "проверьте: возможна другая версия"
                MatchConfidence.NONE -> ""
            },
        )
    }

    /**
     * Resolves every track with the caller's search. Bounded on both axes: at most [limit] tracks
     * and a per-track timeout are applied by the caller, so a big library cannot lock the import.
     */
    suspend fun match(
        library: YandexLibrary,
        search: suspend (YandexTrack) -> List<SongInfo>,
        limit: Int = 120,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): YandexImportReport {
        val likedSource = library.tracks.take(limit)
        val playlistTracks = library.playlists.flatMap { it.tracks }.distinctBy { it.id }.take(limit)
        val albumTracks = library.albums.flatMap { it.tracks }.distinctBy { it.id }.take(limit)
        val total = likedSource.size + playlistTracks.size + albumTracks.size
        var done = 0
        val cache = java.util.concurrent.ConcurrentHashMap<String, ImportMatch>()

        suspend fun resolve(track: YandexTrack): ImportMatch {
            cache[track.id]?.let { return it }
            val candidates = try { search(track) } catch (_: Exception) { emptyList() }
            val match = best(track, candidates)
            cache[track.id] = match
            done++
            onProgress(done, total)
            return match
        }

        val liked = likedSource.map { resolve(it) }
        val playlists = library.playlists.map { playlist ->
            playlist.title to playlist.tracks.distinctBy { it.id }.take(limit).map { resolve(it) }
        }
        val albums = library.albums.filter { it.tracks.isNotEmpty() }.map { album ->
            album.label to album.tracks.distinctBy { it.id }.take(limit).map { resolve(it) }
        }
        return YandexImportReport(
            account = library.account,
            liked = liked,
            playlists = playlists,
            artists = library.artists.take(60),
            albums = albums,
        )
    }
}
