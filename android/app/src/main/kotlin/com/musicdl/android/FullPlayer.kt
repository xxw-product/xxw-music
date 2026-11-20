package com.musicdl.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private val PlayerInk = Color(0xFFF4F4F2)
private val PlayerMuted = Color(0xFFB9BDC4)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FullPlayer(
    state: MusicUiState, progressFlow: StateFlow<Float>, startWithLyrics: Boolean, onClose: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onLike: () -> Unit, onDislike: () -> Unit, onSave: () -> Unit,
    onDeleteDownload: () -> Unit, onFollow: () -> Unit, onLoadLyrics: () -> Unit, onShuffle: () -> Unit,
    onRepeat: () -> Unit, onSpeed: () -> Unit, onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit, onAddToQueue: () -> Unit, onOpenQueue: () -> Unit,
    onOpenClips: () -> Unit, onOpenActions: () -> Unit,
) {
    val song = state.player.song ?: return
    // The bar follows its own flow, so a position tick no longer recomposes the whole screen.
    val playbackProgress by progressFlow.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val artwork by produceState<PlayerArtwork?>(null, song.key()) { value = PlayerArtworkRepository.load(context, song) }
    // A locally downloaded cover wins (it works offline); otherwise use the largest artwork the
    // resolver found, then the catalog URL, then artwork embedded in the file.
    val cover = song.coverUrl?.takeIf { it.startsWith("file:") }
        ?: state.player.artworkUrl?.takeIf { it.isNotBlank() }
        ?: song.coverUrl?.takeIf { it.isNotBlank() && it != "NULL" }
        ?: artwork?.cover
    // The previous artwork stays on screen while the next one decodes: swapping in `null` first
    // made the backdrop and the accent colour flash on every track change.
    var bitmap by remember { mutableStateOf(CoverCache.peek(cover)) }
    LaunchedEffect(cover) {
        bitmap = CoverCache.peek(cover) ?: bitmap
        CoverCache.load(cover)?.let { bitmap = it }
    }
    val palette = remember(bitmap) { coverPalette(bitmap) }
    // Material 3 Expressive: a full tonal scheme grown from the cover colour; default keeps the blurred-cover look.
    val expressive = Appearance.playerTheme == PlayerTheme.EXPRESSIVE
    val scheme = remember(palette.seed) { expressiveScheme(palette.seed) }
    val background by animateColorAsState(if (expressive) scheme.surface else palette.background, tween(420), label = "playerBackground")
    val PlayerAccent = if (expressive) scheme.primary else palette.accent
    val motionEnabled = remember(context) {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    var lyricsMode by rememberSaveable { mutableStateOf(startWithLyrics) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var stageWidthPx by remember { mutableStateOf(1f) }
    var settings by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("lyrics_style", 0) }
    var size by rememberSaveable { mutableStateOf(prefs.getFloat("size", 30f)) }
    var opacity by rememberSaveable { mutableStateOf(prefs.getFloat("opacity", 0.5f)) }
    val downloaded = state.downloads.firstOrNull { it.key == song.key() }
    val progress by animateFloatAsState(drag?.times(0.65f) ?: if (lyricsMode) 1f else 0f,
        animationSpec = if (drag != null) tween(40) else spring(dampingRatio = 0.9f, stiffness = 280f), label = "coverToLyrics")
    val coverRotation = rememberCoverRotation(
        collapsed = lyricsMode,
        playing = state.player.isPlaying && drag == null && progress >= 0.999f,
        enabled = motionEnabled,
    )
    LaunchedEffect(lyricsMode, song.key()) { if (lyricsMode) onLoadLyrics() }
    BackHandler(lyricsMode) { lyricsMode = false }

    androidx.compose.material3.MaterialExpressiveTheme(
        colorScheme = if (expressive) scheme else MaterialTheme.colorScheme,
        motionScheme = androidx.compose.material3.MotionScheme.expressive(),
        shapes = MaterialTheme.shapes,
    ) {
    Surface(Modifier.fillMaxSize(), color = background, contentColor = PlayerInk) {
        Box(Modifier.fillMaxSize()) {
            val canvas = state.player.canvas
            var canvasFailed by remember(canvas?.url) { mutableStateOf(false) }
            if (expressive) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to scheme.primaryContainer.copy(alpha = 0.7f), 0.55f to scheme.surfaceContainer, 1f to scheme.surface)))
            } else if (canvas != null && !canvasFailed) {
                Box(Modifier.fillMaxSize()) {
                    ClipVideo(canvas, onError = { canvasFailed = true }, modifier = Modifier.fillMaxSize())
                    // Canvas is decoration: a dark scrim keeps the title and controls readable.
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.35f),
                                0.5f to Color.Black.copy(alpha = 0.45f),
                                1f to Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                    )
                }
            } else {
                PlayerBackdrop(bitmap, palette)
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", Modifier.size(32.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Сейчас звучит", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.queueCount > 0) "Трек ${state.queuePosition.coerceAtLeast(1)} из ${state.queueCount}"
                            else sourceName(song.source),
                            style = MaterialTheme.typography.labelMedium, color = PlayerMuted,
                        )
                    }
                    IconButton(onClick = onOpenQueue) { Icon(Icons.Rounded.QueueMusic, "Очередь") }
                    IconButton(onClick = onOpenActions) { Icon(Icons.Rounded.MoreHoriz, "Действия с треком") }
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp, bottom = 8.dp)) {
                    BoxWithConstraints(
                        Modifier.weight(1f).fillMaxWidth().onSizeChanged { stageWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    drag = ((drag ?: if (lyricsMode) 1f else 0f) - delta / (stageWidthPx * 0.65f)).coerceIn(0f, 1f)
                                },
                                onDragStopped = { velocity ->
                                    lyricsMode = when {
                                        velocity < -600f -> true; velocity > 600f -> false
                                        else -> (drag ?: progress) >= 0.5f
                                    }
                                    drag = null
                                },
                            ),
                    ) {
                        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                        val side = minOf(maxWidth, maxHeight)
                        if (progress > 0.01f) {
                            LyricsCarousel(state, size, opacity,
                                Modifier.fillMaxSize().padding(top = side / 4 + 20.dp).graphicsLayer { alpha = progress },
                                onSeek, PlayerAccent)
                        }
                        Box(
                            Modifier.size(side).align(Alignment.TopCenter).graphicsLayer {
                                val flight = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat()
                                cameraDistance = 18f * density
                                rotationY = if (motionEnabled) -(360f + coverRotation.floatValue) * progress else 0f
                                rotationZ = if (motionEnabled) -10f * flight else 0f
                                translationY = -side.toPx() * 0.375f * progress + if (motionEnabled) -24.dp.toPx() * flight else 0f
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                                scaleX = 1f - 0.75f * progress; scaleY = scaleX
                                translationX = -((widthPx - side.toPx()) / 2f + side.toPx() * 0.375f) * progress
                            }.semantics {
                                contentDescription = "Обложка трека"
                                onClick(label = if (lyricsMode) "Развернуть обложку" else "Показать текст") {
                                    lyricsMode = !lyricsMode
                                    true
                                }
                            }.clip(RoundedCornerShape(if (expressive) 40.dp else 12.dp)).background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CoverImage(cover, Modifier.fillMaxSize()) {
                                Icon(Icons.Rounded.MusicNote, null, Modifier.size(64.dp), tint = PlayerAccent)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                        if (!lyricsMode && drag == null) {
                            Text("Смахните обложку влево — текст песни", color = PlayerMuted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.songName ?: "Без названия", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.singers.orEmpty(), color = PlayerMuted, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDislike) { Icon(Icons.Rounded.ThumbDown, "Реже", Modifier.size(22.dp), tint = PlayerMuted) }
                    IconButton(onClick = onLike) {
                        Icon(
                            if (song.key() in state.likedKeys) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            "Нравится", Modifier.size(28.dp), tint = PlayerAccent,
                        )
                    }
                }
                PlayerSeek(state, playbackProgress, onSeek, PlayerAccent)
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(checked = state.shuffle, onCheckedChange = { onShuffle() }) {
                        Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) PlayerAccent else PlayerMuted)
                    }
                    TransportButton(direction = -1, tint = PlayerAccent, onClick = onPrevious)
                    TransportButton(playing = state.player.isPlaying, buffering = state.player.isBuffering,
                        prominent = true, tint = PlayerAccent, onClick = onPlayPause)
                    TransportButton(direction = 1, tint = PlayerAccent, onClick = onNext)
                    IconToggleButton(checked = state.repeatOne, onCheckedChange = { onRepeat() }) {
                        Icon(if (state.repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор трека",
                            tint = if (state.repeatOne) PlayerAccent else PlayerMuted)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onSpeed, colors = ButtonDefaults.outlinedButtonColors(contentColor = PlayerInk)) {
                        Text("${state.playbackSpeed.toString().removeSuffix(".0")}×")
                    }
                    Row {
                        IconButton(onClick = onOpenClips) { Icon(Icons.Rounded.PlayCircle, "Клипы") }
                        FilledTonalIconToggleButton(checked = lyricsMode, onCheckedChange = { lyricsMode = it },
                            colors = IconButtonDefaults.filledTonalIconToggleButtonColors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                contentColor = PlayerInk, checkedContainerColor = PlayerAccent,
                                checkedContentColor = Color.Black,
                            )) {
                            Icon(Icons.Rounded.FormatQuote, "Текст песни")
                        }
                        IconButton(onClick = onAddToPlaylist) { Icon(Icons.Rounded.PlaylistAdd, "В плейлист") }
                        IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Tune, "Вид текста") }
                    }
                }
            }
        }
    }
    }
    if (settings) {
        AlertDialog(onDismissRequest = { settings = false }, title = { Text("Карусель текста") }, text = {
            Column {
                Text("Размер активной строки · ${size.toInt()}")
                Slider(size, onValueChange = { size = it; prefs.edit().putFloat("size", it).apply() }, valueRange = 22f..40f)
                Text("Прозрачность соседних строк · ${(opacity * 100).toInt()}%")
                Slider(opacity, onValueChange = { opacity = it; prefs.edit().putFloat("opacity", it).apply() }, valueRange = 0.15f..0.8f)
            }
        }, confirmButton = { TextButton(onClick = { settings = false }) { Text("Готово") } })
    }
}

