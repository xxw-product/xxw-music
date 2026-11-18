package com.musicdl.android

import android.content.Context
import com.musicdl.core.model.SongInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One downloaded audio file, with the optional local cover that belongs to it. */
data class DownloadEntry(
    val key: String,
    val path: String,
    val bytes: Long,
    val coverPath: String? = null,
    val coverBytes: Long = 0L,
    /** LRC file with timings, or plain text when the provider had no timings. */
    val lyricsPath: String? = null,
    val lyricsBytes: Long = 0L,
    /** Downloaded vertical clip (H.264 MP4), reusable offline by the clips screen. */
    val clipPath: String? = null,
    val clipBytes: Long = 0L,
    val at: Long = System.currentTimeMillis(),
    val title: String = "",
    val artist: String = "",
    val remoteCover: String? = null,
) {
    val totalBytes: Long get() = bytes + coverBytes + lyricsBytes + clipBytes
}

/**
 * Offline quota is measured in bytes, not in tracks.
 *
 * A fixed "25 tracks" limit said nothing about how much disk was actually used: twenty 3 MB
 * previews and twenty 60 MB FLAC rips counted the same. The index below keeps the real file size of
 * every download (and of its cover, when covers are enabled), prunes files the user deleted
 * outside the app, and is the single source of truth for "занято / доступно".
 */
class DownloadStore(context: Context) {

    private val prefs = context.getSharedPreferences("musicdl_downloads", Context.MODE_PRIVATE)

