package com.musicdl.core

import com.musicdl.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ArtistIdentityTest {
    @Test fun `artist feed rejects title namesakes and partial names`() {
        assertFalse(ArtistIdentity.matches(SongInfo(songName = "Кино", singers = "Другой"), "Кино"))
        assertFalse(ArtistIdentity.matches(SongInfo(singers = "Кинотеатр"), "Кино"))
        assertFalse(ArtistIdentity.matches(SongInfo(singers = "NULL"), "Кино"))
        assertTrue(ArtistIdentity.matches(SongInfo(singers = "КИНО"), "Кино"))
        assertTrue(ArtistIdentity.matches(SongInfo(singers = "Артист feat. Другой"), "Другой"))
        assertTrue(ArtistIdentity.matches(SongInfo(singers = "AC/DC"), "AC/DC"))
    }
}
