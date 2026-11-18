package com.musicdl.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.musicdl.core.lyrics.Lyrics
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.ClipSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * High-resolution artwork from key-less catalogs.
 *
 * The catalog that supplied the audio often hands out a 300–600 px cover. Four public services
 * index the same release at a much larger size, and one of them almost always knows the record:
 * iTunes (1200x1200), Deezer (1000x1000), Cover Art Archive (1200), and Qobuz's own `_max` variant.
 * The provider cover is kept as the last resort, so a miss never leaves the player without artwork.
 */
object ArtworkSources {

    private const val UA = "XXWMusic/1.0 (Android; artwork resolver)"

    suspend fun resolve(song: SongInfo, timeoutMs: Int = 5_000): String? = withContext(Dispatchers.IO) {
        val title = song.songName.orEmpty().trim()
        val artist = song.singers.orEmpty().trim()
        val local = song.coverUrl?.takeIf { it.startsWith("file:") }
        if (local != null) return@withContext local
        if (title.isBlank() || artist.isBlank()) return@withContext upgrade(song.coverUrl) ?: song.coverUrl
        itunes(title, artist, timeoutMs)
            ?: deezer(title, artist, timeoutMs)
            ?: coverArtArchive(title, artist, timeoutMs)
            ?: upgrade(song.coverUrl)
            ?: song.coverUrl
    }

