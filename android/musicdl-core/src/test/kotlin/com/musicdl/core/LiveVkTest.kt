package com.musicdl.core

import com.musicdl.core.net.SongDownloader
import com.musicdl.core.source.MusicClientConfig
import com.musicdl.core.source.VKMusicClient
import com.musicdl.core.source.VkStreamUrlDecoder
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live VK check, off unless `MUSICDL_LIVE=1`.
 *
 * The token is read from the project `.env` (`vkt = …`) or the `VKT` environment variable and is
 * never printed: only the host of a resolved link and the size of the saved file appear in the log.
 */
class LiveVkTest {

    @Test
    fun searchDecodesMaskedLinksAndDownloads() = runBlocking {
        assumeTrue(System.getenv("MUSICDL_LIVE") == "1")
        val token = envToken("vkt")
        if (token.isNullOrBlank()) {
            println("VK SKIPPED: no vkt in .env")
            assumeTrue(false)
            return@runBlocking
        }
        val work = File("build/live-vk").apply { mkdirs() }
        val config = MusicClientConfig(
            // Same page sizes the app uses, so the live check reproduces its request shape.
            searchSizePerSource = 30,
            searchSizePerPage = 30,
            workDir = work.absolutePath,
            logger = LoggerHandle.SILENT,
            defaultSearchCookies = mapOf("vk_access_token" to token),
        )
        val client = VKMusicClient(config)
        val songs = client.search("lofi hip hop")
        println("VK SEARCH=${songs.size}")
        songs.take(5).forEach { song ->
            val host = song.downloadUrl?.toHttpUrlOrNull()?.host ?: "?"
            println("  VK ROW '${song.songName}' — ${song.singers} | ${host} | ${song.protocol}/${song.ext}")
        }
        check(songs.isNotEmpty()) { "VK search returned nothing" }
        check(songs.none { it.downloadUrl?.contains(VkStreamUrlDecoder.MARKER) == true }) {
            "a masked url reached the result list undecoded"
        }

        // The downloader keeps a 25 s call budget, so a two-hour mix cannot finish here; the
        // shortest result proves the link is downloadable without waiting for it.
        val target = songs.minByOrNull { it.durationSeconds } ?: error("no vk track")
        println("VK TARGET '${target.songName}' duration=${target.duration}")
        val file = runCatching { SongDownloader(config).download(target) }.getOrElse { error ->
            println("VK DOWNLOAD FAILED: ${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
        val head = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
        println("VK DOWNLOADED ${file.name} bytes=${file.length()} head=${head.joinToString(" ") { "%02X".format(it) }}")
        check(file.length() > 100_000) { "downloaded vk file is too small: ${file.length()}" }
        check(!String(head).contains("<")) { "vk download is not audio: ${String(head)}" }
    }

    /** `audio.get` for the first result's owner: the playlist-import path with a real account. */
    @Test
    fun readsAnOwnerLibraryThroughAudioGet() = runBlocking {
        assumeTrue(System.getenv("MUSICDL_LIVE") == "1")
        val token = envToken("vkt")
        if (token.isNullOrBlank()) {
            println("VK SKIPPED: no vkt in .env")
            assumeTrue(false)
            return@runBlocking
        }
        val config = MusicClientConfig(
            workDir = File("build/live-vk").apply { mkdirs() }.absolutePath,
            logger = LoggerHandle.SILENT,
            defaultSearchCookies = mapOf("vk_access_token" to token),
        )
        val client = VKMusicClient(config)
        val mine = client.search("lofi hip hop").firstOrNull()?.identifier?.substringBefore('_')
        println("VK OWNER=$mine")
        check(!mine.isNullOrBlank()) { "no owner id from search" }
        val tracks = client.parsePlaylist("https://vk.com/audios$mine")
        println("VK AUDIOS=${tracks.size}")
        tracks.take(3).forEach { println("  VK AUDIO '${it.songName}' — ${it.singers}") }
        check(tracks.isNotEmpty()) { "audio.get returned no tracks for $mine" }
    }

    /** Reads one `key = value` line from the project `.env`, keeping the value out of the log. */
    private fun envToken(key: String): String? {
        val file = File("../../.env")
        if (file.isFile) {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val trimmed = line.trim().removePrefix("export ").trim()
                val separator = trimmed.indexOf('=')
                if (separator > 0 && trimmed.substring(0, separator).trim() == key) {
                    return trimmed.substring(separator + 1).trim().trim('"', '\'')
                }
            }
        }
        return System.getenv(key.uppercase())
    }
}
