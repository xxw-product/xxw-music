package com.musicdl.core.source

import com.musicdl.core.model.SongInfo

/** Refresh only the same catalog identity, never an approximate title match or another provider. */
suspend fun MusicClient.refreshTrack(song: SongInfo): SongInfo? {
    if (song.identifier.isNullOrBlank()) return null
    val query = listOfNotNull(song.singers?.takeUnless { it == "NULL" }, song.songName)
        .joinToString(" ").trim()
    if (query.isBlank()) return null
    return matchingRefreshedTrack(song, search(query, sourceTimeoutMs = 20_000L).values.flatten())
}

internal fun matchingRefreshedTrack(original: SongInfo, candidates: List<SongInfo>): SongInfo? =
    candidates.firstOrNull {
        original.identifier != null && it.source == original.source &&
            it.identifier == original.identifier && it.hasValidDownloadUrl &&
            (original.durationSeconds <= 35L || it.durationSeconds == 0L || it.durationSeconds > 35L)
    }?.let { fresh ->
        fresh.copy(workDir = original.workDir, lyric = fresh.lyric ?: original.lyric)
    }
