package com.musicdl.core

import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.source.BaseMusicClient
import com.musicdl.core.source.MusicClientConfig
import com.musicdl.core.source.NoSearchProgress
import com.musicdl.core.source.SearchProgress
import com.musicdl.core.util.JsonUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end test of the ported search pipeline against a local server.
 *
 * Proves the whole chain: constructSearchUrls -> HTTP -> JSON parse -> SongInfo
 * mapping -> AudioLinkTester validation -> dedup -> work-dir stamping.
 * That is exactly what the Python `BaseMusicClient.search` does.
 *
 * Routing is path-based (a Dispatcher, not an enqueue queue) because the audio
 * probes and the search pages share one server; a FIFO queue would interleave them.
 */
class SearchPipelineTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun audioResponse() = MockResponse()
        .setHeader("Content-Type", "audio/mpeg")
        .setHeader("Content-Length", "1048576")
        .setBody("ID3fakeaudiopayload")

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    /** Minimal client that points at the mock server, exercising the base class only. */
    private class FakeClient(
        config: MusicClientConfig,
        private val baseUrl: String,
        private val pages: Int,
    ) : BaseMusicClient(config) {
        override val source: String = "FakeMusicClient"

        override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> =
            (1..pages).map { "$baseUrl/search?page=$it&q=$keyword" }

        override suspend fun searchOne(
            keyword: String,
            searchUrl: String,
            results: MutableList<SongInfo>,
            progress: SearchProgress,
        ) {
            val response = http.get(searchUrl, tag = "fake.search") ?: return
            val body = response.use { it.textOrNull() } ?: return
            val root = JsonUtil.parseObject(body) ?: return
            val items = JsonUtil.array(root, "songs") ?: return
            for (element in items) {
                val obj = element as? JsonObject ?: continue
                val id = JsonUtil.str(obj, "id") ?: continue
                val name = JsonUtil.str(obj, "name") ?: continue
                val url = JsonUtil.str(obj, "url") ?: continue
                val status = audioLinkTester.test(url)
                if (!status.ok) continue
                results += SongInfo(
                    source = source,
                    songName = name,
                    singers = JsonUtil.str(obj, "artist") ?: "Tester",
                    ext = status.ext,
                    fileSize = status.fileSize,
                    fileSizeBytes = status.fileSizeBytes,
                    downloadUrl = status.downloadUrl,
                    downloadUrlStatus = status,
                    identifier = id,
                )
            }
        }
    }

    @Test
    fun `artist search uses credits and excludes artist names in titles`() {
        val base = server.url("/").toString().removeSuffix("/")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (request.path.orEmpty().startsWith("/audio")) audioResponse()
            else jsonResponse("""{"songs":[
                {"id":"1","name":"Different title","artist":"Tester","url":"$base/audio/one.mp3"},
                {"id":"2","name":"Tester","artist":"Other","url":"$base/audio/two.mp3"}
            ]}""")
        }
        val client = FakeClient(MusicClientConfig(), base, 1)
        try {
            assertEquals(listOf("1"), runBlocking { client.searchArtist("Tester") }.map { it.identifier })
        } finally { client.close() }
    }

    @Test
    fun `search fans out, validates links, dedups and stamps work dir`() {
        val base = server.url("/").toString().removeSuffix("/")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/search") -> {
                        val page = path.substringAfter("page=").substringBefore('&')
                        if (page == "2") {
                            // page 2 repeats id 1 to prove de-duplication
                            jsonResponse(
                                """{"songs":[
                                    {"id":"1","name":"Alpha","url":"$base/audio/alpha.mp3"},
                                    {"id":"3","name":"Gamma","url":"$base/audio/gamma.mp3"}
                                ]}""",
                            )
                        } else {
                            jsonResponse(
                                """{"songs":[
                                    {"id":"1","name":"Alpha","url":"$base/audio/alpha.mp3"},
                                    {"id":"2","name":"Beta","url":"$base/audio/beta.flac"}
                                ]}""",
                            )
                        }
                    }
                    path.startsWith("/audio") -> audioResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = FakeClient(
            MusicClientConfig(searchSizePerSource = 10, searchSizePerPage = 10, workDir = "/tmp/musicdl"),
            base,
            pages = 2,
        )

        val results = runBlocking { client.search("alpha", progress = NoSearchProgress) }

        // 3 unique ids across 2 pages, all with resolvable audio.
        assertEquals(3, results.size)
        assertEquals(listOf("1", "2", "3"), results.map { it.identifier })

        // Every row carries the work dir stamped by the base class.
        assertTrue(results.all { it.workDir.startsWith("/tmp/musicdl/FakeMusicClient/") })
        assertTrue(results.all { it.hasValidDownloadUrl })
        // Extension inference prefers the URL suffix, so beta.flac stays flac.
        assertEquals(listOf("mp3", "flac", "mp3"), results.map { it.ext })

        client.close()
    }

    @Test
    fun `search survives a failing source page`() {
        val base = server.url("/").toString().removeSuffix("/")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.startsWith("/search") -> {
                        val page = path.substringAfter("page=").substringBefore('&')
                        if (page == "1") {
                            MockResponse().setResponseCode(500)
                        } else {
                            jsonResponse("""{"songs":[{"id":"9","name":"Ok","url":"$base/a.mp3"}]}""")
                        }
                    }
                    path.startsWith("/a.mp3") -> audioResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val client = FakeClient(
            MusicClientConfig(searchSizePerSource = 4, searchSizePerPage = 2, workDir = "/tmp/musicdl"),
            base,
            pages = 2,
        )

        val results = runBlocking { client.search("x", progress = NoSearchProgress) }
        // The 500 page is skipped; the good page still yields its track.
        assertTrue(results.any { it.identifier == "9" })
        client.close()
    }
}
