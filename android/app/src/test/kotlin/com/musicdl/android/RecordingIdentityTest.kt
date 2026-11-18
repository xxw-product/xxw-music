package com.musicdl.android

import com.musicdl.core.model.SongInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIdentityTest {

    private fun song(source: String, id: String, title: String, artist: String) = SongInfo(
        source = source,
        identifier = id,
        songName = title,
        singers = artist,
    )

    @Test
    fun `same song from two catalogs is the same recording`() {
        val deezer = song("DeezerMusicClient", "3135556", "Strobe", "deadmau5")
        val wave = song("NeteaseMusicClient", "4321", "Strobe", "Deadmau5")
        assertTrue(deezer.sameRecording(wave))
    }

    @Test
    fun `punctuation and case do not split a recording`() {
        val a = song("X", "1", "Don’t Stop Me Now", "Queen")
        val b = song("Y", "2", "Don't Stop Me Now!", "QUEEN")
        assertTrue(a.sameRecording(b))
    }

    @Test
    fun `different artists are different recordings`() {
        val a = song("X", "1", "Hello", "Adele")
        val b = song("Y", "2", "Hello", "Lionel Richie")
        assertFalse(a.sameRecording(b))
    }

    @Test
    fun `blank titles never match`() {
        assertFalse(sameRecording(null, "Artist", "", "Artist"))
        assertFalse(sameRecording("   ", null, "", null))
    }
}
