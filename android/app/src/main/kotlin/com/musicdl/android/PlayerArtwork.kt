package com.musicdl.android

import android.content.Context
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.musicdl.core.model.SongInfo
import com.musicdl.core.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

internal data class PlayerArtwork(val cover: String?, val video: String?)

/** Persistent, bounded metadata cache; never cache throttling or temporary upstream failures. */
internal object PlayerArtworkRepository {
    suspend fun load(context: Context, song: SongInfo): PlayerArtwork? = withContext(Dispatchers.IO) {
        val title = song.songName?.takeUnless { it.isBlank() || it == "NULL" } ?: return@withContext null
        val artist = song.singers?.takeUnless { it.isBlank() || it == "NULL" } ?: return@withContext null
        val query = linkedMapOf("s" to title, "a" to artist)
        song.album?.takeUnless { it.isBlank() || it == "NULL" }?.let { query["al"] = it }
        if (song.durationSeconds > 0) query["d"] = song.durationSeconds.toString()
        val encoded = query.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val prefs = context.getSharedPreferences("player_artwork", Context.MODE_PRIVATE)
        val key = com.musicdl.core.util.Crypto.md5Hex("${song.source}:${song.identifier}:$encoded")
        val now = System.currentTimeMillis()
        val cached = runCatching { JSONObject(prefs.getString(key, "")!!) }.getOrNull()
        if (cached != null && now - cached.optLong("time") < 3 * 24 * 60 * 60 * 1000L) {
            return@withContext PlayerArtwork(cached.optString("cover").takeIf { it.startsWith("https://") },
                cached.optString("video").takeIf { it.startsWith("https://") })
        }
        fun request(path: String): kotlinx.serialization.json.JsonObject? {
            val connection = URL("https://artwork.boidu.dev/$path?$encoded").openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            return try {
                if (connection.responseCode != 200) null
                else connection.inputStream.bufferedReader().use { JsonUtil.parseObject(it.readText()) }
            } catch (_: java.io.IOException) { null } finally { connection.disconnect() }
        }
        fun kotlinx.serialization.json.JsonObject.link(name: String) = JsonUtil.str(this, name)?.takeIf { it.startsWith("https://") }
        val artwork = request("")
        // Motion artwork is independent of the music catalog transport.
        val canvas = request("canvas")
        // NopXx/apple-music-artwork-search's documented iTunes/CDN strategy, without a Node server.
        val still = song.coverUrl ?: artwork?.link("static") ?: appleStill(title, artist)
        val result = PlayerArtwork(still, canvas?.link("videoUrl")
            ?: artwork?.link("videoUrlVertical") ?: artwork?.link("videoUrl"))
        if (canvas != null && artwork != null) {
            if (prefs.all.size >= 200) prefs.edit().clear().apply()
            prefs.edit().putString(key, JSONObject().put("time", now)
                .put("cover", result.cover.orEmpty()).put("video", result.video.orEmpty()).toString()).apply()
        }
        result
    }

    private fun appleStill(title: String, artist: String): String? {
        val term = URLEncoder.encode("$artist $title", "UTF-8")
        val connection = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=5").openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        return try {
            if (connection.responseCode != 200) return null
            val root = connection.inputStream.bufferedReader().use { JsonUtil.parseObject(it.readText()) }
            fun normalized(value: String) = value.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")
            val match = JsonUtil.array(root, "results")?.filterIsInstance<kotlinx.serialization.json.JsonObject>()?.firstOrNull {
                normalized(JsonUtil.str(it, "trackName").orEmpty()) == normalized(title) &&
                    normalized(JsonUtil.str(it, "artistName").orEmpty()) == normalized(artist)
            }
            JsonUtil.str(match, "artworkUrl100")?.replace("100x100bb", "1200x1200bb")
        } catch (_: java.io.IOException) { null } finally { connection.disconnect() }
    }
}

/** Separate silent player: never takes audio focus or replaces the music session. */
@Composable
internal fun MotionArtwork(url: String, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner, player) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) player.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.release() }
    }
    LaunchedEffect(playing, resumed, player) { player.playWhenReady = playing && resumed }
    AndroidView(factory = { TextureView(it).apply { player.setVideoTextureView(this) } }, modifier = modifier)
}
