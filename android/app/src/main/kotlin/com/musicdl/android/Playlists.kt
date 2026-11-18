package com.musicdl.android

import com.musicdl.core.model.SongInfo

/** Imported playlists are snapshots, editable by making a local copy. */
data class MusicPlaylist(
    val id: String,
    val name: String,
    val tracks: List<SongInfo> = emptyList(),
    val spotifyOwner: String? = null,
    val spotifyId: String? = null,
    val syncUrl: String? = null,
    val lastSyncedAt: Long = 0L,
) {
    val imported: Boolean get() = spotifyOwner != null
}

internal fun quantity(count: Int, one: String, few: String, many: String): String {
    val word = when {
        count % 100 in 11..14 -> many
        count % 10 == 1 -> one
        count % 10 in 2..4 -> few
        else -> many
    }
    return "$count $word"
}

internal fun trackCount(count: Int): String = quantity(count, "трек", "трека", "треков")
