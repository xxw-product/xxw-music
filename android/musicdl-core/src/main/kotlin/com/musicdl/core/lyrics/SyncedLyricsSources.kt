package com.musicdl.core.lyrics

import com.musicdl.core.model.SongInfo
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI

/** Public synchronized-lyrics index. Only the documented lrc.red TTML host is followed. */
internal class SyncedLyricsSources(private val get: (String, Map<String, String>) -> String?) {
    fun bini(query: LyricQuery, song: SongInfo): Lyrics? {
        val params = mutableMapOf("track" to query.title, "artist" to query.artist)
        if (song.durationSeconds > 0) params["duration"] = song.durationSeconds.toString()
        val data = get("https://lyrics-api.binimum.org/", params)?.let(JsonUtil::parseObject) ?: return null
        val candidates = (data["results"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            .filter { LyricQueries.matches(query.title, query.artist, JsonUtil.str(it, "track_name").orEmpty(), JsonUtil.str(it, "artist_name").orEmpty()) }
            .sortedBy { kotlin.math.abs((JsonUtil.long(it, "duration") ?: song.durationSeconds) - song.durationSeconds) }
        for (item in candidates.take(2)) {
            val duration = JsonUtil.long(item, "duration") ?: 0L
            if (duration > 0 && song.durationSeconds > 0 && kotlin.math.abs(duration - song.durationSeconds) > 8) continue
            val link = JsonUtil.str(item, "lyricsUrl") ?: continue
            val uri = runCatching { URI(link) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host != "lrc.red" || uri.userInfo != null) continue
            val xml = get(link, emptyMap()) ?: continue
            parseTtml(xml)?.let { return it }
        }
        return null
    }

    companion object {
        internal fun parseTime(raw: String): Long? = runCatching {
            val value = raw.trim()
            val seconds = when {
                value.endsWith("ms") -> value.dropLast(2).toDouble() / 1000
                value.endsWith("s") -> value.dropLast(1).toDouble()
                else -> value.split(':').fold(0.0) { sum, part -> sum * 60 + part.toDouble() }
            }
            if (!seconds.isFinite() || seconds < 0 || seconds > 86400) null else (seconds * 1000).toLong()
        }.getOrNull()

        internal fun parseTtml(xml: String): Lyrics? {
            if (xml.contains("<!DOCTYPE", true)) return null
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val lines = doc.getAllElements().filter { it.tagName().substringAfter(':') == "p" }.mapNotNull { p ->
                val start = parseTime(p.attr("begin")) ?: return@mapNotNull null
                val text = p.wholeText().trim()
                if (text.isBlank()) null else LyricLine(start, text)
            }.sortedBy { it.timeMs }
            return lines.takeIf { it.isNotEmpty() }?.let { Lyrics(lines = it, provider = "BiniLyrics · lrc.red") }
        }
    }
}
