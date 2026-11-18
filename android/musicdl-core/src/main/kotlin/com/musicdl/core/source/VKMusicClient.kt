package com.musicdl.core.source

import com.musicdl.core.model.PathUtils
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.textOrNull
import com.musicdl.core.util.JsonUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * VK Music as an optional extra catalog.
 *
 * Limits that shaped this implementation:
 *
 * 1. **Account required.** VK's current music surface is separate from the public v5 API. The
 *    client therefore requires a VK access token and uses the current catalog/audio request shape.
 *    If VK rejects the token for music access, the source remains disabled and reports the server
 *    error instead of pretending that the catalog is available.
 * 2. **Terms and copyright.** Audio returned by VK is licensed per account/region, and the platform
 *    terms forbid redistributing it. This client is a read-only personal-catalog bridge: it is not
 *    in the default source set, it hides nothing from VK, and it downloads only what the user's own
 *    token is allowed to stream. It must not be used to build a public catalog.
 * 3. **Unstable surface.** The method lives on `api.vk.com` under an API version that VK may retire.
 *    Every failure returns an empty list, so the rest of the app simply moves to the next provider.
 */
class VKMusicClient(config: MusicClientConfig) : BaseMusicClient(config) {

    override val source = "VKMusicClient"

    private val token: String? = listOf("vk_access_token", "access_token", "token")
        .firstNotNullOfOrNull { key -> config.defaultSearchCookies[key]?.trim()?.takeIf(String::isNotEmpty) }

    // VK's current music client uses a separate catalog/audio API surface.
    // Keep the public API version out of this client: audio.search on v5.x now
    // reports Unknown method for this flow.
    private val musicApiVersion = "8.154"
    private val musicApiHost = "https://api.vk.ru/method/"

    var lastError: String? = null
        private set

    /** Check the current VK Music catalog endpoint rather than the retired public audio.search call. */
    fun checkAccess(): String? {
        lastError = null
        if (token.isNullOrBlank()) return "VK: сначала войдите в аккаунт"
        val probe = musicApiCall(
            "catalog.getAudioSearch",
            mapOf("query" to "test", "extended" to "1", "need_blocks" to "1"),
        )
        if (probe == null) {
            return lastError ?: "VK Музыка не ответила. Повторите подключение"
        }
        return null
    }

    private fun apiError(error: JsonObject): String = when (JsonUtil.int(error, "error_code")) {
        5 -> if (JsonUtil.int(error, "error_subcode") == 1130)
            "VK: токен выдан для другой сети. Войдите заново на этом устройстве без смены VPN или подключения"
        else "VK: срок входа истёк. Подключите аккаунт заново"
        15, 7 -> "VK не разрешил доступ к музыке этому токену. Нужен вход с правом audio"
        14 -> "VK просит капчу. Откройте VK в браузере, подтвердите вход и повторите"
        17 -> "VK просит подтвердить аккаунт на своём сайте"
        3 -> "VK: этот метод недоступен для текущего токена"
        8 -> "VK: музыкальный API отклонил версию запроса"
        104 -> "VK: музыкальный раздел не найден или сессия устарела. Подключите VK заново"
        6, 9, 29 -> "VK ограничил частоту запросов. Подождите и повторите"
        else -> "VK: ошибка API ${JsonUtil.int(error, "error_code") ?: 0}. Повторите позже"
    }

    init {
        searchHeaders = searchHeaders + mapOf(
            "User-Agent" to "VKAndroidApp/8.30-24806 (Android 14; SDK 34; x86_64; ru; 1080x2400)",
            "Accept" to "application/json",
        )
        downloadHeaders = downloadHeaders + ("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/145.0.0.0 Mobile Safari/537.36")
    }

    override fun constructSearchUrls(keyword: String, rule: Map<String, String>): List<String> {
        if (token.isNullOrBlank()) {
            logger.warning("$source >>> no vk_access_token configured, skipping search")
            return emptyList()
        }
        // The actual request is a POST to catalog.getAudioSearch. The returned URL is only
        // a marker for BaseMusicClient's per-page progress accounting.
        return listOf("vk-music-search://${keyword.trim()}")
    }

