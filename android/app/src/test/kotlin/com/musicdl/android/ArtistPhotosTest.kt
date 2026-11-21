package com.musicdl.android

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistPhotosTest {
    @Test
    fun creditsFallBackToTheFirstArtist() {
        assertEquals(listOf("Drake"), ArtistPhotos.candidates("Drake"))
        assertEquals(listOf("Drake feat. Rihanna", "Drake"), ArtistPhotos.candidates("Drake feat. Rihanna"))
        assertEquals(listOf("A, B", "A"), ArtistPhotos.candidates("A, B"))
    }
}
