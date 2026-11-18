package com.musicdl.core.net

import com.musicdl.core.model.ArtistIdentity
import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.MusicClientConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** What the vertical clip stage can show for a track. */
sealed interface ClipSpec {
    val provider: String
    val coverUrl: String?

    /** A real vertical/square music loop served as MP4. */
    data class Video(
        val url: String,
        override val provider: String,
        override val coverUrl: String? = null,
        val headers: Map<String, String> = emptyMap(),
    ) : ClipSpec

    /** No video provider answered: the UI animates the catalog artwork in 9:16 instead. */
    data class Motion(
        override val provider: String = "Обложка",
        override val coverUrl: String? = null,
    ) : ClipSpec
}

data class ClipAttempt(val provider: String, val result: String, val elapsedMs: Long)

data class ClipResolution(val clip: ClipSpec, val attempts: List<ClipAttempt> = emptyList()) {
    fun summary(): String = attempts.joinToString(" · ") { "${it.provider}: ${it.result}" }
}

/** One source of vertical clips. Returning null means "not available for this track", not "broken". */
interface ClipProvider {
    val name: String
    suspend fun load(song: SongInfo): ClipSpec.Video?
}

/**
 * Ordered provider chain for vertical clips.
 *
 * Rules that make it resilient:
 * - every provider has a hard timeout and one retry; a hang only costs its own slot;
 * - a provider throwing, timing out or answering `null` advances the chain;
 * - [MotionClipProvider] is last and cannot fail, so the clips screen always has something to show
 *   (a 9:16 animated cover) instead of an endless spinner.
 */
class VerticalClipResolver(
    private val config: MusicClientConfig,
    private val providers: List<ClipProvider> = listOf(
        AppleMotionClipProvider(config),
        AppleMusicVideoClipProvider(config),
    ),
    private val perProviderTimeoutMs: Long = 5_000L,
    private val attemptsPerProvider: Int = 2,
) {
    private val motion = MotionClipProvider()
    private val cache = java.util.Collections.synchronizedMap(LinkedHashMap<String, ClipResolution>())
    private val cacheLimit = 64

    suspend fun resolve(song: SongInfo): ClipResolution = withContext(Dispatchers.IO) {
        cache[song.key()]?.takeIf { it.clip is ClipSpec.Video }?.let { return@withContext it }
        // Providers run at the same time, not one after another: with a 7 s budget each, a slow
        // first provider used to consume the whole caller timeout and the second one was never
        // seen. The result still prefers the first provider in the list, but every provider gets
        // its full slot and every attempt is reported.
        val attemptsByProvider = java.util.concurrent.ConcurrentHashMap<String, ClipAttempt>()

        suspend fun attempt(provider: ClipProvider): ClipSpec.Video? {
            val started = System.currentTimeMillis()
            return try {
                val clip = withTimeoutOrNull(perProviderTimeoutMs) { provider.load(song) }
                val elapsed = System.currentTimeMillis() - started
                attemptsByProvider[provider.name] =
                    ClipAttempt(provider.name, if (clip != null) "ок" else "нет клипа", elapsed)
                clip
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val elapsed = System.currentTimeMillis() - started
                attemptsByProvider[provider.name] =
                    ClipAttempt(provider.name, "ошибка: ${error.message?.take(48) ?: "сеть"}", elapsed)
                config.logger.warning("Clip.${provider.name} >>> ${song.songName} (${error.message})")
                null
            }
        }

        val found = providers.map { provider -> async { provider to attempt(provider) } }.awaitAll()
        val log = providers.mapNotNull { attemptsByProvider[it.name] }
        val clip = found.firstNotNullOfOrNull { it.second }
        if (clip != null) return@withContext remember(song, ClipResolution(clip, log))
        val fallback = motion.load(song)
        remember(song, ClipResolution(ClipSpec.Motion(fallback?.provider ?: "Обложка", song.coverUrl), log))
    }

    private fun remember(song: SongInfo, resolution: ClipResolution): ClipResolution {
        if (cache.size >= cacheLimit) cache.clear()
        cache[song.key()] = resolution
        return resolution
    }

    private fun SongInfo.key(): String = "$source:${identifier ?: songName}"
}

