package com.musicdl.core

import com.musicdl.core.lyrics.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricsPreferenceTest {
    @Test fun `plain text cannot win over available timings`() = runBlocking {
        val result = LyricsFetcher.firstAvailable(listOf(
            { Lyrics(plain = "Untimed text", provider = "plain") },
            { Thread.sleep(80); Lyrics(lines = listOf(LyricLine(1000, "Timed line")), provider = "synced") },
        ))
        assertEquals("synced", result?.provider)
    }
    @Test fun `plain text remains a fallback when timings do not exist`() = runBlocking {
        assertEquals("Plain", LyricsFetcher.firstAvailable(listOf({ null }, { Lyrics(plain = "Plain") }))?.plain)
    }
}
