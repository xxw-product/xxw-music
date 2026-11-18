package com.musicdl.android

import com.musicdl.core.model.SongInfo
import com.musicdl.core.transfer.YandexTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TastePenaltyTest {

    private fun song(id: String, artist: String, genre: String? = "Rock", title: String = id) =
        SongInfo(source = "DeezerMusicClient", identifier = id, singers = artist, songName = title, genre = genre)

    @Test fun `a dislike is a track penalty and it decays over time`() {
        val now = 1_800_000_000_000L
        val disliked = song("1", "Some artist")
        val fresh = ListeningSignal(disliked, dislikes = 1, updatedAt = now)
        val old = ListeningSignal(disliked, dislikes = 1, updatedAt = now - 120L * 86_400_000L)
        assertTrue(Wave.penaltyOf(fresh, now) > Wave.penaltyOf(old, now))
        assertTrue(Wave.penaltyOf(fresh, now) > 1f)
    }

    @Test fun `skips propagate to the artist and the genre`() {
        val now = System.currentTimeMillis()
        val skipped = ListeningSignal(song("1", "Loud Band", "Metal"), skips = 3, updatedAt = now)
        val penalties = Wave.penalties(listOf(skipped), now)
        assertTrue((penalties.artists["loud band"] ?: 0f) > 0f)
        assertTrue((penalties.genres["metal"] ?: 0f) > 0f)
        val sibling = song("2", "Loud Band", "Metal")
        val unrelated = song("3", "Quiet Artist", "Jazz")
        assertTrue(Wave.penaltyFor(sibling, penalties) > Wave.penaltyFor(unrelated, penalties))
    }

    @Test fun `a disliked track does not return to the wave`() {
        val disliked = song("bad", "Band")
        val now = System.currentTimeMillis()
        val history = listOf(ListeningSignal(disliked, dislikes = 2, updatedAt = now))
        val ranked = Wave.rank(listOf(disliked, song("ok", "Other")), emptyMap(), emptyMap(), history,
            penalties = Wave.penalties(history, now))
        assertFalse(ranked.any { it.identifier == "bad" })
    }

    @Test fun `positive signals in the same window keep a track recommendable`() {
        val track = song("good", "Band")
        val now = System.currentTimeMillis()
        val history = listOf(ListeningSignal(track, plays = 12, likes = 1, updatedAt = now))
        assertTrue(Wave.penalties(history, now).tracks.isEmpty())
        assertEquals(2, Wave.rank(listOf(track, song("second", "Other")), emptyMap(), emptyMap(), history).size)
    }

    @Test fun `yandex matching separates exact from doubtful and missing`() {
        val wanted = YandexTrack("1", "Song", listOf("Artist"), durationSeconds = 200)
        val exact = song("a", "Artist", title = "Song").copy(durationSeconds = 201)
        val cover = song("b", "Other Artist", title = "Song").copy(durationSeconds = 260)
        val unrelated = song("c", "Nobody", title = "Different")

        assertEquals(MatchConfidence.EXACT, YandexImporter.score(wanted, exact))
        assertEquals(MatchConfidence.DOUBTFUL, YandexImporter.score(wanted, cover))
        assertEquals(MatchConfidence.NONE, YandexImporter.score(wanted, unrelated))

        val best = YandexImporter.best(wanted, listOf(cover, exact))
        assertEquals(MatchConfidence.EXACT, best.confidence)
        assertEquals("a", best.song?.identifier)
    }

    @Test fun `import report counts doubtful and unmatched separately`() {
        val yandex = YandexTrack("1", "Song", listOf("Artist"))
        val report = YandexImportReport(
            account = "user",
            liked = listOf(
                ImportMatch(yandex, song("a", "Artist"), MatchConfidence.EXACT),
                ImportMatch(yandex.copy(id = "2"), song("b", "Artist"), MatchConfidence.DOUBTFUL),
                ImportMatch(yandex.copy(id = "3"), null, MatchConfidence.NONE),
            ),
        )
        assertEquals(1, report.exact)
        assertEquals(1, report.doubtful)
        assertEquals(1, report.unmatched)
        assertEquals(3, report.total)
    }
}
