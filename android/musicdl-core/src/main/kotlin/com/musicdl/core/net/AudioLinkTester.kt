package com.musicdl.core.net

import com.musicdl.core.model.DownloadUrlStatus
import com.musicdl.core.model.PathUtils
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Port of `musicdl/modules/utils/misc.py::AudioLinkTester`.
 *
 * The Python version probes a candidate URL with HEAD, falls back to a streamed GET,
 * then infers the audio extension from URL suffix / Content-Type / Content-Disposition /
 * magic bytes. That inference chain is what decides whether a search hit is usable,
 * so it is ported faithfully rather than simplified.
 */
class AudioLinkTester(
    private val timeoutSeconds: Long = 15,
) {
    companion object {
        val VALID_AUDIO_EXTS: Set<String> = setOf(
            "aac", "aax", "aaxc", "ac3", "adts", "aif", "aifc", "aiff", "alac", "amr", "ape", "au",
            "avr", "awb", "caf", "cda", "dff", "dsf", "dss", "mdl", "dts", "dtshd", "ec3", "f32",
            "f64", "flac", "gsm", "hca", "htk", "iff", "ima", "ircam", "kar", "kss", "l16", "la",
            "m15", "m3u8", "m4a", "m4b", "hes", "m4p", "m4r", "m4s", "mat4", "mat5", "med", "mid",
            "midi", "mlp", "mod", "mo3", "mp1", "mp2", "mp3", "mpa", "mpc", "mpc2k", "mp+", "mpp",
            "ult", "gym", "msv", "mt2", "mtm", "mxmf", "nist", "nsa", "nsf", "oga", "ogg", "okt",
            "oma", "ofr", "ofs", "opus", "paf", "pcm", "psf", "psf1", "psf2", "ptm", "gsf", "pvf",
            "qsf", "ra", "ram", "raw", "rf64", "rmi", "rmj", "rmm", "rmx", "roq", "s3m", "sap",
            "sd2", "sds", "sf", "shn", "sid", "mptm", "ul", "xm", "gbs", "spu", "spx", "ssf", "stm",
            "snd", "tak", "thd", "tta", "spc", "sd2f", "umx", "usf", "miniusf", "voc", "vgm", "vgz",
            "wav", "wave", "w64", "it", "far", "wax", "wma", "wve", "wv", "wvx", "xi", "8svx",
            "16svx", "2sf", "3ga", "669", "aa3", "amf", "at3", "at9", "dmf", "weba",
            // encrypted containers the original also accepts
            "mflac", "mgg", "qmcflac", "qmc0", "qmc3", "qmcogg", "bkcmp3", "bkcflac", "tkm", "kgm",
            "vpr", "kwm", "ncm", "mg3d", "rar",
        )

        private val AUDIO_MIME_EXTRA = setOf(
            "application/octet-stream", "application/flac", "application/ogg",
            "application/vnd.apple.mpegurl", "application/x-flac", "application/x-mpegurl", "video/mp4",
        )

        private val EXT_ALIAS = mapOf(
            "mpeg" to "mp3", "mpga" to "mp3", "x-mp3" to "mp3", "x-mpeg" to "mp3",
            "wave" to "wav", "x-wav" to "wav", "oga" to "ogg", "x-ogg" to "ogg",
            "x-flac" to "flac", "x-aac" to "aac", "x-m4a" to "m4a", "mp4a" to "m4a",
        )

        private val MIME_TO_PREFERRED_EXT = mapOf(
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3", "audio/wav" to "wav", "audio/wave" to "wav",
            "audio/x-wav" to "wav", "audio/flac" to "flac", "audio/x-flac" to "flac",
            "audio/aac" to "aac", "audio/x-aac" to "aac", "audio/ogg" to "ogg",
            "audio/x-ogg" to "ogg", "audio/opus" to "opus", "audio/mp4" to "m4a",
            "audio/x-m4a" to "m4a", "audio/x-m4p" to "m4a", "video/mp4" to "m4a",
            "application/flac" to "flac", "application/x-flac" to "flac", "application/ogg" to "ogg",
            "application/x-mpegurl" to "m3u8", "application/vnd.apple.mpegurl" to "m3u8",
        )

        fun normalizeExt(ext: String?): String? {
            if (ext.isNullOrBlank()) return null
            val cleaned = ext.trim().lowercase(Locale.ROOT).removePrefix(".")
            if (cleaned.isEmpty()) return null
            return EXT_ALIAS[cleaned] ?: cleaned
        }

        fun isValidAudioExt(ext: String?): Boolean {
            val normalized = normalizeExt(ext) ?: return false
            return normalized in VALID_AUDIO_EXTS
        }

        fun normalizeContentType(contentType: String?): String? =
            contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

        fun isAudioMime(contentType: String?): Boolean {
            val ctype = normalizeContentType(contentType) ?: return false
            return ctype.startsWith("audio/") || ctype in AUDIO_MIME_EXTRA
        }

        /** Magic-byte sniffing for the formats that actually show up in these APIs. */
        fun sniffExt(bytes: ByteArray): String? {
            if (bytes.size < 4) return null
            fun at(i: Int) = bytes[i].toInt() and 0xFF
            return when {
                at(0) == 0x66 && at(1) == 0x4C && at(2) == 0x61 && at(3) == 0x43 -> "flac" // fLaC
                at(0) == 0x49 && at(1) == 0x44 && at(2) == 0x33 -> "mp3" // ID3
                at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3" // MPEG frame sync
                at(0) == 0x4F && at(1) == 0x67 && at(2) == 0x67 && at(3) == 0x53 -> "ogg" // OggS
                at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 -> "wav" // RIFF
                at(0) == 0x66 && at(1) == 0x74 && at(2) == 0x79 && at(3) == 0x70 -> "m4a" // ftyp
                at(0) == 0x23 && at(1) == 0x45 && at(2) == 0x58 -> "m3u8" // #EX
                else -> null
            }
        }
    }

    private val probeClient = HttpClient(maxRetries = 1, timeoutSeconds = timeoutSeconds)

    /**
     * Port of `AudioLinkTester.test`. Never throws: a dead link comes back with `ok = false`
     * and the reasons recorded, exactly like the Python version.
     */
    fun test(url: String?, extraHeaders: Map<String, String> = emptyMap()): DownloadUrlStatus {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            return DownloadUrlStatus(
                ok = false,
                originalDownloadUrl = url ?: "",
                downloadUrl = url ?: "",
                reason = listOf("invalid url: not an http(s) link"),
            )
        }

        val reasons = mutableListOf<String>()
        var statusCode = 0
        var finalUrl = url
        var contentType: String? = null
        var sizeBytes = 0L

        // 1) HEAD
        val headResponse = probeClient.head(url, extraHeaders, tag = "audio-link-head")
        if (headResponse != null) {
            headResponse.use { resp ->
                statusCode = resp.code
                finalUrl = resp.request.url.toString()
                contentType = resp.header("Content-Type")
                sizeBytes = parseSizeFromHeaders(resp.header("Content-Length"), resp.header("Content-Range"))
                reasons += "HEAD success: status=$statusCode, ctype=$contentType, final_url=$finalUrl"
                val ext = inferExt(url, finalUrl, contentType, resp.header("Content-Disposition"), ByteArray(0), reasons)
                if (ext != null) {
                    return DownloadUrlStatus(
                        ok = true,
                        contentType = contentType ?: "NULL",
                        ext = ext,
                        originalDownloadUrl = url,
                        downloadUrl = finalUrl,
                        fileSize = PathUtils.byteToMb(sizeBytes),
                        fileSizeBytes = sizeBytes,
                        method = "HEAD + inferred",
                        statusCode = statusCode,
                        reason = reasons,
                    )
                }
                reasons += "HEAD succeeded but no valid audio ext inferred; falling back to GET"
            }
        } else {
            reasons += "HEAD failed or returned non-2xx"
        }

        // 2) streamed GET
        val getResponse = probeClient.get(url, extraHeaders = extraHeaders, tag = "audio-link-get")
        if (getResponse != null) {
            getResponse.use { resp ->
                statusCode = resp.code
                finalUrl = resp.request.url.toString()
                contentType = resp.header("Content-Type")
                sizeBytes = parseSizeFromHeaders(resp.header("Content-Length"), resp.header("Content-Range"))
                reasons += "GET success: status=$statusCode, ctype=$contentType, final_url=$finalUrl"
                val sample = readSample(resp, 8192)
                val ext = inferExt(url, finalUrl, contentType, resp.header("Content-Disposition"), sample, reasons)
                return DownloadUrlStatus(
                    ok = ext != null,
                    contentType = contentType ?: "NULL",
                    ext = ext ?: "NULL",
                    originalDownloadUrl = url,
                    downloadUrl = finalUrl,
                    fileSize = PathUtils.byteToMb(sizeBytes),
                    fileSizeBytes = sizeBytes,
                    method = if (ext != null) "GET(stream) + inferred" else "GET(stream) + ext_inference_failed",
                    statusCode = statusCode,
                    reason = reasons,
                )
            }
        }
        reasons += "GET failed or returned non-2xx"

        return DownloadUrlStatus(
            ok = false,
            contentType = contentType ?: "NULL",
            ext = "NULL",
            originalDownloadUrl = url,
            downloadUrl = finalUrl,
            fileSize = PathUtils.byteToMb(sizeBytes),
            fileSizeBytes = sizeBytes,
            method = "HEAD -> GET (failed)",
            statusCode = statusCode,
            reason = reasons,
        )
    }

    /**
     * Cheap variant of [test] for sources that already know the link is audio.
     *
     * Deezer previews and similar CDN links carry the extension in the URL, so a single HEAD
     * settles it. The full [test] would stream 8 KB per candidate, which is what made a
     * Deezer-only search blow past the per-source timeout.
     */
    fun testFast(url: String?, extraHeaders: Map<String, String> = emptyMap()): DownloadUrlStatus {
        if (url.isNullOrBlank() || !url.startsWith("http")) {
            return DownloadUrlStatus(ok = false, originalDownloadUrl = url ?: "", downloadUrl = url ?: "")
        }
        val suffixExt = normalizeExt(suffixFromUrl(url))
        val response = probeClient.head(url, extraHeaders, tag = "audio-link-fast")
        if (response != null) {
            response.use { resp ->
                val finalUrl = resp.request.url.toString()
                val contentType = resp.header("Content-Type")
                val sizeBytes = parseSizeFromHeaders(resp.header("Content-Length"), resp.header("Content-Range"))
                val ext = suffixExt
                    ?: MIME_TO_PREFERRED_EXT[normalizeContentType(contentType)]
                    ?: normalizeExt(suffixFromUrl(finalUrl))
                if (resp.isSuccessful && ext != null && ext in VALID_AUDIO_EXTS) {
                    return DownloadUrlStatus(
                        ok = true,
                        contentType = contentType ?: "NULL",
                        ext = ext,
                        originalDownloadUrl = url,
                        downloadUrl = finalUrl,
                        fileSize = PathUtils.byteToMb(sizeBytes),
                        fileSizeBytes = sizeBytes,
                        method = "HEAD(fast)",
                        statusCode = resp.code,
                        reason = listOf("fast HEAD ok, ext from suffix/mime"),
                    )
                }
            }
        }
        // HEAD was refused or inconclusive: fall back to the full probe.
        return test(url, extraHeaders)
    }

    private fun inferExt(
        originalUrl: String,
        finalUrl: String,
        contentType: String?,
        contentDisposition: String?,
        sample: ByteArray,
        reasons: MutableList<String>,
    ): String? {
        val candidates = mutableListOf<Pair<String, String?>>()
        candidates += "url_suffix(original)" to suffixFromUrl(originalUrl)
        candidates += "url_suffix(final)" to suffixFromUrl(finalUrl)
        filenameFromDisposition(contentDisposition)?.let { name ->
            candidates += "content_disposition" to name.substringAfterLast('.', "")
        }
        candidates += "mime(content_type)" to MIME_TO_PREFERRED_EXT[normalizeContentType(contentType)]
        candidates += "magic_bytes" to sniffExt(sample)

        for ((strategy, raw) in candidates) {
            val ext = normalizeExt(raw)
            if (ext != null && ext in VALID_AUDIO_EXTS) return ext
            if (raw != null) reasons += "$strategy -> $raw (invalid or non-audio ext)"
        }
        return null
    }

    private fun suffixFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = url.substringBefore('?').substringBefore('#')
        val name = PathUtils.urlDecode(path.substringAfterLast('/'))
        if (name.isEmpty() || !name.contains('.')) return null
        return name.substringAfterLast('.').lowercase(Locale.ROOT).ifEmpty { null }
    }

    private fun filenameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        for (part in disposition.split(';').map { it.trim() }) {
            val lower = part.lowercase(Locale.ROOT)
            if (lower.startsWith("filename*=")) {
                val value = part.substringAfter('=').trim('"')
                return PathUtils.urlDecode(value.substringAfter("''", value))
            }
            if (lower.startsWith("filename=")) return part.substringAfter('=').trim('"')
        }
        return null
    }

    private fun parseSizeFromHeaders(contentLength: String?, contentRange: String?): Long {
        contentLength?.trim()?.toLongOrNull()?.let { return it }
        if (!contentRange.isNullOrBlank() && contentRange.contains('/')) {
            contentRange.substringAfterLast('/').trim().toLongOrNull()?.let { return it }
        }
        return 0L
    }

    private fun readSample(response: okhttp3.Response, maxBytes: Int): ByteArray {
        val body = response.body ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        runCatching {
            body.byteStream().use { stream ->
                val buffer = ByteArray(4096)
                var total = 0
                while (total < maxBytes) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    total += read
                }
            }
        }
        return out.toByteArray()
    }

    fun close() = probeClient.close()
}
