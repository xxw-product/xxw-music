package com.musicdl.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoresTest {

    @Test
    fun `qobuz cover url is upgraded to its largest variant`() {
        assertEquals(
            "https://static.qobuz.com/images/covers/ab/cd/abcdefg_max.jpg",
            ArtworkSources.upgrade("https://static.qobuz.com/images/covers/ab/cd/abcdefg_600.jpg"),
        )
    }

    @Test
    fun `itunes artwork size segment is replaced with 1200`() {
        assertEquals(
            "https://is1.mzstatic.com/image/thumb/x/1200x1200bb.jpg",
            ArtworkSources.upgrade("https://is1.mzstatic.com/image/thumb/x/100x100bb.jpg"),
        )
    }

    @Test
    fun `foreign urls are left untouched and no url means no artwork`() {
        assertEquals("https://cdn.example/cover.png", ArtworkSources.upgrade("https://cdn.example/cover.png"))
        assertNull(ArtworkSources.upgrade(null))
        assertNull(ArtworkSources.upgrade("file:/data/user/0/com.musicdl.android/files/a.webp"))
    }

    @Test
    fun `lrc lines use minutes seconds and centiseconds`() {
        assertEquals("[00:00.00] Intro", LyricsStore.formatLrc(0, "Intro"))
        assertEquals("[01:02.35] Verse", LyricsStore.formatLrc(62_345, "Verse"))
    }
}
