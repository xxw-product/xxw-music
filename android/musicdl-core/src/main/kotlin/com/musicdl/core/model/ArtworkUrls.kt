package com.musicdl.core.model

object ArtworkUrls {
    fun candidates(url: String): List<String> {
        val host = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val hq = when {
            host.endsWith("sndcdn.com") -> url.replace(Regex("-(?:large|small|t[0-9]+x[0-9]+)(?=\\.)"), "-t500x500")
            host.endsWith("saavncdn.com") -> url.replace(Regex("(?:50|150)x(?:50|150)(?=\\.)"), "500x500")
            host.endsWith("mzstatic.com") -> url.replace(Regex("[0-9]+x[0-9]+bb"), "1200x1200bb")
            else -> url
        }
        return listOf(hq, url).distinct()
    }
}
