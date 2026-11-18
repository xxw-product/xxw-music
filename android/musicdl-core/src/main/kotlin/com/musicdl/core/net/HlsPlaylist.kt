package com.musicdl.core.net

import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.util.Locale

internal object HlsPlaylist {
    data class Media(val extension: String, val resources: List<HttpUrl>)

    private data class Variant(val url: HttpUrl, val bandwidth: Long, val audioGroup: String?)
    private data class Rendition(val url: HttpUrl, val group: String?, val isDefault: Boolean)

    private val attribute = Regex("(?:^|,)\\s*([A-Z0-9-]+)=(?:\"([^\"]*)\"|([^,]+))")
    private val unsupportedTags = mapOf(
        "#EXT-X-SESSION-KEY" to "encryption",
        "#EXT-X-BYTERANGE" to "byte ranges",
        "#EXT-X-DISCONTINUITY" to "discontinuities",
        "#EXT-X-PART" to "partial segments",
        "#EXT-X-PRELOAD-HINT" to "partial segments",
        "#EXT-X-SKIP" to "skipped segments",
        "#EXT-X-GAP" to "missing segments",
        "#EXT-X-I-FRAMES-ONLY" to "I-frame playlists",
        "#EXT-X-DEFINE" to "playlist variables",
    )
    private val streamExtensions = mapOf(
        "aac" to "aac", "adts" to "aac", "mp3" to "mp3", "ac3" to "ac3",
        "ec3" to "ec3", "ts" to "ts", "m2ts" to "ts",
    )
    private val fragmentExtensions = setOf("mp4", "m4s", "m4a", "cmfa", "cmfv")

    fun resolve(response: Response, get: (HttpUrl) -> Response): Media {
        val lines = read(response)
        val base = response.request.url
        val rendition = selectRendition(lines, base) ?: return media(lines, base)
        return get(rendition).use { media(read(it), it.request.url) }
    }

    private fun read(response: Response): List<String> {
        val body = response.body ?: invalid("playlist response has no body")
        // Cap manifest size while the audio resources themselves are streamed to disk.
        val source = body.source()
        if (source.request(4L * 1024 * 1024 + 1)) invalid("playlist exceeds 4 MiB")
        val lines = source.readUtf8().removePrefix("\uFEFF").lineSequence()
            .map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U") invalid("expected #EXTM3U")
        for (line in lines) {
            val tag = line.substringBefore(':')
            unsupportedTags[tag]?.let(::unsupported)
            if (tag == "#EXT-X-KEY" && attributes(line)["METHOD"] != "NONE") unsupported("encryption")
        }
        return lines
    }

    private fun selectRendition(lines: List<String>, base: HttpUrl): HttpUrl? {
        val variants = mutableListOf<Variant>()
        val renditions = mutableListOf<Rendition>()
        for ((index, line) in lines.withIndex()) {
            when (line.substringBefore(':')) {
                "#EXT-X-STREAM-INF" -> {
                    val attrs = attributes(line)
                    val url = lines.getOrNull(index + 1)?.takeUnless { it.startsWith('#') }
                        ?: invalid("variant is missing its URI")
                    val bandwidth = (attrs["AVERAGE-BANDWIDTH"] ?: attrs["BANDWIDTH"])?.toLongOrNull()
                        ?: invalid("variant is missing a valid bandwidth")
                    variants += Variant(resolve(base, url), bandwidth, attrs["AUDIO"])
                }
                "#EXT-X-MEDIA" -> {
                    val attrs = attributes(line)
                    val uri = attrs["URI"]
                    if (attrs["TYPE"] == "AUDIO" && uri != null) {
                        renditions += Rendition(resolve(base, uri), attrs["GROUP-ID"], attrs["DEFAULT"] == "YES")
                    }
                }
            }
        }
        val variant = variants.maxByOrNull { it.bandwidth }
        val eligible = if (variant == null) renditions else renditions.filter { it.group == variant.audioGroup }
        return (eligible.firstOrNull { it.isDefault } ?: eligible.firstOrNull())?.url ?: variant?.url
    }

    private fun media(lines: List<String>, base: HttpUrl): Media {
        if (lines.none { it == "#EXT-X-ENDLIST" }) unsupported("live or nested playlists")
        val segments = mutableListOf<HttpUrl>()
        var initialization: HttpUrl? = null
        var needsUri = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    if ("BYTERANGE" in attrs) unsupported("byte ranges")
                    val map = resolve(base, attrs["URI"] ?: invalid("initialization segment is missing its URI"))
                    if (map != initialization && (initialization != null || segments.isNotEmpty())) {
                        unsupported("changing initialization segments")
                    }
                    initialization = map
                }
                line.startsWith("#EXTINF:") -> {
                    if (needsUri) invalid("segment is missing its URI")
                    needsUri = true
                }
                !line.startsWith('#') -> {
                    if (!needsUri) invalid("URI is missing #EXTINF")
                    segments += resolve(base, line)
                    needsUri = false
                }
            }
        }
        if (needsUri || segments.isEmpty()) invalid("playlist has no complete segment list")
        val extensions = segments.map { it.pathSegments.last().substringAfterLast('.', "").lowercase(Locale.ROOT) }.toSet()
        if (initialization != null) {
            if (extensions.any { it !in fragmentExtensions }) unsupported("unrecognized fragmented MP4 segment format")
            return Media("mp4", listOf(initialization) + segments)
        }
        val extension = extensions.map { streamExtensions[it] }.distinct().singleOrNull()
            ?: unsupported("unrecognized or mixed segment formats")
        return Media(extension, segments)
    }

    private fun attributes(line: String): Map<String, String> = attribute.findAll(line.substringAfter(':', ""))
        .associate { match -> match.groupValues[1] to (match.groups[2]?.value ?: match.groupValues[3].trim()) }

    private fun resolve(base: HttpUrl, uri: String): HttpUrl =
        base.resolve(uri.takeIf(String::isNotBlank) ?: invalid("empty URI")) ?: invalid("invalid URI")

    private fun invalid(reason: String): Nothing = throw IOException("Invalid HLS playlist: $reason")
    private fun unsupported(feature: String): Nothing = throw IOException("Unsupported HLS feature: $feature")
}
