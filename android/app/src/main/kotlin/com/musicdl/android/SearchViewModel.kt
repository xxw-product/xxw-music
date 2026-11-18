package com.musicdl.android
import android.provider.Settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.musicdl.core.lyrics.Lyrics
import com.musicdl.core.lyrics.LyricsFetcher
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.ClipSpec
import com.musicdl.core.net.ClipResolution
import com.musicdl.core.net.VerticalClipResolver
import com.musicdl.core.source.ProviderCascade
import com.musicdl.core.transfer.YandexAuthException
import com.musicdl.core.transfer.SpotifyLinkParser
import com.musicdl.core.transfer.SpotifyParseResult
import com.musicdl.core.transfer.YandexMusicLibrary
import com.musicdl.core.transfer.YandexTrack
import com.musicdl.core.source.MusicClient
import com.musicdl.core.source.MusicClientConfig
import com.musicdl.core.source.MusicClientRegistry
import com.musicdl.core.source.SearchProgress
import com.musicdl.core.source.refreshTrack
import com.musicdl.core.util.LoggerHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.musicdl.core.source.TrackGenreClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Download lifecycle for one row, keyed by [SongInfo.key]. */
enum class SaveState { RUNNING, DONE, FAILED }

data class PlayerUiState(
    val song: SongInfo? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Largest artwork found for the current track; null until it resolves or when only the catalog cover exists. */
    val artworkUrl: String? = null,
    /** Spotify-Canvas analogue: a short silent vertical loop shown behind the player. */
    val canvas: ClipSpec.Video? = null,
) {
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class MusicUiState(
    val autoDownload: Boolean = false,
    val listeningCount: Int = 0,
    val searchArtistOnly: Boolean = false,
    val playlists: List<MusicPlaylist> = emptyList(),
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false,
    val playbackSpeed: Float = 1f,
    val queueItems: List<SongInfo> = emptyList(),
    val queueHistory: List<SongInfo> = emptyList(),
    val queueCount: Int = 0,
    val queuePosition: Int = 0,
    val downloads: List<DownloadEntry> = emptyList(),
    val downloadUsedBytes: Long = 0L,
    val downloadLimitBytes: Long = DownloadStore.DEFAULT_LIMIT,
    val downloadCovers: Boolean = true,
    val downloadLyrics: Boolean = true,
    val downloadClips: Boolean = false,
    val clips: ClipUiState = ClipUiState(),
    val importReport: YandexImportReport? = null,
    val isImporting: Boolean = false,
    val importProgress: String = "",
    val vkToken: String = "",
    val serviceConnecting: Boolean = false,
    val serviceStatus: String = "",
    val linkImportResult: String? = null,
    val yandexToken: String = "",
    val qobuzAppId: String = "",
    val qobuzAppSecret: String = "",
    val qobuzToken: String = "",
    val soundcloudClientId: String = "",
    val keyword: String = "",
    val isSearching: Boolean = false,
    val progressText: String = "",
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val results: List<SongInfo> = emptyList(),
    val activeSources: Set<String> = MusicClient.DEFAULT_MUSIC_SOURCES.toSet(),
    val player: PlayerUiState = PlayerUiState(),
    val saves: Map<String, SaveState> = emptyMap(),
    val savedFiles: List<String> = emptyList(),
    val error: String? = null,
    val wave: List<SongInfo> = emptyList(),
    val isWaveLoading: Boolean = false,
    val coefficients: Map<String, Float> = emptyMap(),
    /** Liked tracks, newest first. */
    val likes: List<SongInfo> = emptyList(),
    val likedKeys: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
    /** Which list the main feed shows. */
    val tab: FeedTab = FeedTab.SEARCH,
    /** Artists the user follows: their tracks live on the «Авторы» tab. */
    val followedArtists: List<String> = emptyList(),
    /** Artist whose tracks are open on the «Авторы» tab; null shows the subscription list. */
    val artistFeed: String? = null,
    val artistTracks: List<SongInfo> = emptyList(),
    val isArtistLoading: Boolean = false,
    /** True when the search came back empty and the recommended set would add new sources. */
    val canWiden: Boolean = false,
    /** Lyrics of the current track: timed lines when the provider has them, plain text otherwise. */
    val lyrics: Lyrics? = null,
    val isLyricsLoading: Boolean = false,
    val lyricsError: String? = null,
) {
    val allSourcesSelected: Boolean get() = activeSources.size >= MusicClientRegistry.registeredModules.size
}

enum class FeedTab { SEARCH, WAVE, LIKES, ARTISTS, SETTINGS }

/** A source that has not answered in this time is abandoned so the feed keeps moving. */
private const val SEARCH_TIMEOUT_MS = 20_000L

/** Whole-wave budget. The pipeline is bounded inside too; this is the last line of defence. */
private const val WAVE_BUDGET_MS = 45_000L

/** Clip resolution budget, including the last-resort cover motion fallback. */
private const val CLIP_BUDGET_MS = 20_000L

fun SongInfo.key(): String = "${source}:${identifier ?: songName}"

/**
 * Search + streaming + downloads + the personalized wave, backed by [LibraryStore].
 *
 * The engine calls are the ported [MusicClient]; playback is Media3 with the per-track headers the
 * audio tester used, so a link that validated in the engine streams here too.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val waveStore = WaveStore(application)
    private val library = LibraryStore(application)
    private val downloads = DownloadStore(application)
    private var waveJob: Job? = null
    private var waveEpoch = 0
    private var clipJob: Job? = null
    private val metadataClient = TrackGenreClient()
    private val metadataSlots = Semaphore(3)
    private val downloadSlots = Semaphore(2)
    private val automaticJobs = mutableMapOf<String, Job>()
    private var listenedMs = 0L
    private var listeningRecorded = false
    private var tasteEpoch = 0
    private var lastListeningTick = android.os.SystemClock.elapsedRealtime()
    private var lastTrackKey: String? = null
    private val clipResolver by lazy { VerticalClipResolver(clientConfig(), perProviderTimeoutMs = 7_000L) }

    /** What the last session was playing; restored so the panel is not empty on a cold start. */
    private val restoredPlayback = library.playback()

    /** Order and the history of tracks that really played; both survive a cold start. */
    private val playbackQueue = PlaybackQueue(
        songs = restoredPlayback?.queue.orEmpty(),
        index = restoredPlayback?.index ?: -1,
        shuffle = restoredPlayback?.shuffle ?: false,
        history = restoredPlayback?.history.orEmpty(),
    )

    /** 0..1 playback position, published every tick without touching [MusicUiState]. */
    private val _playerProgress = MutableStateFlow(0f)
    val playerProgress: StateFlow<Float> = _playerProgress.asStateFlow()

    private val _state = MutableStateFlow(
        MusicUiState(
            playlists = emptyList(),
            autoDownload = library.autoDownload,
            listeningCount = waveStore.totalPlays(),
            keyword = library.lastKeyword,
            activeSources = supportedSources(library.activeSources),
            coefficients = waveStore.coefficients(),
            results = library.lastResults().filter { it.source in ALL_SOURCES },
            // Offline fallback waves may contain local files whose "source" no longer exists in the
            // provider list; they are still playable, so they must survive a restart.
            wave = library.lastWave().filter { it.source in ALL_SOURCES || library.localFileFor(it) != null },
            likes = emptyList(),
            likedKeys = emptySet(),
            history = emptyList(),
            tab = library.tab,
            followedArtists = emptyList(),
            downloads = downloads.entries(),
            // Offline copies survive a restart; the action sheet must not offer to fetch them again.
            saves = downloads.entries().associate { it.key to SaveState.DONE },
            downloadUsedBytes = downloads.usedBytes(),
            downloadLimitBytes = downloads.limitBytes,
            downloadCovers = downloads.downloadCovers,
            downloadLyrics = downloads.downloadLyrics,
            downloadClips = downloads.downloadClips,
            vkToken = library.vkToken,
            yandexToken = library.yandexToken,
            qobuzAppId = library.qobuzAppId,
            qobuzAppSecret = library.qobuzAppSecret,
            qobuzToken = library.qobuzUserToken,
            soundcloudClientId = library.soundcloudClientId,
            shuffle = playbackQueue.shuffle,
            queueItems = playbackQueue.items,
            queueHistory = playbackQueue.historyItems,
            queueCount = playbackQueue.size,
            queuePosition = playbackQueue.index + 1,
            savedFiles = run {
                val fromStore = library.savedFiles()
                val dir = File((application.getExternalFilesDir(null) ?: application.filesDir), "musicdl_outputs")
                val onDisk = if (dir.exists()) dir.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "m3u8", "mp4", "ts") }.toList().sortedByDescending { it.lastModified() }.map { it.absolutePath } else emptyList()
                (fromStore + onDisk).distinct().filter { File(it).exists() }.take(400)
            },
            player = restoredPlayback?.let { snapshot ->
                val song = snapshot.queue.getOrNull(snapshot.index)
                PlayerUiState(
                    song = song,
                    positionMs = snapshot.positionMs,
                    durationMs = (song?.durationSeconds ?: 0L) * 1000L,
                )
            } ?: PlayerUiState(),
        ),
    )
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val logger = object : LoggerHandle {
        override fun info(message: String) { android.util.Log.d("musicdl", message) }
        override fun warning(message: String) { android.util.Log.w("musicdl", message) }
        override fun error(message: String) { android.util.Log.e("musicdl", message) }
    }

    private val playerListener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishPlayer()
                persistPlayback()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                publishPlayer()
                if (playbackState == Player.STATE_ENDED) advanceQueue(automatic = true)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                _state.update { it.copy(player = it.player.copy(isBuffering = false, isPlaying = false)) }
                logger.error("Playback failed: ${error.errorCodeName}: ${error.message}")
                val song = _state.value.player.song
                val sourceError = error.errorCode in 2000..2999 || error.errorCode == 4001
                if (song != null && sourceError && library.localFileFor(song) == null &&
                    !song.downloadUrl.orEmpty().startsWith("file:") && !refreshAttempted
                ) {
                    refreshPlayback(song, player.currentPosition.coerceAtLeast(0L))
                } else {
                    _messages.tryEmit("Не удалось открыть трек: ${error.errorCodeName}")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publishPlayer()
    }

    private val player: ExoPlayer = (PlayerHolder.player ?: ExoPlayer.Builder(application).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
    }).also { p ->
        PlayerHolder.player = p
        PlayerHolder.uiAttached = true
        // Ensure foreground service is running so playback survives backgrounding.
        runCatching { PlaybackService.start(getApplication()) }
        p.addListener(playerListener)
    }

    private var positionJob: Job? = null
    private var playbackPersistJob: Job? = null
    private var lastPositionSaveMs = android.os.SystemClock.elapsedRealtime()

    init {
        // Restore large JSON snapshots away from the main thread. A large downloaded library can
        // otherwise make the first wave tap look like an application hang/ANR.
        viewModelScope.launch(Dispatchers.IO) {
            val playlists = library.playlists()
            val likes = library.likes()
            val history = library.history()
            val followedArtists = library.followedArtists()
            val restoredWave = library.lastWave().filter { it.source in ALL_SOURCES || library.localFileFor(it) != null }
            val restoredResults = library.lastResults().filter { it.source in ALL_SOURCES }
            withContext(Dispatchers.Main.immediate) {
                _state.update { current ->
                    current.copy(
                        playlists = playlists,
                        likes = likes,
                        likedKeys = likes.map { it.key() }.toSet(),
                        history = history,
                        followedArtists = followedArtists,
                        wave = restoredWave,
                        results = restoredResults,
                    )
                }
            }
        }

        PlayerHolder.next = ::playNext
        PlayerHolder.previous = ::playPrevious
        positionJob = viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying) publishPlayer()
                observeListening()
                // Persist the cursor every ~3 s: a force-stop kills the process before onCleared
                // runs, so the periodic write is what actually survives a swipe-away.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPositionSaveMs >= 3000L) {
                    persistPlayback()
                    lastPositionSaveMs = now
                }
                delay(if (player.isPlaying) 100L else 500L)
            }
        }
    }

    private fun publishPlayer() {
        // An empty new ExoPlayer must not erase the restored cursor before playback starts.
        if (player.mediaItemCount == 0) return
        val duration = player.duration.takeIf { it > 0L } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0L)
        val playing = player.isPlaying
        val buffering = player.playbackState == Player.STATE_BUFFERING
        // The scrubber is a separate flow: it changes ten times a second, while the rest of the UI
        // state changes about once a second. Without this split every list row recomposed on each
        // position tick, which is what made the wave and search screens stutter during playback.
        _playerProgress.value = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val current = _state.value.player
        val secondChanged = position / 1_000L != current.positionMs / 1_000L
        if (current.isPlaying == playing && current.isBuffering == buffering &&
            current.durationMs == duration && !secondChanged
        ) return
        _state.update {
            it.copy(
                player = it.player.copy(
                    isPlaying = playing,
                    isBuffering = buffering,
                    positionMs = position,
                    durationMs = duration,
                ),
            )
        }
    }

    fun createPlaylist(name: String) {
        val title = name.trim().take(100).takeIf { it.isNotEmpty() } ?: return
        updatePlaylists(library.playlists() + MusicPlaylist(java.util.UUID.randomUUID().toString(), title))
    }

    fun renamePlaylist(id: String, name: String) {
        val title = name.trim().take(100).takeIf { it.isNotEmpty() } ?: return
        updatePlaylists(library.playlists().map { if (it.id == id && !it.imported) it.copy(name = title) else it })
    }

    fun deletePlaylist(id: String) = updatePlaylists(library.playlists().filterNot { it.id == id })

    fun copyPlaylist(id: String) {
        val original = library.playlists().firstOrNull { it.id == id } ?: return
        updatePlaylists(library.playlists() + original.copy(id = java.util.UUID.randomUUID().toString(),
            name = original.name + " · копия", spotifyOwner = null, spotifyId = null))
    }

    fun addToPlaylist(id: String, song: SongInfo) {
        val existing = library.playlists().firstOrNull { it.id == id }
        if (existing != null && existing.tracks.any { it.key() == song.key() }) {
            _messages.tryEmit("Уже в плейлисте")
            return
        }
        updatePlaylists(library.playlists().map {
            if (it.id == id && !it.imported) it.copy(tracks = (it.tracks + song).distinctBy { track -> track.key() }) else it
        })
        waveStore.recordPlaylistAdd(song)
        enrichMetadata(song)
    }

    fun removeFromPlaylist(id: String, key: String) = updatePlaylists(library.playlists().map {
        if (it.id == id && !it.imported) it.copy(tracks = it.tracks.filterNot { track -> track.key() == key }) else it
    })

    fun moveInPlaylist(id: String, key: String, direction: Int) = updatePlaylists(library.playlists().map {
        if (it.id != id || it.imported) it else {
            val tracks = it.tracks.toMutableList()
            val from = tracks.indexOfFirst { track -> track.key() == key }
            val to = from + direction
            if (from >= 0 && to in tracks.indices) java.util.Collections.swap(tracks, from, to)
            it.copy(tracks = tracks)
        }
    })

    private fun updatePlaylists(items: List<MusicPlaylist>) {
        library.savePlaylists(items)
        _state.update { it.copy(playlists = items) }
    }

    fun toggleShuffle() {
        playbackQueue.setShuffle(!_state.value.shuffle)
        publishQueue()
        persistPlayback()
    }
    fun toggleRepeat() { _state.update { it.copy(repeatOne = !it.repeatOne) } }
    fun cycleSpeed() {
        val speed = when (_state.value.playbackSpeed) { 1f -> 1.25f; 1.25f -> 1.5f; else -> 1f }
        player.setPlaybackSpeed(speed)
        _state.update { it.copy(playbackSpeed = speed) }
    }

    private val outputDir: File
        get() {
            val context = getApplication<Application>()
            return File(context.getExternalFilesDir(null) ?: context.filesDir, "musicdl_outputs")
        }

    /**
     * The search budget is per source, not per track, so a source that resolves links slowly
     * (Deezer walks converter mirrors) must not be given the same 8 hits as a fast one.
     */
    private fun clientConfig(): MusicClientConfig = MusicClientConfig(
        // 8 per source was the reason deeper matches never surfaced: a catalog answers twenty hits
        // and we kept the first eight.
        searchSizePerSource = 30,
        searchSizePerPage = 30,
        workDir = outputDir.absolutePath,
        logger = logger,
        defaultSearchCookies = buildMap {
            library.vkToken.takeIf { it.isNotBlank() }?.let { put("vk_access_token", it) }
            runCatching {
                Settings.Secure.getString(getApplication<Application>().contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("vk_device_id", it) }
            library.yandexToken.takeIf { it.isNotBlank() }?.let { put("yandex_token", it) }
            library.qobuzAppId.takeIf { it.isNotBlank() }?.let { put("qobuz_app_id", it) }
            library.qobuzAppSecret.takeIf { it.isNotBlank() }?.let { put("qobuz_app_secret", it) }
            library.qobuzUserToken.takeIf { it.isNotBlank() }?.let { put("user_auth_token", it) }
            library.soundcloudClientId.takeIf { it.isNotBlank() }?.let { put("client_id", it) }
        },
    )

    private fun buildClient(sources: Set<String>): MusicClient = MusicClient(
        musicSources = sources.toList(),
        baseConfig = clientConfig(),
    )

    /**
     * Results are ordered by how well they answer the query, not by size: a 1 MB Apple preview of
     * the track the user typed must sit above an unrelated 40 MB FLAC. Size only breaks ties.
     */
    private fun songOrder(keyword: String): Comparator<SongInfo> =
        compareByDescending<SongInfo> { relevance(it, keyword) }.thenByDescending { it.fileSizeBytes }

    /** How many query words appear in title/artist, with an exact title match weighted highest. */
    private fun relevance(song: SongInfo, keyword: String): Int {
        val query = keyword.trim().lowercase()
        if (query.isEmpty()) return 0
        val title = song.songName.orEmpty().lowercase()
        val haystack = "$title ${song.singers.orEmpty().lowercase()}"
        var score = query.split(' ').filter { it.isNotBlank() }.count { haystack.contains(it) } * 2
        if (title == query) score += 6
        if (title.contains(query)) score += 2
        return score
    }

    fun onKeywordChange(value: String) = _state.update { it.copy(keyword = value) }
    fun setSearchArtistOnly(value: Boolean) = _state.update { it.copy(searchArtistOnly = value) }

    fun setTab(tab: FeedTab) {
        library.tab = tab
        _state.update { it.copy(tab = tab) }
    }

    fun toggleSource(source: String) {
        if (source !in ALL_SOURCES) return
        _state.update { current ->
            val next = if (source in current.activeSources) {
                current.activeSources - source
            } else {
                current.activeSources + source
            }
            val effective = next.ifEmpty { setOf(source) }
            library.activeSources = effective
            current.copy(activeSources = effective)
        }
    }

    /** The "Авто" set: catalogs that answer without an account or a geo-block. */
    fun selectRecommendedSources() {
        library.activeSources = DEFAULT_SELECTION
        _state.update { it.copy(activeSources = DEFAULT_SELECTION) }
    }

    /** "Только этот источник" — the Deezer-alone case, with the rescue pass as a safety net. */
    fun selectOnlySource(source: String) {
        if (source !in ALL_SOURCES) return
        library.activeSources = setOf(source)
        _state.update { it.copy(activeSources = setOf(source)) }
    }

    fun selectAllSources() {
        val all = MusicClientRegistry.registeredModules
        library.activeSources = all
        _state.update { it.copy(activeSources = all) }
    }

    /** Re-run the previous keyword, e.g. from the history chips. */
    fun searchKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
        search()
    }

    fun search() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty() || _state.value.isSearching) return

        library.lastKeyword = keyword
        library.pushHistory(keyword)

        _state.update {
            it.copy(
                isSearching = true,
                results = emptyList(),
                saves = emptyMap(),
                error = null,
                tab = FeedTab.SEARCH,
                history = library.history(),
                progressText = "Готовлю источники: ${it.activeSources.size}…",
                progressCompleted = 0,
                progressTotal = 0,
            )
        }
        NowBarStatus.show(getApplication(), "XXW Music · поиск", "Ищу «$keyword»…")

        viewModelScope.launch {
            val sources = _state.value.activeSources
            val allowPreviews = allowsPreviews(sources)
            val client = buildClient(sources)
            val progress = object : SearchProgress {
                override fun onSourceTask(source: String, message: String, completed: Int, total: Int) {
                    _state.update {
                        it.copy(
                            progressText = "Ищу: ${source.removeSuffix("MusicClient")} · $completed/$total",
                            progressCompleted = completed,
                            progressTotal = total,
                        )
                    }
                }

                override fun onOverall(completed: Int, total: Int, message: String) {
                    _state.update { it.copy(progressCompleted = completed, progressTotal = total) }
                }
            }
            val streamed = java.util.Collections.synchronizedMap(LinkedHashMap<String, SongInfo>())
            fun snapshot(): List<SongInfo> = synchronized(streamed) {
                streamed.values.sortedWith(songOrder(keyword))
            }
            try {
                val bySource = client.search(
                    keyword = keyword,
                    progress = progress,
                    sourceTimeoutMs = SEARCH_TIMEOUT_MS,
                    onSourceResult = { source, songs ->
                        val fresh = songs.filter { isPlayable(it, allowPreviews) && matchesQuery(it, keyword) }
                            .filter { streamed.put(it.key(), it) == null }
                        if (fresh.isNotEmpty()) {
                            _state.update { current ->
                                current.copy(
                                    results = snapshot(),
                                    progressText = "${source.removeSuffix("MusicClient")}: +${fresh.size}",
                                )
                            }
                        }
                    },
                )
                val flat = bySource.values.flatten()
                    .filter { isPlayable(it, allowPreviews) && matchesQuery(it, keyword) }
                    .distinctBy { it.key() }
                    .sortedWith(songOrder(keyword))

                // Nothing is widened behind the user's back: the chosen sources are the chosen
                // sources. An empty feed gets an explicit «Искать в рекомендованных» instead.
                if (flat.isNotEmpty()) library.saveResults(flat)
                _state.update {
                    it.copy(
                        isSearching = false,
                        results = flat,
                        progressText = if (flat.isEmpty()) "Нет доступных треков" else "Треков готово: ${flat.size}",
                        error = if (flat.isEmpty()) emptyResultHint(sources) else null,
                        canWiden = flat.isEmpty() && (RECOMMENDED_SOURCES - sources).isNotEmpty(),
                    )
                }
                if (flat.isNotEmpty()) _messages.tryEmit("Готово треков: ${flat.size}")
                NowBarStatus.complete(getApplication(), "Поиск завершён · ${flat.size} треков")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _state.update {
                    it.copy(
                        isSearching = false,
                        results = snapshot(),
                        error = err.message ?: "Поиск не удался",
                    )
                }
                NowBarStatus.complete(getApplication(), "Поиск не удался")
            } finally {
                // Closing the OkHttp pool touches sockets, so it must stay off the main thread.
                withContext(Dispatchers.IO) { client.close() }
            }
        }
    }

    /**
     * A single-source search has no fallback, so an empty result there is a different problem
     * from an empty result across many sources. Saying which one it is saves the user a guess.
     */
    private fun emptyResultHint(sources: Set<String>): String {
        val names = sources.map { it.removeSuffix("MusicClient") }.sorted()
        return when {
            names.size == 1 -> "${names.first()} не дал рабочей ссылки на этот запрос. " +
                "Попробуйте другое написание или включите ещё источники."
            else -> "Ни один из источников (${names.size}) не дал рабочей ссылки — попробуйте другой запрос."
        }
    }

    /**
     * «Моя волна»: bounded at every stage, and always ends in a playable queue or a clear error.
     * No artist search, genre search or metadata lookup can hold [MusicUiState.isWaveLoading].
     */
    fun startWave() {
        if (waveJob?.isActive == true) return
        val epoch = ++waveEpoch
        _state.update { it.copy(isWaveLoading = true, error = null, progressText = "Волна: собираем профиль") }
        NowBarStatus.show(getApplication(), "XXW Music · Моя волна", "Подбираем музыку…")
        waveJob = viewModelScope.launch {
            try {
                // Treat the actual music library as taste evidence too. Previously downloaded
                // tracks were only used by the offline fallback, so a user with a full local
                // library but few likes had no artist profile and got generic/empty catalog hits.
                val (saved, libraryTracks, likesSnapshot) = withContext(Dispatchers.IO) {
                    val savedTracks = library.playlists().flatMap { it.tracks }
                    Triple(savedTracks, library.downloaded(), library.likes())
                }
                // A large local library is legitimate taste data, but building the taste model
                // is CPU work. With ~2000 downloaded tracks the old code performed distinctBy(),
                // genre inference, artist normalization and profile scoring on the UI thread;
                // that could block Android's main looper long enough to produce an ANR exactly
                // after the progress bar reached the "уточняем вкус" stage.
                val (seeds, profile) = withContext(Dispatchers.Default) {
                    val tasteTracks = (likesSnapshot + saved + libraryTracks).distinctBy { it.key() }
                    val listening = waveStore.listening()
                    val seeds = (tasteTracks + listening.map { it.song }).distinctBy { it.key() }
                    val profile = Wave.profile(
                        _state.value.coefficients,
                        likesSnapshot,
                        listening,
                        library.followedArtists(),
                        saved = (saved + libraryTracks).distinctBy { it.key() },
                    )
                    seeds to profile
                }
                _state.update { it.copy(progressText = "Волна: уточняем вкус") }
                withContext(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        // A failed metadata lookup must never abort the whole wave.
                        coroutineScope {
                            seeds.take(12).map { song -> async { runCatching { enrichMetadataNow(song) } } }.awaitAll()
                        }
                    }
                }
                // The wave always has the anonymous catalogs as a floor, even if the user narrowed
                // the search to a token-gated source that is not configured.
                val sources = (supportedSources(_state.value.activeSources) + RECOMMENDED_SOURCES).toSet()
                val engine = WaveEngine(
                    config = clientConfig(),
                    sources = sources,
                    accept = { isPlayable(it, allowsPreviews(sources)) },
                    enrich = { enrichMetadataNow(it) },
                    report = { text -> _state.update { it.copy(progressText = text) } },
                )
                val recent = _state.value.wave.map(Wave::recordingKey).toSet()
                // Hard stop: the build runs in its own scope, so the UI budget is measured in wall
                // clock and does not wait for a provider stuck in a blocking socket. Loading can
                // therefore never outlive WAVE_BUDGET_MS, whatever the network does.
                val buildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val buildJob = buildScope.async { engine.build(profile, waveStore.generation, recent) }
                val build = try {
                    withTimeoutOrNull(WAVE_BUDGET_MS) { buildJob.await() }
                } finally {
                    buildJob.cancel()
                    buildScope.cancel()
                } ?: throw WaveUnavailable("Подбор не уложился в отведённое время. Проверьте соединение и попробуйте ещё раз.")
                if (epoch != waveEpoch) return@launch
                val tracks = build.tracks
                if (tracks.isEmpty()) throw WaveUnavailable("Источники не дали треков. Попробуйте ещё раз.")
                // Wave results can be large. Serializing 150 tracks into SharedPreferences on the
                // main thread was the remaining source of the ANR: the progress reached 100%, then
                // Android was blocked while encodeSongs() and prefs.apply() ran.
                withContext(Dispatchers.IO) {
                    library.saveWave(tracks)
                    waveStore.generation = waveStore.generation + 1
                    waveStore.prune()
                }
                _state.update {
                    it.copy(
                        wave = tracks,
                        coefficients = waveStore.coefficients(),
                        error = null,
                        progressText = "Волна готова: ${tracks.size}",
                    )
                }
                _messages.tryEmit("Волна готова: ${tracks.size} треков")
                startQueue(tracks, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == waveEpoch) {
                    // The button must still produce music when the catalogs are unreachable: the
                    // previous wave and every downloaded/liked file are real, playable tracks.
                    val fallback = withContext(Dispatchers.IO) { fallbackWave() }
                    if (fallback.isNotEmpty()) {
                        withContext(Dispatchers.IO) { library.saveWave(fallback) }
                        _state.update {
                            it.copy(wave = fallback, error = null, progressText = "Волна: офлайн-подбор")
                        }
                        _messages.tryEmit("Источники недоступны — включили офлайн-подбор: ${fallback.size}")
                        startQueue(fallback, 0)
                    } else {
                        _state.update { it.copy(error = error.message ?: "Не удалось собрать волну") }
                        _messages.tryEmit("Волна: ${error.message ?: "проверьте соединение"}")
                    }
                }
            } finally {
                // Loading always finishes; a stale job must not clear a newer one's flag.
                if (epoch == waveEpoch) _state.update { it.copy(isWaveLoading = false) }
                NowBarStatus.complete(getApplication(), "Моя волна завершена")
            }
        }
    }

    /**
     * Network failure must not silently turn the library into a recommendation feed.
     * Reuse only a previously generated catalog wave; local files remain available in the Library.
     */
    private fun fallbackWave(): List<SongInfo> =
        _state.value.wave.filter { it.source in ALL_SOURCES && library.localFileFor(it) == null }
            .distinctBy { it.key() }.take(150)

    private fun startQueue(songs: List<SongInfo>, index: Int) {
        if (songs.isEmpty()) return
        playbackQueue.setQueue(songs, index)
        publishQueue()
        play(songs[index.coerceIn(0, songs.lastIndex)])
    }

    private fun publishQueue() {
        _state.update {
            it.copy(
                queueItems = playbackQueue.items,
                queueHistory = playbackQueue.historyItems,
                queueCount = playbackQueue.size,
                queuePosition = playbackQueue.index + 1,
                shuffle = playbackQueue.shuffle,
            )
        }
    }

    private fun observeListening() {
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = (now - lastListeningTick).coerceIn(0, 1000)
        lastListeningTick = now
        if (!player.isPlaying || listeningRecorded) return
        listenedMs += elapsed
        val song = _state.value.player.song ?: return
        val duration = player.duration.takeIf { it > 0 } ?: song.durationSeconds * 1000
        val threshold = if (duration > 0) minOf(30_000L, duration / 2) else 30_000L
        if (listenedMs >= threshold.coerceAtLeast(5_000L) && !listeningRecorded) {
            // The evidence itself is written when the track leaves the player, with the real listened
            // time, so a long track is not counted as "full" after 30 seconds.
            listeningRecorded = true
            enrichMetadata(song)
            if (_state.value.autoDownload) save(song, automatic = true)
        }
    }

    /** Writes the evidence for the track that is leaving the player, once per switch. */
    private fun settleListening() {
        val song = _state.value.player.song ?: return
        if (song.key() == lastTrackKey) return
        val duration = player.duration.takeIf { it > 0L } ?: song.durationSeconds * 1000L
        if (listenedMs >= 2_000L) {
            if (listeningRecorded) waveStore.recordListen(song, listenedMs, duration)
            else waveStore.recordSkip(song, listenedMs, duration)
        }
        lastTrackKey = song.key()
        _state.update { it.copy(listeningCount = waveStore.totalPlays()) }
    }

    private fun refreshDownloads() {
        _state.update {
            it.copy(
                downloads = downloads.entries(),
                downloadUsedBytes = downloads.usedBytes(),
                downloadLimitBytes = downloads.limitBytes,
                downloadCovers = downloads.downloadCovers,
                downloadLyrics = downloads.downloadLyrics,
                downloadClips = downloads.downloadClips,
            )
        }
    }

    private fun enrichMetadata(song: SongInfo) {
        viewModelScope.launch { enrichMetadataNow(song) }
    }

    private suspend fun enrichMetadataNow(song: SongInfo): SongInfo {
        val epoch = tasteEpoch
        val fresh = metadataSlots.withPermit { metadataClient.enrich(song) }
        if (fresh.genre == song.genre && fresh.tags == song.tags) return fresh
        library.updateMetadata(fresh)
        if (epoch == tasteEpoch) waveStore.updateMetadata(fresh)
        fun List<SongInfo>.updated() = map { if (it.key() == fresh.key()) it.copy(genre = fresh.genre, tags = fresh.tags) else it }
        _state.update { current -> current.copy(likes = current.likes.updated(), results = current.results.updated(),
            wave = current.wave.updated(), artistTracks = current.artistTracks.updated(),
            player = if (current.player.song?.key() == fresh.key()) current.player.copy(song = fresh) else current.player) }
        return fresh
    }

    fun resetWave() {
        tasteEpoch++
        waveEpoch++
        waveJob?.cancel()
        waveStore.reset()
        library.saveWave(emptyList())
        listenedMs = 0; listeningRecorded = false
        _state.update { it.copy(wave = emptyList(), coefficients = waveStore.coefficients(), listeningCount = 0, isWaveLoading = false) }
        _messages.tryEmit("Волна сброшена. Следующая подборка учтёт вашу библиотеку и новые прослушивания.")
    }

    fun setAutoDownload(enabled: Boolean) {
        library.autoDownload = enabled
        if (!enabled) automaticJobs.values.toList().forEach { it.cancel() }
        _state.update { it.copy(autoDownload = enabled) }
    }

    /** Offline quota is measured in bytes: 500 MB … 4 GB, or unlimited. */
    fun setDownloadLimit(bytes: Long) {
        if (bytes !in DownloadStore.LIMITS) return
        downloads.limitBytes = bytes
        if (bytes in 1..downloads.usedBytes()) automaticJobs.values.toList().forEach { it.cancel() }
        refreshDownloads()
        _messages.tryEmit("Лимит офлайн-музыки: ${DownloadStore.label(bytes)}")
    }

    fun setDownloadCovers(enabled: Boolean) {
        downloads.downloadCovers = enabled
        refreshDownloads()
        _messages.tryEmit(if (enabled) "Обложки будут сохраняться вместе с треками" else "Обложки не сохраняются")
    }

    fun setDownloadLyrics(enabled: Boolean) {
        downloads.downloadLyrics = enabled
        refreshDownloads()
        _messages.tryEmit(if (enabled) "Тексты будут сохраняться вместе с треками (LRC)" else "Тексты не сохраняются")
    }

    fun setDownloadClips(enabled: Boolean) {
        downloads.downloadClips = enabled
        refreshDownloads()
        _messages.tryEmit(if (enabled) "Клипы будут сохраняться вместе с треками (MP4)" else "Клипы не сохраняются")
    }

    fun clearCovers() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) { downloads.clearCovers() }
            refreshDownloads()
            _messages.tryEmit(if (freed > 0L) "Обложки удалены · ${DownloadStore.sizeLabel(freed)}" else "Скачанных обложек нет")
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                val entryPaths = downloads.entries()
                val total = downloads.clearAll()
                entryPaths.forEach { entry ->
                    library.removeDownload(entry.key)
                    library.removeSavedFile(entry.path)
                }
                total
            }
            library.clearPlayback()
            refreshDownloads()
            _state.update { it.copy(savedFiles = emptyList(), likes = library.likes(), likedKeys = emptySet()) }
            _messages.tryEmit("Скачанное удалено · ${DownloadStore.sizeLabel(freed)}")
        }
    }

    fun deleteDownload(song: SongInfo) {
        val entry = downloads.entryFor(song) ?: return
        val freed = downloads.remove(entry.key)
        library.removeDownload(song.key())
        library.removeSavedFile(entry.path)
        // Deleting a track from the library is a negative signal, like taking it out of liked music.
        waveStore.recordLibraryRemove(song)
        _state.update { current ->
            current.copy(
                saves = current.saves + (song.key() to SaveState.FAILED),
                savedFiles = current.savedFiles.filterNot { it == entry.path },
                likes = library.likes(),
                likedKeys = library.likes().map { it.key() }.toSet(),
                downloads = downloads.entries(),
                downloadUsedBytes = downloads.usedBytes(),
            )
        }
        _messages.tryEmit("Удалено · освобождено ${DownloadStore.sizeLabel(freed)}")
    }

    fun setVkToken(token: String) {
        library.vkToken = token
        _state.update { it.copy(vkToken = token.trim()) }
    }

    /** After a successful VK sign-in the catalog is switched on — otherwise it would stay hidden. */
    fun connectVk(token: String) {
        if (_state.value.serviceConnecting) return
        val clean = token.trim()
        if (clean.isBlank()) return
        _state.update { it.copy(serviceConnecting = true, serviceStatus = "VK: проверяем доступ к музыке…") }
        viewModelScope.launch {
            try {
                val error = withContext(Dispatchers.IO) {
                    val client = com.musicdl.core.source.VKMusicClient(clientConfig().copy(
                        defaultSearchCookies = mapOf("vk_access_token" to clean),
                    ))
                    try { client.checkAccess() } finally { client.close() }
                }
                if (error != null) {
                    _state.update { it.copy(serviceStatus = error) }
                    _messages.tryEmit(error)
                } else {
                    setVkToken(clean)
                    if ("VKMusicClient" !in _state.value.activeSources) toggleSource("VKMusicClient")
                    _state.update { it.copy(serviceStatus = "VK подключён · поиск и импорт готовы") }
                    _messages.tryEmit("VK Музыка подключена")
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(serviceStatus = "Не удалось проверить VK. Проверьте интернет и повторите") }
            } finally {
                _state.update { it.copy(serviceConnecting = false) }
            }
        }
    }

    fun setYandexToken(token: String) {
        library.yandexToken = token
        _state.update { it.copy(yandexToken = token.trim()) }
    }

    /** Qobuz signs every catalog request with app id + secret; a user token unlocks the account. */
    fun setQobuzCredentials(appId: String, appSecret: String, userToken: String) {
        library.qobuzAppId = appId
        library.qobuzAppSecret = appSecret
        library.qobuzUserToken = userToken
        _state.update {
            it.copy(
                qobuzAppId = appId.trim(),
                qobuzAppSecret = appSecret.trim(),
                qobuzToken = userToken.trim(),
            )
        }
        _messages.tryEmit(if (appId.isBlank() || appSecret.isBlank()) "Ключи Qobuz очищены" else "Ключи Qobuz сохранены")
    }

    fun setSoundcloudClientId(id: String) {
        library.soundcloudClientId = id
        _state.update { it.copy(soundcloudClientId = id.trim()) }
        _messages.tryEmit(if (id.isBlank()) "SoundCloud будет искать client_id сам" else "client_id SoundCloud сохранён")
    }

    private var refreshJob: Job? = null
    private var refreshAttempted = false
    private var playGeneration = 0L

    private fun refreshPlayback(song: SongInfo, positionMs: Long) {
        refreshAttempted = true
        val generation = playGeneration
        _state.update { it.copy(player = it.player.copy(isBuffering = true)) }
        refreshJob = viewModelScope.launch {
            // A → B → C → fallback: an expired link is re-resolved at its own catalog first, then
            // anywhere else the same recording is available.
            val cascade = ProviderCascade(
                order = listOf(song.source) + supportedSources(_state.value.activeSources).toList(),
                baseConfig = clientConfig(),
                perProviderTimeoutMs = 7_000L,
            )
            try {
                val result = cascade.streamable(song)
                if (generation != playGeneration) return@launch
                val fresh = result.value
                if (fresh == null) {
                    _state.update { it.copy(player = it.player.copy(isBuffering = false)) }
                    _messages.tryEmit("Ссылка недоступна: ${result.summary()}")
                    return@launch
                }
                playbackQueue.replace(fresh)
                library.updateStream(fresh)
                _state.update { current -> current.copy(
                    results = current.results.map { if (it.key() == song.key()) fresh else it },
                    wave = current.wave.map { if (it.key() == song.key()) fresh else it },
                    likes = library.likes(),
                    artistTracks = current.artistTracks.map { if (it.key() == song.key()) fresh else it },
                ) }
                startPlayback(fresh, positionMs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == playGeneration) {
                    _state.update { it.copy(player = it.player.copy(isBuffering = false)) }
                    _messages.tryEmit("Не удалось обновить ссылку на трек")
                }
            }
        }
    }

    /** A new user selection cancels recovery of the previous track. */
    fun play(song: SongInfo, startAtMs: Long = 0L) {
        if (song.source !in ALL_SOURCES && library.localFileFor(song) == null) {
            _messages.tryEmit("Этот провайдер больше не поддерживается. Скачанные треки доступны офлайн.")
            return
        }
        if (_state.value.player.song?.key() != song.key()) {
            settleListening()
            listenedMs = 0L
            listeningRecorded = false
            resolveArtwork(song)
            resolveCanvas(song)
        }
        lastListeningTick = android.os.SystemClock.elapsedRealtime()
        enrichMetadata(song)
        playGeneration++
        refreshJob?.cancel()
        refreshAttempted = false
        startPlayback(song, startAtMs)
    }

    private var artworkJob: Job? = null

    /**
     * Finds the largest available artwork for the expanded player. It never blocks playback: the
     * catalog cover is shown immediately and replaced when a 1000–1200 px version arrives.
     */
    private fun resolveArtwork(song: SongInfo) {
        artworkJob?.cancel()
        if (song.coverUrl?.startsWith("file:") == true) return
        artworkJob = viewModelScope.launch {
            val url = withTimeoutOrNull(8_000L) { ArtworkSources.resolve(song) } ?: return@launch
            if (_state.value.player.song?.key() != song.key()) return@launch
            if (url != song.coverUrl) _state.update { it.copy(player = it.player.copy(artworkUrl = url)) }
        }
    }

    private var canvasJob: Job? = null

    /**
     * Resolves the short looping visual for the expanded player. A downloaded clip wins and costs
     * nothing; otherwise the provider chain answers within its own budget and the player keeps the
     * static cover when there is no clip for the track.
     */
    private fun resolveCanvas(song: SongInfo) {
        canvasJob?.cancel()
        canvasJob = viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { ClipStore.pathFor(getApplication(), song.key()) }
            val clip = local?.let { ClipSpec.Video(it.toURI().toString(), "Скачано") }
                ?: (withTimeoutOrNull(8_000L) { clipResolver.resolve(song) }?.clip as? ClipSpec.Video)
            if (_state.value.player.song?.key() != song.key()) return@launch
            _state.update { it.copy(player = it.player.copy(canvas = clip)) }
        }
    }

    private fun startPlayback(song: SongInfo, startAtMs: Long) {
        runCatching { PlaybackService.start(getApplication()) }
        val localFile = library.localFileFor(song)
        val url = localFile?.toURI()?.toString() ?: song.downloadUrl ?: run {
            _messages.tryEmit("Нет рабочей ссылки: ${song.songName}")
            return
        }
        val headers = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36")
            putAll(song.downloadHeaders)
        }
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val metadata = MediaMetadata.Builder()
            .setTitle(song.songName ?: "XXW Music")
            .setArtist(song.singers?.takeIf { it.isNotBlank() && it != "NULL" } ?: "")
            .setAlbumTitle(song.album?.takeIf { it.isNotBlank() && it != "NULL" })
            .build()
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
        val factory = androidx.media3.datasource.DefaultDataSource.Factory(getApplication(), dataSource)
        // Downloaded HLS is merged into MP4/AAC/TS, not a playlist. Never select HLS for it.
        val isLocal = localFile != null || android.net.Uri.parse(url).scheme == "file"
        val mediaSource = if (isLocal) {
            androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(factory).createMediaSource(item)
        }
        playbackQueue.play(song)
        playbackQueue.markPlayed(song)
        publishQueue()
        player.stop()
        player.setMediaSource(mediaSource, true)
        player.prepare()
        if (startAtMs > 0L) player.seekTo(startAtMs)
        player.playWhenReady = true
        lyricsJob?.cancel()
        lyricsKey = null
        _state.update {
            it.copy(
                player = PlayerUiState(song = song, isBuffering = true, positionMs = startAtMs),
                lyrics = null,
                isLyricsLoading = false,
                lyricsError = null,
            )
        }
        persistPlayback()
    }

    /** Card tap: play it, or pause it when it is already the current track. */
    fun playOrToggle(song: SongInfo) {
        val current = _state.value.player.song
        if (current?.key() == song.key() && player.mediaItemCount > 0) {
            togglePlayPause()
        } else {
            val visible = with(_state.value) {
                when (tab) { FeedTab.SEARCH -> results; FeedTab.WAVE -> wave; FeedTab.LIKES -> likes; FeedTab.ARTISTS -> artistTracks; FeedTab.SETTINGS -> emptyList() }
            }
            setQueue(song, visible)
            play(song)
        }
    }

    private fun setQueue(song: SongInfo, songs: List<SongInfo>) {
        val selection = com.musicdl.core.model.PlaybackSelection.from(song, songs)
        playbackQueue.setQueue(selection.songs, selection.index)
        publishQueue()
    }

    fun playFromPlaylist(id: String, index: Int) {
        val songs = library.playlists().firstOrNull { it.id == id }?.tracks ?: return
        val song = songs.getOrNull(index) ?: return
        playbackQueue.setQueue(songs, index)
        publishQueue()
        play(song)
    }

    private fun persistPlayback() {
        publishQueue()
        val song = _state.value.player.song
        val queue = playbackQueue.items.toList()
        val index = playbackQueue.index.coerceAtLeast(0)
        val history = playbackQueue.historyItems
        val shuffle = playbackQueue.shuffle
        val position = if (player.mediaItemCount > 0 && !player.isPlaying) {
            player.currentPosition.coerceAtLeast(0L)
        } else {
            _state.value.player.positionMs
        }
        playbackPersistJob?.cancel()
        playbackPersistJob = viewModelScope.launch(Dispatchers.IO) {
            // Queue snapshots are JSON too. Never serialize a 100+ track wave on the UI thread.
            if (song == null || queue.isEmpty()) {
                library.clearPlayback()
            } else {
                library.savePlayback(
                    queue = queue,
                    index = index,
                    positionMs = position,
                    history = history,
                    shuffle = shuffle,
                )
            }
        }
    }

    /** Play a list from the top, e.g. the whole "Нравится" playlist. */
    fun playAll(songs: List<SongInfo>) = startQueue(songs, 0)

    private fun advanceQueue(automatic: Boolean = false) {
        if (playbackQueue.isEmpty) return
        // With shuffle on, "next" walks the shuffled order instead of picking a new random index.
        val next = playbackQueue.next(automatic = automatic, repeatOne = _state.value.repeatOne) ?: return
        play(next)
    }

    /**
     * Back always returns the track that actually played before this one — never a fresh random
     * pick, which is what made shuffle feel broken. At the start of the history it restarts the
     * current track instead of dead-ending.
     */
    fun playPrevious() {
        if (playbackQueue.isEmpty) return
        val previous = playbackQueue.previous() ?: return
        play(previous, startAtMs = 0L)
    }

    fun playNext() {
        if (playbackQueue.isEmpty) {
            _messages.tryEmit("Очередь пуста")
            return
        }
        advanceQueue()
    }

    // ------------------------------------------------------------- queue

    /** «Проигрывать следующим»: directly after the current track. */
    fun playNextInQueue(song: SongInfo) {
        if (song.source !in ALL_SOURCES && library.localFileFor(song) == null) {
            _messages.tryEmit("Этот провайдер недоступен")
            return
        }
        playbackQueue.insertNext(song)
        publishQueue()
        persistPlayback()
        _messages.tryEmit("Далее: ${song.songName ?: "трек"}")
    }

    fun addToQueue(song: SongInfo) {
        playbackQueue.append(listOf(song))
        publishQueue()
        persistPlayback()
        _messages.tryEmit("В очереди: ${song.songName ?: "трек"}")
    }

    fun addManyToQueue(songs: List<SongInfo>) {
        if (songs.isEmpty()) return
        playbackQueue.append(songs)
        publishQueue()
        persistPlayback()
        _messages.tryEmit("Добавлено в очередь: ${songs.size}")
    }

    fun playQueueAt(index: Int) {
        val song = playbackQueue.playAt(index) ?: return
        publishQueue()
        play(song)
    }

    fun moveInQueue(from: Int, to: Int) {
        if (playbackQueue.move(from, to)) {
            publishQueue()
            persistPlayback()
        }
    }

    fun removeFromQueue(index: Int) {
        val wasCurrent = index == playbackQueue.index
        val removed = playbackQueue.removeAt(index) ?: return
        publishQueue()
        persistPlayback()
        _messages.tryEmit("Убрано из очереди: ${removed.songName ?: "трек"}")
        // Removing the playing track must not leave the player on a track that is gone.
        if (wasCurrent) {
            val next = playbackQueue.current
            if (next == null) clearQueue() else play(next)
        }
    }

    fun clearQueue() {
        playbackQueue.clear()
        publishQueue()
        player.stop()
        player.clearMediaItems()
        library.clearPlayback()
        _state.update { it.copy(player = PlayerUiState()) }
        _messages.tryEmit("Очередь очищена")
    }

    fun togglePlayPause() {
        runCatching { PlaybackService.start(getApplication()) }
        when {
            refreshJob?.isActive == true -> {
                playGeneration++
                refreshJob?.cancel()
                _state.update { it.copy(player = it.player.copy(isBuffering = false)) }
            }
            player.playerError != null -> _state.value.player.song?.let { play(it, player.currentPosition) }
            player.isPlaying -> player.pause()
            player.mediaItemCount > 0 -> {
                if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
            // A restored session has a song but no ExoPlayer item yet: rebuild it where it stopped.
            else -> _state.value.player.song?.let { song ->
                play(song, startAtMs = _state.value.player.positionMs)
            }
        }
        publishPlayer()
        persistPlayback()
    }

    fun seekTo(fraction: Float) {
        val duration = player.duration
        if (duration > 0L) player.seekTo((duration * fraction).toLong())
        publishPlayer()
        persistPlayback()
    }

    private var lyricsKey: String? = null
    private var lyricsJob: Job? = null

    /**
     * Fetch lyrics for the current track once per song. lrclib answers for the song itself, so the
     * lookup is by title/artist and works for any source that produced the hit.
     */
    fun loadLyrics() {
        val song = _state.value.player.song ?: return
        val key = song.key()
        if (lyricsKey == key && (_state.value.isLyricsLoading || _state.value.lyrics != null)) return
        lyricsKey = key
        lyricsJob?.cancel()
        _state.update { it.copy(lyrics = null, isLyricsLoading = true, lyricsError = null) }
        lyricsJob = viewModelScope.launch {
            val found = try { LyricsFetcher.fetch(song, logger) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { null }
            if (lyricsKey != key || _state.value.player.song?.key() != key) return@launch
            _state.update {
                it.copy(
                    lyrics = found,
                    isLyricsLoading = false,
                    lyricsError = if (found == null) "Текст не найден в доступных источниках" else null,
                )
            }
        }
    }

    /**
     * «Авто» never yields teasers, but a preview source the user picked on purpose is honoured and
     * its rows are labelled: that is the only place a mainstream release exists at all.
     */
    private fun isPlayable(song: SongInfo, allowPreviews: Boolean): Boolean {
        if (allowPreviews) return true
        return !isPreviewSource(song.source) &&
            (song.durationSeconds == 0L || song.durationSeconds > 35L)
    }

    /**
     * Full-track catalogs answer anything vaguely similar, which is where the unrelated titles in
     * the feed came from. A hit has to mention a meaningful word of the query to be kept.
     */
    private fun matchesQuery(song: SongInfo, keyword: String): Boolean {
        val words = keyword.lowercase().split(' ').map { it.trim() }.filter { it.length >= 3 }
        if (words.isEmpty()) return true
        val haystack = "${song.songName.orEmpty()} ${song.singers.orEmpty()}".lowercase()
        return words.any { haystack.contains(it) }
    }

    /** Explicit, user-initiated widening: recommended sources are added and the search re-runs. */
    fun widenSearch() {
        val merged = (_state.value.activeSources + RECOMMENDED_SOURCES).toSet()
        library.activeSources = merged
        _state.update { it.copy(activeSources = merged, canWiden = false) }
        search()
    }

    /** Follow / unfollow the artist of a track; returns the subscription list to the UI. */
    fun toggleFollow(song: SongInfo) {
        val artist = song.singers?.trim().orEmpty()
        if (artist.isEmpty() || artist == "NULL") {
            _messages.tryEmit("У трека нет исполнителя")
            return
        }
        val following = library.toggleFollowArtist(artist)
        _state.update { it.copy(followedArtists = library.followedArtists()) }
        _messages.tryEmit(if (following) "Подписка: $artist" else "Отписка: $artist")
    }

    fun unfollowArtist(name: String) {
        if (library.isFollowing(name)) library.toggleFollowArtist(name)
        _state.update {
            it.copy(
                followedArtists = library.followedArtists(),
                artistFeed = it.artistFeed?.takeUnless { feed -> feed.equals(name, ignoreCase = true) },
            )
        }
    }

    /** «Что у него нового»: the same engine search, but for one artist and full tracks only. */
    private var artistJob: Job? = null
    private var artistGeneration = 0

    fun findArtist() {
        val name = _state.value.keyword.trim().takeIf { it.isNotEmpty() } ?: return
        library.lastKeyword = name
        library.pushHistory(name)
        setTab(FeedTab.ARTISTS)
        openArtist(name)
    }

    fun openArtist(name: String) {
        artistJob?.cancel()
        val generation = ++artistGeneration
        _state.update { it.copy(artistFeed = name, artistTracks = emptyList(), isArtistLoading = true) }
        artistJob = viewModelScope.launch {
            val sources = _state.value.activeSources.filterNot { isPreviewSource(it) }.toSet()
                .ifEmpty { RECOMMENDED_SOURCES }
            val client = buildClient(sources)
            try {
                val bySource = client.search(
                    keyword = name,
                    artistOnly = true,
                    sourceTimeoutMs = SEARCH_TIMEOUT_MS,
                    onSourceResult = callback@{ _, songs ->
                        if (generation != artistGeneration) return@callback
                        val fresh = songs
                            .filter { isPlayable(it, allowsPreviews(_state.value.activeSources)) }
                            .filter { com.musicdl.core.model.ArtistIdentity.matches(it, name) }
                            .filter { song -> _state.value.artistTracks.none { it.key() == song.key() } }
                        if (fresh.isNotEmpty()) {
                            _state.update { current ->
                                current.copy(artistTracks = (current.artistTracks + fresh).sortedWith(songOrder(name)))
                            }
                        }
                    },
                )
                if (generation != artistGeneration) return@launch
                val flat = bySource.values.flatten()
                    .filter { isPlayable(it, allowsPreviews(_state.value.activeSources)) }
                    .filter { com.musicdl.core.model.ArtistIdentity.matches(it, name) }
                    .distinctBy { it.key() }
                    .sortedWith(songOrder(name))
                _state.update { it.copy(artistTracks = flat, isArtistLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _state.update { it.copy(isArtistLoading = false) }
                _messages.tryEmit("Не удалось обновить: ${err.message}")
            } finally {
                withContext(Dispatchers.IO) { client.close() }
            }
        }
    }

    fun closeArtist() {
        artistGeneration++
        artistJob?.cancel()
        _state.update { it.copy(artistFeed = null, artistTracks = emptyList(), isArtistLoading = false) }
    }

    fun stop() {
        playGeneration++
        refreshJob?.cancel()
        player.stop()
        player.clearMediaItems()
        playbackQueue.clear()
        publishQueue()
        library.clearPlayback()
        _state.update { it.copy(player = PlayerUiState()) }
    }

    /** One tap: fetch the file, then report where it landed. */
    fun save(song: SongInfo) = save(song, automatic = false)

    private fun save(song: SongInfo, automatic: Boolean) {
        val key = song.key()
        if (_state.value.saves[key] == SaveState.RUNNING) return
        downloads.entryFor(song)?.let { existing ->
            _state.update { it.copy(saves = it.saves + (key to SaveState.DONE)) }
            if (!automatic) _messages.tryEmit("Уже скачано · ${DownloadStore.sizeLabel(existing.totalBytes)}")
            return
        }
        if (automatic && !_state.value.autoDownload) return
        // The index can be lost while the audio is still on disk (reinstall, restored backup).
        // A local file is the strongest proof the track is already downloaded — adopt it instead of
        // fetching the song again.
        library.localFileFor(song)?.let { file ->
            // Keep the key of the stored copy, and never index the same file twice: both would
            // make the quota count identical bytes two times.
            val storedKey = (library.downloaded() + library.likes())
                .firstOrNull { it.key() == key || it.sameRecording(song) }?.key() ?: key
            if (downloads.entries().none { it.path == file.absolutePath }) {
                downloads.add(
                    DownloadEntry(
                        key = storedKey,
                        path = file.absolutePath,
                        bytes = file.length(),
                        title = song.songName.orEmpty(),
                        artist = song.singers.orEmpty(),
                        remoteCover = song.coverUrl,
                    ),
                )
            }
            _state.update {
                it.copy(
                    saves = it.saves + (key to SaveState.DONE),
                    downloads = downloads.entries(),
                    downloadUsedBytes = downloads.usedBytes(),
                )
            }
            if (!automatic) _messages.tryEmit("Уже скачано · ${DownloadStore.sizeLabel(file.length())}")
            return
        }
        // Estimate before touching the network; the real size is checked again after the download.
        val estimated = song.fileSizeBytes.takeIf { it > 0L }
            ?: (song.durationSeconds * 40_000L).coerceAtLeast(3L * DownloadStore.MB)
        if (!downloads.canFit(estimated)) {
            if (!automatic) _messages.tryEmit(limitReachedMessage(estimated))
            return
        }
        _state.update { it.copy(saves = it.saves + (key to SaveState.RUNNING)) }
        NowBarStatus.show(
            getApplication(),
            "XXW Music · скачивание",
            song.songName?.takeIf { it.isNotBlank() } ?: "Скачивание трека…",
        )

        val job = viewModelScope.launch {
            val client = buildClient(setOf(song.source))
            try {
                downloadSlots.withPermit {
                    if (automatic && !_state.value.autoDownload) {
                        _state.update { it.copy(saves = it.saves - key) }
                        return@withPermit
                    }
                    val enriched = metadataSlots.withPermit { metadataClient.enrich(song) }
                    val target = try {
                        client.download(enriched)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (original: java.io.IOException) {
                        // Signed manifests and segment URLs expire in persisted library entries.
                        val fresh = client.refreshTrack(song) ?: throw original
                        client.download(fresh)
                    }
                    val audioBytes = target.length()
                    if (!downloads.canFit(audioBytes)) {
                        target.delete()
                        _state.update { it.copy(saves = it.saves + (key to SaveState.FAILED)) }
                        _messages.tryEmit(limitReachedMessage(audioBytes))
                        return@withPermit
                    }
                    // Optional sidecars. Each one is downloaded only after the quota check for the
                    // bytes already on disk, so a filled quota never costs the audio file.
                    var extras = audioBytes
                    val coverFile = if (_state.value.downloadCovers) {
                        // Metadata lookups are best-effort: a slow catalog must not delay the audio download.
                        val hq = withTimeoutOrNull(10_000L) {
                            withContext(Dispatchers.IO) { ArtworkSources.resolve(enriched) }
                        }
                        withContext(Dispatchers.IO) { CoverStore.save(getApplication(), hq ?: enriched.coverUrl, key) }
                    } else null
                    val coverBytes = coverFile?.takeIf { it.isFile }?.length() ?: 0L
                    val keptCover = coverFile?.takeIf { downloads.canFit(extras + coverBytes) }
                    if (keptCover == null) coverFile?.delete() else extras += coverBytes
                    val localCover = keptCover?.toURI()?.toString()

                    val lyricsFile = if (_state.value.downloadLyrics) {
                        val lyrics = runCatching { LyricsFetcher.fetch(enriched, logger) }.getOrNull()
                        if (lyrics != null && !lyrics.isBlank) {
                            val file = withContext(Dispatchers.IO) { LyricsStore.save(getApplication(), key, lyrics) }
                            file?.takeIf { downloads.canFit(extras + it.length()) }.also { kept ->
                                if (kept == null) file?.delete()
                            }
                        } else null
                    } else null
                    val lyricsBytes = lyricsFile?.length() ?: 0L
                    if (lyricsFile != null) extras += lyricsBytes

                    val clipFile = if (_state.value.downloadClips) {
                        val clip = runCatching { clipResolver.resolve(enriched) }.getOrNull()?.clip as? ClipSpec.Video
                        val file = clip?.let { withContext(Dispatchers.IO) { ClipStore.save(getApplication(), key, it) } }
                        file?.takeIf { downloads.canFit(extras + it.length()) }.also { kept ->
                            if (kept == null) file?.delete()
                        }
                    } else null
                    val clipBytes = clipFile?.length() ?: 0L
                    if (clipFile != null) extras += clipBytes

                    downloads.add(
                        DownloadEntry(
                            key = key,
                            path = target.absolutePath,
                            bytes = audioBytes,
                            coverPath = keptCover?.absolutePath,
                            coverBytes = if (keptCover != null) coverBytes else 0L,
                            lyricsPath = lyricsFile?.absolutePath,
                            lyricsBytes = lyricsBytes,
                            clipPath = clipFile?.absolutePath,
                            clipBytes = clipBytes,
                            title = enriched.songName.orEmpty(),
                            artist = enriched.singers.orEmpty(),
                            remoteCover = enriched.coverUrl,
                        ),
                    )
                    if (automatic) library.addAutomaticFile(target.absolutePath)
                    library.addSavedFile(target.absolutePath)
                    // A downloaded track is a durable library entry, playable without its expiring URL;
                    // with covers enabled the artwork is local too and works offline.
                    library.saveDownload(
                        enriched.copy(coverUrl = localCover ?: enriched.coverUrl),
                        target.absolutePath,
                        addToLikes = !automatic,
                    )
                    // Saving into the library is a positive taste signal of its own.
                    if (!automatic) waveStore.recordLibraryAdd(enriched)
                    _state.update {
                        it.copy(
                            saves = it.saves + (key to SaveState.DONE),
                            likes = library.likes(),
                            likedKeys = library.likes().map { track -> track.key() }.toSet(),
                            savedFiles = (listOf(target.absolutePath) + it.savedFiles).distinct(),
                            downloads = downloads.entries(),
                            downloadUsedBytes = downloads.usedBytes(),
                        )
                    }
                    val stillDownloading = _state.value.saves.values.count { it == SaveState.RUNNING }
                    if (stillDownloading > 0) {
                        NowBarStatus.show(getApplication(), "XXW Music · скачивание", "Осталось активных: $stillDownloading")
                    } else {
                        NowBarStatus.complete(getApplication(), "Скачивание завершено")
                    }
                    if (!automatic) _messages.tryEmit("Сохранено · ${DownloadStore.sizeLabel(extras)}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                _state.update { it.copy(saves = it.saves + (key to SaveState.FAILED)) }
                if (_state.value.saves.values.none { it == SaveState.RUNNING }) {
                    NowBarStatus.complete(getApplication(), "Скачивание завершилось с ошибкой")
                }
                _messages.tryEmit("Не удалось сохранить: ${err.message ?: "неизвестная ошибка"}")
            } finally {
                automaticJobs.remove(key)
                if (_state.value.saves[key] == SaveState.RUNNING) _state.update { it.copy(saves = it.saves - key) }
                withContext(Dispatchers.IO) { client.close() }
            }
        }
        if (automatic) automaticJobs[key] = job
    }

    /** The hero action: start streaming and keep a copy, in one press. */
    fun playAndSave(song: SongInfo) {
        play(song)
        save(song)
    }

    fun saveAll() = saveAll(_state.value.results)

    /** One press downloads a whole list, e.g. the «Нравится» playlist. */
    fun saveAll(songs: List<SongInfo>) {
        val pending = songs.filter {
            downloads.entryFor(it) == null && _state.value.saves[it.key()] != SaveState.DONE
        }
        if (pending.isEmpty()) {
            _messages.tryEmit("Всё уже скачано")
            return
        }
        pending.forEach(::save)
        _messages.tryEmit("Скачиваю треков: ${pending.size}…")
    }

    fun saveAllLikes() = saveAll(_state.value.likes)

    /** What the quota text says when a download cannot start or does not fit. */
    private fun limitReachedMessage(needBytes: Long): String {
        if (downloads.unlimited) return "Недостаточно свободного места на устройстве"
        val free = downloads.availableBytes() ?: 0L
        return "Лимит ${DownloadStore.label(_state.value.downloadLimitBytes)} заполнен: свободно " +
            "${DownloadStore.sizeLabel(free)}, нужно ${DownloadStore.sizeLabel(needBytes)}. " +
            "Удалите скачанные треки или увеличьте лимит."
    }

    // ------------------------------------------------------------- likes

    /** Heart on a card: toggles the "Нравится" playlist and nudges the taste model. */
    fun toggleLike(song: SongInfo) {
        val nowLiked = library.toggleLike(song)
        val likes = library.likes()
        _state.update {
            it.copy(likes = likes, likedKeys = likes.map { liked -> liked.key() }.toSet())
        }
        if (nowLiked) {
            like(song)
        } else {
            waveStore.recordLibraryRemove(song)
            _messages.tryEmit("Убрано из «Нравится»")
        }
    }

    fun isLiked(song: SongInfo): Boolean = song.key() in _state.value.likedKeys

    fun removeLike(song: SongInfo) {
        library.removeLike(song.key())
        waveStore.recordLibraryRemove(song)
        val likes = library.likes()
        _state.update { it.copy(likes = likes, likedKeys = likes.map { liked -> liked.key() }.toSet()) }
    }

    fun clearHistory() {
        library.clearHistory()
        _state.update { it.copy(history = emptyList()) }
    }

    /** Taste feedback from the cards and the player. */
    fun like(song: SongInfo) {
        waveStore.recordLike(song)
        enrichMetadata(song)
        if (_state.value.autoDownload) save(song, automatic = true)
        _messages.tryEmit("В «Нравится» · учтено в вашей волне")
    }

    fun dislike(song: SongInfo) {
        waveStore.recordDislike(song)
        _state.update { it.copy(wave = it.wave.filterNot { track -> Wave.recordingKey(track) == Wave.recordingKey(song) }) }
        _messages.tryEmit("Будем предлагать этот трек и его автора реже")
    }

    fun likeCurrent() = _state.value.player.song?.let(::toggleLike)

    fun dislikeCurrent() = _state.value.player.song?.let(::dislike)

    fun setGenreWeight(genreId: String, value: Float) {
        waveStore.putCoefficient(genreId, value)
        _state.update { it.copy(coefficients = it.coefficients + (genreId to value.coerceIn(0f, 2f))) }
    }

    // ------------------------------------------------------------- vertical clips

    fun openClips() {
        val items = playbackQueue.items.ifEmpty { _state.value.wave.ifEmpty { _state.value.likes } }
        if (items.isEmpty()) {
            _messages.tryEmit("Сначала включите музыку — клипы идут по очереди")
            return
        }
        val current = _state.value.player.song
        val index = current?.let { song -> items.indexOfFirst { it.key() == song.key() } }
            ?.takeIf { it >= 0 } ?: 0
        _state.update { it.copy(clips = ClipUiState(clips = items, index = index)) }
        loadClip(index)
    }

    fun closeClips() {
        clipJob?.cancel()
        _state.update { it.copy(clips = ClipUiState()) }
    }

    fun clipsNext() {
        val clips = _state.value.clips.clips
        if (clips.isEmpty()) return
        loadClip((_state.value.clips.index + 1).mod(clips.size))
    }

    fun clipsPrevious() {
        val clips = _state.value.clips.clips
        if (clips.isEmpty()) return
        loadClip((_state.value.clips.index - 1).mod(clips.size))
    }

    fun retryClip() = loadClip(_state.value.clips.index)

    private fun loadClip(index: Int) {
        val clips = _state.value.clips.clips
        val song = clips.getOrNull(index) ?: return
        clipJob?.cancel()
        _state.update { it.copy(clips = it.clips.copy(index = index, resolution = null, isLoading = true, error = null)) }
        clipJob = viewModelScope.launch {
            // A downloaded clip plays from disk instantly and works without a network.
            val local = withContext(Dispatchers.IO) { ClipStore.pathFor(getApplication(), song.key()) }
            val resolution = if (local != null) {
                ClipResolution(ClipSpec.Video(local.toURI().toString(), "Скачано"))
            } else {
                withTimeoutOrNull(CLIP_BUDGET_MS) { clipResolver.resolve(song) }
            }
            if (_state.value.clips.index != index || _state.value.clips.song?.key() != song.key()) return@launch
            val failure = resolution?.attempts?.lastOrNull()?.takeIf { it.result != "ок" }
            _state.update { current ->
                current.copy(
                    clips = current.clips.copy(
                        resolution = resolution,
                        isLoading = false,
                        error = if (resolution == null) "Клип не загрузился вовремя"
                        else failure?.let { "${it.provider}: ${it.result}. Показана обложка." },
                    ),
                )
            }
            // Warm the next clip in the background so a swipe does not wait for the network.
            clips.getOrNull((index + 1).mod(clips.size))?.let { next ->
                viewModelScope.launch { runCatching { clipResolver.resolve(next) } }
            }
        }
    }

    // ------------------------------------------------------------- Yandex import

    fun importYandexMusic() {
        TransferForegroundService.startYandex(getApplication())
    }

    internal fun importYandexMusicInternal(onFinished: () -> Unit = {}) {
        if (_state.value.isImporting) { onFinished(); return }
        val token = library.yandexToken
        if (token.isBlank()) {
            _messages.tryEmit("Вставьте токен Яндекс Музыки в настройках")
            onFinished()
            return
        }
        _state.update { it.copy(isImporting = true, importProgress = "Яндекс: подключаемся", importReport = null) }
        viewModelScope.launch {
            val yandex = runCatching { YandexMusicLibrary(token, logger) }.getOrNull()
            if (yandex == null) {
                _state.update {
                    it.copy(isImporting = false, importProgress = "",
                        importReport = YandexImportReport(account = "", error = "Не удалось создать клиент Яндекса"))
                }
                return@launch
            }
            try {
                val data = withTimeoutOrNull(45_000L) { yandex.library() }
                    ?: throw YandexAuthException("Яндекс не ответил вовремя")
                val client = buildClient(supportedSources(_state.value.activeSources))
                try {
                    val slots = Semaphore(3)
                    val report = YandexImporter.match(
                        library = data,
                        search = { track: YandexTrack ->
                            slots.withPermit {
                                withTimeoutOrNull(9_000L) {
                                    client.search(track.query, sourceTimeoutMs = 5_000L).values.flatten()
                                        .distinctBy { it.key() }
                                }.orEmpty()
                            }
                        },
                        onProgress = { done, total ->
                            _state.update { it.copy(importProgress = "Яндекс: $done из $total") }
                        },
                    )
                    val applied = withContext(Dispatchers.IO) { runCatching { applyImport(report) } }
                    applied.exceptionOrNull()?.let { failure ->
                        _state.update {
                            it.copy(importReport = report.copy(
                                error = failure.message ?: "Перенос получен, но сохранить его не удалось",
                            ))
                        }
                    }
                } finally {
                    runCatching { withContext(Dispatchers.IO) { client.close() } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(importReport = YandexImportReport(account = "", error = error.message ?: "Импорт не удался"))
                }
                _messages.tryEmit(error.message ?: "Импорт не удался")
            } finally {
                runCatching { yandex.close() }
                _state.update { it.copy(isImporting = false, importProgress = "") }
                onFinished()
            }
        }
    }

    /** Adds only matches the user can trust, keeps doubtful ones in a review playlist. */
    private fun applyImport(report: YandexImportReport) {
        var addedTracks = 0
        var skipped = 0
        report.liked.forEach { match ->
            val song = match.song
            if (song != null && match.confidence == MatchConfidence.EXACT) {
                if (library.addLike(song)) addedTracks++ else skipped++
            }
        }
        var addedPlaylists = 0
        // Playlists and liked albums both become playlists; the name is the duplicate guard.
        fun importNamed(entries: List<Pair<String, List<ImportMatch>>>, prefix: String) {
            entries.forEach { (title, matches) ->
                val tracks = matches.filter { it.confidence == MatchConfidence.EXACT }.mapNotNull { it.song }
                    .distinctBy { it.key() }
                if (tracks.isEmpty()) return@forEach
                val name = "$prefix$title"
                if (library.playlists().any { it.name.equals(name, ignoreCase = true) }) return@forEach
                library.savePlaylists(
                    library.playlists() + MusicPlaylist(java.util.UUID.randomUUID().toString(), name, tracks),
                )
                addedPlaylists++
            }
        }
        importNamed(report.playlists, "Яндекс · ")
        importNamed(report.albums, "Яндекс · Альбом · ")
        val doubtful = (report.liked + report.playlists.flatMap { it.second } + report.albums.flatMap { it.second })
            .filter { it.confidence == MatchConfidence.DOUBTFUL }
            .mapNotNull { it.song }.distinctBy { it.key() }
        if (doubtful.isNotEmpty() && library.playlists().none { it.name == "Перенос · требует проверки" }) {
            library.savePlaylists(
                library.playlists() + MusicPlaylist(
                    java.util.UUID.randomUUID().toString(),
                    "Перенос · требует проверки",
                    doubtful,
                ),
            )
            addedPlaylists++
        }
        var followed = 0
        report.artists.forEach { artist -> if (library.toggleFollowArtist(artist)) followed++ }
        val final = report.copy(
            addedTracks = addedTracks,
            addedPlaylists = addedPlaylists,
            followedArtists = followed,
            skippedDuplicates = skipped,
        )
        _state.update {
            it.copy(
                importReport = final,
                playlists = library.playlists(),
                likes = library.likes(),
                likedKeys = library.likes().map { song -> song.key() }.toSet(),
                followedArtists = library.followedArtists(),
            )
        }
        _messages.tryEmit("Перенос: ${final.exact} точных, ${final.doubtful} под вопросом, ${final.unmatched} не найдено")
    }

    fun dismissImportReport() = _state.update { it.copy(importReport = null) }

    // --------------------------------------------------------- playlist by link

    /**
     * Imports a playlist from a share link through the providers the Telegram plugin supported:
     * Yandex Music and VK hand out track lists (and, where the account may stream them, the files
     * themselves); Spotify links yield metadata only. Rows without a file are matched against the
     * active catalogs — the plugin's own fallback, minus yt-dlp, which needs a Python runtime.
     */
    fun setSpotifySyncToken(token: String) {
        library.spotifyToken = token.trim()
        _messages.tryEmit("Токен Spotify сохранён только на устройстве")
    }

    fun syncPlaylist(id: String) {
        val playlist = library.playlists().firstOrNull { it.id == id } ?: return
        playlist.syncUrl?.let { importPlaylistLink(it, syncId = id) }
    }

    fun importPlaylistLink(rawLink: String, download: Boolean = false, syncId: String? = null) {
        TransferForegroundService.startPlaylist(getApplication(), rawLink, download, syncId)
    }

    internal fun importPlaylistLinkInternal(rawLink: String, download: Boolean = false, syncId: String? = null, onFinished: () -> Unit = {}) {
        val link = Regex("https?://[^\\s<>]+").find(rawLink.trim())?.value ?: rawLink.trim()
        if (link.isEmpty()) { onFinished(); return }
        if (_state.value.isImporting) {
            _messages.tryEmit("Импорт уже идёт")
            onFinished()
            return
        }
        _state.update { it.copy(isImporting = true, importProgress = "Ссылка: читаем плейлист", linkImportResult = null) }
        viewModelScope.launch {
            try {
                val parsedLink = withContext(Dispatchers.IO) { parsePlaylistLink(link) }
                parsedLink.error?.let { message ->
                    _state.update { it.copy(linkImportResult = message) }
                    _messages.tryEmit(message)
                    return@launch
                }
                val name = parsedLink.name
                val parsed = parsedLink.tracks
                if (parsed.isEmpty()) {
                    _state.update { it.copy(linkImportResult = "Плейлист пустой, приватный или ссылка не поддерживается") }
                    _messages.tryEmit("По ссылке не нашлось треков")
                    return@launch
                }
                _state.update { it.copy(importProgress = "Ссылка: ${parsed.size} треков, ищем файлы") }
                val playable = resolvePlayable(parsed)
                if (playable.isEmpty()) {
                    _state.update { it.copy(linkImportResult = "Треки прочитаны, но файлы не найдены. Попробуйте позже") }
                    _messages.tryEmit("Треки есть, но ни один источник не отдал файл")
                    return@launch
                }
                val title = listOf(name, trackCount(playable.size)).filter { it.isNotBlank() }.joinToString(" · ")
                val existing = library.playlists().firstOrNull { it.id == syncId || it.syncUrl == link }
                fun identity(song: SongInfo) = "${song.singers.orEmpty().trim().lowercase()}|${song.songName.orEmpty().trim().lowercase()}|${song.durationSeconds / 5}"
                val merged = ((existing?.tracks ?: emptyList()) + playable).distinctBy(::identity)
                val synced = (existing ?: MusicPlaylist(java.util.UUID.randomUUID().toString(), title)).copy(
                    tracks = merged, syncUrl = link, lastSyncedAt = System.currentTimeMillis(),
                )
                updatePlaylists(library.playlists().filterNot { it.id == synced.id } + synced)
                val result = "Импортировано ${playable.size} из ${parsed.size}" +
                    if (playable.size < parsed.size) " · остальные не найдены в каталогах" else " · всё готово"
                _state.update { it.copy(linkImportResult = result) }
                _messages.tryEmit(result)
                if (download) saveAll(playable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message?.takeIf { it.startsWith("Spotify:") || it.startsWith("Яндекс") }
                    ?: "Импорт не удался. Проверьте ссылку и соединение"
                _state.update { it.copy(linkImportResult = message) }
                _messages.tryEmit(message)
            } finally {
                _state.update { it.copy(isImporting = false, importProgress = "") }
                onFinished()
            }
        }
    }

    /** Parsed link plus the sentence to show when the provider refused: `error` always explains why. */
    private data class ParsedLink(
        val name: String,
        val tracks: List<SongInfo> = emptyList(),
        val error: String? = null,
    )

    private suspend fun parsePlaylistLink(link: String): ParsedLink {
        val uri = link.toHttpUrlOrNull()
            ?: return ParsedLink("", error = "Нужна полная ссылка https:// на профиль или плейлист")
        val parts = uri.pathSegments.filter { it.isNotEmpty() }
        val yandexProfile = uri.host.startsWith("music.yandex.") && parts.size == 2 && parts[0] == "users"
        val spotifyProfile = uri.host == "open.spotify.com" && "user" in parts
        if (yandexProfile || spotifyProfile) {
            val urls = if (yandexProfile) {
                val client = com.musicdl.core.source.YandexMusicClient(clientConfig())
                try { client.profilePlaylistUrls(link) } finally { client.close() }
            } else com.musicdl.core.transfer.SpotifyProfileSync.playlistUrls(link, library.spotifyToken)
            if (urls.isEmpty()) return ParsedLink("Профиль", error = "В профиле нет доступных публичных плейлистов")
            val tracks = mutableListOf<SongInfo>()
            for ((index, url) in urls.withIndex()) {
                _state.update { it.copy(importProgress = "Профиль: плейлист ${index + 1} из ${urls.size}") }
                val parsed = parsePlaylistLink(url)
                parsed.error?.let { return ParsedLink("Профиль", error = it) }
                tracks += parsed.tracks
            }
            return ParsedLink(if (yandexProfile) "Яндекс · профиль ${parts.last()}" else "Spotify · профиль", tracks.distinctBy { it.key() })
        }
        if (uri.host == "open.spotify.com") {
            val parsed = SpotifyLinkParser.parse(link, logger)
            if (parsed is SpotifyParseResult.Failure) return ParsedLink("Spotify", error = parsed.message)
            val spotify = (parsed as SpotifyParseResult.Success).link
            val completeTracks = if (library.spotifyToken.isNotBlank() && spotify.kind == "playlist")
                com.musicdl.core.transfer.SpotifyProfileSync.tracks(spotify.id, library.spotifyToken) else spotify.tracks
            val songs = completeTracks.map { track ->
                SongInfo(
                    source = "SpotifyMusicClient",
                    identifier = "sp_${track.id}",
                    rawData = mapOf("link" to spotify.id),
                    songName = track.title,
                    singers = track.artists.ifBlank { null },
                    album = track.album.ifBlank { null },
                    durationSeconds = track.durationSeconds,
                    duration = SongInfo.formatDuration(track.durationSeconds),
                    coverUrl = track.coverUrl,
                )
            }
            return ParsedLink("Spotify · ${spotify.name}", songs)
        }
        val label = when {
            link.contains("yandex", ignoreCase = true) -> "Яндекс"
            link.contains("vk.com", ignoreCase = true) || link.contains("vk.ru", ignoreCase = true) -> "VK"
            else -> "Плейлист"
        }
        if (label == "VK") {
            if (library.vkToken.isBlank()) return ParsedLink("VK", error = "Для VK нужен вход. Подключите VK в настройках → Музыкальные сервисы")
            val vk = com.musicdl.core.source.VKMusicClient(clientConfig())
            try {
                val tracks = vk.parsePlaylist(link)
                return ParsedLink("VK", tracks, vk.lastError)
            } finally { vk.close() }
        }
        val tracks = MusicClient(listOf("YandexMusicClient"), clientConfig()).run {
            try {
                parsePlaylist(link)
            } finally {
                runCatching { close() }
            }
        }
        // The provider's own title is carried on the rows; the label alone makes every import look alike.
        val title = tracks.firstOrNull()?.rawData?.get("playlistTitle") as? String
        val name = if (title.isNullOrBlank()) label else "$label · $title"
        return ParsedLink(name, tracks)
    }

    /** Keeps the order of the link; tracks without a file are looked up in the active catalogs. */
    private suspend fun resolvePlayable(tracks: List<SongInfo>): List<SongInfo> = coroutineScope {
        val missing = tracks.filterNot { it.hasValidDownloadUrl }
        if (missing.isEmpty()) return@coroutineScope tracks
        // One attempt per provider: a retry only doubles the wait, and a miss just moves to the next
        // catalog. Six lanes keep a 100-track playlist at about a minute instead of six.
        val cascade = ProviderCascade(
            (supportedSources(_state.value.activeSources) + RECOMMENDED_SOURCES).toList(),
            clientConfig(),
            attemptsPerProvider = 1,
        )
        val slots = Semaphore(6)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val total = missing.size
        val resolved = missing.map { track ->
            async(Dispatchers.IO) {
                val hit = slots.withPermit { cascade.find(track.songName, track.singers).value }
                _state.update { it.copy(importProgress = "Ссылка: ${done.incrementAndGet()} из $total") }
                track.key() to hit
            }
        }.awaitAll().toMap()
        tracks.mapNotNull { track -> if (track.hasValidDownloadUrl) track else resolved[track.key()] }
    }

    override fun onCleared() {
        settleListening()
        persistPlayback()
        positionJob?.cancel()
        metadataClient.close()
        player.removeListener(playerListener)
        PlayerHolder.uiAttached = false
        PlayerHolder.next = null
        PlayerHolder.previous = null
        if (PlayerHolder.session == null && PlayerHolder.player === player) {
            player.release()
            PlayerHolder.player = null
        }
        super.onCleared()
    }
}
