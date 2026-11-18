package com.musicdl.android

import android.content.Context
import com.musicdl.core.model.SongInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Listening evidence is separate from the library: resetting taste never removes music.
 *
 * Signals are intentionally small and additive — one full listen, one early skip, one dislike — and
 * every one of them carries its own timestamp so [Wave] can decay it. That is what makes the model
 * adapt when musical taste changes instead of permanently punishing an artist discovered once.
 */
class WaveStore(context: Context) {
    private val prefs = context.getSharedPreferences("wave_prefs", Context.MODE_PRIVATE)

    fun coefficients(): Map<String, Float> = Genres.byId.keys.associateWith {
        prefs.getFloat("coef_$it", Genres.DEFAULT_WEIGHT)
    }

    fun putCoefficient(id: String, value: Float) {
        if (id in Genres.byId && value.isFinite()) prefs.edit().putFloat("coef_$id", value.coerceIn(0f, 2f)).apply()
    }

    var generation: Int
        get() = prefs.getInt("generation", 0)
        set(value) { prefs.edit().putInt("generation", value).apply() }

    fun listening(): List<ListeningSignal> = runCatching {
        val raw = prefs.getString(KEY_V3, null) ?: prefs.getString(KEY_V2, "[]")
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            ListeningSignal(
                song = SongInfo(
                    source = item.optString("source"),
                    identifier = item.optString("id").takeIf { it.isNotBlank() },
                    songName = item.optString("title"),
                    singers = item.optString("artist"),
                    genre = item.optString("genre"),
                    tags = item.optString("tags"),
                    coverUrl = item.optString("cover").takeIf { it.isNotBlank() },
                ),
                plays = item.optInt("plays"),
                skips = item.optInt("skips"),
                lastPlayedAt = item.optLong("at"),
                shortListens = item.optInt("short"),
                repeats = item.optInt("repeats"),
                likes = item.optInt("likes"),
                dislikes = item.optInt("dislikes"),
                playlistAdds = item.optInt("playlist"),
                libraryAdds = item.optInt("library"),
                libraryRemovals = item.optInt("removed"),
                updatedAt = item.optLong("updated", item.optLong("at")),
            )
        }
    }.getOrDefault(emptyList())

    /** Legacy entry point kept for likes/skips coming from older call sites. */
    fun record(song: SongInfo, skipped: Boolean = false) = mutate(song) { signal ->
        val now = System.currentTimeMillis()
        if (skipped) signal.copy(skips = signal.skips + 1, updatedAt = now)
        else signal.copy(plays = signal.plays + 1, lastPlayedAt = now, updatedAt = now)
    }

    /**
     * A listen that crossed the threshold: full listens count fully, a half listen counts as a
     * partial positive, and a replay within 30 minutes strengthens the same recording.
     */
    fun recordListen(song: SongInfo, playedMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        val duration = durationMs.takeIf { it > 0L } ?: song.durationSeconds * 1000L
        val full = duration > 0L && playedMs >= duration * 0.85f
        val half = duration > 0L && playedMs >= duration * 0.5f
        mutate(song) { signal ->
            val replay = signal.lastPlayedAt > 0L && now - signal.lastPlayedAt in 1..1_800_000L
            when {
                full -> signal.copy(plays = signal.plays + 1, repeats = signal.repeats + if (replay) 1 else 0,
                    lastPlayedAt = now, updatedAt = now)
                half -> signal.copy(repeats = signal.repeats + if (replay) 1 else 0, lastPlayedAt = now, updatedAt = now)
                else -> signal.copy(shortListens = signal.shortListens + 1, lastPlayedAt = now, updatedAt = now)
            }
        }
    }

    /**
     * A quick skip, or a skip after a short listen. The second skip of the same recording within
     * five minutes counts double: it is a stronger signal than a single accidental tap.
     */
    fun recordSkip(song: SongInfo, playedMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        val duration = durationMs.takeIf { it > 0L } ?: song.durationSeconds * 1000L
        val quick = playedMs < 15_000L || (duration > 0L && playedMs < duration * 0.35f)
        mutate(song) { signal ->
            val repeated = signal.lastPlayedAt > 0L && now - signal.lastPlayedAt < 300_000L && signal.skips > 0
            when {
                quick -> signal.copy(skips = signal.skips + if (repeated) 2 else 1, lastPlayedAt = now, updatedAt = now)
                duration > 0L && playedMs < duration * 0.6f ->
                    signal.copy(shortListens = signal.shortListens + 1, lastPlayedAt = now, updatedAt = now)
                else -> signal
            }
        }
    }

    fun recordLike(song: SongInfo) = mutate(song) { it.copy(likes = it.likes + 1, updatedAt = System.currentTimeMillis()) }
    fun recordDislike(song: SongInfo) = mutate(song) { it.copy(dislikes = it.dislikes + 1, updatedAt = System.currentTimeMillis()) }
    fun recordPlaylistAdd(song: SongInfo) = mutate(song) { it.copy(playlistAdds = it.playlistAdds + 1, updatedAt = System.currentTimeMillis()) }
    fun recordLibraryAdd(song: SongInfo) = mutate(song) { it.copy(libraryAdds = it.libraryAdds + 1, updatedAt = System.currentTimeMillis()) }
    fun recordLibraryRemove(song: SongInfo) = mutate(song) { it.copy(libraryRemovals = it.libraryRemovals + 1, updatedAt = System.currentTimeMillis()) }

    fun updateMetadata(song: SongInfo) {
        write(listening().map {
            if (Wave.recordingKey(it.song) == Wave.recordingKey(song)) it.copy(song = it.song.copy(
                genre = song.genre, tags = song.tags, coverUrl = song.coverUrl ?: it.song.coverUrl,
            )) else it
        })
    }

    fun penalties(now: Long = System.currentTimeMillis()): Penalties = Wave.penalties(listening(), now)

    fun totalPlays(): Int = listening().sumOf { it.plays }

    /**
     * Old evidence disappears gradually: signals whose decayed weight has fallen below a floor and
     * that are older than half a year are dropped, so the file cannot grow forever.
     */
    fun prune(now: Long = System.currentTimeMillis()) {
        val kept = listening().filter { signal ->
            val age = now - maxOf(signal.lastPlayedAt, signal.updatedAt)
            Wave.rawScore(signal) != 0f || Wave.decay(age) > 0.05f
        }
        if (kept.size != listening().size) write(kept)
    }

    private fun mutate(song: SongInfo, transform: (ListeningSignal) -> ListeningSignal) {
        val current = listening()
        val key = Wave.recordingKey(song)
        val previous = current.firstOrNull { Wave.recordingKey(it.song) == key }
            ?: ListeningSignal(song = song, updatedAt = System.currentTimeMillis())
        val updated = transform(previous).copy(song = previous.song.copy(
            coverUrl = previous.song.coverUrl ?: song.coverUrl,
        ))
        write((listOf(updated) + current.filterNot { Wave.recordingKey(it.song) == key }).take(MAX_SIGNALS))
    }

    private fun write(signals: List<ListeningSignal>) {
        val array = JSONArray()
        signals.forEach { signal -> array.put(JSONObject().apply {
            put("source", signal.song.source)
            put("id", signal.song.identifier ?: signal.song.songName.orEmpty())
            put("title", signal.song.songName.orEmpty())
            put("artist", signal.song.singers.orEmpty())
            put("genre", signal.song.genre.orEmpty())
            put("tags", signal.song.tags.orEmpty())
            put("cover", signal.song.coverUrl.orEmpty())
            put("plays", signal.plays)
            put("skips", signal.skips)
            put("short", signal.shortListens)
            put("repeats", signal.repeats)
            put("likes", signal.likes)
            put("dislikes", signal.dislikes)
            put("playlist", signal.playlistAdds)
            put("library", signal.libraryAdds)
            put("removed", signal.libraryRemovals)
            put("at", signal.lastPlayedAt)
            put("updated", signal.updatedAt)
        }) }
        prefs.edit().putString(KEY_V3, array.toString()).apply()
    }

    fun reset() { prefs.edit().clear().apply() }

    private companion object {
        const val KEY_V3 = "listening_v3"
        const val KEY_V2 = "listening_v2"
        const val MAX_SIGNALS = 500
    }
}
