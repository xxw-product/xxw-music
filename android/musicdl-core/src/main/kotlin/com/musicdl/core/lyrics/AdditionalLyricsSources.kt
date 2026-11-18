package com.musicdl.core.lyrics

import com.musicdl.core.model.SongInfo
import com.musicdl.core.util.JsonUtil
import org.jsoup.Jsoup

/** Lyrics-only lookups: no audio resolution, accounts or API keys. Transport is injectable for tests. */
internal class AdditionalLyricsSources(private val get: (String, Map<String, String>) -> String?) {
    private fun json(url: String, query: Map<String, String>) = get(url, query)?.let(JsonUtil::parseObject)

    fun jioSaavn(song: SongInfo): Lyrics? {
        if (song.source != "JioSaavnMusicClient") return null
        val id = song.identifier ?: return null
        val data = json("https://www.jiosaavn.com/api.php", mapOf(
            "__call" to "lyrics.getLyrics", "ctx" to "web6dot0", "api_version" to "4",
            "_format" to "json", "_marker" to "0", "lyrics_id" to id,
        )) ?: return null
        val html = JsonUtil.str(data, "lyrics") ?: return null
        val document = Jsoup.parse(html.replace(Regex("(?i)<br\\s*/?>"), "\n"))
        return textLyrics(document.wholeText(), "JioSaavn")
    }

    companion object {
        /** No title-only matching: a namesake from another artist is not a fallback. */
        internal fun matches(title: String, artist: String, hitTitle: String, hitArtist: String): Boolean {
            return LyricQueries.matches(title, artist, hitTitle, hitArtist)
        }

        internal fun textLyrics(raw: String?, provider: String): Lyrics? {
            val text = raw?.trim()?.takeIf { it.isNotBlank() && it != "NULL" && !it.startsWith("http") } ?: return null
            val lines = LyricsFetcher.parseLrc(text)
            if (lines.isNotEmpty()) return Lyrics(lines = lines, plain = text, provider = provider)
            if (text.startsWith("{") || text.startsWith("<") || text.contains("<!DOCTYPE", true)) return null
            return Lyrics(plain = text, provider = provider)
        }
    }
}