    override suspend fun searchOne(
        keyword: String,
        searchUrl: String,
        results: MutableList<SongInfo>,
        progress: SearchProgress,
    ) {
        val root = musicApiCall(
            "catalog.getAudioSearch",
            mapOf(
                "query" to keyword,
                "extended" to "1",
                "need_blocks" to "1",
            ),
        ) ?: return

        val response = root["response"] as? JsonObject ?: return
        var items = collectAudioObjects(response)

        // Search results can be returned as a catalog pointer. Follow its default section
        // when the envelope does not contain the audio package directly.
        if (items.isEmpty()) {
            val catalog = JsonUtil.obj(response, "catalog")
            val sectionId = catalog?.let { JsonUtil.str(it, "default_section") }
            if (!sectionId.isNullOrBlank()) {
                musicApiCall(
                    "catalog.getSection",
                    mapOf("section_id" to sectionId, "extended" to "1", "need_blocks" to "1"),
                )?.let { sectionRoot ->
                    val sectionResponse = sectionRoot["response"] as? JsonObject
                    if (sectionResponse != null) items = collectAudioObjects(sectionResponse)
                }
            }
        }

        // Refresh stream URLs in small batches. VK may return metadata first and an empty/masked
        // URL in the search envelope; audio.getById is the normal follow-up for fresh URLs.
        val refreshed = refreshAudioItems(items)
        val usable = if (refreshed.isNotEmpty()) refreshed else items
        usable.forEachIndexed { index, item ->
            progress.onSourceTask(source, "$source._search >>> ${index + 1}/${usable.size} results", index + 1, usable.size)
            runCatching { parseSong(item) }.getOrNull()?.let(results::add)
            if (strictLimitSearchSizePerPage && results.size >= searchSizePerPage) return
        }
    }

    private fun collectAudioObjects(root: JsonObject): List<JsonObject> {
        val found = LinkedHashMap<String, JsonObject>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val id = JsonUtil.str(element, "id")
                    val owner = JsonUtil.str(element, "owner_id")
                    val title = JsonUtil.str(element, "title")
                    val artist = JsonUtil.str(element, "artist")
                    val looksLikeAudio = id != null && owner != null && title != null && artist != null
                    if (looksLikeAudio) found["$owner:$id"] = element
                    element.values.forEach(::walk)
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return found.values.toList()
    }

    private fun refreshAudioItems(items: List<JsonObject>): List<JsonObject> {
        if (items.isEmpty()) return emptyList()
        val ids = items.mapNotNull { item ->
            val owner = JsonUtil.str(item, "owner_id")
            val id = JsonUtil.str(item, "id")
            if (!owner.isNullOrBlank() && !id.isNullOrBlank()) "${owner}_$id" else null
        }.distinct()
        val refreshed = ArrayList<JsonObject>()
        ids.chunked(10).forEach { chunk ->
            val root = musicApiCall("audio.getById", mapOf("audios" to chunk.joinToString(","))) ?: return@forEach
            val response = root["response"] as? JsonArray ?: return@forEach
            response.filterIsInstance<JsonObject>().forEach { refreshed += it }
        }
        return refreshed
    }

