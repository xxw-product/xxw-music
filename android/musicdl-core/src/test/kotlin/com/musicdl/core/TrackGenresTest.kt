package com.musicdl.core

import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.TrackGenres
import com.musicdl.core.source.TrackGenreClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TrackGenresTest {
    @Test fun `qobuz album taxonomy and soundcloud quoted tags survive parsing`() {
        val qobuz = Json.parseToJsonElement("""{"album":{"genre":{"name":"Electronic"},"genres_list":["Electronic → House","Dance"]}}""").jsonObject
        val genres = TrackGenres.fromCatalog(qobuz)
        assertEquals("Electronic", genres.first)
        assertTrue(genres.second!!.contains("House"))
        val soundcloud = Json.parseToJsonElement("""{"genre":"Hip-Hop","tag_list":"\"alternative hip hop\" rap","title":"Rock"}""").jsonObject
        val merged = TrackGenres.merge(SongInfo(songName = "Rock"), soundcloud)
        assertEquals("Hip-Hop", merged.genre)
        assertTrue(merged.tags!!.contains("alternative hip hop"))
    }

    @Test fun `deezer genres are enriched from album and album request is cached`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":1,"album":{"id":42}}"""))
            server.enqueue(MockResponse().setBody("""{"genres":{"data":[{"name":"Jazz"},{"name":"Soul"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"id":2,"album":{"id":42}}"""))
            server.start()
            TrackGenreClient(deezerBase = server.url("/").toString().trimEnd('/')).use { client ->
                val first = client.enrich(SongInfo(source = "DeezerMusicClient", identifier = "1"))
                val second = client.enrich(SongInfo(source = "DeezerMusicClient", identifier = "2"))
                assertEquals("Jazz", first.genre)
                assertEquals(first.genre, second.genre)
                assertTrue(first.tags!!.contains("Soul"))
                assertEquals(3, server.requestCount)
            }
        }
    }

    @Test fun `qobuz cache distinguishes track query parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":1,"album":{"genre":{"name":"Rock"}}}"""))
            server.enqueue(MockResponse().setBody("""{"id":2,"album":{"genre":{"name":"Jazz"}}}"""))
            server.start()
            TrackGenreClient(qobuzBase = server.url("/").toString().trimEnd('/')).use { client ->
                assertEquals("Rock", client.enrich(SongInfo(source = "QobuzMusicClient", identifier = "1")).genre)
                assertEquals("Jazz", client.enrich(SongInfo(source = "QobuzMusicClient", identifier = "2")).genre)
            }
        }
    }

    @Test fun `provider error preserves original metadata and never guesses from title`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"error":{"message":"Unavailable"}}"""))
            server.start()
            TrackGenreClient(deezerBase = server.url("/").toString().trimEnd('/')).use { client ->
                val original = SongInfo(source = "DeezerMusicClient", identifier = "1", songName = "Pop star")
                assertEquals(original, client.enrich(original))
            }
        }
    }
}
