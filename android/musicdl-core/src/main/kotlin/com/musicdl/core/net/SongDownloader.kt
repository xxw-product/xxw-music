package com.musicdl.core.net

import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.MusicClientConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class SongDownloader(private val config: MusicClientConfig) {
    suspend fun download(song: SongInfo): File {
        val url = song.downloadUrl?.toHttpUrlOrNull() ?: throw IOException("Missing or invalid download URL")
        val http = HttpClient(
            logger = config.logger,
            maxRetries = config.maxRetries,
            maintainSession = true,
            defaultHeaders = withUserAgent(config.defaultDownloadHeaders + song.downloadHeaders),
            defaultCookies = config.defaultDownloadCookies + song.downloadCookies,
        )
        fun get(resource: HttpUrl): Response = http.get(resource.toString(), tag = "download")
            ?: throw IOException("Could not download ${resource.encodedPath}")

        try {
            return get(url).use { response ->
                if (isHls(song, url, response)) {
                    val media = HlsPlaylist.resolve(response, ::get)
                    save(song.copy(ext = media.extension)) { output ->
                        for (resource in media.resources) {
                            currentCoroutineContext().ensureActive()
                            get(resource).use { copyBody(it, output) }
                        }
                    }
                } else {
                    if (song.source == "QobuzMusicClient") {
                        val input = response.body?.source() ?: throw IOException("Missing FLAC body")
                        if (!input.request(4) || input.buffer.clone().readUtf8(4) != "fLaC") {
                            throw IOException("Qobuz returned a non-FLAC response; download not saved")
                        }
                    }
                    val type = AudioLinkTester.normalizeContentType(response.header("Content-Type"))
                    if (type in setOf("text/html", "application/json", "text/plain")) {
                        throw IOException("Server returned $type instead of audio")
                    }
                    save(song) { copyBody(response, it) }
                }
            }
        } finally {
            http.close()
        }
    }

    private fun isHls(song: SongInfo, url: HttpUrl, response: Response): Boolean =
        song.protocol.equals("HLS", ignoreCase = true) || song.ext.equals("m3u8", ignoreCase = true) ||
            url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
            response.request.url.encodedPath.endsWith(".m3u8", ignoreCase = true) ||
            AudioLinkTester.normalizeContentType(response.header("Content-Type")) in HLS_CONTENT_TYPES

    private suspend fun save(song: SongInfo, write: suspend (OutputStream) -> Unit): File {
        val directory = File(song.resolveSavePath { false }).absoluteFile.parentFile
            ?: throw IOException("Missing download directory")
        if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not create $directory")
        val temporary = File.createTempFile(".musicdl-", ".part", directory)
        try {
            temporary.outputStream().buffered().use { write(it) }
            currentCoroutineContext().ensureActive()
            // Keep path selection and publication together for concurrent downloads in this process.
            return synchronized(publishLock) {
                val target = File(song.resolveSavePath()).absoluteFile
                if (!temporary.renameTo(target)) throw IOException("Could not save ${target.name}")
                target
            }
        } finally {
            temporary.delete()
        }
    }

    private suspend fun copyBody(response: Response, output: OutputStream) {
        val input = response.body?.byteStream() ?: throw IOException("Missing download body")
        val context = currentCoroutineContext()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var receivedData = false
        var total = 0L
        val expected = response.body?.contentLength() ?: -1L
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            total += count
            receivedData = true
        }
        if (!receivedData) throw IOException("Empty download body")
        if (expected >= 0L && total != expected) throw IOException("Incomplete download: $total of $expected bytes")
    }

    /**
     * Some hosts (Wikimedia Commons among them) reject OkHttp's default UA with HTTP 403,
     * so a descriptive one is supplied unless the source already asked for its own.
     */
    private fun withUserAgent(headers: Map<String, String>): Map<String, String> =
        if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
        else headers + ("User-Agent" to USER_AGENT)

    companion object {
        private const val USER_AGENT = "MusicDL-Android/1.0"
        private val publishLock = Any()
        private val HLS_CONTENT_TYPES = setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        )
    }
}
