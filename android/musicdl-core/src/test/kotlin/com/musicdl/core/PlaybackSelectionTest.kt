package com.musicdl.core

import com.musicdl.core.model.PlaybackSelection
import com.musicdl.core.model.SongInfo
import org.junit.Assert.*
import org.junit.Test

class PlaybackSelectionTest {
    private val songs = (1..3).map { SongInfo(source = "catalog", identifier = "$it", songName = "Track $it") }

    @Test fun `tapping a search result captures neighbours so next plays another track`() {
        val selected = PlaybackSelection.from(songs[1], songs)
        assertEquals(songs[2], selected.songs[selected.nextIndex()])
        assertEquals(songs[0], selected.songs[PlaybackSelection(selected.songs, 2).nextIndex()])
    }

    @Test fun `manual next advances even in repeat one mode while automatic end repeats`() {
        val selected = PlaybackSelection.from(songs[0], songs)
        assertEquals(1, selected.nextIndex(repeatOne = true))
        assertEquals(0, selected.nextIndex(automatic = true, repeatOne = true))
    }

    @Test fun `captured queue survives changing search results and unknown track gets its own queue`() {
        val results = songs.toMutableList()
        val selected = PlaybackSelection.from(songs[0], results)
        results.clear()
        assertEquals(songs, selected.songs)
        assertEquals(listOf(songs[0]), PlaybackSelection.from(songs[0], emptyList()).songs)
        assertEquals(-1, PlaybackSelection(emptyList(), -1).nextIndex())
    }
}
