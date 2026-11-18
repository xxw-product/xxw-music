package com.musicdl.core.model

import java.text.Normalizer
import java.util.Locale

/** Compare artist credits only; a title mentioning the artist does not establish authorship. */
object ArtistIdentity {
    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun matches(song: SongInfo, artist: String): Boolean {
        val wanted = normalize(artist).takeIf { it.isNotBlank() && it != "null" } ?: return false
        val credit = song.singers?.takeUnless { it == "NULL" } ?: return false
        if (normalize(credit) == wanted) return true
        return credit.split(Regex("(?i)\\s*(?:,|;|/|&|\\bfeat\\.?|\\bft\\.?|\\bfeaturing\\b|\\s+x\\s+)\\s*"))
            .any { normalize(it) == wanted }
    }
}
