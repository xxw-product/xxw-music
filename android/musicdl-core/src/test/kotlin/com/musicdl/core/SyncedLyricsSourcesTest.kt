package com.musicdl.core

import com.musicdl.core.lyrics.*
import com.musicdl.core.model.SongInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SyncedLyricsSourcesTest {
    @Test fun `both soundcloud title orders preserve original and recover real artist`() {
        val queries = LyricQueries.forSong(SongInfo(songName = "Miura - BATO", singers = "Uploader"))
        assertTrue(queries.contains(LyricQuery("Miura", "BATO")))
        assertTrue(LyricQueries.forSong(SongInfo(songName = "BATO — Miura (Official Audio)")).contains(LyricQuery("Miura", "BATO")))
        assertTrue(LyricQueries.matches("Pain & Gain", "BATO", "Pain & Gain", "BATO, SALUKI"))
        assertFalse(LyricQueries.matches("Miura", "BATO", "Miura 2", "BATO"))
        assertFalse(LyricQueries.matches("Miura", "BATO", "Miura", "Vegas Jones"))
    }

    @Test fun `TTML timestamps come from source including inline word spans`() {
        val lyrics = SyncedLyricsSources.parseTtml("""<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
          <p begin="00:00:12.357" end="00:00:15.000"><span>First </span><span>line</span></p>
          <p begin="15.5s">Second line</p><p>Untimed</p>
        </div></body></tt>""")!!
        assertEquals(listOf(12357L, 15500L), lyrics.lines.map { it.timeMs })
        assertEquals("First line", lyrics.lines[0].text)
        assertNull(SyncedLyricsSources.parseTtml("<tt><p>Not timed</p></tt>"))
    }

    @Test fun `index rejects wrong song duration and foreign URLs`() {
        val requests = mutableListOf<String>()
        val source = SyncedLyricsSources { url, _ ->
            requests += url
            """{"results":[
              {"track_name":"Miura 2","artist_name":"BATO","duration":88,"lyricsUrl":"https://lrc.red/wrong.ttml"},
              {"track_name":"Miura","artist_name":"BATO","duration":104,"lyricsUrl":"https://evil.example/test.ttml"}
            ]}"""
        }
        assertNull(source.bini(LyricQuery("Miura", "BATO"), SongInfo(durationSeconds = 104)))
        assertEquals(1, requests.size)
    }

    @Test fun `live BATO examples return real synchronized lines`() = runBlocking {
        assumeTrue(System.getenv("MUSICDL_LIVE_LYRICS") == "1")
        for ((title, artist, duration) in listOf(Triple("Miura", "BATO", 104L), Triple("Pain & Gain", "BATO, SALUKI", 160L))) {
            val lyrics = LyricsFetcher.fetch(SongInfo(songName = title, singers = artist, durationSeconds = duration))
            assertTrue("$artist - $title must have real timings", lyrics?.hasTimings == true)
            assertTrue(lyrics!!.lines.size >= 10)
            assertTrue(lyrics.lines.last().timeMs <= duration * 1000 + 2000)
            println("LIVE: $artist - $title: ${lyrics.lines.size} timed lines, ${lyrics.provider}")
        }
    }
}
