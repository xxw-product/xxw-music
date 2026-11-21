package com.musicdl.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musicdl.core.lyrics.Lyrics
import com.musicdl.core.model.SongInfo
import com.musicdl.core.source.MusicClient
import com.musicdl.core.source.MusicClientRegistry

@Composable
fun MusicdlScreen(viewModel: SearchViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = state.openCollection != null) { viewModel.closeCollection() }
    // A tab the user hid in Appearance must not stay open.
    LaunchedEffect(Appearance.showWaveTab, Appearance.showArtistsTab, state.tab) {
        if ((state.tab == FeedTab.WAVE && !Appearance.showWaveTab) || (state.tab == FeedTab.ARTISTS && !Appearance.showArtistsTab)) {
            viewModel.setTab(FeedTab.SEARCH)
        }
    }
    val snackbarHost = remember { SnackbarHostState() }
    // One lookup table instead of a linear scan of the download index for every visible card.
    val downloadsByKey = remember(state.downloads) { state.downloads.associateBy { it.key } }
    // The same recording arrives with different provider keys after a re-search or a wave refresh;
    // a plain key lookup made the sheet offer "Скачать" for a track that was already offline.
    // The fuzzy fallback scans every download; remember its answer per track until the downloads change.
    val downloadedCache = remember(state.downloads) { HashMap<String, DownloadEntry?>() }
    fun downloadedEntry(song: SongInfo): DownloadEntry? {
        val key = song.key()
        downloadsByKey[key]?.let { return it }
        if (downloadedCache.containsKey(key)) return downloadedCache[key]
        return state.downloads.firstOrNull { sameRecording(it.title, it.artist, song.songName, song.singers) }
            .also { downloadedCache[key] = it }
    }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var playlistSong by remember { mutableStateOf<SongInfo?>(null) }
    if (showPlaylists) PlaylistLibrary(state, viewModel, onClose = { showPlaylists = false })
    playlistSong?.let { track -> PlaylistPicker(state.playlists, track,
        onCreate = viewModel::createPlaylist,
        onAdd = { id -> viewModel.addToPlaylist(id, track); playlistSong = null },
        onClose = { playlistSong = null }) }
    var showWeights by rememberSaveable { mutableStateOf(false) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var fullPlayerLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var actionsSong by remember { mutableStateOf<SongInfo?>(null) }

    if (showFullPlayer) {
        PlayerCardOverlay(onClosed = { showFullPlayer = false }) { closeCard ->
            FullPlayer(
                state = state,
                progressFlow = viewModel.playerProgress,
                startWithLyrics = fullPlayerLyrics,
                onClose = closeCard,
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::playNext,
                onPrevious = viewModel::playPrevious,
                onSeek = viewModel::seekTo,
                onLike = viewModel::likeCurrent,
                onDislike = viewModel::dislikeCurrent,
                onSave = { state.player.song?.let(viewModel::save) },
                onDeleteDownload = { state.player.song?.let(viewModel::deleteDownload) },
                onFollow = { state.player.song?.let(viewModel::toggleFollow) },
                onLoadLyrics = viewModel::loadLyrics,
                onShuffle = viewModel::toggleShuffle,
                onRepeat = viewModel::toggleRepeat,
                onSpeed = viewModel::cycleSpeed,
                onAddToPlaylist = { state.player.song?.let { playlistSong = it } },
                onPlayNext = { state.player.song?.let(viewModel::playNextInQueue) },
                onAddToQueue = { state.player.song?.let(viewModel::addToQueue) },
                onOpenQueue = { showQueue = true },
                onOpenClips = { showFullPlayer = false; viewModel.openClips() },
                onOpenActions = { actionsSong = state.player.song },
                onOpenArtist = {
                    val song = state.player.song
                    val artists = ArtistNames.split(song?.singers)
                    when {
                        song == null || artists.isEmpty() -> Unit
                        artists.size == 1 -> { showFullPlayer = false; viewModel.setTab(FeedTab.ARTISTS); viewModel.openArtist(artists.first()) }
                        else -> viewModel.openArtistPicker(song)
                    }
                },
            )
        }
    }

    state.artistPicker?.let { song ->
        ArtistPickerSheet(
            song = song,
            followed = state.followedArtists,
            onDismiss = viewModel::closeArtistPicker,
            onOpen = { name ->
                viewModel.closeArtistPicker()
                showFullPlayer = false
                viewModel.setTab(FeedTab.ARTISTS)
                viewModel.openArtist(name)
            },
            onFollow = viewModel::toggleFollowName,
            onAlbum = { showFullPlayer = false; viewModel.searchAlbumOf(song) },
        )
    }

    actionsSong?.let { track ->
        TrackActionsSheet(
            song = track,
            isLiked = track.key() in state.likedKeys,
            isFollowed = state.followedArtists.any { it.equals(track.singers?.trim(), ignoreCase = true) },
            isCurrent = state.player.song?.key() == track.key(),
            save = state.saves[track.key()],
            downloaded = downloadedEntry(track),
            onDismiss = { actionsSong = null },
            onPlay = { actionsSong = null; viewModel.playOrToggle(track) },
            onPlayNext = { actionsSong = null; viewModel.playNextInQueue(track) },
            onAddToQueue = { actionsSong = null; viewModel.addToQueue(track) },
            onLike = { viewModel.toggleLike(track) },
            onDownload = { actionsSong = null; viewModel.save(track) },
            onDeleteDownload = { actionsSong = null; viewModel.deleteDownload(track) },
            onAddToPlaylist = { actionsSong = null; playlistSong = track },
            onFollow = { viewModel.toggleFollow(track) },
            onLyrics = {
                actionsSong = null
                viewModel.playOrToggle(track)
                fullPlayerLyrics = true
                showFullPlayer = true
                viewModel.loadLyrics()
            },
        )
    }

    if (showQueue) {
        QueueSheet(
            items = state.queueItems,
            currentKey = state.player.song?.key(),
            shuffle = state.shuffle,
            onClose = { showQueue = false },
            onPlay = { index -> showQueue = false; viewModel.playQueueAt(index) },
            onMove = viewModel::moveInQueue,
            onRemove = viewModel::removeFromQueue,
            onClear = { viewModel.clearQueue(); showQueue = false },
            onShuffle = viewModel::toggleShuffle,
            candidates = (state.results + state.wave + state.likes).distinctBy { it.key() },
            onAdd = viewModel::addToQueue,
            onAddAll = {
                val missing = (state.results + state.wave + state.likes).distinctBy { it.key() }
                    .filter { candidate -> state.queueItems.none { it.key() == candidate.key() } }
                viewModel.addManyToQueue(missing)
            },
        )
    }

    if (state.clips.clips.isNotEmpty()) {
        ClipsScreen(
            state = state.clips,
            onClose = viewModel::closeClips,
            onNext = viewModel::clipsNext,
            onPrevious = viewModel::clipsPrevious,
            onRetry = viewModel::retryClip,
        )
    }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(message)
        }
    }

    if (showSources) {
        SourcesDialog(
            state = state,
            onDismiss = { showSources = false },
            onRecommended = {
                viewModel.selectRecommendedSources()
                showSources = false
            },
            onAll = viewModel::selectAllSources,
            onToggle = viewModel::toggleSource,
            onOnly = viewModel::selectOnlySource,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .animateContentSize()
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surfaceContainerLow,
                    0.45f to MaterialTheme.colorScheme.background,
                    1f to MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
            ),
    ) {
        val glassBackdrop = rememberGlassBackdrop()
        var navCollapsed by remember { mutableStateOf(false) }
        val realGlass = Appearance.realGlassActive
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().height(68.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.tab == FeedTab.SETTINGS) {
                            IconButton(onClick = { viewModel.setTab(FeedTab.LIKES) }) {
                                Icon(Icons.Rounded.ArrowBack, "Назад в библиотеку")
                            }
                        }
                        Crossfade(state.tab, label = "pageTitle", modifier = Modifier.weight(1f)) { tab ->
                            Text(when (tab) {
                                FeedTab.SEARCH -> "Поиск"
                                FeedTab.WAVE -> "Волна"
                                FeedTab.LIKES -> "Библиотека"
                                FeedTab.ARTISTS -> "Исполнители"
                                FeedTab.SETTINGS -> "Настройки"
                            }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        }
                        if (state.tab == FeedTab.WAVE) {
                            VpnMenuButton(onOpenSettings = { viewModel.setTab(FeedTab.SETTINGS) },
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        if (state.tab == FeedTab.SEARCH) IconButton(onClick = { showSources = true }) {
                            Icon(Icons.Rounded.Tune, "Источники поиска")
                        }
                    }
                    if (state.tab == FeedTab.SEARCH && !Appearance.searchOverlay) {
                        SearchBar(state, viewModel)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            bottomBar = {
                // The wave tab has its own player card; two players on one page look wrong.
                val waveCardShown = state.tab == FeedTab.WAVE && !state.isWaveLoading &&
                    state.player.song?.let { current -> state.wave.any { it.key() == current.key() } } == true
                val miniShown = state.player.song != null && !waveCardShown
                val glassBar = Appearance.liquidGlass && Appearance.glassNav && Appearance.navCollapse &&
                    (!miniShown || Appearance.glassMini)
                // Scrolling down folds the tab bar into one circle and the mini player takes the rest of the row.
                Crossfade(navCollapsed && glassBar, animationSpec = tween(220), label = "bottomBar") { folded ->
                    if (folded) {
                        Column {
                            // Same total height as the unfolded bar, so the list padding never moves.
                            val spare = navBarHeight() + (if (miniShown) 60.dp else 0.dp) - 60.dp
                            Spacer(Modifier.height(spare.coerceAtLeast(0.dp)))
                            Row(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 10.dp).height(60.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                CollapsedNavButton(state.tab, onClick = { navCollapsed = false })
                                if (miniShown) {
                                    Spacer(Modifier.width(4.dp))
                                    MiniPlayer(state, progressFlow = viewModel.playerProgress,
                                        onOpen = { fullPlayerLyrics = false; showFullPlayer = true },
                                        onPlayPause = viewModel::togglePlayPause, onNext = viewModel::playNext,
                                        onQueue = { showQueue = true }, collapsed = true, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedVisibility(miniShown) {
                                MiniPlayer(state, progressFlow = viewModel.playerProgress,
                                    onOpen = { fullPlayerLyrics = false; showFullPlayer = true },
                                    onPlayPause = viewModel::togglePlayPause, onNext = viewModel::playNext,
                                    onQueue = { showQueue = true })
                            }
                            AppNavigation(state.tab, viewModel::setTab)
                        }
                    }
                }
            },
        ) { padding ->
            val scrollStates = FeedTab.entries.associateWith { tab -> key(tab) { rememberLazyListState() } }
            // Like the iOS tab bar: it shrinks to icons while the list scrolls down and returns on the way back up.
            val activeList = scrollStates.getValue(state.tab)
            LaunchedEffect(activeList) {
                navCollapsed = false
                var lastIndex = activeList.firstVisibleItemIndex
                var lastOffset = activeList.firstVisibleItemScrollOffset
                var travel = 0f          // scrolled in one direction since it last changed
                var lastSwitch = 0L
                snapshotFlow { activeList.firstVisibleItemIndex to activeList.firstVisibleItemScrollOffset }.collect { (index, offset) ->
                    val delta = ((index - lastIndex) * 1000 + (offset - lastOffset)).toFloat()
                    lastIndex = index; lastOffset = offset
                    travel = if (delta * travel < 0f) delta else travel + delta
                    val now = android.os.SystemClock.uptimeMillis()
                    // A real swipe is needed to flip the bar, and it may not flip again at once: no flicker on flings.
                    val ready = now - lastSwitch > 450L
                    if (index == 0 && offset < 40) {
                        if (navCollapsed) { navCollapsed = false; lastSwitch = now }
                    } else if (ready && travel > 140f && !navCollapsed) { navCollapsed = true; lastSwitch = now; travel = 0f }
                    else if (ready && travel < -90f && navCollapsed) { navCollapsed = false; lastSwitch = now; travel = 0f }
                }
            }
            val searchOverlay = Appearance.searchOverlay && state.tab == FeedTab.SEARCH
            var searchBlockPx by remember { mutableIntStateOf(0) }
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = scrollStates.getValue(state.tab),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = if (realGlass) 0.dp else padding.calculateBottomPadding())
                    .glassSource(glassBackdrop, realGlass, edge = padding.calculateBottomPadding() + 22.dp)
                    .padding(horizontal = 18.dp),
                contentPadding = PaddingValues(
                    top = if (searchOverlay) with(LocalDensity.current) { searchBlockPx.toDp() } else 0.dp,
                    bottom = if (realGlass) padding.calculateBottomPadding() else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.tab == FeedTab.WAVE) {
                    item {
                        val fillPage = state.wave.isNotEmpty() && !state.isWaveLoading
                        Box(if (fillPage) Modifier.fillParentMaxHeight().padding(bottom = 8.dp) else Modifier) {
                        WaveCard(
                            fillHeight = fillPage,
                            state = state,
                            showWeights = showWeights,
                            onToggleWeights = { showWeights = !showWeights },
                            onStart = viewModel::startWave,
                            onWeight = viewModel::setGenreWeight,
                            onToggle = viewModel::togglePlayPause,
                            onNext = viewModel::playNext,
                            onPrevious = viewModel::playPrevious,
                            onOpenPlayer = { fullPlayerLyrics = false; showFullPlayer = true },
                            onLike = { viewModel.likeCurrent() },
                            onDislike = { viewModel.dislikeCurrent() },
                            onResume = { state.wave.firstOrNull()?.let { viewModel.playOrToggle(it) } },
                        )
                        }
                    }
                }
                if (state.tab == FeedTab.WAVE) {
                    if (state.isWaveLoading) {
                        item { SearchingCard(state) }
                    } else if (state.wave.isEmpty()) {
                        item { NoticeCard(state.error ?: "Начните слушать свою волну.") }
                    } else {
                    }
                } else if (state.tab == FeedTab.SETTINGS) {
                    item { SettingsContent(state, viewModel) }
                } else if (state.tab == FeedTab.ARTISTS) {
                    val artist = state.artistFeed
                    if (artist == null) {
                        item { SectionHeader("Избранные авторы", state.followedArtists.size) }
                        if (state.followedArtists.isEmpty()) {
                            item {
                                NoticeCard(
                                    "Здесь будут ваши любимые исполнители.",
                                )
                            }
                        }
                        items(state.followedArtists, key = { "artist:$it" }) { name ->
                            ArtistRow(
                                name = name,
                                onOpen = { viewModel.openArtist(name) },
                                onUnfollow = { viewModel.unfollowArtist(name) },
                            )
                        }
                    } else {
                        item {
                            ArtistHero(
                                name = artist,
                                following = state.followedArtists.any { it.equals(artist, ignoreCase = true) },
                                loading = state.isArtistLoading,
                                trackCount = state.artistTracks.size,
                                onBack = viewModel::closeArtist,
                                onRefresh = { viewModel.openArtist(artist) },
                                onFollow = { viewModel.toggleFollowName(artist) },
                                onPlay = { viewModel.playAll(state.artistTracks) },
                                onShuffle = { viewModel.playShuffled(state.artistTracks) },
                                onAlbums = { viewModel.searchAlbumsOfArtist(artist) },
                            )
                        }
                        if (state.isArtistLoading && state.artistTracks.isEmpty()) {
                            item { SearchingCard(state) }
                        }
                        items(state.artistTracks, key = { "at:${it.key()}" }) { song ->
                            SongCard(
                                song = song,
                                save = state.saves[song.key()],
                                isCurrent = state.player.song?.key() == song.key(),
                                isPlaying = state.player.song?.key() == song.key() && state.player.isPlaying,
                                isLiked = song.key() in state.likedKeys,
                                isFollowed = state.isFollowedBy(song),
                                onPlay = { viewModel.playOrToggle(song) },
                                onSave = { viewModel.save(song) },
                                onLike = { viewModel.toggleLike(song) },
                                onFollow = { viewModel.toggleFollow(song) },
                                onAddToPlaylist = { playlistSong = song },
                                downloaded = downloadedEntry(song),
                                onActions = { actionsSong = song },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } else if (state.tab == FeedTab.LIKES) {
                    item {
                        SettingsEntry(onOpen = { viewModel.setTab(FeedTab.SETTINGS) })
                    }
                    item { PlaylistEntry(state, onOpen = { showPlaylists = true }) }
                    item {
                        LikesHeader(
                            count = state.likes.size,
                            onPlayAll = { viewModel.playAll(state.likes) },
                            onDownloadAll = viewModel::saveAllLikes,
                        )
                    }
                    if (state.likes.isEmpty()) {
                        item { NoticeCard("Сохраняйте любимые треки — они появятся здесь.") }
                    }
                    items(state.likes, key = { "like:${it.key()}" }) { song ->
                        SongCard(
                            song = song,
                            save = state.saves[song.key()],
                            isCurrent = state.player.song?.key() == song.key(),
                            isPlaying = state.player.song?.key() == song.key() && state.player.isPlaying,
                            isLiked = true,
                            onPlay = { viewModel.playOrToggle(song) },
                            isFollowed = state.isFollowedBy(song),
                            onFollow = { viewModel.toggleFollow(song) },
                                onAddToPlaylist = { playlistSong = song },
                            onSave = { viewModel.save(song) },
                            onLike = { viewModel.toggleLike(song) },
                            downloaded = downloadedEntry(song),
                                onActions = { actionsSong = song },
                                modifier = Modifier.animateItem(),
                        )
                    }
                } else {
                    if (state.isSearching) {
                        item { SearchingCard(state) }
                    }

                    state.error?.let { message ->
                        if (!state.isSearching) {
                            item { NoticeCard(message) }
                        }
                    }

                    if (state.canWiden && !state.isSearching) {
                        item { WidenCard(onWiden = viewModel::widenSearch) }
                    }

                    if (state.history.isNotEmpty() && state.openCollection == null && !state.isSearching &&
                        (if (state.searchKind == SearchKind.TRACKS) state.results.isEmpty() else state.collections.isEmpty())) {
                        item {
                            HistoryRow(
                                history = state.history,
                                onPick = viewModel::searchKeyword,
                                onClear = viewModel::clearHistory,
                            )
                        }
                    }

                    val open = state.openCollection
                    if (open != null) {
                        item {
                            CollectionHeader(
                                collection = open,
                                loading = state.isCollectionLoading,
                                enabled = state.collectionTracks.isNotEmpty(),
                                onBack = viewModel::closeCollection,
                                onPlayAll = { viewModel.playAll(state.collectionTracks) },
                                onQueue = { viewModel.addManyToQueue(state.collectionTracks) },
                                onSave = viewModel::saveCollectionAsPlaylist,
                            )
                        }
                        items(state.collectionTracks, key = { "col:${it.key()}" }) { song ->
                            SongCard(
                                song = song,
                                save = state.saves[song.key()],
                                isCurrent = state.player.song?.key() == song.key(),
                                isPlaying = state.player.song?.key() == song.key() && state.player.isPlaying,
                                isLiked = song.key() in state.likedKeys,
                                isFollowed = state.isFollowedBy(song),
                                onPlay = { viewModel.playOrToggle(song) },
                                onFollow = { viewModel.toggleFollow(song) },
                                onAddToPlaylist = { playlistSong = song },
                                onSave = { viewModel.save(song) },
                                onLike = { viewModel.toggleLike(song) },
                                downloaded = downloadedEntry(song),
                                onActions = { actionsSong = song },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    } else if (state.searchKind != SearchKind.TRACKS) {
                        if (state.collections.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    if (state.searchKind == SearchKind.ALBUMS) "Альбомы" else "Плейлисты",
                                    state.collections.size,
                                )
                            }
                            items(state.collections, key = { "col:${it.source}:${it.id}" }) { collection ->
                                CollectionCard(collection, onOpen = { viewModel.openCollection(collection) }, modifier = Modifier.animateItem())
                            }
                        }
                    } else if (state.results.isNotEmpty() || state.artistHits.isNotEmpty()) {
                        if (state.artistHits.isNotEmpty()) {
                            item { SectionHeader("Исполнители", state.artistHits.size) }
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    items(state.artistHits, key = { "hit:${it.name}" }) { hit ->
                                        ArtistHitCard(
                                            hit = hit,
                                            following = state.followedArtists.any { it.equals(hit.name, ignoreCase = true) },
                                            onOpen = { viewModel.setTab(FeedTab.ARTISTS); viewModel.openArtist(hit.name) },
                                            onFollow = { viewModel.toggleFollowName(hit.name) },
                                        )
                                    }
                                }
                            }
                        }
                        if (state.results.isNotEmpty()) item { SectionHeader("Треки", state.results.size) }
                        items(state.results, key = { "res:${it.key()}" }) { song ->
                            SongCard(
                                song = song,
                                save = state.saves[song.key()],
                                isCurrent = state.player.song?.key() == song.key(),
                                isPlaying = state.player.song?.key() == song.key() && state.player.isPlaying,
                                isLiked = song.key() in state.likedKeys,
                                isFollowed = state.isFollowedBy(song),
                                onPlay = { viewModel.playOrToggle(song) },
                                onFollow = { viewModel.toggleFollow(song) },
                                onAddToPlaylist = { playlistSong = song },
                                onSave = { viewModel.save(song) },
                                onLike = { viewModel.toggleLike(song) },
                                downloaded = downloadedEntry(song),
                                onActions = { actionsSong = song },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
            if (searchOverlay) {
                Column(
                    Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()).padding(horizontal = 18.dp)
                        .onSizeChanged { searchBlockPx = it.height },
                ) {
                    SearchBar(state, viewModel)
                    Spacer(Modifier.height(8.dp))
                }
            }
            }
        }
    }
}

@Composable
private fun SearchBar(state: MusicUiState, viewModel: SearchViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val artistOnly = state.searchArtistOnly
    val submit = { keyboard?.hide(); if (artistOnly) viewModel.findArtist() else viewModel.search() }
    Column {
        SegmentedTabs(
            listOf(
                Triple("Треки", !artistOnly && state.searchKind == SearchKind.TRACKS) { viewModel.setSearchKind(SearchKind.TRACKS) },
                Triple("Альбомы", state.searchKind == SearchKind.ALBUMS) { viewModel.setSearchKind(SearchKind.ALBUMS) },
                Triple("Исполнители", artistOnly) { viewModel.setSearchArtistOnly(true) },
                Triple("Плейлисты", state.searchKind == SearchKind.PLAYLISTS) { viewModel.setSearchKind(SearchKind.PLAYLISTS) },
            ),
        )
        Spacer(Modifier.height(10.dp))
    val glassSearch = Appearance.liquidGlass && Appearance.glassSearch
    val searchShape = if (glassSearch) CircleShape else MaterialTheme.shapes.medium
    Surface(
        shape = searchShape,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().glassOutline(searchShape, glassSearch),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                placeholder = {
                    Text(
                        when {
                            artistOnly -> "Имя исполнителя"
                            state.searchKind == SearchKind.ALBUMS -> "Название альбома"
                            state.searchKind == SearchKind.PLAYLISTS -> "Название плейлиста"
                            else -> "Название трека"
                        },
                    )
                },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onKeywordChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Очистить")
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = { submit() },
                enabled = !state.isSearching && state.keyword.isNotBlank(),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (state.isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = "Поиск")
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesDialog(
    state: MusicUiState,
    onDismiss: () -> Unit,
    onRecommended: () -> Unit,
    onAll: () -> Unit,
    onToggle: (String) -> Unit,
    onOnly: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        title = { Text("Источники") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRecommended, shape = MaterialTheme.shapes.small) {
                        Text("Авто · ${RECOMMENDED_SOURCES.size}")
                    }
                    FilledTonalButton(onClick = onAll, shape = MaterialTheme.shapes.small) {
                        Text("Все ${ALL_SOURCES.size}")
                    }
                }
                Text(
                    text = if (state.isSearching) {
                        "Идёт поиск — новый набор применится к следующему запросу."
                    } else {
                        "${RECOMMENDED_SOURCES.sortedBy(::sourceName).joinToString(", ") { sourceName(it) }}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(ALL_SOURCES.sortedBy { sourceName(it) }, key = { it }) { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(source) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = source in state.activeSources, onCheckedChange = { onToggle(source) })
                            Text(
                                text = sourceName(source),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onOnly(source) }) {
                                Text("только", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WaveCard(
    fillHeight: Boolean = false,
    state: MusicUiState,
    showWeights: Boolean,
    onToggleWeights: () -> Unit,
    onStart: () -> Unit,
    onWeight: (String, Float) -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onResume: () -> Unit,
) {
    val hasWave = state.wave.isNotEmpty() && !state.isWaveLoading
    Box(
        modifier = (if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).clip(MaterialTheme.shapes.extraLarge)
            .background(Vinyl).border(1.dp, Color(0xFF303238), MaterialTheme.shapes.extraLarge),
    ) {
        VinylAtmosphere(Modifier.matchParentSize())
        Column(
            modifier = (if (fillHeight) Modifier.fillMaxSize() else Modifier).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Моя волна",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "Весь ваш вкус. Новое звучание.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Equalizer(active = state.player.isPlaying, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            if (hasWave) {
                Box(
                    (if (fillHeight) Modifier.weight(1f) else Modifier).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { WavePlayerBody(state, onToggle, onNext, onPrevious, onOpenPlayer, onLike, onDislike, onResume) }
            } else Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.isWaveLoading) CircularProgressIndicator(Modifier.size(48.dp).padding(10.dp), strokeWidth = 1.5.dp, color = Silver)
                else TransportButton(prominent = true, compact = true, onClick = onStart)
                Column(Modifier.weight(1f)) {
                    Text(if (state.isWaveLoading) "Собираем вашу волну" else if (hasWave) "Встряхнуть волну" else "Включить волну", color = Silver,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (!hasWave) Text("Знакомое и ещё не открытое", color = Silver.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onToggleWeights) { Text(if (showWeights) "Скрыть" else "Жанры", color = Silver) }
            }

            AnimatedVisibility(visible = showWeights) {
                GenreWeights(state.coefficients, onWeight)
            }
        }
    }
}

/** Wave player body shown inside the vinyl card: one current track, no list. */
@Composable
private fun WavePlayerBody(
    state: MusicUiState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    onResume: () -> Unit,
) {
    val waveKeys = remember(state.wave) { state.wave.map { it.key() }.toSet() }
    val current = state.player.song?.takeIf { it.key() in waveKeys }
    val song = current ?: state.wave.first()
    val liked = song.key() in state.likedKeys
    var dragX by remember { mutableFloatStateOf(0f) }
    // Swipe the cover to change tracks, tap it to open the full player.
    val coverModifier = Modifier.fillMaxWidth(0.72f).aspectRatio(1f)
        .graphicsLayer { translationX = dragX; alpha = 1f - (kotlin.math.abs(dragX) / 1200f).coerceIn(0f, 0.5f) }
        .clip(MaterialTheme.shapes.large)
        .clickable(enabled = current != null, onClick = onOpenPlayer)
        .pointerInput(current?.key()) {
            val threshold = 80.dp.toPx()
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (current != null) {
                        if (dragX < -threshold) onNext() else if (dragX > threshold) onPrevious()
                    }
                    dragX = 0f
                },
                onDragCancel = { dragX = 0f },
                onHorizontalDrag = { change, amount -> change.consume(); dragX += amount },
            )
        }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CoverImage(
            url = if (current != null) state.player.artworkUrl ?: song.coverUrl else song.coverUrl,
            modifier = coverModifier,
        ) {
            Box(coverModifier.background(Color(0xFF1B1D22)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(56.dp), tint = Silver.copy(alpha = 0.5f))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(song.songName.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Silver)
            Text(song.singers.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Silver.copy(alpha = 0.65f))
        }
        Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDislike, enabled = current != null) {
                Icon(Icons.Rounded.ThumbDown, "Реже", tint = Silver.copy(alpha = 0.8f))
            }
            TransportButton(
                playing = current != null && state.player.isPlaying,
                buffering = current != null && state.player.isBuffering,
                prominent = true,
                onClick = if (current != null) onToggle else onResume,
            )
            IconButton(onClick = onLike, enabled = current != null) {
                Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится", tint = Silver)
            }
        }
    }
}

@Composable
private fun GenreWeights(coefficients: Map<String, Float>, onWeight: (String, Float) -> Unit) {
    val top = Genres.all
        .sortedByDescending { coefficients[it.id] ?: Genres.DEFAULT_WEIGHT }
        .take(6)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        top.forEach { genre ->
            val value = coefficients[genre.id] ?: Genres.DEFAULT_WEIGHT
            val animated by animateFloatAsState(
                targetValue = (value / 2f).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 380f),
                label = "weight_${genre.id}",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = genre.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "×%.1f".format(value),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.width(38.dp),
                )
                TextButton(onClick = { onWeight(genre.id, value - 0.2f) }, modifier = Modifier.width(40.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
                TextButton(onClick = { onWeight(genre.id, value + 0.2f) }, modifier = Modifier.width(40.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
            }
        }
    }
}

@Composable
private fun SearchingCard(state: MusicUiState) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = state.progressText.ifBlank { "Ищу…" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = {
                    if (state.progressTotal > 0) {
                        (state.progressCompleted.toFloat() / state.progressTotal.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun CollectionCard(collection: com.musicdl.core.source.MusicCollection, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CollectionCover(collection, Modifier.size(64.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        collection.owner?.takeIf { it.isNotBlank() },
                        collection.trackCount.takeIf { it > 0 }?.let { "$it тр." },
                        collection.year?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(collection.source.removeSuffix("MusicClient"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CollectionCover(collection: com.musicdl.core.source.MusicCollection, modifier: Modifier) {
    CoverImage(collection.coverUrl, modifier.clip(RoundedCornerShape(8.dp))) {
        Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CollectionHeader(
    collection: com.musicdl.core.source.MusicCollection,
    loading: Boolean,
    enabled: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onQueue: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← К результатам") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CollectionCover(collection, Modifier.size(104.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                collection.owner?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        if (collection.kind == com.musicdl.core.source.CollectionKind.ALBUM) "Альбом" else "Плейлист",
                        collection.trackCount.takeIf { it > 0 }?.let { "$it тр." },
                        collection.year?.takeIf { it.isNotBlank() },
                        collection.source.removeSuffix("MusicClient"),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  Загружаю треки…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlayAll, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Играть всё") }
            FilledTonalButton(onClick = onQueue, enabled = enabled, modifier = Modifier.weight(1f)) { Text("В очередь") }
        }
        TextButton(onClick = onSave, enabled = enabled) { Text("Сохранить как плейлист") }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Compact player that also works as the handle of the full player: pull it up to expand, tap to open.
 * Drag distance is tiny and local — no dialog is created until the gesture is released.
 */
@Composable
private fun MiniPlayer(state: MusicUiState, progressFlow: StateFlow<Float>, onOpen: () -> Unit,
                       onPlayPause: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit, collapsed: Boolean = false,
                       modifier: Modifier = Modifier) {
    val song = state.player.song ?: return
    val playbackProgress by progressFlow.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val maxPull = with(density) { 130.dp.toPx() }
    val openPull = with(density) { 34.dp.toPx() }
    var dragging by remember(song.key()) { mutableStateOf(false) }
    var dragY by remember(song.key()) { mutableFloatStateOf(0f) }
    val pull by animateFloatAsState(
        targetValue = if (dragging) dragY else 0f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
        label = "miniPlayerPull",
    )
    val progress = (-pull / maxPull).coerceIn(0f, 1f)
    val glassMini = Appearance.liquidGlass && Appearance.glassMini
    val miniShape = if (glassMini) CircleShape else MaterialTheme.shapes.medium
    val shrink by animateFloatAsState(
        if (collapsed && glassMini && Appearance.navCollapse) 1f else 0f,
        spring(dampingRatio = 0.85f, stiffness = 400f), label = "miniShrink",
    )
    val card: @Composable () -> Unit = {
    Surface(color = if (glassMini) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh, shape = miniShape,
        modifier = Modifier.padding(horizontal = if (glassMini) 16.dp else 12.dp, vertical = 4.dp).fillMaxWidth()
            .glassSurface(miniShape, MaterialTheme.colorScheme.surfaceContainerHigh, glassMini, real = true)
            .graphicsLayer {
                translationY = pull * 0.45f
                scaleX = 1f + 0.035f * progress
                scaleY = 1f + 0.035f * progress
            }
            .draggable(
                state = rememberDraggableState { delta -> dragY = (dragY + delta).coerceIn(-maxPull, 0f) },
                orientation = Orientation.Vertical,
                onDragStarted = { dragging = true; dragY = 0f },
                onDragStopped = { velocity ->
                    val open = dragY < -openPull || velocity < -1_050f
                    dragging = false
                    dragY = 0f
                    if (open) onOpen()
                },
            )
            .clickable(onClick = onOpen)) {
        if (glassMini) Row(Modifier.padding(start = 7.dp, end = 4.dp).height((52f - 10f * shrink).dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size((38f - 8f * shrink).dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                CoverImage(song.coverUrl, Modifier.fillMaxSize()) { Icon(Icons.Rounded.MusicNote, null) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.songName ?: "Без названия", modifier = Modifier.basicMarquee(), softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (shrink < 0.5f) Text(song.singers.orEmpty(), modifier = Modifier.basicMarquee(), softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TransportButton(playing = state.player.isPlaying, buffering = state.player.isBuffering, compact = true, onClick = onPlayPause)
            TransportButton(direction = 1, compact = true, onClick = onNext)
        } else Column {
            // Grabber: the visual cue that this card can be pulled up into the full player.
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 30.dp, height = 3.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)))
            }
            Row(Modifier.padding(start = 10.dp, end = 4.dp).height(58.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    CoverImage(song.coverUrl, Modifier.fillMaxSize()) { Icon(Icons.Rounded.MusicNote, null) }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(song.songName ?: "Без названия", modifier = Modifier.basicMarquee(), softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(song.singers.orEmpty(), modifier = Modifier.basicMarquee(), softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onQueue) { Icon(Icons.Rounded.QueueMusic, "Очередь", Modifier.size(22.dp)) }
                TransportButton(playing = state.player.isPlaying, buffering = state.player.isBuffering, compact = true, onClick = onPlayPause)
                TransportButton(direction = 1, compact = true, onClick = onNext)
            }
            LinearProgressIndicator(progress = { playbackProgress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
    }
    // The glass capsule keeps a fixed slot, so shrinking it never changes the bottom padding of the list.
    if (glassMini) Box(modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.BottomCenter) { card() } else card()
}

/** Four bars bouncing while audio plays — the expressive take on a "now playing" indicator. */
@Composable
private fun Equalizer(active: Boolean, tint: Color) {
    // A transition that exists but is inactive still schedules a frame every vsync. When paused the
    // bars are static, so no animation object is created at all.
    if (active) {
        val transition = rememberInfiniteTransition(label = "eq")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) { index ->
                val height by transition.animateFloat(
                    initialValue = 5f,
                    targetValue = (12 + index * 4).toFloat(),
                    animationSpec = infiniteRepeatable(
                        animation = tween(300 + index * 90, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "eqBar$index",
                )
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(height.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.9f)),
                )
            }
        }
    } else {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

@Composable
private fun SongCard(
    song: SongInfo,
    save: SaveState?,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
    onLike: () -> Unit,
    isFollowed: Boolean,
    onFollow: () -> Unit,
    onAddToPlaylist: () -> Unit = {},
    downloaded: DownloadEntry? = null,
    onActions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val container = if (isCurrent) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val coverFallbackBrush = remember(primary, tertiary) {
        Brush.linearGradient(listOf(primary.copy(alpha = 0.85f), tertiary.copy(alpha = 0.7f)))
    }
    val glassCard = Appearance.liquidGlass && Appearance.glassCards
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (glassCard) Color.Transparent else container,
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(MaterialTheme.shapes.large, container, glassCard)
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = if (Appearance.compactCards) 5.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (Appearance.showCovers) Box(
                modifier = Modifier
                    .size(if (Appearance.compactCards) 44.dp else 54.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(coverFallbackBrush),
                contentAlignment = Alignment.Center,
            ) {
                CoverImage(url = song.coverUrl, modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = (song.songName ?: "?").trim().take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (Appearance.showCovers) Spacer(Modifier.width(12.dp)) else Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.songName ?: "Без названия",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.singers ?: "Неизвестный исполнитель",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (Appearance.showTrackDetails) Text(
                    text = listOfNotNull(
                        (song.ext ?: "?").uppercase(),
                        song.duration.takeIf { it != "-:-:-" },
                        song.source.removeSuffix("MusicClient").takeIf { it.isNotBlank() },
                        "превью".takeIf { isPreviewSource(song.source) },
                        downloaded?.let { "скачано · ${DownloadStore.sizeLabel(it.totalBytes)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPlaying) Equalizer(true, MaterialTheme.colorScheme.primary)
            TrackMenuButton(onClick = onActions)
        }
    }
}

@Composable
private fun LikesHeader(count: Int, onPlayAll: () -> Unit, onDownloadAll: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Нравится",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = if (count == 0) "Пустой плейлист" else "$count треков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            if (count > 0) {
                FilledTonalButton(onClick = onPlayAll) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Слушать", maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDownloadAll) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Скачать все",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Недавние запросы",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) { Text("Очистить") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history, key = { it }) { keyword ->
                FilterChip(
                    selected = false,
                    onClick = { onPick(keyword) },
                    label = { Text(keyword, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** The person icon on a card: is this track's artist already followed? */
private fun MusicUiState.isFollowedBy(song: SongInfo): Boolean {
    return ArtistNames.split(song.singers).any { artist -> followedArtists.any { it.equals(artist, ignoreCase = true) } }
}

/** The artists of a multi-artist track: open one, follow one, or jump to the album. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ArtistPickerSheet(
    song: SongInfo,
    followed: List<String>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onFollow: (String) -> Unit,
    onAlbum: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ArtistNames.split(song.singers).forEach { name ->
                val following = followed.any { it.equals(name, ignoreCase = true) }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable { onOpen(name) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtistAvatar(name, 64.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Исполнитель", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (following) {
                        FilledTonalButton(onClick = { onFollow(name) }) { Text("Подписан", maxLines = 1, softWrap = false) }
                    } else {
                        OutlinedButton(onClick = { onFollow(name) }) { Text("Подписаться", maxLines = 1, softWrap = false) }
                    }
                }
            }
            if (!song.album.isNullOrBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onAlbum),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        CoverImage(url = song.coverUrl, modifier = Modifier.fillMaxSize()) {}
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(song.album.orEmpty(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Альбом", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.Search, contentDescription = "Найти альбом", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ArtistHitCard(hit: ArtistPhotos.Hit, following: Boolean, onOpen: () -> Unit, onFollow: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(132.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ArtistAvatar(hit.name, 76.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                hit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.height(40.dp),
            )
            if (following) {
                FilledTonalButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Text("В подписках", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            } else {
                OutlinedButton(onClick = onFollow, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                    Text("Подписаться", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun ArtistRow(name: String, onOpen: () -> Unit, onUnfollow: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtistAvatar(name, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Смотреть треки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onUnfollow) {
                Icon(
                    Icons.Rounded.PersonRemove,
                    contentDescription = "Отписаться",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    name: String,
    following: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUnfollow: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "К авторам")
            }
            ArtistAvatar(name, 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (loading) "Ищу треки…" else "Только полные треки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить")
            }
            IconButton(onClick = onUnfollow) {
                Icon(if (following) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd,
                    contentDescription = if (following) "Отписаться" else "Подписаться")
            }
        }
    }
}

/** Offered instead of silently searching sources the user did not pick. */
@Composable
private fun WidenCard(onWiden: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "В выбранных источниках пусто",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Добавить рекомендованные (${RECOMMENDED_SOURCES.size}) и поискать там?",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FilledTonalButton(onClick = onWiden) { Text("Искать") }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
