package com.musicdl.core

import com.musicdl.core.model.ArtworkUrls
import org.junit.Assert.*
import org.junit.Test

class ArtworkUrlsTest {
    @Test fun `soundcloud gets high quality with original retained as fallback`() {
        val url = "https://i1.sndcdn.com/artworks-123-large.jpg"
        assertEquals(listOf("https://i1.sndcdn.com/artworks-123-t500x500.jpg", url), ArtworkUrls.candidates(url))
        assertEquals(listOf("https://example.com/150x150.jpg"), ArtworkUrls.candidates("https://example.com/150x150.jpg"))
    }
}
