package com.musicdl.android

import com.musicdl.core.model.SongInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveDiscoveryTest {
    private fun song(id: String, artist: String) =
        SongInfo(source = "DeezerMusicClient", identifier = id, singers = artist, songName = id, genre = "Rock")

    private fun profile(artists: Map<String, Int>, penalties: Penalties = Penalties()) =
        TasteProfile(genres = emptyMap(), artists = artists, history = emptyList(), penalties = penalties)

    @Test fun `discovery skips known and rejected artists and prefers repeated relations`() {
        val profile = profile(
            mapOf("Alpha" to 50, "Beta" to 30),
            Penalties(artists = mapOf("rejected" to 2f)),
        )
        val scores = Wave.discovery(
            mapOf(
                "Alpha" to listOf("Gamma", "Beta", "Rejected", "Delta"),
                "Beta" to listOf("Delta", "Alpha", "Epsilon"),
            ),
            profile,
        )
        assertFalse("alpha" in scores)
        assertFalse("beta" in scores)
        assertFalse("rejected" in scores)
        assertEquals("delta", scores.keys.first())
        assertTrue("gamma" in scores && "epsilon" in scores)
    }

    @Test fun `favourites exclude artists the listener keeps rejecting`() {
        val profile = profile(
            mapOf("Liked" to 40, "Hated" to 90),
            Penalties(artists = mapOf("hated" to 2.5f)),
        )
        assertEquals(listOf("Liked"), Wave.favoriteArtists(profile, 0, 5))
    }

    @Test fun `an artist rejected repeatedly never returns, discovery lifts new artists`() {
        val penalties = Penalties(artists = mapOf("hated" to 3.5f))
        val ranked = Wave.rank(
            listOf(song("1", "Hated"), song("2", "Fresh"), song("3", "Other")),
            emptyMap(), emptyMap(), penalties = penalties, discovery = mapOf("fresh" to 3f),
        )
        assertFalse(ranked.any { it.singers == "Hated" })
        assertEquals("Fresh", ranked.first().singers)
    }
}
