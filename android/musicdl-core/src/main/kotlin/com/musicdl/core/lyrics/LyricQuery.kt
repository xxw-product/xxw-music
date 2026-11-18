package com.musicdl.core.lyrics

import com.musicdl.core.model.SongInfo
import java.text.Normalizer
import java.util.Locale

internal data class LyricQuery(val title: String, val artist: String)

internal object LyricQueries {
    fun clean(value: String): String = value
        .replace(Regex("(?i)\\s*[\\[(][^\\])]*(?:official|audio|video|lyrics|slowed|reverb|sped|boosted|prod\\.?)[^\\])]*[\\])]"), " ")
        .replace(Regex("(?i)\\s+(?:prod\\.?|produced by)\\s+.*$"), "")
        .replace(Regex("\\s+"), " ").trim()

    fun normalized(value: String): String = Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun forSong(song: SongInfo): List<LyricQuery> {
        val title = clean(song.songName.orEmpty())
        val artist = song.singers?.takeUnless { it.isBlank() || it == "NULL" }.orEmpty()
        val candidates = mutableListOf<LyricQuery>()
        fun add(t: String, a: String) {
            if (t.isNotBlank() && a.isNotBlank()) candidates += LyricQuery(clean(t), a.trim())
        }
        add(title, artist)
        val parts = title.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        if (parts.size == 2) { add(parts[1], parts[0]); add(parts[0], parts[1]) }
        val lead = artist.split(Regex("(?i)\\s*(?:,|;|&|\\bfeat\\.?|\\bft\\.?)\\s*"))[0]
        add(title, lead)
        return candidates.distinct().take(4)
    }

    fun matches(title: String, artist: String, hitTitle: String, hitArtist: String): Boolean {
        val t = normalized(title); val a = normalized(artist)
        val ht = normalized(hitTitle); val ha = normalized(hitArtist)
        return t.isNotBlank() && a.isNotBlank() && t == ht &&
            (a == ha || " $ha ".contains(" $a ") || " $a ".contains(" $ha ") && ha.isNotBlank())
    }
}