/** Last provider in the chain: no network, never fails. */
class MotionClipProvider : ClipProvider {
    override val name = "Обложка"

    override suspend fun load(song: SongInfo): ClipSpec.Video? = null
}

/**
 * Apple Music motion artwork: the short vertical loops Music shows for editorial releases.
 *
 * `itunes.apple.com/search` supplies the catalog id anonymously; `amp-api` then answers the
 * `editorialVideo` block that carries `motionDetailTall` / `motionDetailSquare` MP4 URLs. The
 * developer token is scraped from the music.apple.com bundle exactly like the Python client does.
 * Tracks without motion artwork simply return null so the chain moves on.
 */
class AppleMotionClipProvider(
    private val config: MusicClientConfig,
    private val storefront: String = "us",
) : ClipProvider {

    override val name = "Apple Motion"

    private val http = HttpClient(
        logger = config.logger,
        maxRetries = 1,
        maintainSession = true,
        timeoutSeconds = 8,
        defaultHeaders = mapOf(
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        ),
    )

    private val tokenAt = AtomicLong(0L)
    @Volatile private var developerToken: String? = null

    override suspend fun load(song: SongInfo): ClipSpec.Video? = withContext(Dispatchers.IO) {
        val title = song.songName?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val artist = song.singers?.trim()?.takeIf { it.isNotEmpty() && it != "NULL" }
        val bearer = token() ?: return@withContext null
        val term = URLEncoder.encode(listOfNotNull(artist, title).joinToString(" "), "UTF-8")
        // A track can exist in one storefront and be missing from another, so try a few.
        for (market in listOf(storefront, "ru", "gb", "us").distinct()) {
            val body = http.get(
                "https://amp-api.music.apple.com/v1/catalog/$market/search",
                query = mapOf("term" to term, "types" to "songs", "limit" to "10"),
                extraHeaders = mapOf("Authorization" to "Bearer $bearer"),
            )?.use { it.textOrNull() } ?: continue
            val songs = JsonUtilObject.items(JsonUtilObject.path(body, listOf("results", "songs", "data")))
            val match = songs.firstOrNull { candidate ->
                val attributes = JsonUtilObject.path(candidate, listOf("attributes")) as? JsonObject ?: return@firstOrNull false
                val name = JsonUtilObject.str(attributes, "name").orEmpty()
                val artistName = JsonUtilObject.str(attributes, "artistName").orEmpty()
                name.similar(title) && (artist == null || ArtistIdentity.matches(SongInfo(singers = artistName), artist))
            } ?: continue
            val attributes = JsonUtilObject.path(match, listOf("attributes")) as? JsonObject ?: continue
            val video = JsonUtilObject.path(attributes, listOf("editorialVideo", "motionDetailTall", "video"))
                ?: JsonUtilObject.path(attributes, listOf("editorialVideo", "motionDetailSquare", "video"))
                ?: JsonUtilObject.path(attributes, listOf("editorialVideo", "motionSquareVideo1x1", "video"))
            val url = JsonUtilObject.text(video)?.takeIf { it.startsWith("https://") } ?: continue
            return@withContext ClipSpec.Video(
                url = url,
                provider = name,
                coverUrl = song.coverUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36"),
            )
        }
        null
    }

    /** Homepage → `assets/index-*.js` → the embedded `eyJ…` JWT, cached for ten minutes. */
    private fun token(): String? {
        val cached = developerToken
        if (cached != null && System.currentTimeMillis() - tokenAt.get() < 600_000L) return cached
        val home = http.get("https://music.apple.com/us/new")?.use { it.textOrNull() } ?: return cached
        val indexJs = Regex("(?:^|/)(assets/index[~-][^/\"]+\\.js)").find(home)?.groupValues?.get(1)
            ?: return cached
        val bundle = http.get("https://music.apple.com/$indexJs", query = mapOf("l" to "en-US"))
            ?.use { it.textOrNull() } ?: return cached
        val found = Regex("\"(eyJ[A-Za-z0-9\\-_]+\\.eyJ[A-Za-z0-9\\-_]+\\.[A-Za-z0-9\\-_]+)\"")
            .find(bundle)?.groupValues?.get(1) ?: return cached
        developerToken = found
        tokenAt.set(System.currentTimeMillis())
        return found
    }

    private fun String.similar(title: String): Boolean {
        val left = normalize(this)
        val right = normalize(title)
        if (left.isEmpty() || right.isEmpty()) return false
        return left == right || left.contains(right) || right.contains(left)
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace("ё", "е")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/**
 * Apple Music video preview.
 *
 * The iTunes Search API is key-less and returns a directly playable `previewUrl` (H.264 MP4) for
 * music videos. It is not vertical like Canvas, but it is a real clip for tracks that have no
 * motion artwork, and it lives on the same CDN as the rest of the Apple catalogue.
 */
class AppleMusicVideoClipProvider(
    private val config: MusicClientConfig,
    private val storefront: String = "us",
) : ClipProvider {
    override val name = "Apple Video"

    private val http = HttpClient(
        logger = config.logger,
        maxRetries = 1,
        timeoutSeconds = 8,
        defaultHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json",
        ),
    )

    override suspend fun load(song: SongInfo): ClipSpec.Video? = withContext(Dispatchers.IO) {
        val title = song.songName?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        val artist = song.singers?.trim()?.takeIf { it.isNotEmpty() && it != "NULL" }
        val term = URLEncoder.encode(listOfNotNull(artist, title).joinToString(" "), "UTF-8")
        // No `country` first: the storefront is inferred from the request and covers Russian
        // releases too. A US-only query used to hide most non-English videos.
        val queries = listOf(
            "https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=12&media=music",
            "https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=12&media=music&country=$storefront",
            "https://itunes.apple.com/search?term=$term&entity=musicVideo&limit=12&media=music&country=ru",
        )
        for (query in queries) {
            val body = http.get(query)?.use { it.textOrNull() } ?: continue
            val results = JsonUtilObject.items(JsonUtilObject.path(body, listOf("results")))
                .filter { !JsonUtilObject.str(it, "previewUrl").isNullOrBlank() }
            if (results.isEmpty()) continue
            val match = results.firstOrNull { item ->
                val name = JsonUtilObject.str(item, "trackName").orEmpty()
                val artistName = JsonUtilObject.str(item, "artistName").orEmpty()
                val wanted = title.normalizedForClip()
                val actual = name.normalizedForClip()
                val titleOk = actual == wanted || actual.contains(wanted) || wanted.contains(actual)
                val artistOk = artist == null || ArtistIdentity.matches(SongInfo(singers = artistName), artist)
                titleOk && artistOk
            } ?: results.firstOrNull { item ->
                val artistName = JsonUtilObject.str(item, "artistName").orEmpty()
                artist != null && ArtistIdentity.matches(SongInfo(singers = artistName), artist)
            } ?: continue
            val url = JsonUtilObject.str(match, "previewUrl")?.takeIf { it.startsWith("https://") } ?: continue
            return@withContext ClipSpec.Video(
                url = url,
                provider = name,
                coverUrl = song.coverUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36",
                    "Referer" to "https://music.apple.com/",
                ),
            )
        }
        null
    }

    private fun String.normalizedForClip(): String = lowercase(Locale.ROOT)
        .replace("ё", "е")
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/** Thin JSON helpers so this file does not depend on the source package internals. */
private object JsonUtilObject {
    fun parse(body: String): kotlinx.serialization.json.JsonElement? =
        runCatching { com.musicdl.core.util.JsonUtil.lenient.parseToJsonElement(body) }.getOrNull()

    fun path(body: String, keys: List<Any>): kotlinx.serialization.json.JsonElement? =
        com.musicdl.core.util.JsonUtil.extractElement(parse(body), keys)

    fun path(root: JsonObject, keys: List<Any>): kotlinx.serialization.json.JsonElement? =
        com.musicdl.core.util.JsonUtil.extractElement(root, keys)

    fun items(element: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
        (element as? kotlinx.serialization.json.JsonArray).orEmpty().filterIsInstance<JsonObject>()

    fun str(obj: JsonObject, key: String): String? = com.musicdl.core.util.JsonUtil.str(obj, key)

    fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        com.musicdl.core.util.JsonUtil.stringify(element)
}