/**
 * The current cover as the player's background: full-bleed, blurred, cropped without distortion and
 * cross-faded between tracks, so switching never flashes a black frame.
 */
@Composable
private fun PlayerBackdrop(bitmap: android.graphics.Bitmap?, palette: CoverPalette) {
    // A ~80 px copy upscaled to the screen is already a soft gradient, and it costs a fraction of
    // a full-screen RenderEffect blur while looking the same under the scrim.
    val soft = remember(bitmap) {
        bitmap?.let { source ->
            val longest = maxOf(source.width, source.height)
            if (longest <= BACKDROP_PIXELS) source
            else {
                val scale = BACKDROP_PIXELS.toFloat() / longest
                android.graphics.Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            }
        }
    }
    Crossfade(targetState = soft, animationSpec = tween(700), label = "playerBackdrop") { image ->
        Box(Modifier.fillMaxSize()) {
            if (image != null) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                        .blurBackdrop(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(palette.background))
            }
            // Readability first: the scrim keeps text and controls legible on any artwork.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.45f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black.copy(alpha = 0.86f),
                    ),
                ),
            )
        }
    }
}

/** No clip for this track: the cover becomes a deep, soft wash instead of a recognisable image. */
private fun Modifier.blurBackdrop(): Modifier =
    if (android.os.Build.VERSION.SDK_INT >= 31) {
        blur(24.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
    } else this

// A 32 px source upscaled to a phone screen is already a gradient; the 24 dp blur removes the last
// shapes. Keeping the bitmap tiny is what makes such a strong blur affordable on every frame.
private const val BACKDROP_PIXELS = 32

@Composable
private fun PlayerSeek(state: MusicUiState, progress: Float, onSeek: (Float) -> Unit, PlayerAccent: Color) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.player.song?.key()) { seeking = null }
    Column {
        VinylSeekBar(value = seeking ?: progress, onChange = { seeking = it },
            onCommit = { seeking?.let(onSeek); seeking = null }, enabled = state.player.durationMs > 0,
            tint = PlayerAccent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val position = seeking?.let { (it * state.player.durationMs).toLong() } ?: state.player.positionMs
            Text(playerTime(position), color = PlayerMuted, style = MaterialTheme.typography.labelMedium)
            Text(playerTime(state.player.durationMs), color = PlayerMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun playerTime(ms: Long): String =
    "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable
private fun LyricsCarousel(state: MusicUiState, size: Float, opacity: Float, modifier: Modifier,
                           onSeek: (Float) -> Unit, PlayerAccent: Color) {
    val lyrics = state.lyrics
    when {
        state.isLyricsLoading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PlayerAccent)
        }
        lyrics == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(state.lyricsError ?: "Текст пока не найден", textAlign = TextAlign.Center, color = PlayerMuted)
        }
        lyrics.hasTimings -> BoxWithConstraints(modifier) {
            val active = lyrics.lines.indexOfLast { it.timeMs <= state.player.positionMs }
            val listState = rememberLazyListState()
            val padding = (maxHeight / 2 - 44.dp).coerceAtLeast(0.dp)
            LaunchedEffect(active, lyrics, maxHeight, size) {
                if (active >= 0) {
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == active }) listState.scrollToItem(active)
                    val layout = listState.layoutInfo
                    layout.visibleItemsInfo.firstOrNull { it.index == active }?.let { row ->
                        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                        listState.animateScrollBy(row.offset + row.size / 2f - center)
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = padding), horizontalAlignment = Alignment.CenterHorizontally) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val distance = abs(index - active)
                    val scale by animateFloatAsState(if (distance == 0) 1f else (0.86f - distance * 0.06f).coerceAtLeast(0.65f), label = "lineScale")
                    val alpha by animateFloatAsState(if (distance == 0) 1f else (opacity / distance.coerceAtLeast(1)).coerceAtLeast(0.08f), label = "lineOpacity")
                    Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(enabled = state.player.durationMs > 0) {
                        onSeek((line.timeMs.toFloat() / state.player.durationMs).coerceIn(0f, 1f))
                    }.padding(vertical = 10.dp).graphicsLayer {
                        scaleX = scale; scaleY = scale; this.alpha = alpha
                    }, contentAlignment = Alignment.Center) {
                        Text(line.text, fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center,
                            fontWeight = if (index == active) FontWeight.Bold else FontWeight.Medium,
                            color = if (index == active) PlayerInk else PlayerMuted)
                    }
                }
            }
        }
        else -> LazyColumn(modifier, contentPadding = PaddingValues(vertical = 24.dp)) {
            item { Text("Без синхронизации", color = PlayerMuted, style = MaterialTheme.typography.labelMedium) }
            item {
                Text(lyrics.plain.orEmpty(), fontSize = size.sp, lineHeight = (size * 1.4f).sp,
                    color = PlayerInk, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
            }
        }
    }
}
