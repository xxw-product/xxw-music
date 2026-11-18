package com.musicdl.core.source

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.SongInfo
import org.junit.Assert.*
import org.junit.Test

class TrackRefreshTest {
    private val original = SongInfo(source = "SoundCloudMusicClient", identifier = "123",
        songName = "Track", durationSeconds = 200, workDir = "saved-location", lyric = "Original lyrics")
    private val fresh = original.copy(downloadUrl = "https://example.com/fresh.mp3",
        downloadUrlStatus = DownloadUrlStatus(ok = true), workDir = "new-search", lyric = null)

    @Test fun `refresh keeps exact identity original directory and lyrics`() {
        val selected = matchingRefreshedTrack(original, listOf(fresh))!!
        assertEquals(fresh.downloadUrl, selected.downloadUrl)
        assertEquals(original.workDir, selected.workDir)
        assertEquals(original.lyric, selected.lyric)
    }

    @Test fun `refresh rejects same title from another ID or source`() {
        assertNull(matchingRefreshedTrack(original, listOf(fresh.copy(identifier = "456"), fresh.copy(source = "Other"))))
    }

    @Test fun `refresh rejects preview and dead links`() {
        assertNull(matchingRefreshedTrack(original, listOf(fresh.copy(durationSeconds = 30))))
        assertNull(matchingRefreshedTrack(original, listOf(fresh.copy(downloadUrlStatus = DownloadUrlStatus(ok = false)))))
    }
}
