package com.musicdl.android

import com.musicdl.core.model.SongInfo
import org.junit.Assert.*
import org.junit.Test

class WaveTest {
    private fun song(id: String, artist: String, genre: String? = "Rock", title: String = id) =
        SongInfo(source = "DeezerMusicClient", identifier = id, singers = artist, songName = title, genre = genre)

    @Test fun `title and artist cannot invent a genre`() {
        assertEquals(Genres.other, Wave.inferGenre(song("1", "BTS", null, "Pop Rock Star")))
    }

    @Test fun `short tags do not match inside unrelated words`() {
        assertEquals(Genres.other, Wave.inferGenre(song("1", "Artist", "Alternative photography")))
    }

    @Test fun `every artist contributes search seeds`() {
        val queries = Wave.buildQueries(emptyMap(), mapOf("First" to 100, "Second" to 2, "Third" to 1))
        listOf("First", "Second", "Third").forEach { artist -> assertTrue(queries.any { it.contains(artist) }) }
    }

    @Test fun `a prolific favourite cannot monopolize a wave`() {
        val candidates = (1..20).map { song("a$it", "Favourite") } +
            (1..12).map { song("b$it", "Artist $it") }
        val ranked = Wave.rank(candidates, emptyMap(), mapOf("Favourite" to 500))
        assertTrue(ranked.count { it.singers == "Favourite" } <= 2)
        assertTrue(ranked.take(10).map { it.singers }.distinct().size >= 8)
    }

    @Test fun `wave can contain more than forty tracks`() {
        val candidates = (1..70).map { song("track-$it", "Artist $it") }
        val ranked = Wave.rank(candidates, emptyMap(), emptyMap())
        assertEquals(70, ranked.size)
    }

    @Test fun `same recording across providers appears once`() {
        val original = song("1", "Artist", title = "Song")
        val duplicate = original.copy(source = "QobuzMusicClient", identifier = "2")
        assertEquals(1, Wave.rank(listOf(original, duplicate), emptyMap(), emptyMap()).size)
    }

    @Test fun `file size cannot define musical preference`() {
        val known = song("known", "Known", "Jazz")
        val large = song("large", "Unknown", "Metal").copy(fileSizeBytes = 200_000_000)
        assertEquals(known, Wave.rank(listOf(large, known), mapOf("jazz" to 1f, "metal" to 0.7f), emptyMap()).first())
    }

    @Test fun `listening and library both shape taste without like toggle inflation`() {
        val now = 1_800_000_000_000L
        val rock = song("r", "Rock artist", "Rock")
        val jazz = song("j", "Jazz artist", "Jazz")
        val profile = Wave.profile(emptyMap(), listOf(rock, rock),
            listOf(ListeningSignal(jazz, plays = 25, lastPlayedAt = now)), listOf("Followed"), now)
        assertTrue(profile.genres.getValue("jazz") > profile.genres.getValue("rock"))
        assertEquals(30, profile.artists["Rock artist"])
        assertTrue(profile.artists.containsKey("Followed"))
    }

    @Test fun `rotation eventually includes every artist in a large library`() {
        val artists = (1..25).associate { "Artist $it" to it }
        val queries = (0..5).flatMap { Wave.buildQueries(emptyMap(), artists, it) }.toSet()
        assertTrue(queries.containsAll(artists.keys))
    }

    @Test fun `blocked genres and repeatedly skipped recordings are excluded`() {
        val blocked = song("metal", "Metal artist", "Metal")
        val skipped = song("skip", "Skip artist", "Jazz")
        val allowed = song("ok", "Good artist", "Rock")
        val ranked = Wave.rank(listOf(blocked, skipped, allowed), mapOf("metal" to 0f), emptyMap(),
            listOf(ListeningSignal(skipped, plays = 0, skips = 3)))
        assertEquals(listOf(allowed), ranked)
    }

    @Test fun `recent results lose priority to equally relevant new recordings`() {
        val old = song("old", "Old artist")
        val fresh = song("fresh", "New artist")
        assertEquals(fresh, Wave.rank(listOf(old, fresh), emptyMap(), emptyMap(), recent = setOf(Wave.recordingKey(old))).first())
    }

    @Test fun `disabled legacy selections migrate to supported providers`() {
        assertEquals(RECOMMENDED_SOURCES, supportedSources(setOf("SpotifyMusicClient")))
        assertEquals(setOf("DeezerMusicClient"), supportedSources(setOf("DeezerMusicClient", "KuwoMusicClient")))
    }

    @Test fun `a jazz profile explores adjacent styles rather than unrelated metal`() {
        val profile = Wave.profile(emptyMap(), listOf(song("j", "Jazz artist", "Jazz")), emptyList(), emptyList())
        val queries = Wave.buildQueries(profile.genres, profile.artists)
        assertTrue(queries.contains(Genres.byId.getValue("jazz").query))
        assertFalse(queries.contains(Genres.byId.getValue("metal").query))
    }
}
