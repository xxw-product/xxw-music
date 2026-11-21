package com.musicdl.android

/** Splits a track credit such as "Баста, Guf" or "A feat. B" into separate artists. */
object ArtistNames {
    private val SEPARATORS = Regex("""\s*(?:,|;|\s(?:feat\.?|ft\.?|при участии)\s)\s*""", RegexOption.IGNORE_CASE)

    fun split(credit: String?): List<String> =
        credit.orEmpty().split(SEPARATORS).map { it.trim() }
            .filter { it.length >= 1 && !it.equals("NULL", ignoreCase = true) }
            .distinctBy { it.lowercase() }
}
