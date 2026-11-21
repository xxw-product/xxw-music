package com.musicdl.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistNamesTest {
    @Test
    fun commaSeparatedCreditsBecomeSeparateArtists() {
        assertEquals(listOf("Баста", "Guf"), ArtistNames.split("Баста, Guf"))
        assertEquals(listOf("VILLIAN", "madk1d"), ArtistNames.split("VILLIAN,madk1d"))
        assertEquals(listOf("Drake", "Rihanna"), ArtistNames.split("Drake feat. Rihanna"))
        assertEquals(listOf("A", "B"), ArtistNames.split("A; B"))
    }

    @Test
    fun singleNamesAndJunkAreHandled() {
        assertEquals(listOf("Simon & Garfunkel"), ArtistNames.split("Simon & Garfunkel"))
        assertEquals(listOf("AC/DC"), ArtistNames.split("AC/DC"))
        assertEquals(emptyList<String>(), ArtistNames.split(null))
        assertEquals(emptyList<String>(), ArtistNames.split("NULL"))
        assertEquals(listOf("A"), ArtistNames.split("A, a"))
    }
}
