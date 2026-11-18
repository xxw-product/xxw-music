package com.musicdl.core.model

import java.net.URLDecoder
import java.util.Locale

/**
 * Port of the path/string helpers the Python clients lean on:
 * `pathvalidate.sanitize_filepath`, `legalizestring`, `AudioLinkTester.byte2mb`.
 */
object PathUtils {

    private val ILLEGAL = Regex("""[<>:"/\\|?*\u0000-\u001F]""")
    private val RESERVED_WINDOWS = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Conservative cross-platform filename sanitizer (Android-safe, Windows-safe). */
    fun sanitizeFileName(raw: String, maxLength: Int = 120): String {
        var name = ILLEGAL.replace(raw, "_").trim().trimEnd('.')
        if (name.isEmpty()) name = "NULL"
        if (name.substringBefore('.').uppercase(Locale.ROOT) in RESERVED_WINDOWS) name = "_$name"
        if (name.length > maxLength) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0 && name.length - dot <= 8) {
                name.take(maxLength - (name.length - dot)) + name.substring(dot)
            } else {
                name.take(maxLength)
            }
        }
        return name
    }

    /** Port of `legalizestring`: strip markup/entities, collapse whitespace, never return blank. */
    fun legalizeString(value: String?, fallback: String = "NULL"): String {
        if (value.isNullOrBlank()) return fallback
        var text = value
            .replace("\\\"", "\"")
            .replace("<\\/", "</")
            .replace("\\/>", "/>")
        text = UNICODE_ESCAPE.replace(text) { m -> m.groupValues[1].toInt(16).toChar().toString() }
        text = decodeEntities(text)
        text = text.replace(Regex("<[^>]*>"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text.ifBlank { fallback }
    }

    private val UNICODE_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    private fun decodeEntities(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x", true) -> body.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: match.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChar()?.toString() ?: match.value
            else -> NAMED_ENTITIES[body.lowercase(Locale.ROOT)] ?: match.value
        }
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "-", "mdash" to "-", "hellip" to "...", "middot" to "*", "laquo" to "\"",
        "raquo" to "\"", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
    )

    /** Port of `AudioLinkTester.byte2mb`. */
    fun byteToMb(size: Long): String {
        if (size <= 0L) return "NULL"
        val mb = Math.round(size.toDouble() / 1024.0 / 1024.0 * 100.0) / 100.0
        return if (mb == 0.0) "NULL" else "$mb MB"
    }

    fun join(vararg parts: String): String =
        parts.filter { it.isNotEmpty() }
            .joinToString("/")
            .replace(Regex("/+"), "/")

    /** Port of `urllib.parse.unquote` usage in extension inference. */
    fun urlDecode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
