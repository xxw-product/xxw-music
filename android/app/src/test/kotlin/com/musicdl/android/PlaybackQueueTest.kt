package com.musicdl.android

import com.musicdl.core.model.SongInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackQueueTest {

    private fun song(id: String, artist: String = "Artist") =
        SongInfo(source = "DeezerMusicClient", identifier = id, songName = "Song $id", singers = artist)

    private fun queue(vararg ids: String) = PlaybackQueue(
        songs = ids.map { song(it) },
        index = 0,
        random = Random(7),
    )

    @Test fun `back returns the track that actually played, not another random one`() {
        val queue = queue("a", "b", "c", "d")
        queue.setShuffle(true)
        val first = queue.current!!
        val second = queue.next()!!
        queue.markPlayed(first)
        queue.markPlayed(second)
        val back = queue.previous()
        assertEquals(first, back)
        assertNotEquals(second, back)
    }

    @Test fun `shuffle walks one order instead of re-randomizing every press`() {
        val queue = queue("a", "b", "c", "d", "e", "f")
        queue.setShuffle(true)
        val walked = mutableListOf(queue.current!!)
        repeat(5) { queue.next()?.let(walked::add) }
        // Six presses walk the whole shuffled order exactly once before wrapping.
        assertEquals(6, walked.distinct().size)
        assertEquals(queue.items.map { it.identifier }.toSet(), walked.map { it.identifier }.toSet())
    }

    @Test fun `play next inserts directly after the current track`() {
        val queue = queue("a", "b", "c")
        queue.playAt(0)
        val extra = song("x")
        queue.insertNext(extra)
        assertEquals(listOf("a", "x", "b", "c"), queue.items.map { it.identifier })
        assertEquals(extra, queue.next())
    }

    @Test fun `history survives switching to another playlist`() {
        val queue = queue("a", "b")
        queue.playAt(0); queue.markPlayed(song("a"))
        queue.playAt(1); queue.markPlayed(song("b"))
        queue.setQueue(listOf(song("other1"), song("other2")), 0)
        val back = queue.previous()
        assertEquals("b", back?.identifier)
        assertTrue(queue.items.any { it.identifier == "b" })
    }

    @Test fun `removing the playing track keeps the cursor on a real item`() {
        val queue = queue("a", "b", "c")
        queue.playAt(1)
        queue.removeAt(1)
        assertEquals(listOf("a", "c"), queue.items.map { it.identifier })
        assertTrue(queue.index in queue.items.indices)
    }

    @Test fun `drag and drop reorder moves the cursor with its track`() {
        val queue = queue("a", "b", "c")
        queue.playAt(2)
        assertTrue(queue.move(2, 0))
        assertEquals("c", queue.current?.identifier)
        assertEquals(0, queue.index)
        assertEquals(listOf("c", "a", "b"), queue.items.map { it.identifier })
    }

    @Test fun `shuffle off restores the user's order`() {
        val queue = queue("a", "b", "c", "d")
        val natural = queue.items.map { it.identifier }
        queue.setShuffle(true)
        queue.setShuffle(false)
        assertEquals(natural, queue.items.map { it.identifier })
    }

    @Test fun `clearing leaves nothing to advance to`() {
        val queue = queue("a")
        queue.clear()
        assertTrue(queue.isEmpty)
        assertEquals(null, queue.next())
        assertFalse(queue.historyItems.isNotEmpty())
    }
}
