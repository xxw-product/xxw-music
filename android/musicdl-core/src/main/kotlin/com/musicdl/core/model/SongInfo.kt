package com.musicdl.core.model

/**
 * Port of `musicdl/modules/utils/data.py::SongInfo`.
 *
 * The Python original is a mutable dataclass with dict-like access. Kotlin keeps that
 * ergonomics where it matters (copy/update/merge, identifier dedup, save-path resolution)
 * but makes the field set explicit and typed.
 */
data class SongInfo(
    /** Raw payloads returned by the source APIs: search / download / lyric / quality. */
    val rawData: Map<String, Any?> = emptyMap(),
    /** Which client produced this record, e.g. "NeteaseMusicClient". */
    val source: String = "",
    /** Upstream origin when [source] is an aggregator. */
    val rootSource: String? = null,

    val songName: String? = null,
    val singers: String? = null,
    val album: String? = null,
    val ext: String? = null,
    val fileSizeBytes: Long = 0L,
    val fileSize: String = "NULL",
    val durationSeconds: Long = 0L,
    val duration: String = "-:-:-",
    val bitrate: Int? = null,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,

    val genre: String? = null,
    val tags: String? = null,
    val description: String? = null,
    val lyric: String? = null,
    val coverUrl: String? = null,

    /** Episodes for FM/audiobook sources (Ximalaya and friends). */
    val episodes: List<SongInfo> = emptyList(),

    val downloadUrl: String? = null,
    val downloadUrlStatus: DownloadUrlStatus = DownloadUrlStatus(),
    val downloadHeaders: Map<String, String> = emptyMap(),
    val downloadCookies: Map<String, String> = emptyMap(),
    val chunkSize: Int = 1024 * 1024,
    /** "HTTP" or "HLS". */
    val protocol: String = "HTTP",

    val identifier: String? = null,
    val workDir: String = ".",
) {
    /** Port of `SongInfo.with_valid_download_url`. */
    val hasValidDownloadUrl: Boolean
        get() {
            if (episodes.isNotEmpty()) return episodes.all { it.hasValidDownloadUrl }
            val urlOk = downloadUrl?.startsWith("http") == true
            return urlOk && downloadUrlStatus.ok
        }

    /** Port of `SongInfo.largerthan`: compares the human-readable file size in MB. */
    fun largerThan(other: SongInfo): Boolean {
        val a = fileSize.removeSuffix("MB").trim().toDoubleOrNull() ?: 0.0
        val b = other.fileSize.removeSuffix("MB").trim().toDoubleOrNull() ?: 0.0
        return a > b
    }

    /** Port of `SongInfo.save_path`: "<song> - <identifier>.<ext>", de-duplicated on disk. */
    fun resolveSavePath(exists: (String) -> Boolean = { java.io.File(it).exists() }): String {
        val safeName = PathUtils.sanitizeFileName(songName ?: "NULL")
        val safeId = PathUtils.sanitizeFileName(identifier ?: "NULL")
        val extension = (ext ?: "").removePrefix(".")
        val base = if (extension.isEmpty()) "$safeName - $safeId" else "$safeName - $safeId.$extension"
        var candidate = PathUtils.join(workDir, base)
        var index = 1
        while (exists(candidate)) {
            val deduped = if (extension.isEmpty()) {
                "$safeName - $safeId ($index)"
            } else {
                "$safeName - $safeId ($index).$extension"
            }
            candidate = PathUtils.join(workDir, deduped)
            index++
        }
        return candidate
    }

    fun update(
        rawData: Map<String, Any?> = this.rawData,
        songName: String? = this.songName,
        singers: String? = this.singers,
        album: String? = this.album,
        ext: String? = this.ext,
        fileSizeBytes: Long = this.fileSizeBytes,
        fileSize: String = this.fileSize,
        durationSeconds: Long = this.durationSeconds,
        duration: String = this.duration,
        lyric: String? = this.lyric,
        coverUrl: String? = this.coverUrl,
        downloadUrl: String? = this.downloadUrl,
        downloadUrlStatus: DownloadUrlStatus = this.downloadUrlStatus,
        identifier: String? = this.identifier,
        workDir: String = this.workDir,
        protocol: String = this.protocol,
        episodes: List<SongInfo> = this.episodes,
    ): SongInfo = copy(
        rawData = rawData,
        songName = songName,
        singers = singers,
        album = album,
        ext = ext,
        fileSizeBytes = fileSizeBytes,
        fileSize = fileSize,
        durationSeconds = durationSeconds,
        duration = duration,
        lyric = lyric,
        coverUrl = coverUrl,
        downloadUrl = downloadUrl,
        downloadUrlStatus = downloadUrlStatus,
        identifier = identifier,
        workDir = workDir,
        protocol = protocol,
        episodes = episodes,
    )

    companion object {
        /** Port of `SongInfo.seconds2hms` formatting used across every client. */
        fun formatDuration(seconds: Long): String {
            if (seconds <= 0L) return "-:-:-"
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }
    }
}

/** Port of the `download_url_status` dict produced by `AudioLinkTester.test`. */
data class DownloadUrlStatus(
    val ok: Boolean = false,
    val contentType: String = "NULL",
    val ext: String = "NULL",
    val originalDownloadUrl: String = "",
    val downloadUrl: String = "",
    val fileSize: String = "NULL",
    val fileSizeBytes: Long = 0L,
    val method: String = "",
    val statusCode: Int = 0,
    val reason: List<String> = emptyList(),
)
