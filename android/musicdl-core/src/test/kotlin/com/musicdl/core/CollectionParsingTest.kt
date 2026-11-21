package com.musicdl.core

import com.musicdl.core.source.CollectionKind
import com.musicdl.core.source.CollectionParsing
import com.musicdl.core.source.MusicCollection
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class CollectionParsingTest {

    @Test
    fun deezerAlbumsAndPlaylists() {
        val albums = CollectionParsing.deezerCollections(
            """{"data":[{"id":302127,"title":"Discovery","cover_xl":"https://c/xl.jpg","nb_tracks":14,"artist":{"name":"Daft Punk"},"release_date":"2001-03-07"},{"id":1}]}""",
            CollectionKind.ALBUM, "DeezerMusicClient",
        )
        assertEquals(1, albums.size)
        assertEquals("Daft Punk", albums[0].owner)
        assertEquals(14, albums[0].trackCount)
        assertEquals("https://c/xl.jpg", albums[0].coverUrl)

        val lists = CollectionParsing.deezerCollections(
            """{"data":[{"id":9,"title":"Chill","picture_xl":"https://p/xl.jpg","nb_tracks":50,"user":{"name":"Anna"}}]}""",
            CollectionKind.PLAYLIST, "DeezerMusicClient",
        )
        assertEquals("Anna", lists[0].owner)
        assertEquals("https://p/xl.jpg", lists[0].coverUrl)
    }

    @Test
    fun deezerTracksSkipUnreadableAndFollowNext() {
        val collection = MusicCollection("DeezerMusicClient", "1", CollectionKind.ALBUM, "Discovery", "Daft Punk", "https://c/xl.jpg", 2)
        val (tracks, next) = CollectionParsing.deezerTracks(
            """{"data":[{"id":1,"title":"One More Time","duration":320,"artist":{"name":"Daft Punk"}},{"id":2,"title":"Gone","readable":false,"duration":10}],"next":"https://api.deezer.com/album/1/tracks?index=100"}""",
            collection,
        )
        assertEquals(1, tracks.size)
        assertEquals("1", tracks[0].identifier)
        assertEquals("Discovery", tracks[0].album)
        assertEquals(320L, tracks[0].durationSeconds)
        assertNull(tracks[0].downloadUrl)
        assertEquals("https://api.deezer.com/album/1/tracks?index=100", next)
    }

    @Test
    fun soundCloudCollectionsDropEmptyOnes() {
        val found = CollectionParsing.soundCloudCollections(
            """{"collection":[{"id":5,"title":"EP","track_count":3,"user":{"username":"dj"},"artwork_url":null,"tracks":[{"id":1,"artwork_url":"https://a/x-large.jpg"}],"created_at":"2020-01-01T00:00:00Z"},{"id":6,"title":"Empty","track_count":0}]}""",
            CollectionKind.ALBUM, "SoundCloudMusicClient",
        )
        assertEquals(1, found.size)
        assertEquals("dj", found[0].owner)
        assertEquals("2020", found[0].year)
    }

    @Test
    fun soundCloudStubsAreFilledInPlaylistOrder() {
        val collection = MusicCollection("SoundCloudMusicClient", "5", CollectionKind.PLAYLIST, "Mix", "dj", null, 2)
        val body = """{"tracks":[{"id":10},{"id":11,"title":"Full","duration":61000,"user":{"username":"u"}}]}"""
        assertEquals(listOf("10"), CollectionParsing.soundCloudStubIds(body))
        val extra = CollectionParsing.soundCloudTrackArray("""[{"id":10,"title":"Stub filled","duration":120000,"user":{"username":"v"}}]""")
        val tracks = CollectionParsing.soundCloudTracks(body, extra, collection)
        assertEquals(listOf("10", "11"), tracks.map { it.identifier })
        assertEquals("Stub filled", tracks[0].songName)
        assertEquals(120L, tracks[0].durationSeconds)
        assertTrue(tracks.all { it.downloadUrl == null })
    }
}
