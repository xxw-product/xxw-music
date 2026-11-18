package com.musicdl.core

import com.musicdl.core.lyrics.AdditionalLyricsSources
import com.musicdl.core.lyrics.LyricsFetcher
import com.musicdl.core.model.SongInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AdditionalLyricsSourcesTest {
    @Test fun `soundcloud description preserves actual timings without network`() = runBlocking {
        val lyric = LyricsFetcher.fetch(SongInfo(source = "SoundCloudMusicClient", songName = "Song",
            description = "[00:01.00]First line\n[00:03.20]Second line"))
        assertEquals(3200L, lyric?.lines?.last()?.timeMs)
    }

    @Test fun `embedded chinese lyrics are not used`() = runBlocking {
        assertNull(LyricsFetcher.fetch(SongInfo(source = "NeteaseMusicClient", songName = "Song", lyric = "[00:01]Text")))
    }
    @Test fun `matching rejects namesakes and accepts punctuation differences`() {
        assertTrue(AdditionalLyricsSources.matches("Hello!", "Adele", "Hello", "ADELE"))
        assertFalse(AdditionalLyricsSources.matches("Hello", "Adele", "Hello", "Other Artist"))
        assertFalse(AdditionalLyricsSources.matches("Hello", "Adele", "Hello live", "Adele"))
        assertFalse(AdditionalLyricsSources.matches("Hello", "", "Hello", "Adele"))
    }

    @Test fun `embedded lyrics are available without network`() = runBlocking {
        val lyrics = LyricsFetcher.fetch(SongInfo(source = "TestMusicClient", lyric = "[00:02.00]Line one", songName = "Song"))
        assertEquals("Test", lyrics?.provider)
        assertEquals(2000L, lyrics?.lines?.single()?.timeMs)
    }

    @Test fun `jiosaavn preserves html line breaks and ignores unrelated sources`() {
        val provider = AdditionalLyricsSources { _, _ -> """{"lyrics":"One<br>Two<br />Three"}""" }
        assertEquals("One\nTwo\nThree", provider.jioSaavn(SongInfo(source = "JioSaavnMusicClient", identifier = "id"))?.plain)
        assertNull(provider.jioSaavn(SongInfo(source = "Other")))
        assertNull(AdditionalLyricsSources.textLyrics("<html>Error</html>", "test"))
        assertNull(AdditionalLyricsSources.textLyrics("NULL", "test"))
    }
}
