package com.musicdl.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cover art for cards and the player.
 *
 * Catalogs hand out plain http(s) artwork URLs, but a downloaded track can also carry a `file:`
 * cover saved by [CoverStore] so the artwork survives offline. One bounded memory cache covers
 * both without adding an image-loading dependency.
 */
object CoverCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun peek(url: String?): Bitmap? = url?.let { synchronized(cache) { cache[it] } }

    suspend fun load(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        peek(url)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            if (url.startsWith("file:") || url.startsWith("/")) {
                decodeFile(File(if (url.startsWith("file:")) runCatching { java.net.URI(url).path }.getOrNull().orEmpty() else url))
            } else {
                com.musicdl.core.model.ArtworkUrls.candidates(url).firstNotNullOfOrNull { download(it) }
            }
        } ?: return null
        synchronized(cache) { cache.put(url, bitmap) }
        return bitmap
    }

    private fun decodeFile(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(url: String): Bitmap? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "MusicDL-Android/1.0")
            setRequestProperty("Accept", "image/*")
        }
        try {
            connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/**
 * Optional local cover storage. Covers are small, but they are counted in the download quota when
 * the user enables "Скачивать обложки треков" and can be deleted independently of the audio files.
 */
object CoverStore {
    private const val MAX_COVER_BYTES = 3 * 1024 * 1024

    fun directory(context: android.content.Context): File =
        File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * Stores artwork as lossy WebP (quality 88): the same visible quality as a 1200 px JPEG at a
     * third of the bytes, and every Android device decodes WebP in hardware.
     */
    fun save(context: android.content.Context, url: String?, key: String): File? {
        val remote = url?.takeIf { it.startsWith("http") } ?: return null
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").take(90)
        val bytes = fetch(remote) ?: return null
        val bitmap = decodeDownsampled(bytes)
        if (bitmap != null) {
            val target = File(directory(context), "$safe.webp")
            val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            }
            val written = runCatching {
                FileOutputStream(target).use { output -> bitmap.compress(format, 88, output) }
            }.getOrDefault(false)
            bitmap.recycle()
            if (written) return target.takeIf { it.length() > 0L }
            target.delete()
        }
        // Unknown format: keep the original bytes rather than lose the cover entirely.
        val fallback = File(directory(context), "$safe.img")
        return runCatching {
            fallback.writeBytes(bytes)
            fallback.takeIf { it.length() > 0L }
        }.getOrNull()
    }

    fun delete(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        val file = File(path)
        val size = file.length()
        return if (file.isFile && file.delete()) size else 0L
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MusicDL-Android/1.0")
            setRequestProperty("Accept", "image/*")
        }
        try {
            val length = connection.contentLengthLong
            if (length > MAX_COVER_BYTES) return null
            connection.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_COVER_BYTES) return null
                }
                buffer.toByteArray().takeIf { it.isNotEmpty() }
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

/** Draws the artwork, or [fallback] (a letter tile / note icon) when there is none. */
@Composable
fun CoverImage(url: String?, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val bitmap by produceState(initialValue = CoverCache.peek(url), url) {
        value = CoverCache.peek(url)
        value = CoverCache.load(url)
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        fallback()
    }
}
