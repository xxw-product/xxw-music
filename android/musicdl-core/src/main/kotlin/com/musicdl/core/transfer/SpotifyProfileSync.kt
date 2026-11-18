package com.musicdl.core.transfer

import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Read-only Spotify Web API. Never sends the bearer token to an embed page or a foreign host. */
object SpotifyProfileSync {
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()

    fun playlistUrls(profile: String, token: String): List<String> {
        require(token.isNotBlank()) { "Spotify: для профиля нужен access token в настройках. Публичный плейлист можно перенести без него" }
        val uri = profile.toHttpUrl()
        require(uri.host == "open.spotify.com") { "Нужен профиль open.spotify.com/user/…" }
        val parts = uri.pathSegments.filter { it.isNotBlank() }.let { if (it.firstOrNull()?.startsWith("intl-") == true) it.drop(1) else it }
        require(parts.size == 2 && parts[0] == "user") { "Нужен профиль open.spotify.com/user/…" }
        val start = "https://api.spotify.com/v1/users/${parts[1]}/playlists?limit=50"
        return pages(start, token).mapNotNull { JsonUtil.str(it, "id") }
            .distinct().map { "https://open.spotify.com/playlist/$it" }
    }

    fun tracks(playlistId: String, token: String): List<SpotifyLinkTrack> =
        pages("https://api.spotify.com/v1/playlists/$playlistId/tracks?limit=100", token).mapNotNull { row ->
            val track = JsonUtil.obj(row, "track") ?: return@mapNotNull null
            val id = JsonUtil.str(track, "id") ?: return@mapNotNull null
            val name = JsonUtil.str(track, "name") ?: return@mapNotNull null
            val album = JsonUtil.obj(track, "album")
            SpotifyLinkTrack(id, name,
                JsonUtil.array(track, "artists").orEmpty().filterIsInstance<JsonObject>()
                    .mapNotNull { JsonUtil.str(it, "name") }.joinToString(", "),
                JsonUtil.str(album, "name").orEmpty(), (JsonUtil.long(track, "duration_ms") ?: 0L) / 1000,
                JsonUtil.array(album, "images").orEmpty().filterIsInstance<JsonObject>().firstOrNull()?.let { JsonUtil.str(it, "url") })
        }

    private fun pages(start: String, token: String): List<JsonObject> {
        val rows = mutableListOf<JsonObject>()
        val seen = mutableSetOf<String>()
        var next: String? = start
        while (next != null) {
            val url = next.toHttpUrl()
            require(url.scheme == "https" && url.host == "api.spotify.com" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()) { "Spotify: неверный адрес страницы API" }
            check(seen.add(next)) { "Spotify повторил страницу списка" }
            val root = http.newCall(Request.Builder().url(url).header("Authorization", "Bearer ${token.trim()}").build()).execute().use { response ->
                when (response.code) {
                    401 -> error("Spotify: токен истёк. Обновите access token в настройках")
                    403 -> error("Spotify: нет доступа к профилю или плейлисту. Проверьте права приложения")
                    429 -> error("Spotify ограничил запросы. Повторите позже")
                }
                check(response.isSuccessful) { "Spotify: HTTP ${response.code}" }
                JsonUtil.parseObject(response.body?.string()) ?: error("Spotify: неверный ответ API")
            }
            rows += JsonUtil.array(root, "items").orEmpty().filterIsInstance<JsonObject>()
            next = JsonUtil.str(root, "next")?.takeIf { it.startsWith("https://") }
        }
        return rows
    }
}