    /**
     * Playlist and single-audio share links, through the same calls the Telegram plugin makes:
     * `audio.get` (owner + album + `access_key`, paged by 1000) for playlists and an owner's audio
     * list, `audio.getById` for one `/audio{owner}_{id}_{hash}` link.
     */
    override fun parsePlaylist(playlistUrl: String): List<SongInfo> {
        val link = parseVkLink(playlistUrl.trim()) ?: return emptyList()
        if (token.isNullOrBlank()) {
            logger.warning("$source >>> no vk_access_token configured, cannot read a playlist")
            return emptyList()
        }
        val items = when (link.kind) {
            "audio" -> musicApiCall("audio.getById", mapOf("audios" to link.audioId))
                ?.let { (it["response"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>() }
                .orEmpty()
            "playlist" -> audioPages(
                buildMap {
                    link.owner?.let { put("owner_id", it) }
                    link.album?.let { put("album_id", it) }
                    link.accessKey?.let { put("access_key", it) }
                },
            )
            "audios" -> audioPages(link.owner?.let { mapOf("owner_id" to it) }.orEmpty())
            else -> emptyList()
        }
        val name = listOfNotNull(link.owner, link.album ?: link.audioId).joinToString("-").ifBlank { "vk" }
        val workDir = constructUniqueWorkDir("vk-$name")
        // Library sync must keep metadata even when VK does not supply a stream URL.
        val songs = removeDuplicates(items.mapNotNull { item -> parseSong(item) ?: metadataSong(item) })
        val title = (items.firstOrNull()?.let { item -> JsonUtil.obj(item, "album")?.let { JsonUtil.str(it, "title") } }
            ?: songs.firstOrNull()?.album)?.takeIf { it.isNotBlank() }
        return songs.map { song ->
            song.copy(workDir = workDir, rawData = song.rawData + ("playlistTitle" to (title ?: "VK · $name")))
        }
    }

    private data class VkLink(
        val kind: String,
        val owner: String? = null,
        val album: String? = null,
        val accessKey: String? = null,
        val audioId: String = "",
    )

    /** Port of the plugin's `_parse_vk_url`: every share-link shape VK hands out. */
    private fun parseVkLink(url: String): VkLink? {
        Regex("""vk\.(?:com|ru)/music/(?:playlist|album)/(-?\d+)_(\d+)(?:_([0-9a-zA-Z]+))?""").find(url)?.let { match ->
            return VkLink(
                kind = "playlist",
                owner = match.groupValues[1],
                album = match.groupValues[2],
                accessKey = match.groupValues[3].ifEmpty { null },
            )
        }
        Regex("""act=audio_playlist(-?\d+)_(\d+)""").find(url)?.let { match ->
            val hash = Regex("""access_hash=([0-9a-zA-Z]+)""").find(url)?.groupValues?.get(1)?.ifEmpty { null }
            return VkLink("playlist", owner = match.groupValues[1], album = match.groupValues[2], accessKey = hash)
        }
        Regex("""vk\.(?:com|ru)/audio(-?\d+_\d+)(?:_([0-9a-zA-Z]+))?(?:[?/#]|$)""").find(url)?.let { match ->
            val hash = match.groupValues[2]
            return VkLink("audio", audioId = if (hash.isEmpty()) match.groupValues[1] else "${match.groupValues[1]}_$hash")
        }
        Regex("""vk\.(?:com|ru)/audios(-?\d+)""").find(url)?.let { match ->
            return VkLink("audios", owner = match.groupValues[1])
        }
        val uri = url.toHttpUrlOrNull() ?: return null
        if (uri.host !in setOf("vk.com", "vk.ru", "m.vk.com", "m.vk.ru")) return null
        val screen = uri.pathSegments.firstOrNull()?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: return null
        val numeric = Regex("id(\\d+)").matchEntire(screen)?.groupValues?.get(1)
        val owner = numeric ?: apiCall("users.get", mapOf("user_ids" to screen))
            ?.let { (it["response"] as? JsonArray)?.firstOrNull() as? JsonObject }
            ?.let { JsonUtil.str(it, "id") } ?: return null
        return VkLink("audios", owner = owner)
    }

    /** `audio.get` pages by a thousand items; the loop stops on an empty page or the reported total. */
    private fun audioPages(params: Map<String, String>): List<JsonObject> {
        val items = ArrayList<JsonObject>()
        var offset = 0
        while (true) {
            val root = musicApiCall("audio.get", params + mapOf("count" to "200", "offset" to offset.toString())) ?: break
            val response = root["response"]
            val page = when (response) {
                is JsonObject -> JsonUtil.array(response, "items").orEmpty().filterIsInstance<JsonObject>()
                is JsonArray -> response.filterIsInstance<JsonObject>()
                else -> emptyList()
            }
            if (page.isEmpty()) break
            items += page
            offset += page.size
            val total = (response as? JsonObject)?.let { JsonUtil.int(it, "count") } ?: 0
            if (total in 1..offset || page.size < 200) break
        }
        return items
    }

    private fun musicApiCall(method: String, params: Map<String, String>): JsonObject? {
        val access = token ?: return null
        val form = buildMap {
            put("access_token", access)
            put("v", musicApiVersion)
            put("lang", "ru")
            put("extended", "1")
            config.defaultSearchCookies["vk_device_id"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("device_id", it) }
            putAll(params)
        }
        val headers = searchHeaders + mapOf(
            "X-VK-Android-Client" to "new",
            "Referer" to "https://id.vk.ru",
            "Origin" to "https://id.vk.ru",
            "Accept" to "application/json",
        )
        val url = musicApiHost + method
        val root = http.postForm(url, form = form, extraHeaders = headers, tag = "vk-music-$method")
            ?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        JsonUtil.obj(root, "error")?.let { error ->
            lastError = apiError(error)
            logger.error("$source >>> $lastError")
            return null
        }
        return root
    }

    private fun apiCall(method: String, params: Map<String, String>): JsonObject? {
        val access = token ?: return null
        val url = "https://api.vk.com/method/$method".toHttpUrl().newBuilder().apply {
            setQueryParameter("access_token", access)
            setQueryParameter("v", "5.199")
            params.forEach { (key, value) -> setQueryParameter(key, value) }
        }.build().toString()
        val root = http.get(url, extraHeaders = searchHeaders)?.use { JsonUtil.parseObject(it.textOrNull()) } ?: return null
        JsonUtil.obj(root, "error")?.let { error ->
            lastError = apiError(error)
            logger.error("$source >>> $lastError")
            return null
        }
        return root
    }

    private fun metadataSong(item: JsonObject): SongInfo? {
        val id = JsonUtil.str(item, "id") ?: return null
        val title = JsonUtil.str(item, "title")?.takeIf { it.isNotBlank() } ?: return null
        val seconds = JsonUtil.long(item, "duration") ?: 0L
        return SongInfo(source = source, identifier = "${JsonUtil.str(item, "owner_id")}_$id",
            songName = title, singers = JsonUtil.str(item, "artist"),
            durationSeconds = seconds, duration = SongInfo.formatDuration(seconds))
    }

    private fun parseSong(item: JsonObject): SongInfo? {
        val rawUrl = JsonUtil.str(item, "url")?.takeIf { it.startsWith("http") } ?: return null
        // VK masks most playback links as `audio_api_unavailable` placeholders that only turn into
        // a real (often HLS) URL after decoding with the account id.
        val url = VkStreamUrlDecoder.decode(rawUrl, viewerId) ?: run {
            if (rawUrl.contains(VkStreamUrlDecoder.MARKER)) {
                logger.warning("$source >>> could not decode the masked stream url of ${JsonUtil.str(item, "id")}")
            }
            return null
        }
        val hls = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val title = PathUtils.legalizeString(JsonUtil.str(item, "title")) ?: return null
        val artist = PathUtils.legalizeString(JsonUtil.str(item, "artist"))
        val seconds = JsonUtil.long(item, "duration") ?: 0L
        val id = JsonUtil.str(item, "id") ?: return null
        val owner = JsonUtil.str(item, "owner_id")
        val album = JsonUtil.str(item, "album") ?: JsonUtil.str(JsonUtil.obj(item, "album"), "title")
        val status = downloadUrlStatusFor(url, hls)
        return SongInfo(
            source = source,
            identifier = if (owner != null) "${owner}_$id" else id,
            rawData = mapOf("search" to item),
            songName = title,
            singers = artist,
            album = PathUtils.legalizeString(album),
            durationSeconds = seconds,
            duration = SongInfo.formatDuration(seconds),
            ext = if (hls) "m3u8" else "mp3",
            fileSizeBytes = status.fileSizeBytes,
            fileSize = status.fileSize,
            coverUrl = JsonUtil.str(item, "photo_600") ?: JsonUtil.str(item, "thumb"),
            downloadUrl = url,
            downloadUrlStatus = status,
            downloadHeaders = downloadHeaders,
            protocol = if (hls) "HLS" else "HTTP",
        )
    }

    /** The `i` un-masking operation XORs its argument with the signed-in account id. */
    private val viewerId: Long by lazy {
        val root = apiCall("users.get", emptyMap()) ?: return@lazy 0L
        (root["response"] as? JsonArray)?.filterIsInstance<JsonObject>()
            ?.firstOrNull()?.let { JsonUtil.long(it, "id") } ?: 0L
    }

    /**
     * The audio URL is already handed out by the API; the size is unknown until the file is
     * requested, so a placeholder status marks it usable without an extra HEAD per track.
     */
    private fun downloadUrlStatusFor(url: String, hls: Boolean) = com.musicdl.core.model.DownloadUrlStatus(
        ok = true, contentType = if (hls) "application/vnd.apple.mpegurl" else "audio/mpeg",
        ext = if (hls) "m3u8" else "mp3", originalDownloadUrl = url,
        downloadUrl = url, fileSize = "NULL", fileSizeBytes = 0L, method = "GET", statusCode = 200,
    )
}
