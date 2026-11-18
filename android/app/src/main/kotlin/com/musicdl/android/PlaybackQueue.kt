package com.musicdl.android

import com.musicdl.core.model.SongInfo
import kotlin.random.Random

/**
 * Playback order and playback history are two different lists.
 *
 * The old code kept a single `queue`/`queueIndex` pair and, with shuffle on, picked a random
 * *index* on every "next". Two bugs followed: "previous" picked another random track instead of the
 * one that had just played, and a queue built from the visible feed was silently replaced by a
 * single track. Here:
 *
 * - [items] is the order the queue is in right now (what the queue sheet shows and what "next"
 *   walks);
 * - [history] is the stack of tracks that actually played, so "previous" is a real back step across
 *   playlists, manual picks, shuffle and «проигрывать следующим».
 */
class PlaybackQueue(
    songs: List<SongInfo> = emptyList(),
    index: Int = -1,
    shuffle: Boolean = false,
    history: List<SongInfo> = emptyList(),
    private val random: Random = Random.Default,
) {
    private val order = ArrayList<SongInfo>(songs)
    /** The user's own order, restored when shuffle is switched off. */
    private val natural = ArrayList<SongInfo>(songs)
    private val played = ArrayDeque<SongInfo>()

    var index: Int = if (order.isEmpty()) -1 else index.coerceIn(0, order.lastIndex)
        private set

    var shuffle: Boolean = shuffle
        private set

    init {
        history.forEach { played.addLast(it) }
        if (shuffle && order.size > 1) applyShuffle(keepCurrent = false)
    }

    val items: List<SongInfo> get() = order
    val size: Int get() = order.size
    val current: SongInfo? get() = order.getOrNull(index)
    val historyItems: List<SongInfo> get() = played.toList()
    val isEmpty: Boolean get() = order.isEmpty()

    fun keyOf(song: SongInfo): String = "${song.source}:${song.identifier ?: song.songName}"

    /** A new list (feed, playlist, artist page). History deliberately survives the switch. */
    fun setQueue(songs: List<SongInfo>, at: Int) {
        order.clear(); natural.clear()
        order += songs.distinctBy(::keyOf)
        natural += order
        index = if (order.isEmpty()) -1 else at.coerceIn(0, order.lastIndex)
        if (shuffle && order.size > 1) {
            val current = order.getOrNull(index)
            applyShuffle(keepCurrent = false)
            if (current != null) index = order.indexOfFirst { keyOf(it) == keyOf(current) }.coerceAtLeast(0)
        }
    }

    /** Selects a position in the current order; used by queue clicks and playlist rows. */
    fun playAt(position: Int): SongInfo? {
        if (position !in order.indices) return null
        index = position
        return order[position]
    }

    fun play(song: SongInfo): SongInfo {
        val position = order.indexOfFirst { keyOf(it) == keyOf(song) }
        index = if (position >= 0) position else {
            order += song
            natural += song
            order.lastIndex
        }
        return order[index]
    }

    /**
     * Records that [song] actually started playing. Consecutive repeats are collapsed so the
     * history stays a list of tracks, not of button presses.
     */
    fun markPlayed(song: SongInfo) {
        if (played.lastOrNull()?.let { keyOf(it) == keyOf(song) } == true) return
        played.addLast(song)
        while (played.size > HISTORY_LIMIT) played.removeFirst()
    }

    fun next(automatic: Boolean = false, repeatOne: Boolean = false): SongInfo? {
        if (order.isEmpty()) return null
        if (automatic && repeatOne) return order.getOrNull(index)
        index = if (index < 0) 0 else (index + 1) % order.size
        return order[index]
    }

    /**
     * Back to the track that actually played before this one. It is re-inserted when it is no
     * longer part of the queue (playlist switch), so playback continues instead of dead-ending.
     */
    fun previous(): SongInfo? {
        if (played.isEmpty()) return order.getOrNull(index)
        val current = order.getOrNull(index) ?: played.last()
        if (played.lastOrNull()?.let { keyOf(it) == keyOf(current) } == true) {
            played.removeLast()
        }
        val previous = played.lastOrNull() ?: run {
            played.addLast(current)
            return order.getOrNull(index)
        }
        val position = order.indexOfFirst { keyOf(it) == keyOf(previous) }
        index = if (position >= 0) position else {
            order.add((index + 1).coerceIn(0, order.size), previous)
            (index + 1).coerceIn(0, order.lastIndex)
        }
        return previous
    }

    /** «Проигрывать следующим»: directly after the current track, not at the end. */
    fun insertNext(song: SongInfo): Int {
        if (order.isEmpty()) {
            order += song; natural += song; index = 0
            return 0
        }
        val existing = order.indexOfFirst { keyOf(it) == keyOf(song) }
        if (existing >= 0) order.removeAt(existing)
        val target = (index + 1).coerceIn(0, order.size)
        order.add(target, song)
        if (natural.none { keyOf(it) == keyOf(song) }) natural.add(song)
        if (existing in 0..index) index--
        return target
    }

    fun append(songs: List<SongInfo>) {
        val fresh = songs.filter { song -> order.none { keyOf(it) == keyOf(song) } }
        if (fresh.isEmpty()) return
        if (order.isEmpty()) index = 0
        order += fresh
        natural += fresh
        if (shuffle) applyShuffle(keepCurrent = true)
    }

    fun removeAt(position: Int): SongInfo? {
        if (position !in order.indices) return null
        val removed = order.removeAt(position)
        natural.removeAll { keyOf(it) == keyOf(removed) }
        if (order.isEmpty()) index = -1
        else if (position < index) index--
        else if (position == index) index = index.coerceAtMost(order.lastIndex)
        return removed
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in order.indices || to !in order.indices || from == to) return false
        val song = order.removeAt(from)
        order.add(to, song)
        if (from == index) index = to
        else if (from < index && to >= index) index--
        else if (from > index && to <= index) index++
        natural.removeAll { keyOf(it) == keyOf(song) }
        natural.add(to.coerceIn(0, natural.size), song)
        return true
    }

    /** Swaps a refreshed snapshot in place (a view URL changed, the song did not). */
    fun replace(song: SongInfo): Boolean {
        var changed = false
        listOf(order, natural).forEach { list ->
            val at = list.indexOfFirst { keyOf(it) == keyOf(song) }
            if (at >= 0) { list[at] = song; changed = true }
        }
        if (played.any { keyOf(it) == keyOf(song) }) {
            val copy = played.toList()
            played.clear()
            copy.forEach { played.addLast(if (keyOf(it) == keyOf(song)) song else it) }
            changed = true
        }
        return changed
    }

    fun clear() {
        order.clear(); natural.clear(); index = -1
    }

    fun clearHistory() = played.clear()

    fun setShuffle(enabled: Boolean) {
        if (shuffle == enabled) return
        shuffle = enabled
        if (enabled) {
            applyShuffle(keepCurrent = true)
        } else {
            val current = current
            order.clear(); order += natural
            index = current?.let { song -> order.indexOfFirst { keyOf(it) == keyOf(song) } } ?: -1
        }
    }

    private fun applyShuffle(keepCurrent: Boolean) {
        val current = if (keepCurrent) order.getOrNull(index) else null
        val rest = order.filterNot { current != null && keyOf(it) == keyOf(current) }.shuffled(random)
        order.clear()
        if (current != null) order += current
        order += rest
        index = if (current != null) 0 else -1
    }

    private companion object {
        const val HISTORY_LIMIT = 60
    }
}
