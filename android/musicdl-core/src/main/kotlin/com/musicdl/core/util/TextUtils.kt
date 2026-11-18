package com.musicdl.core.util

import java.util.Base64
import java.util.Locale

/**
 * Port of the lyric/duration helpers in `musicdl/modules/utils/misc.py`:
 * `cleanlrc`, `extractdurationsecondsfromlrc`, and the `to_seconds_func`
 * lambda the audiobook clients inline.
 */
object TextUtils {

    private val TIMESTAMP = Regex("""\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]""")
    private val TIMESTAMP_PARTS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val METADATA = Regex("""^\[(ti|ar|al|by|offset|length|re|ve|kana):.*]$""", RegexOption.IGNORE_CASE)

    /** Port of `cleanlrc`: drop timeline tags and metadata headers, keep the text. */
    fun cleanLrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n')
            .split('\n')
            .map(String::trim)
            .filter { it.isNotEmpty() && !METADATA.matches(it) }
            .map { TIMESTAMP.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n").ifBlank { null }
    }

    /** Port of `extractdurationsecondsfromlrc`: the last timeline stamp wins. */
    fun extractDurationSecondsFromLrc(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        var seconds = 0L
        for (match in TIMESTAMP_PARTS.findAll(raw)) {
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val secs = match.groupValues[2].toLongOrNull() ?: continue
            val total = minutes * 60 + secs
            if (total > seconds) seconds = total
        }
        return seconds
    }

    /** Port of the inline `to_seconds_func` used by the scrapers and audiobook clients. */
    fun parseDurationText(raw: String?): Long {
        val text = raw?.trim()?.lowercase(Locale.ROOT) ?: return 0L
        if (text.isEmpty()) return 0L
        if (text.contains(':') || text.contains('：')) {
            val parts = Regex("""\d+""").findAll(text.replace('：', ':')).map { it.value.toLong() }.toList()
            return when {
                parts.size >= 3 -> parts[parts.size - 3] * 3600 + parts[parts.size - 2] * 60 + parts[parts.size - 1]
                parts.size == 2 -> parts[0] * 60 + parts[1]
                parts.size == 1 -> parts[0]
                else -> 0L
            }
        }
        val hours = Regex("""(\d+)\s*(?:小时|时|h|hr)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val minutes = Regex("""(\d+)\s*(?:分钟|分|m|min)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val secs = Regex("""(\d+)\s*(?:秒|s|sec)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val total = hours * 3600 + minutes * 60 + secs
        return if (total > 0) total else Regex("""\d+""").find(text)?.value?.toLongOrNull() ?: 0L
    }

    /** URL-safe or standard base64, padding optional; null when the payload is not base64. */
    fun decodeBase64(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
    }

    fun encodeBase64UrlNoPadding(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
}