    var limitBytes: Long
        get() = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT)
        set(value) {
            prefs.edit().putLong(KEY_LIMIT, if (value <= 0L) 0L else value).apply()
        }

    var downloadCovers: Boolean
        get() = prefs.getBoolean(KEY_COVERS, true)
        set(value) { prefs.edit().putBoolean(KEY_COVERS, value).apply() }

    var downloadLyrics: Boolean
        get() = prefs.getBoolean(KEY_LYRICS, true)
        set(value) { prefs.edit().putBoolean(KEY_LYRICS, value).apply() }

    var downloadClips: Boolean
        get() = prefs.getBoolean(KEY_CLIPS, false)
        set(value) { prefs.edit().putBoolean(KEY_CLIPS, value).apply() }

    fun entries(): List<DownloadEntry> = runCatching {
        val array = JSONArray(prefs.getString(KEY_INDEX, "[]"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val path = item.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val file = File(path)
            if (!file.isFile || file.length() <= 0L) return@mapNotNull null
            val cover = item.optString("cover").takeIf { it.isNotBlank() }?.let(::File)
            val coverFile = cover?.takeIf { it.isFile }
            val lyrics = item.optString("lyrics").takeIf { it.isNotBlank() }?.let(::File)
            val lyricsFile = lyrics?.takeIf { it.isFile }
            val clip = item.optString("clip").takeIf { it.isNotBlank() }?.let(::File)
            val clipFile = clip?.takeIf { it.isFile && it.length() > 0L }
            DownloadEntry(
                key = item.optString("key"),
                path = path,
                bytes = file.length(),
                coverPath = coverFile?.absolutePath,
                coverBytes = coverFile?.length() ?: 0L,
                lyricsPath = lyricsFile?.absolutePath,
                lyricsBytes = lyricsFile?.length() ?: 0L,
                clipPath = clipFile?.absolutePath,
                clipBytes = clipFile?.length() ?: 0L,
                at = item.optLong("at"),
                title = item.optString("title"),
                artist = item.optString("artist"),
                remoteCover = item.optString("remoteCover").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Finds an offline copy even when the same recording is returned by another catalog: provider
     * identifiers change between searches, the title and artist the user sees do not. Without this,
     * a re-search or a refreshed wave happily downloaded a song that was already on the device.
     */
    fun entryFor(song: SongInfo): DownloadEntry? = entries().firstOrNull { entry ->
        entry.key == song.key() || sameRecording(entry.title, entry.artist, song.songName, song.singers)
    }

    fun entryForPath(path: String): DownloadEntry? = entries().firstOrNull { it.path == path }

    fun usedBytes(): Long = entries().sumOf { it.totalBytes }

    fun audioBytes(): Long = entries().sumOf { it.bytes }

    fun coverBytes(): Long = entries().sumOf { it.coverBytes }

    val unlimited: Boolean get() = limitBytes <= 0L

    fun availableBytes(): Long? = if (unlimited) null else (limitBytes - usedBytes()).coerceAtLeast(0L)

    /** Reserve before the download so two parallel jobs cannot both pass the last free bytes. */
    fun canFit(estimatedBytes: Long): Boolean {
        if (unlimited) return true
        val estimate = estimatedBytes.coerceAtLeast(0L)
        return usedBytes() + estimate <= limitBytes
    }

    fun add(entry: DownloadEntry) {
        val current = entries().filterNot { it.key == entry.key || it.path == entry.path }
        write((listOf(entry) + current).take(MAX_ENTRIES))
    }

    /** Deletes audio and its cover; returns the number of bytes freed. */
    fun remove(key: String): Long {
        val entry = entries().firstOrNull { it.key == key } ?: return 0L
        var freed = 0L
        listOfNotNull(entry.path, entry.coverPath, entry.lyricsPath, entry.clipPath).forEach { path ->
            File(path).takeIf { it.isFile }?.let { file -> freed += file.length(); file.delete() }
        }
        write(entries().filterNot { it.key == key })
        return freed
    }

    /** Removes an entry by file path, used when a library snapshot is deleted. */
    fun removeByPath(path: String): Long = entries().firstOrNull { it.path == path }?.let { remove(it.key) } ?: 0L

    /** Deletes every locally stored cover but keeps the audio files downloaded. */
    fun clearCovers(): Long {
        var freed = 0L
        val updated = entries().map { entry ->
            entry.coverPath?.let { path -> File(path).takeIf { it.isFile }?.let { file -> freed += file.length(); file.delete() } }
            entry.copy(coverPath = null, coverBytes = 0L)
        }
        write(updated)
        return freed
    }

    fun clearAll(): Long {
        var freed = 0L
        entries().forEach { entry ->
            listOfNotNull(entry.path, entry.coverPath, entry.lyricsPath, entry.clipPath).forEach { path ->
                File(path).takeIf { it.isFile }?.let { file -> freed += file.length(); file.delete() }
            }
        }
        write(emptyList())
        return freed
    }

    private fun write(items: List<DownloadEntry>) {
        val array = JSONArray()
        items.forEach { entry ->
            array.put(JSONObject().apply {
                put("key", entry.key)
                put("path", entry.path)
                put("cover", entry.coverPath.orEmpty())
                put("lyrics", entry.lyricsPath.orEmpty())
                put("clip", entry.clipPath.orEmpty())
                put("at", entry.at)
                put("title", entry.title)
                put("artist", entry.artist)
                put("remoteCover", entry.remoteCover.orEmpty())
            })
        }
        prefs.edit().putString(KEY_INDEX, array.toString()).apply()
    }

    companion object {
        const val MB = 1024L * 1024L
        const val DEFAULT_LIMIT = 1024L * MB
        val LIMITS: List<Long> = listOf(500L * MB, 1024L * MB, 2048L * MB, 3072L * MB, 4096L * MB, 0L)

        fun label(bytes: Long): String = when {
            bytes <= 0L -> "Без ограничений"
            bytes >= 1024L * MB -> "${bytes / (1024L * MB)} ГБ"
            else -> "${bytes / MB} МБ"
        }

        fun sizeLabel(bytes: Long): String = when {
            bytes <= 0L -> "0 МБ"
            bytes >= 1024L * MB -> String.format(java.util.Locale.ROOT, "%.2f ГБ", bytes / (1024.0 * MB))
            bytes < MB -> "${(bytes / 1024L).coerceAtLeast(1L)} КБ"
            // MB already is 1024 * 1024 bytes; dividing by 1024 * MB here printed a 1.4 MB file
            // as "0.0 МБ" because the value was really in gigabytes.
            else -> String.format(java.util.Locale.ROOT, "%.1f МБ", bytes / MB.toDouble())
        }

        private const val KEY_INDEX = "download_index"
        private const val KEY_LIMIT = "download_limit_bytes"
        private const val KEY_COVERS = "download_covers"
        private const val KEY_LYRICS = "download_lyrics"
        private const val KEY_CLIPS = "download_clips"
        private const val MAX_ENTRIES = 5000
    }
}
