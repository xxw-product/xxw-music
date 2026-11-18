package com.musicdl.core

import com.musicdl.core.source.MusicClient
import com.musicdl.core.source.MusicClientConfig
import com.musicdl.core.source.ProviderCascade
import com.musicdl.core.source.YandexMusicClient
import com.musicdl.core.transfer.SpotifyLinkParser
import com.musicdl.core.transfer.SpotifyParseResult
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Temporary live check of the playlist-link import path. Not part of the suite. */
class LiveYandexLinkImportTest {

    private val logger = object : LoggerHandle {
        override fun info(message: String) = println("  INFO  $message")
        override fun warning(message: String) = println("  WARN  $message")
        override fun error(message: String) = println("  ERROR $message")
    }

    @Test
    fun yandexPlaylistLinkImportsAndDownloads() = runBlocking {
        // Live network test: opt in with MUSICDL_LIVE=1 so offline runs stay green.
        assumeTrue(System.getenv("MUSICDL_LIVE") == "1")
        val work = File("build/live-import").apply { mkdirs() }
        val config = MusicClientConfig(searchSizePerSource = 5, workDir = work.absolutePath, logger = logger)
        val link = "https://music.yandex.ru/users/Chillhopmusic/playlists/1001"

        val yandex = YandexMusicClient(config)
        val tracks = try {
            yandex.parsePlaylist(link)
        } finally {
            yandex.close()
        }
        println("PARSED=${tracks.size}")
        tracks.take(3).forEach {
            println("TRACK '${it.songName}' | '${it.singers}' | ${it.durationSeconds}s | cover=${!it.coverUrl.isNullOrBlank()} | url=${it.downloadUrl}")
        }

        val cascade = ProviderCascade(
            listOf("DeezerMusicClient", "SoundCloudMusicClient", "QobuzMusicClient"),
            config,
            perProviderTimeoutMs = 15_000L,
        )
        val resolved = tracks.take(3).map { track ->
            val result = cascade.find(track.songName, track.singers)
            println("RESOLVE '${track.songName}' -> ${result.value?.source} '${result.value?.songName}' | ${result.attempts.size} attempts")
            track to result.value
        }
        val hit = resolved.firstNotNullOfOrNull { it.second } ?: error("no catalog matched any of the 3 tracks")

        val client = MusicClient(listOf(hit.source), config)
        val file = try {
            client.download(hit)
        } finally {
            client.close()
        }
        println("DOWNLOADED ${file.absolutePath} bytes=${file.length()}")
        check(file.length() > 100_000) { "downloaded file is too small: ${file.length()}" }
    }

    @Test
    fun spotifyLinkExplainsWhyItFailed() {
        assumeTrue(System.getenv("MUSICDL_LIVE") == "1")
        val missing = SpotifyLinkParser.parse("https://open.spotify.com/playlist/0000000000000000000000")
        println("SPOTIFY missing -> $missing")
        check(missing is SpotifyParseResult.Failure) { "a bogus playlist id must fail with a message" }

        val notSpotify = SpotifyLinkParser.parse("https://music.yandex.ru/users/x/playlists/1")
        println("SPOTIFY bad link -> $notSpotify")
        check(notSpotify is SpotifyParseResult.Failure) { "a non-Spotify link must fail with a message" }
    }
}
