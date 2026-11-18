package com.musicdl.core.model

/** Capture the list at the time of the tap; later searches must not replace a playing queue. */
data class PlaybackSelection(val songs: List<SongInfo>, val index: Int) {
    companion object {
        fun from(song: SongInfo, visible: List<SongInfo>): PlaybackSelection {
            val index = visible.indexOfFirst { sameTrack(it, song) }
            return if (index < 0) PlaybackSelection(listOf(song), 0) else PlaybackSelection(visible.toList(), index)
        }

        fun sameTrack(first: SongInfo, second: SongInfo): Boolean = first.source == second.source &&
            (first.identifier ?: first.songName) == (second.identifier ?: second.songName)
    }

    fun nextIndex(automatic: Boolean = false, repeatOne: Boolean = false): Int =
        if (songs.isEmpty()) -1 else if (automatic && repeatOne) index.coerceIn(songs.indices)
        else (index + 1).mod(songs.size)
}
