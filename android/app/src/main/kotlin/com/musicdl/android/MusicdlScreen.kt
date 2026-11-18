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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.graphics.graphicsLayer
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
    val snackbarHost = remember { SnackbarHostState() }
    // One lookup table instead of a linear scan of the download index for every visible card.
    val downloadsByKey = remember(state.downloads) { state.downloads.associateBy { it.key } }
    // The same recording arrives with different provider keys after a re-search or a wave refresh;
    // a plain key lookup made the sheet offer "Скачать" for a track that was already offline.
    fun downloadedEntry(song: SongInfo): DownloadEntry? = downloadsByKey[song.key()]
        ?: state.downloads.firstOrNull { sameRecording(it.title, it.artist, song.songName, song.singers) }
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
            )
        }
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
                        if (state.tab == FeedTab.SEARCH) IconButton(onClick = { showSources = true }) {
                            Icon(Icons.Rounded.Tune, "Источники поиска")
                        }
                    }
                    if (state.tab == FeedTab.SEARCH) {
                        SearchBar(state, viewModel)
                        Spacer(Modifier.height(12.dp))
                    }
                }
            },
            bottomBar = {
                Column {
                    AnimatedVisibility(state.player.song != null) {
                        MiniPlayer(state, progressFlow = viewModel.playerProgress,
                            onOpen = { fullPlayerLyrics = false; showFullPlayer = true },
                            onPlayPause = viewModel::togglePlayPause, onNext = viewModel::playNext,
                            onQueue = { showQueue = true })
                    }
                    AppNavigation(state.tab, viewModel::setTab)
                }
            },
        ) { padding ->
            val scrollStates = FeedTab.entries.associateWith { tab -> key(tab) { rememberLazyListState() } }
            LazyColumn(
                state = scrollStates.getValue(state.tab),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.tab == FeedTab.WAVE) {
                    item {
                        WaveCard(
                            state = state,
                            showWeights = showWeights,
                            onToggleWeights = { showWeights = !showWeights },
                            onStart = viewModel::startWave,
                            onWeight = viewModel::setGenreWeight,
                        )
                    }
                }
                if (state.tab == FeedTab.WAVE) {
                    if (state.isWaveLoading) {
                        item { SearchingCard(state) }
                    } else if (state.wave.isEmpty()) {
                        item { NoticeCard(state.error ?: "Начните слушать свою волну.") }
                    } else {
                        item { SectionHeader("Моя волна", state.wave.size) }
                        items(state.wave, key = { "wave:${it.key()}" }) { song ->
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
                            ArtistHeader(
                                name = artist,
                                following = state.followedArtists.any { it.equals(artist, ignoreCase = true) },
                                loading = state.isArtistLoading,
                                onBack = viewModel::closeArtist,
                                onRefresh = { viewModel.openArtist(artist) },
                                onUnfollow = { viewModel.toggleFollow(SongInfo(singers = artist)) },
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

                    if (state.history.isNotEmpty() && state.results.isEmpty() && !state.isSearching) {
                        item {
                            HistoryRow(
                                history = state.history,
                                onPick = viewModel::searchKeyword,
                                onClear = viewModel::clearHistory,
                            )
                        }
                    }

                    if (state.results.isNotEmpty()) {
                        item { SectionHeader("Треки", state.results.size) }
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
        }
    }
}

@Composable
private fun SearchBar(state: MusicUiState, viewModel: SearchViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val artistOnly = state.searchArtistOnly
    val submit = { keyboard?.hide(); if (artistOnly) viewModel.findArtist() else viewModel.search() }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !artistOnly, onClick = { viewModel.setSearchArtistOnly(false) }, label = { Text("Треки") })
            FilterChip(selected = artistOnly, onClick = { viewModel.setSearchArtistOnly(true) }, label = { Text("По исполнителю") })
        }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                placeholder = { Text(if (artistOnly) "Имя исполнителя" else "Название трека") },
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
                shape = MaterialTheme.shapes.small,
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
                        Text("Все ${MusicClientRegistry.registeredModules.size}")
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
                    items(MusicClientRegistry.registeredModules.sortedBy { sourceName(it) }, key = { it }) { source ->
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
    state: MusicUiState,
    showWeights: Boolean,
    onToggleWeights: () -> Unit,
    onStart: () -> Unit,
    onWeight: (String, Float) -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
            .background(Vinyl).border(1.dp, Color(0xFF303238), MaterialTheme.shapes.extraLarge),
    ) {
        VinylAtmosphere(Modifier.matchParentSize())
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            Spacer(Modifier.height(40.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.isWaveLoading) CircularProgressIndicator(Modifier.size(48.dp).padding(10.dp), strokeWidth = 1.5.dp, color = Silver)
                else TransportButton(prominent = true, compact = true, onClick = onStart)
                Column(Modifier.weight(1f)) {
                    Text(if (state.isWaveLoading) "Собираем вашу волну" else "Включить волну", color = Silver,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Знакомое и ещё не открытое", color = Silver.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onToggleWeights) { Text(if (showWeights) "Скрыть" else "Жанры", color = Silver) }
            }

            AnimatedVisibility(visible = showWeights) {
                GenreWeights(state.coefficients, onWeight)
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
                       onPlayPause: () -> Unit, onNext: () -> Unit, onQueue: () -> Unit) {
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
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()
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
        Column {
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
    Surface(
        shape = MaterialTheme.shapes.large,
        color = container,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                            ),
                        ),
                    ),
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
            Spacer(Modifier.width(12.dp))
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
                Text(
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
    val artist = song.singers?.trim().orEmpty()
    return artist.isNotEmpty() && followedArtists.any { it.equals(artist, ignoreCase = true) }
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
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.trim().take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
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
