package com.musicdl.android

import com.musicdl.core.source.MusicClientRegistry

/**
 * Source sets.
 *
 * [RECOMMENDED_SOURCES] is what "Авто" searches: catalogs that answer anonymously and hand out a
 * playable file. Token-gated catalogs are [EXTRA_SOURCES]: available in the
 * picker, never enabled behind the user's back.
 */
private val DEFAULT_SOURCES: Set<String> = setOf(
    "QobuzMusicClient",
    "DeezerMusicClient",
    "SoundCloudMusicClient",
)

/** Search needs the user's own account; Yandex still imports playlist links without a token. */
private val TOKEN_GATED_SOURCES: Set<String> = setOf("YandexMusicClient")

val ALL_SOURCES: Set<String> = MusicClientRegistry.registeredModules - "VKMusicClient"

val RECOMMENDED_SOURCES: Set<String> = DEFAULT_SOURCES.filter { it in ALL_SOURCES }.toSet()

val DEFAULT_SELECTION: Set<String> = RECOMMENDED_SOURCES

/** Registered but not part of «Авто»: needs the user's own token. */
val EXTRA_SOURCES: Set<String> = ALL_SOURCES - RECOMMENDED_SOURCES

/** Kept for callers that still ask; the app serves full tracks everywhere. */
val PREVIEW_SOURCES: Set<String> = emptySet()

fun isPreviewSource(source: String): Boolean = source in PREVIEW_SOURCES
fun allowsPreviews(sources: Set<String>): Boolean = false
fun sourceName(source: String): String = source.removeSuffix("MusicClient")
fun needsToken(source: String): Boolean = source in TOKEN_GATED_SOURCES

fun supportedSources(sources: Set<String>?): Set<String> =
    sources.orEmpty().intersect(ALL_SOURCES).ifEmpty { DEFAULT_SELECTION }
