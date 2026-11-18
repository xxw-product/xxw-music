package com.musicdl.core

import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.MusicClient
import com.musicdl.core.source.MusicClientConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MusicDownloadTest {
    @get:Rule val output = TemporaryFolder()
    @get:Rule val server = MockWebServer()

    private fun song(path: String, protocol: String = "HTTP") = SongInfo(
        songName = "Track",
        identifier = "42",
        ext = "mp3",
        workDir = output.root.absolutePath,
        downloadUrl = server.url(path).toString(),
        protocol = protocol,
    )

    private fun download(song: SongInfo): File = runBlocking {
        val client = MusicClient(baseConfig = MusicClientConfig(workDir = output.root.absolutePath, maxRetries = 1))
        try {
            client.download(song)
        } finally {
            client.close()
        }
    }

    private fun body(content: String) = MockResponse().setBody(content)

    @Test
    fun `configured output directory overrides restored relative track directory`() = runBlocking {
        server.enqueue(body("music"))
        val client = MusicClient(baseConfig = MusicClientConfig(workDir = output.root.absolutePath, maxRetries = 1))
        try {
            val saved = client.download(song("/audio").copy(workDir = "musicdl_outputs"))
            assertEquals(output.root.canonicalFile, saved.parentFile.canonicalFile)
            assertEquals("music", saved.readText())
        } finally { client.close() }
    }

    @Test
    fun `download follows redirected master and saves the best variant's audio rendition in order`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/new/index.m3u8"))
        server.enqueue(body("""
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="First, no default",URI="no.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Default",DEFAULT=YES,URI="rendition/media.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=100000
            low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200000,AUDIO="audio"
            high.m3u8
        """.trimIndent()))
        server.enqueue(body("""
            #EXTM3U
            #EXT-X-TARGETDURATION:3
            #EXTINF:3,
            one.aac?token=abc
            #EXTINF:3,
            ../two.aac
            #EXT-X-ENDLIST
        """.trimIndent()))
        server.enqueue(body("first"))
        server.enqueue(body("second"))

        val saved = download(song("/old/start.M3U8?sign=x").copy(
            downloadHeaders = mapOf("Referer" to "https://example.com/"),
            downloadCookies = mapOf("session" to "fixture"),
        ))

        assertEquals("Track - 42.aac", saved.name)
        assertEquals("firstsecond", saved.readText())
        val requests = List(5) { server.takeRequest() }
        assertEquals(listOf(
            "/old/start.M3U8?sign=x", "/new/index.m3u8", "/new/rendition/media.m3u8",
            "/new/rendition/one.aac?token=abc", "/new/two.aac",
        ), requests.map { it.path })
        assertTrue(requests.all { it.getHeader("Referer") == "https://example.com/" })
        assertTrue(requests.all { it.getHeader("Cookie") == "session=fixture" })
    }

    @Test
    fun `download writes a fragmented MP4 initialization before its media fragments`() {
        server.enqueue(body("""
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:2
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2,
            one.m4s
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2,
            two.m4s
            #EXT-X-ENDLIST
        """.trimIndent()).setHeader("Content-Type", "application/vnd.apple.mpegurl"))
        server.enqueue(body("init"))
        server.enqueue(body("one"))
        server.enqueue(body("two"))

        val saved = download(song("/extensionless"))

        assertEquals("Track - 42.mp4", saved.name)
        assertEquals("initonetwo", saved.readText())
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `unsupported HLS features fail before downloading any segments`() {
        for ((feature, tag) in listOf(
            "encryption" to "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"",
            "byte ranges" to "#EXT-X-BYTERANGE:8@0",
            "discontinuities" to "#EXT-X-DISCONTINUITY",
        )) {
            server.enqueue(body("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n$tag\npart.aac\n#EXT-X-ENDLIST"))
            val error = assertThrows(IOException::class.java) { download(song("/manifest", "HLS")) }
            assertTrue(error.message.orEmpty().contains(feature))
        }
        server.enqueue(body("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\npart.aac"))
        val error = assertThrows(IOException::class.java) { download(song("/manifest", "HLS")) }
        assertTrue(error.message.orEmpty().contains("live"))
        assertEquals(4, server.requestCount)
        assertTrue(output.root.list().orEmpty().isEmpty())
    }

    @Test
    fun `failed segments leave existing files intact and no partial output`() {
        val existing = File(output.root, "Track - 42.aac").apply { writeText("existing") }
        server.enqueue(body("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\none.aac\n#EXTINF:2,\ntwo.aac\n#EXT-X-ENDLIST"))
        server.enqueue(body("first"))
        server.enqueue(MockResponse().setResponseCode(404))

        assertThrows(IOException::class.java) { download(song("/media.m3u8")) }
        assertEquals("existing", existing.readText())
        assertEquals(listOf(existing), output.root.listFiles().orEmpty().toList())
    }

    @Test
    fun `qobuz saves genuine FLAC and forwards required headers`() {
        server.enqueue(body("fLaCfixture").setHeader("Content-Type", "audio/flac"))
        val saved = download(song("/flac").copy(source = "QobuzMusicClient", ext = "flac",
            downloadHeaders = mapOf("X-API-Key" to "fixture-key")))
        assertEquals("fLaCfixture", saved.readText())
        assertTrue(saved.name.endsWith(".flac"))
        assertEquals("fixture-key", server.takeRequest().getHeader("X-API-Key"))
    }

    @Test
    fun `qobuz rejects error bodies without publishing a file`() {
        server.enqueue(body("{\"error\":\"unavailable\"}").setHeader("Content-Type", "audio/flac"))
        assertThrows(IOException::class.java) {
            download(song("/flac").copy(source = "QobuzMusicClient", ext = "flac"))
        }
        assertTrue(output.root.list().orEmpty().isEmpty())
    }

    @Test
    fun `direct HTTP downloads still save using the requested extension and deduplicate`() {
        val existing = File(output.root, "Track - 42.mp3").apply { writeText("existing") }
        server.enqueue(body("music"))

        val saved = download(song("/audio"))

        assertEquals("Track - 42 (1).mp3", saved.name)
        assertEquals("music", saved.readText())
        assertEquals("existing", existing.readText())
    }
}