    /** iTunes hands out a 100x100 URL whose size segment can be replaced. */
    suspend fun itunes(title: String, artist: String, timeoutMs: Int = 5_000): String? = withContext(Dispatchers.IO) {
        val query = encode("$artist $title")
        val body = get("https://itunes.apple.com/search?term=$query&entity=song&limit=6&media=music", timeoutMs)
            ?: return@withContext null
        runCatching {
            val results = JSONObject(body).optJSONArray("results") ?: return@runCatching null
            (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                .firstOrNull { item ->
                    matches(item.optString("trackName"), item.optString("artistName"), title, artist)
                }
                ?.optString("artworkUrl100")
                ?.takeIf { it.isNotBlank() }
                ?.replace("100x100", "1200x1200")
        }.getOrNull()
    }

    suspend fun deezer(title: String, artist: String, timeoutMs: Int = 5_000): String? = withContext(Dispatchers.IO) {
        val query = encode("$artist $title")
        val body = get("https://api.deezer.com/search?q=$query&limit=6", timeoutMs) ?: return@withContext null
        runCatching {
            val data = JSONObject(body).optJSONArray("data") ?: return@runCatching null
            (0 until data.length()).mapNotNull { data.optJSONObject(it) }
                .firstOrNull { item ->
                    matches(item.optString("title"), item.optJSONObject("artist")?.optString("name"), title, artist)
                }
                ?.optJSONObject("album")
                ?.optString("cover_xl")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** MusicBrainz + Cover Art Archive: the only source with original scans, not shop art. */
    suspend fun coverArtArchive(title: String, artist: String, timeoutMs: Int = 5_000): String? =
        withContext(Dispatchers.IO) {
            val query = encode("""release:"$title" AND artist:"$artist"""")
            val body = get("https://musicbrainz.org/ws/2/release?query=$query&fmt=json&limit=1", timeoutMs)
                ?: return@withContext null
            val id = runCatching {
                JSONObject(body).optJSONArray("releases")?.optJSONObject(0)?.optString("id")
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
            "https://coverartarchive.org/release/$id/front-1200"
        }

    /** Larger variant of the URL the provider already gave us (Qobuz `_600` → `_max`). */
    fun upgrade(url: String?): String? = url?.takeIf { it.startsWith("http") }?.let { value ->
        when {
            value.contains("qobuz.com") -> value.replace(Regex("_\\d{3}(?=\\.(jpg|jpeg|png))"), "_max")
            value.contains("mzstatic.com") -> value.replace("100x100", "1200x1200")
            else -> value
        }
    }

    private fun matches(title: String?, artist: String?, wantTitle: String, wantArtist: String): Boolean {
        val a = normalize(title)
        val b = normalize(wantTitle)
        if (a.isBlank() || b.isBlank()) return false
        val titleOk = a == b || a.contains(b) || b.contains(a)
        val artistA = normalize(artist)
        val artistB = normalize(wantArtist)
        val artistOk = artistA.isBlank() || artistB.isBlank() || artistA.contains(artistB) || artistB.contains(artistA)
        return titleOk && artistOk
    }

    private fun normalize(value: String?): String = value.orEmpty().lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun get(url: String, timeoutMs: Int): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json,image/*")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Lyrics sidecar: LRC when timings exist, plain UTF-8 text otherwise. */
object LyricsStore {
    fun directory(context: android.content.Context): File =
        File(context.filesDir, "lyrics").apply { mkdirs() }

    fun save(context: android.content.Context, key: String, lyrics: Lyrics): File? {
        if (lyrics.isBlank) return null
        val timed = lyrics.hasTimings
        val text = if (timed) {
            buildString {
                appendLine("[ar:${lyrics.provider}]")
                lyrics.lines.forEach { line -> appendLine(formatLrc(line.timeMs, line.text)) }
            }
        } else {
            lyrics.plain.orEmpty()
        }
        if (text.isBlank()) return null
        val safe = safeName(key)
        val target = File(directory(context), "$safe.${if (timed) "lrc" else "txt"}")
        return runCatching {
            target.writeText(text, Charsets.UTF_8)
            target.takeIf { it.length() > 0L }
        }.getOrNull()
    }

    internal fun formatLrc(timeMs: Long, text: String): String {
        val total = (timeMs.coerceAtLeast(0L) + 5) / 10 // centiseconds, rounded
        val minutes = total / 6000
        val seconds = (total % 6000) / 100
        val centis = total % 100
        return String.format(Locale.ROOT, "[%02d:%02d.%02d] %s", minutes, seconds, centis, text)
    }

    fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90)
}

/**
 * Downloaded vertical clips.
 *
 * Clips are stored as the provider's H.264 MP4: hardware decoders play it instantly on every
 * Android device, while re-encoding to a "better" codec would add a slow decode path and quality
 * loss for a file that is already only a few seconds long.
 */
object ClipStore {
    private const val MAX_CLIP_BYTES = 40L * 1024 * 1024

    fun directory(context: android.content.Context): File =
        File(context.filesDir, "clips").apply { mkdirs() }

    fun pathFor(context: android.content.Context, key: String): File? =
        File(directory(context), "${LyricsStore.safeName(key)}.mp4").takeIf { it.isFile && it.length() > 0L }

    suspend fun save(
        context: android.content.Context,
        key: String,
        clip: ClipSpec.Video,
        timeoutMs: Int = 20_000,
    ): File? = withContext(Dispatchers.IO) {
        if (!clip.url.startsWith("http")) return@withContext null
        val target = File(directory(context), "${LyricsStore.safeName(key)}.mp4")
        val temp = File(target.parentFile, "${target.name}.part")
        val ok = runCatching {
            val connection = (URL(clip.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "XXWMusic/1.0 (Android; clip cache)")
                clip.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching false
                val declared = connection.contentLengthLong
                if (declared > MAX_CLIP_BYTES) return@runCatching false
                var written = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > MAX_CLIP_BYTES) return@runCatching false
                            output.write(buffer, 0, read)
                        }
                    }
                }
                written > 0L
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (!ok || temp.length() <= 0L) {
            temp.delete()
            return@withContext null
        }
        runCatching { temp.renameTo(target) }.getOrDefault(false)
            .takeIf { it && target.isFile }?.let { target }
            ?: run { temp.delete(); null }
    }
}

/** Small helper shared by the cover cache and the player: decode without blowing up memory. */
internal fun decodeDownsampled(bytes: ByteArray, maxDimension: Int = 1400): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
