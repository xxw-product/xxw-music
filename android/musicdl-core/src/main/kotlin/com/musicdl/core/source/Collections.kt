package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

enum class CollectionKind { ALBUM, PLAYLIST }

/** An album or a playlist found in a catalog; its tracks are fetched only when it is opened. */
data class MusicCollection(
    val source: String,
    val id: String,
    val kind: CollectionKind,
    val title: String,
    val owner: String? = null,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val year: String? = null,
)

/**
 * Pure JSON → model mapping for catalog collections, kept apart from the HTTP clients so it can be
 * tested on saved payloads. Tracks come back without a file link on purpose: resolving a stream
 * costs a request per track, so it happens when a track is actually played.
 */
object CollectionParsing {

    private fun JsonElement?.objects(): List<JsonObject> = (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    fun deezerCollections(body: String?, kind: CollectionKind, source: String): List<MusicCollection> =
        JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("data")).objects().mapNotNull { item ->
            val id = JsonUtil.str(item, "id") ?: return@mapNotNull null
            val title = JsonUtil.str(item, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MusicCollection(
                source = source, id = id, kind = kind, title = PathUtils.legalizeString(title) ?: title,
                owner = if (kind == CollectionKind.ALBUM) JsonUtil.extract(item, listOf("artist", "name"))
                else JsonUtil.extract(item, listOf("user", "name")),
                coverUrl = JsonUtil.str(item, "cover_xl") ?: JsonUtil.str(item, "cover_big")
                    ?: JsonUtil.str(item, "picture_xl") ?: JsonUtil.str(item, "picture_big"),
                trackCount = (JsonUtil.long(item, "nb_tracks") ?: 0L).toInt(),
                year = JsonUtil.str(item, "release_date")?.take(4),
            )
        }

    /** Returns the tracks and the URL of the next page, if any. */
    fun deezerTracks(body: String?, collection: MusicCollection): Pair<List<SongInfo>, String?> {
        val root = JsonUtil.parseObject(body) ?: return emptyList<SongInfo>() to null
        val tracks = JsonUtil.extractElement(root, listOf("data")).objects().mapNotNull { item ->
            if (item["readable"]?.toString() == "false") return@mapNotNull null
            val id = JsonUtil.str(item, "id") ?: return@mapNotNull null
            val title = JsonUtil.str(item, "title") ?: return@mapNotNull null
            val seconds = JsonUtil.long(item, "duration") ?: 0L
            SongInfo(
                source = collection.source, identifier = id,
                songName = PathUtils.legalizeString(title),
                singers = PathUtils.legalizeString(JsonUtil.extract(item, listOf("artist", "name")) ?: collection.owner),
                album = PathUtils.legalizeString(
                    JsonUtil.extract(item, listOf("album", "title")) ?: collection.title.takeIf { collection.kind == CollectionKind.ALBUM },
                ),
                durationSeconds = seconds, duration = SongInfo.formatDuration(seconds),
                coverUrl = JsonUtil.extract(item, listOf("album", "cover_xl")) ?: collection.coverUrl,
                rawData = mapOf("collection" to collection.title),
            )
        }
        return tracks to JsonUtil.str(root, "next")?.takeIf { it.startsWith("http") }
    }

    fun soundCloudCollections(body: String?, kind: CollectionKind, source: String): List<MusicCollection> =
        JsonUtil.extractElement(JsonUtil.parseObject(body), listOf("collection")).objects().mapNotNull { item ->
            val id = JsonUtil.str(item, "id") ?: return@mapNotNull null
            val title = JsonUtil.str(item, "title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val count = (JsonUtil.long(item, "track_count") ?: 0L).toInt()
            if (count == 0) return@mapNotNull null
            val firstCover = JsonUtil.extractElement(item, listOf("tracks")).objects()
                .firstNotNullOfOrNull { JsonUtil.str(it, "artwork_url") }
            MusicCollection(
                source = source, id = id, kind = kind, title = PathUtils.legalizeString(title) ?: title,
                owner = JsonUtil.extract(item, listOf("user", "username")),
                coverUrl = (JsonUtil.str(item, "artwork_url") ?: firstCover)?.replace("-large.", "-t500x500."),
                trackCount = count,
                year = (JsonUtil.str(item, "release_date") ?: JsonUtil.str(item, "created_at"))?.take(4),
            )
        }

    /** Ids of playlist entries that arrived as stubs (an id and nothing else) and need a second lookup. */
    fun soundCloudStubIds(playlistBody: String?): List<String> =
        JsonUtil.extractElement(JsonUtil.parseObject(playlistBody), listOf("tracks")).objects()
            .filter { JsonUtil.str(it, "title").isNullOrBlank() }.mapNotNull { JsonUtil.str(it, "id") }

    /** Playlist order is kept; [extra] holds the stub tracks fetched afterwards, keyed by id. */
    fun soundCloudTracks(playlistBody: String?, extra: List<JsonObject>, collection: MusicCollection): List<SongInfo> {
        val byId = extra.associateBy { JsonUtil.str(it, "id") }
        return JsonUtil.extractElement(JsonUtil.parseObject(playlistBody), listOf("tracks")).objects().mapNotNull { raw ->
            val item = if (JsonUtil.str(raw, "title").isNullOrBlank()) byId[JsonUtil.str(raw, "id")] ?: return@mapNotNull null else raw
            val id = JsonUtil.str(item, "id") ?: return@mapNotNull null
            val title = JsonUtil.str(item, "title") ?: return@mapNotNull null
            val seconds = (JsonUtil.long(item, "duration") ?: 0L) / 1000
            SongInfo(
                source = collection.source, identifier = id,
                songName = PathUtils.legalizeString(title),
                singers = PathUtils.legalizeString(
                    JsonUtil.extract(item, listOf("publisher_metadata", "artist")) ?: JsonUtil.extract(item, listOf("user", "username")),
                ),
                album = PathUtils.legalizeString(
                    JsonUtil.extract(item, listOf("publisher_metadata", "album_title"))
                        ?: collection.title.takeIf { collection.kind == CollectionKind.ALBUM },
                ),
                durationSeconds = seconds, duration = SongInfo.formatDuration(seconds),
                genre = JsonUtil.str(item, "genre"),
                coverUrl = JsonUtil.str(item, "artwork_url") ?: collection.coverUrl,
                rawData = mapOf("collection" to collection.title),
            )
        }
    }

    /** Standalone `tracks?ids=` answer: a JSON array of full track objects. */
    fun soundCloudTrackArray(body: String?): List<JsonObject> = JsonUtil.parse(body).objects()
}
