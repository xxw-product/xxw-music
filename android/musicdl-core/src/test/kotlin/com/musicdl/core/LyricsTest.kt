package com.musicdl.core

import com.musicdl.core.lyrics.LyricLine
import com.musicdl.core.lyrics.LyricsFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC parsing: the timings are the whole point of the lyrics sheet, so they are pinned here. */
class LyricsTest {

    @Test
    fun `parseLrc reads timestamps and sorts them`() {
        val lrc = """
            [ar:Nirvana]
            [00:38.10]It's fun to lose and to pretend
            [00:33.50]Load up on guns, bring your friends
            [00:41]She's over-bored, and self-assured
        """.trimIndent()

        val lines = LyricsFetcher.parseLrc(lrc)

        assertEquals(3, lines.size)
        assertEquals(LyricLine(33_500, "Load up on guns, bring your friends"), lines[0])
        assertEquals(38_100, lines[1].timeMs)
        assertEquals(41_000, lines[2].timeMs)
    }

    @Test
    fun `parseLrc handles repeated stamps on one line`() {
        val lines = LyricsFetcher.parseLrc("[00:10.00][01:10.50]Hello")

        assertEquals(2, lines.size)
        assertEquals(10_000, lines[0].timeMs)
        assertEquals(70_500, lines[1].timeMs)
        assertTrue(lines.all { it.text == "Hello" })
    }

    @Test
    fun `parseLrc ignores metadata, blank text and null input`() {
        assertTrue(LyricsFetcher.parseLrc("[by:someone]\n[00:05.00]   \n").isEmpty())
        assertTrue(LyricsFetcher.parseLrc(null).isEmpty())
        assertTrue(LyricsFetcher.parseLrc("").isEmpty())
    }

    @Test
    fun `single digit fractions scale to milliseconds`() {
        assertEquals(62_500, LyricsFetcher.parseLrc("[01:02.5]x").single().timeMs)
        assertEquals(62_050, LyricsFetcher.parseLrc("[01:02.05]x").single().timeMs)
    }
}
