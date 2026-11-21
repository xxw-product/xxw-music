package com.musicdl.android

/** Splits a track credit such as "Баста, Guf" or "A feat. B" into separate artists. */
object ArtistNames {
    private val SEPARATORS = Regex("""\s*(?:,|;|\s(?:feat\.?|ft\.?|при участии)\s)\s*""", RegexOption.IGNORE_CASE)

    // Lists recompose often and ask for the same credits again and again; the regex runs once per credit.
    private val cache = object : LinkedHashMap<String, List<String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 2048
    }

    fun split(credit: String?): List<String> {
        val text = credit.orEmpty()
        synchronized(cache) { cache[text] }?.let { return it }
        val parts = text.split(SEPARATORS).map { it.trim() }
            .filter { it.length >= 1 && !it.equals("NULL", ignoreCase = true) }
            .distinctBy { it.lowercase() }
        synchronized(cache) { cache[text] = parts }
        return parts
    }
}
