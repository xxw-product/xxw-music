package com.musicdl.core

import com.musicdl.core.net.SpotifyCanvas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyCanvasTest {
    private val id = "3OHfY25tqY28d16oZczHc8"
    private fun payload(uri: String = "spotify:track:$id", url: String = "https://canvaz.scdn.co/video.mp4") =
        """{"canvasesList":[{"trackUri":"$uri","canvasUrl":"$url"}]}"""

    @Test fun `accepts both real upstream response and documented envelope`() {
        assertEquals("https://canvaz.scdn.co/video.mp4", SpotifyCanvas.videoUrl(payload(), id))
        assertEquals("https://canvaz.scdn.co/video.mp4", SpotifyCanvas.videoUrl("""{"data":${payload()}}""", id))
    }

    @Test fun `foreign tracks insecure links malformed and empty responses fall back to cover`() {
        assertNull(SpotifyCanvas.videoUrl(payload(uri = "spotify:track:another"), id))
        assertNull(SpotifyCanvas.videoUrl(payload(url = "http://example.com/video.mp4"), id))
        assertNull(SpotifyCanvas.videoUrl(payload(url = "https:///video.mp4"), id))
        assertNull(SpotifyCanvas.videoUrl("<html>upstream unavailable</html>", id))
        assertNull(SpotifyCanvas.videoUrl("""{"canvasesList":[]}""", id))
        assertNull(SpotifyCanvas.videoUrl(payload(), "invalid"))
    }
}
