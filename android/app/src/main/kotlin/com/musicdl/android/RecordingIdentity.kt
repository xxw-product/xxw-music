package com.musicdl.android

import com.musicdl.core.model.SongInfo

/**
 * Provider-independent identity of a recording.
 *
 * `SongInfo.key()` is `source:identifier`, which is right for playback but wrong for ownership:
 * the same song comes back from Deezer, a mirror and the wave with three different identifiers, so
 * an exact key check declared a downloaded track "missing" and fetched it again. Title and artist
 * are what the user actually sees, so they decide whether two records are the same recording.
 */
internal fun sameRecording(
    titleA: String?,
    artistA: String?,
    titleB: String?,
    artistB: String?,
): Boolean {
    val title = normalizeRecordingPart(titleA)
    if (title.isEmpty() || title != normalizeRecordingPart(titleB)) return false
    return normalizeRecordingPart(artistA) == normalizeRecordingPart(artistB)
}

internal fun SongInfo.sameRecording(other: SongInfo): Boolean =
    sameRecording(songName, singers, other.songName, other.singers)

private fun normalizeRecordingPart(value: String?): String =
    value.orEmpty().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
