package com.musicdl.android

import com.musicdl.core.model.SongInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveRelevanceTest {

    private fun song(
        id: String,
        artist: String,
        title: String = "Track $id",
        genre: String? = "Rock",
        tags: String? = null,
    ) = SongInfo(
        source = "DeezerMusicClient",
        identifier = id,
        singers = artist,
        songName = title,
        genre = genre,
        tags = tags,
    )

    private fun profile(vararg songs: SongInfo) =
        Wave.profile(emptyMap(), songs.toList(), emptyList(), emptyList())

    @Test
    fun `artist query only accepts that artist`() {
        assertTrue(Wave.matchesArtist("Кино", "Кино"))
        assertTrue(Wave.matchesArtist("Nirvana feat. Dave Grohl", "Nirvana"))
        assertTrue(Wave.matchesArtist("The Weeknd, Daft Punk", "Daft Punk"))
    }

    @Test
    fun `a song named after the artist is not that artist`() {
        val impostor = song("1", artist = "Some Band", title = "Кино")
        assertFalse(Wave.matchesArtist(impostor.singers, "Кино"))
    }

    @Test
    fun `genre seed requires genre metadata`() {
        val genre = Genres.byId.getValue("rock")
        assertTrue(Wave.matchesGenre(song("1", "A", genre = "Alternative Rock"), genre))
        assertFalse(Wave.matchesGenre(song("2", "A", genre = "Jazz"), genre))
        assertFalse(Wave.matchesGenre(song("3", "A", genre = null), genre))
    }

    @Test
    fun `bollywood tracks stay out of a rock library`() {
        val library = profile(song("r", "Rock artist", genre = "Rock"))
        val bollywood = song("b", "Arijit Singh", title = "Tum Hi Ho", genre = "Bollywood")
        assertTrue(Wave.isForeignCulture(bollywood, library))
    }

    @Test
    fun `indic script is rejected only when the library has none`() {
        val devanagari = song("d", "कलाकार", title = "गाना", genre = "Pop")
        assertTrue(Wave.isForeignCulture(devanagari, profile(song("r", "Artist", genre = "Pop"))))
        assertFalse(Wave.isForeignCulture(devanagari, profile(devanagari)))
    }

    @Test
    fun `a library that listens to indian music keeps receiving it`() {
        val indian = song("i", "Arijit Singh", genre = "Bollywood")
        val library = profile(indian)
        assertTrue("indian" in Wave.cultureMarkers(indian))
        assertEquals(setOf("indian"), Wave.cultureMarkers(indian))
        assertFalse(Wave.isForeignCulture(indian.copy(identifier = "i2"), library))
    }

    @Test
    fun `qobuz genre results are only kept for known artists`() {
        val library = profile(song("1", "Known artist", genre = "Rock"))
        val genreSeed = WaveSeed(WaveSeedKind.GENRE, "rock", genre = Genres.byId.getValue("rock"))
        val stranger = song("2", "Unknown band", genre = "Rock").copy(source = "QobuzMusicClient")
        val known = song("3", "Known artist", genre = "Rock").copy(source = "QobuzMusicClient")
        assertFalse(Wave.acceptsSeedResult(stranger, genreSeed, library, library.artists.keys, allowGlobalCatalog = false))
        assertTrue(Wave.acceptsSeedResult(known, genreSeed, library, library.artists.keys, allowGlobalCatalog = false))
    }

    @Test
    fun `qobuz is allowed for an exact artist search`() {
        val library = profile(song("1", "Nirvana", genre = "Rock"))
        val seed = WaveSeed(WaveSeedKind.ARTIST, "Nirvana", artist = "Nirvana")
        val hit = song("2", "Nirvana", genre = "Rock").copy(source = "QobuzMusicClient")
        val miss = song("3", "Nirvana Tribute Band", genre = "Rock").copy(source = "QobuzMusicClient")
        assertTrue(Wave.acceptsSeedResult(hit, seed, library, library.artists.keys, allowGlobalCatalog = true))
        assertFalse(Wave.acceptsSeedResult(miss, seed, library, library.artists.keys, allowGlobalCatalog = true))
    }

    @Test
    fun `relaxed stage still rejects foreign music`() {
        val library = profile(song("1", "Rock artist", genre = "Rock"))
        val base = WaveSeed(WaveSeedKind.BASE, "top hits")
        val hindi = song("2", "Arijit Singh", title = "Tum Hi Ho", genre = "Bollywood")
        assertFalse(Wave.acceptsSeedResult(hindi, base, library, emptyList(), true, relaxed = true))
        val neutral = song("3", "Unknown", genre = null)
        assertTrue(Wave.acceptsSeedResult(neutral, base, library, emptyList(), true, relaxed = true))
    }

    @Test
    fun `seeds keep their intent`() {
        val library = profile(song("1", "Rock artist", genre = "Rock"))
        val artistSeeds = Wave.artistSeeds(library, generation = 1)
        val genreSeeds = Wave.genreSeeds(library, generation = 1)
        assertTrue(artistSeeds.isNotEmpty() && artistSeeds.all { it.kind == WaveSeedKind.ARTIST && it.artist != null })
        assertTrue(genreSeeds.isNotEmpty() && genreSeeds.all { it.kind == WaveSeedKind.GENRE && it.genre != null })
        assertTrue(genreSeeds.none { seed -> artistSeeds.any { it.query == seed.query } })
    }
}
