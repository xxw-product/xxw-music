package com.musicdl.core

import com.musicdl.core.source.AudiusMusicClient
import com.musicdl.core.source.MusicClientConfig
import com.musicdl.core.source.WikimediaCommonsMusicClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSourcesTest {
    @Test
    fun commonsSearchKeepsAudioAndUsesFileMetadata() = withServer { server ->
        val audio = server.url("/prelude.ogg")
        server.enqueue(
            json(
                """
                {"query":{"pages":[
                    {"pageid":7,"title":"File:Prelude.ogg","imageinfo":[
                        {"url":"$audio","mime":"audio/ogg","duration":64,"size":2048,
                         "extmetadata":{"Artist":{"value":"J. S. Bach"}}}]},
                    {"pageid":8,"title":"File:Photo.jpg","imageinfo":[
                        {"url":"${server.url("/photo.jpg")}","mime":"image/jpeg"}]}
                ]}}
                """.trimIndent(),
            ),
        )
        val client = WikimediaCommonsMusicClient(MusicClientConfig(), server.url("/w/api.php").toString())
        try {
            val song = runBlocking { client.search("Bach & friends") }.single()
            assertEquals("Prelude", song.songName)
            assertEquals("J. S. Bach", song.singers)
            assertEquals("7", song.identifier)
            assertEquals(2048L, song.fileSizeBytes)
            assertEquals("ogg", song.ext)
            assertEquals(audio.toString(), song.downloadUrl)
            assertEquals("Bach & friends filetype:audio", server.takeRequest().requestUrl?.queryParameter("gsrsearch"))
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun audiusPlaylistResolvesAndReturnsDownloadableTracks() = withServer { server ->
        server.enqueue(json("""{"data":{"id":"P123","playlist_name":"My mix"}}"""))
        server.enqueue(json("""{"data":[{"id":"T456","title":"Ocean","duration":90,"user":{"name":"Artist"}}]}"""))
        server.enqueue(MockResponse().addHeader("Content-Type", "audio/mpeg").addHeader("Content-Length", 2048))
        val client = AudiusMusicClient(MusicClientConfig(), server.url("/v1/").toString())
        try {
            val track = client.parsePlaylist("https://audius.co/artist/playlist/my-mix").single()
            assertEquals("Ocean", track.songName)
            assertEquals("Artist", track.singers)
            assertEquals("mp3", track.ext)
            assertTrue(track.workDir.endsWith("My mix"))
            assertEquals("/v1/resolve", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/v1/playlists/P123/tracks", server.takeRequest().requestUrl?.encodedPath)
            assertEquals("/v1/tracks/T456/stream", server.takeRequest().requestUrl?.encodedPath)
        } finally {
            client.close()
        }
    }

    private fun json(body: String) = MockResponse().addHeader("Content-Type", "application/json").setBody(body)

    private fun withServer(test: (MockWebServer) -> Unit) = MockWebServer().use { server ->
        server.start()
        test(server)
    }
}
