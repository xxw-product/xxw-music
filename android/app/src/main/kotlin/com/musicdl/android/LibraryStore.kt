package com.musicdl.android

import android.content.Context
import com.musicdl.core.model.SongInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Everything the app must remember across launches.
 *
 * The complaint "при каждом новом заходе все слетает" was literal: the ViewModel held the
 * selected sources, the last search, the wave and the likes in memory only, so a cold start
 * reset all of it. This store is the single place that survives a process death.
 *
 * Tracks are stored as JSON rather than as a database row because a liked track is a snapshot:
 * the upstream link expires, but the title, artist and cover stay true.
 */
class LibraryStore(context: Context) {

    private val prefs = context.getSharedPreferences("musicdl_library", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun playlists(): List<MusicPlaylist> = runCatching {
        json.parseToJsonElement(prefs.getString("playlists", "[]")!!).jsonArray.map { element ->
            val obj = element.jsonObject
            MusicPlaylist(obj.str("id")!!, obj.str("name")!!,
                decodeSongs(obj["tracks"].toString()), obj.str("spotifyOwner"), obj.str("spotifyId"),
                obj.str("syncUrl"), obj.str("lastSyncedAt")?.toLongOrNull() ?: 0L)
        }
    }.getOrDefault(emptyList())

    fun savePlaylists(items: List<MusicPlaylist>) {
        val array = kotlinx.serialization.json.JsonArray(items.map { playlist -> buildJsonObject {
            put("id", JsonPrimitive(playlist.id)); put("name", JsonPrimitive(playlist.name))
            put("spotifyOwner", JsonPrimitive(playlist.spotifyOwner)); put("spotifyId", JsonPrimitive(playlist.spotifyId))
            put("syncUrl", JsonPrimitive(playlist.syncUrl))
            put("lastSyncedAt", JsonPrimitive(playlist.lastSyncedAt.toString()))
            put("tracks", json.parseToJsonElement(encodeSongs(playlist.tracks)))
        } })
        prefs.edit().putString("playlists", array.toString()).apply()
    }

    // ---------------------------------------------------------------- settings

    var activeSources: Set<String>?
        get() = prefs.getStringSet(KEY_SOURCES, null)?.takeIf { it.isNotEmpty() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_SOURCES) else putStringSet(KEY_SOURCES, value)
            }.apply()
        }

    var lastKeyword: String
        get() = prefs.getString(KEY_KEYWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_KEYWORD, value).apply()

    var autoDownload: Boolean
        get() = prefs.getBoolean("auto_download", false)
        set(value) { prefs.edit().putBoolean("auto_download", value).apply() }
    var autoDownloadLimit: Int
        get() = prefs.getInt("auto_limit", 25).coerceIn(10, 200)
        set(value) { prefs.edit().putInt("auto_limit", value.coerceIn(10, 200)).apply() }

    /** Optional per-source credentials. Kept out of any public asset and never logged. */
    var vkToken: String
        get() = prefs.getString("vk_token", "").orEmpty()
        set(value) { prefs.edit().putString("vk_token", value.trim()).apply() }

    var spotifyToken: String
        get() = prefs.getString("spotify_sync_token", "").orEmpty()
        set(value) { prefs.edit().putString("spotify_sync_token", value.trim()).apply() }

    var yandexToken: String
        get() = prefs.getString("yandex_token", "").orEmpty()
        set(value) { prefs.edit().putString("yandex_token", value.trim()).apply() }

    /** Qobuz app credentials, so the user's own app id replaces the bundled one. */
    var qobuzAppId: String
        get() = prefs.getString("qobuz_app_id", "").orEmpty()
        set(value) { prefs.edit().putString("qobuz_app_id", value.trim()).apply() }

    var qobuzAppSecret: String
        get() = prefs.getString("qobuz_app_secret", "").orEmpty()
        set(value) { prefs.edit().putString("qobuz_app_secret", value.trim()).apply() }

    var qobuzUserToken: String
        get() = prefs.getString("qobuz_user_token", "").orEmpty()
        set(value) { prefs.edit().putString("qobuz_user_token", value.trim()).apply() }

    /** Optional SoundCloud API id; without it the client scrapes the public site. */
    var soundcloudClientId: String
        get() = prefs.getString("soundcloud_client_id", "").orEmpty()
        set(value) { prefs.edit().putString("soundcloud_client_id", value.trim()).apply() }

    fun automaticFiles(): Set<String> = prefs.getStringSet("automatic_files", emptySet()).orEmpty()
        .filter { java.io.File(it).isFile }.toSet()
    fun addAutomaticFile(path: String) {
        prefs.edit().putStringSet("automatic_files", automaticFiles() + path).apply()
    }
    fun updateMetadata(song: SongInfo) {
        val edit = prefs.edit()
        for (key in listOf(KEY_RESULTS, KEY_WAVE, KEY_LIKES, KEY_QUEUE, "downloaded_tracks")) {
            val tracks = decodeSongs(prefs.getString(key, null))
            if (tracks.any { it.key() == song.key() }) edit.putString(key, encodeSongs(tracks.map {
                if (it.key() == song.key()) it.copy(genre = song.genre, tags = song.tags) else it
            }))
        }
        edit.apply()
        savePlaylists(playlists().map { playlist -> playlist.copy(tracks = playlist.tracks.map {
            if (it.key() == song.key()) it.copy(genre = song.genre, tags = song.tags) else it
        }) })
    }

    // ---------------------------------------------------------------- likes

    /** Liked tracks, newest first. */
    fun likes(): List<SongInfo> = decodeSongs(prefs.getString(KEY_LIKES, null))

    fun isLiked(key: String): Boolean = likes().any { it.key() == key }

    /** Adds or removes a like; returns true when the track is liked afterwards. */
    /** Idempotent add used by imports: no duplicate likes, no reset of the existing list. */
    fun addLike(song: SongInfo): Boolean {
        if (isLiked(song.key())) return false
        prefs.edit().putString(KEY_LIKES, encodeSongs((listOf(song) + likes()).take(MAX_LIKES))).apply()
        return true
    }

    fun toggleLike(song: SongInfo): Boolean {
        val current = likes()
        val existing = current.firstOrNull { it.key() == song.key() }
        val updated = if (existing != null) {
            current.filterNot { it.key() == song.key() }
        } else {
            listOf(song) + current
        }
        prefs.edit().putString(KEY_LIKES, encodeSongs(updated.take(MAX_LIKES))).apply()
        return existing == null
    }

    /** Removes an offline copy: the file is gone, so the snapshot must stop claiming it is playable. */
    fun removeDownload(key: String) {
        val downloads = decodeSongs(prefs.getString("downloaded_tracks", null)).filterNot { it.key() == key }
        val likes = likes().filterNot { it.key() == key }
        prefs.edit()
            .putString("downloaded_tracks", encodeSongs(downloads))
            .putString(KEY_LIKES, encodeSongs(likes))
            .apply()
    }

    /** Snapshot of every downloaded track, in download order. */
    fun downloaded(): List<SongInfo> = decodeSongs(prefs.getString("downloaded_tracks", null))

    fun savedPathFor(key: String): String? = decodeSongs(prefs.getString("downloaded_tracks", null))
        .firstOrNull { it.key() == key }?.downloadUrl
        ?.let { runCatching { java.net.URI(it) }.getOrNull() }
        ?.takeIf { it.scheme == "file" }?.path

    fun saveDownload(song: SongInfo, path: String, addToLikes: Boolean = true) {
        val local = song.copy(downloadUrl = java.io.File(path).toURI().toString(), protocol = "HTTP",
            ext = java.io.File(path).extension)
        val updated = listOf(local) + likes().filterNot { it.key() == song.key() }
        val downloads = decodeSongs(prefs.getString("downloaded_tracks", null))
        val editor = prefs.edit()
        if (addToLikes) editor.putString(KEY_LIKES, encodeSongs(updated.take(MAX_LIKES)))
        editor
            .putString("downloaded_tracks", encodeSongs(listOf(local) + downloads.filterNot { it.key() == song.key() }))
            .apply()
    }

    /** Independent of likes: unliking a download must not disable offline playback. */
    fun localFileFor(song: SongInfo): java.io.File? {
        val downloads = decodeSongs(prefs.getString("downloaded_tracks", null)) + likes()
        val candidates = listOf(song) + downloads.filter { it.key() == song.key() || it.sameRecording(song) }
        return candidates.asSequence().mapNotNull { track ->
            val uri = runCatching { java.net.URI(track.downloadUrl.orEmpty()) }.getOrNull()
            if (uri?.scheme != "file") null else runCatching { java.io.File(uri) }.getOrNull()
        }.firstOrNull { it.isFile && it.length() > 0L }
    }

    fun removeLike(key: String) {
        val updated = likes().filterNot { it.key() == key }
        prefs.edit().putString(KEY_LIKES, encodeSongs(updated)).apply()
    }

    // ---------------------------------------------------------------- history

    /** Recently searched keywords, newest first, de-duplicated. */
    fun history(): List<String> = prefs.getString(KEY_HISTORY, "").orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun pushHistory(keyword: String) {
        val clean = keyword.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + history().filterNot { it.equals(clean, ignoreCase = true) })
            .take(MAX_HISTORY)
        prefs.edit().putString(KEY_HISTORY, updated.joinToString("\n")).apply()
    }

    fun clearHistory() = prefs.edit().remove(KEY_HISTORY).apply()

    // ---------------------------------------------------------------- last results

    /** The previous search, so the list is not empty on a cold start. */
    fun lastResults(): List<SongInfo> = decodeSongs(prefs.getString(KEY_RESULTS, null))

    fun saveResults(songs: List<SongInfo>) {
        prefs.edit().putString(KEY_RESULTS, encodeSongs(songs.take(MAX_RESULTS))).apply()
    }

    fun clearResults() = prefs.edit().remove(KEY_RESULTS).apply()

    fun updateStream(song: SongInfo) {
        val edit = prefs.edit()
        for (key in listOf(KEY_RESULTS, KEY_WAVE, KEY_LIKES, KEY_QUEUE)) {
            val tracks = decodeSongs(prefs.getString(key, null))
            if (tracks.any { it.key() == song.key() }) {
                edit.putString(key, encodeSongs(tracks.map {
                    if (it.key() == song.key() && !it.downloadUrl.orEmpty().startsWith("file:")) song else it
                }))
            }
        }
        edit.apply()
    }

    // ---------------------------------------------------------------- downloaded files

    fun savedFiles(): List<String> = decodeStringList(prefs.getString(KEY_SAVED_FILES, null))

    fun addSavedFile(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val updated = (listOf(clean) + savedFiles().filterNot { it == clean }).take(MAX_SAVED_FILES)
        prefs.edit().putString(KEY_SAVED_FILES, encodeStringList(updated)).apply()
    }

    fun removeSavedFile(path: String) {
        prefs.edit().putString(KEY_SAVED_FILES, encodeStringList(savedFiles().filterNot { it == path })).apply()
    }

    // ---------------------------------------------------------------- wave

    fun lastWave(): List<SongInfo> = decodeSongs(prefs.getString(KEY_WAVE, null))

    fun saveWave(songs: List<SongInfo>) {
        // The wave is a queue, not a search page: keep a generous ceiling instead of the 60-row
        // search history limit, so a large wave survives a restart.
        prefs.edit().putString(KEY_WAVE, encodeSongs(songs.take(MAX_WAVE))).apply()
    }

    // ---------------------------------------------------------------- playback

    /** Queue, cursor, position and playback history, so a cold start resumes exactly where it was. */
    data class PlaybackSnapshot(
        val queue: List<SongInfo>,
        val index: Int,
        val positionMs: Long,
        val history: List<SongInfo> = emptyList(),
        val shuffle: Boolean = false,
    )

    fun playback(): PlaybackSnapshot? {
        val songs = decodeSongs(prefs.getString(KEY_QUEUE, null))
        if (songs.isEmpty()) return null
        val index = prefs.getInt(KEY_QUEUE_INDEX, 0).coerceIn(0, songs.lastIndex)
        return PlaybackSnapshot(
            queue = songs,
            index = index,
            positionMs = prefs.getLong(KEY_POSITION, 0L),
            history = decodeSongs(prefs.getString(KEY_HISTORY_SONGS, null)),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
        )
    }

    fun savePlayback(queue: List<SongInfo>, index: Int, positionMs: Long,
                     history: List<SongInfo> = emptyList(), shuffle: Boolean = false) {
        if (queue.isEmpty()) return clearPlayback()
        prefs.edit()
            .putString(KEY_QUEUE, encodeSongs(queue))
            .putString(KEY_HISTORY_SONGS, encodeSongs(history.take(60)))
            .putInt(KEY_QUEUE_INDEX, index.coerceAtLeast(0))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, shuffle)
            .apply()
    }

    fun clearPlayback() = prefs.edit()
        .remove(KEY_QUEUE)
        .remove(KEY_QUEUE_INDEX)
        .remove(KEY_POSITION)
        .remove(KEY_HISTORY_SONGS)
        .remove(KEY_SHUFFLE)
        .apply()

    // ---------------------------------------------------------------- followed artists

    /** Artists the user follows, newest first. */
    fun followedArtists(): List<String> = prefs.getString(KEY_ARTISTS, "").orEmpty()
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "NULL" }

    fun isFollowing(artist: String): Boolean =
        followedArtists().any { it.equals(artist.trim(), ignoreCase = true) }

    /** Adds or removes a subscription; returns true when the artist is followed afterwards. */
    fun toggleFollowArtist(artist: String): Boolean {
        val clean = artist.trim()
        if (clean.isEmpty() || clean == "NULL") return false
        val current = followedArtists()
        val existing = current.any { it.equals(clean, ignoreCase = true) }
        val updated = if (existing) {
            current.filterNot { it.equals(clean, ignoreCase = true) }
        } else {
            listOf(clean) + current
        }
        prefs.edit().putString(KEY_ARTISTS, updated.take(MAX_ARTISTS).joinToString("\n")).apply()
        return !existing
    }

    // ---------------------------------------------------------------- navigation

    var tab: FeedTab
        get() = runCatching { FeedTab.valueOf(prefs.getString(KEY_TAB, FeedTab.WAVE.name).orEmpty()) }
            .getOrDefault(FeedTab.WAVE)
        set(value) = prefs.edit().putString(KEY_TAB, value.name).apply()

    // ---------------------------------------------------------------- serialization

    private fun encodeSongs(songs: List<SongInfo>): String {
        val array = kotlinx.serialization.json.JsonArray(
            songs.map { song ->
                buildJsonObject {
                    put("source", JsonPrimitive(song.source))
                    put("identifier", JsonPrimitive(song.identifier))
                    put("songName", JsonPrimitive(song.songName))
                    put("singers", JsonPrimitive(song.singers))
                    put("album", JsonPrimitive(song.album))
                    put("ext", JsonPrimitive(song.ext))
                    put("fileSize", JsonPrimitive(song.fileSize))
                    put("fileSizeBytes", JsonPrimitive(song.fileSizeBytes))
                    put("duration", JsonPrimitive(song.duration))
                    put("durationSeconds", JsonPrimitive(song.durationSeconds))
                    put("coverUrl", JsonPrimitive(song.coverUrl))
                    put("downloadUrl", JsonPrimitive(song.downloadUrl))
                    put("protocol", JsonPrimitive(song.protocol))
                    put("lyric", JsonPrimitive(song.lyric))
                    put("genre", JsonPrimitive(song.genre))
                    put("tags", JsonPrimitive(song.tags))
                    put("description", JsonPrimitive(song.description))
                    put("downloadHeaders", buildJsonObject {
                        song.downloadHeaders.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    })
                }
            },
        )
        return array.toString()
    }

    private fun decodeSongs(raw: String?): List<SongInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                SongInfo(
                    source = obj.str("source").orEmpty(),
                    identifier = obj.str("identifier"),
                    songName = obj.str("songName"),
                    singers = obj.str("singers"),
                    album = obj.str("album"),
                    ext = obj.str("ext"),
                    fileSize = obj.str("fileSize") ?: "NULL",
                    fileSizeBytes = obj.str("fileSizeBytes")?.toLongOrNull() ?: 0L,
                    duration = obj.str("duration") ?: "-:-:-",
                    durationSeconds = obj.str("durationSeconds")?.toLongOrNull() ?: 0L,
                    coverUrl = obj.str("coverUrl"),
                    downloadUrl = obj.str("downloadUrl"),
                    protocol = obj.str("protocol") ?: "HTTP",
                    lyric = obj.str("lyric"),
                    genre = obj.str("genre"), tags = obj.str("tags"), description = obj.str("description"),
                    downloadHeaders = (obj["downloadHeaders"] as? JsonObject)?.mapValues {
                        it.value.jsonPrimitive.content
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it != "null" }

    private fun encodeStringList(items: List<String>): String =
        json.encodeToString(items)

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_SOURCES = "active_sources"
        const val KEY_KEYWORD = "last_keyword"
        const val KEY_LIKES = "liked_tracks"
        const val KEY_HISTORY = "search_history"
        const val KEY_RESULTS = "last_results"
        const val KEY_WAVE = "last_wave"
        const val KEY_QUEUE = "playback_queue"
        const val KEY_QUEUE_INDEX = "playback_index"
        const val KEY_POSITION = "playback_position"
        const val KEY_TAB = "feed_tab"
        const val KEY_ARTISTS = "followed_artists"
        const val KEY_HISTORY_SONGS = "played_history"
        const val KEY_SHUFFLE = "playback_shuffle"
        const val MAX_LIKES = 500
        const val MAX_RESULTS = 60
        const val MAX_WAVE = 500
        const val MAX_HISTORY = 12
        const val MAX_ARTISTS = 60
        const val KEY_SAVED_FILES = "saved_files"
        const val MAX_SAVED_FILES = 400
    }
}
