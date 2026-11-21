package com.musicdl.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.derivedStateOf
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

internal val PlayerInk = Color(0xFFF4F4F2)
internal val PlayerMuted = Color(0xFFB9BDC4)

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FullPlayer(
    state: MusicUiState, progressFlow: StateFlow<Float>, startWithLyrics: Boolean, onClose: () -> Unit,
    onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Float) -> Unit, onLike: () -> Unit, onDislike: () -> Unit, onSave: () -> Unit,
    onDeleteDownload: () -> Unit, onFollow: () -> Unit, onLoadLyrics: () -> Unit, onShuffle: () -> Unit,
    onRepeat: () -> Unit, onSpeed: () -> Unit, onAddToPlaylist: () -> Unit,
    onPlayNext: () -> Unit, onAddToQueue: () -> Unit, onOpenQueue: () -> Unit,
    onOpenClips: () -> Unit, onOpenActions: () -> Unit, onOpenArtist: () -> Unit = {},
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
    val background by animateColorAsState(palette.background, tween(420), label = "playerBackground")
    val theme = Appearance.playerTheme
    val branded = theme.branded
    val PlayerAccent = when {
        expressive -> scheme.primary
        theme == PlayerTheme.YANDEX || theme == PlayerTheme.SPOTIFY -> Appearance.accentColor
        theme == PlayerTheme.APPLE -> Color.White
        else -> palette.accent
    }
    val surfaceColor = when (theme) {
        PlayerTheme.YANDEX -> YandexBg
        PlayerTheme.SPOTIFY -> SpotifyBg
        PlayerTheme.APPLE -> Color.Black
        else -> background
    }
    val coverRadius = when (theme) {
        PlayerTheme.EXPRESSIVE -> 40.dp
        PlayerTheme.YANDEX -> 20.dp
        PlayerTheme.SPOTIFY -> 6.dp
        else -> 12.dp
    }
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
    LaunchedEffect(lyricsMode, song.key(), theme) { if (lyricsMode || theme == PlayerTheme.SPOTIFY) onLoadLyrics() }
    BackHandler(lyricsMode) { lyricsMode = false }

    val hooks = PlayerHooks(
        state = state, progress = playbackProgress, title = song.songName ?: "Без названия", artist = song.singers.orEmpty(),
        subtitle = if (state.queueCount > 0) "Трек ${state.queuePosition.coerceAtLeast(1)} из ${state.queueCount}" else sourceName(song.source),
        songKey = song.key(), liked = song.key() in state.likedKeys, lyricsMode = lyricsMode, seed = palette.seed, cover = bitmap, accent = Appearance.accentColor,
        onClose = onClose, onPlayPause = onPlayPause, onNext = onNext, onPrevious = onPrevious, onSeek = onSeek,
        onLike = onLike, onDislike = onDislike, onShuffle = onShuffle, onRepeat = onRepeat, onSpeed = onSpeed,
        onAddToPlaylist = onAddToPlaylist, onOpenQueue = onOpenQueue, onOpenActions = onOpenActions,
        onToggleLyrics = { lyricsMode = !lyricsMode }, onArtist = onOpenArtist,
    )
    androidx.compose.material3.MaterialExpressiveTheme(
        colorScheme = if (expressive) scheme else MaterialTheme.colorScheme,
        motionScheme = androidx.compose.material3.MotionScheme.expressive(),
        shapes = MaterialTheme.shapes,
    ) {
    val styledLyrics = lyricsMode && (theme == PlayerTheme.SPOTIFY || theme == PlayerTheme.YANDEX)
    val vivid by animateColorAsState(vividBackground(palette.seed), tween(420), label = "vividLyrics")
    val lyricsStyle = when {
        !styledLyrics -> LyricsStyle.DEFAULT
        theme == PlayerTheme.SPOTIFY -> LyricsStyle.SPOTIFY
        else -> LyricsStyle.YANDEX
    }
    Surface(Modifier.fillMaxSize(), color = if (styledLyrics) vivid else surfaceColor, contentColor = PlayerInk) {
        Box(Modifier.fillMaxSize()) {
            val canvas = state.player.canvas
            var canvasFailed by remember(canvas?.url) { mutableStateOf(false) }
            if (canvas != null && !canvasFailed && !branded) {
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
            } else when (theme) {
                PlayerTheme.YANDEX -> Unit
                PlayerTheme.SPOTIFY -> if (!lyricsMode) SpotifyBackdrop(palette.seed)
                PlayerTheme.APPLE -> AppleBackdrop(bitmap, progress) { PlayerBackdrop(bitmap, palette, scrim = 1f - 0.45f * progress.coerceIn(0f, 1f)) }
                else -> PlayerBackdrop(bitmap, palette)
            }
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp)) {
                if (branded) ThemedHeader(theme, hooks) else Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
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
                                Modifier.fillMaxSize().padding(top = when {
                                    theme == PlayerTheme.APPLE || lyricsStyle == LyricsStyle.SPOTIFY -> 8.dp
                                    lyricsStyle == LyricsStyle.YANDEX -> 52.dp
                                    else -> side / 4 + 20.dp
                                }).graphicsLayer { alpha = progress },
                                onSeek, PlayerAccent, lyricsStyle, lerp(palette.seed, Color.White, 0.65f))
                        }
                        if (lyricsStyle == LyricsStyle.YANDEX) YandexCollapseButton(hooks.onToggleLyrics, Modifier.align(Alignment.TopEnd))
                        if (theme != PlayerTheme.APPLE && !(styledLyrics && progress > 0.95f)) Box(
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
                            }.clip(RoundedCornerShape(coverRadius)).background(Color.Black.copy(alpha = 0.35f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            CoverImage(cover, Modifier.fillMaxSize()) {
                                Icon(Icons.Rounded.MusicNote, null, Modifier.size(64.dp), tint = PlayerAccent)
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(if (theme == PlayerTheme.SPOTIFY) 64.dp else 28.dp), contentAlignment = Alignment.Center) {
                        if (theme == PlayerTheme.SPOTIFY) {
                            if (!lyricsMode) SpotifyLyricLine(state, Modifier.fillMaxSize())
                        } else if (!lyricsMode && drag == null && theme != PlayerTheme.APPLE) {
                            Text("Смахните обложку влево — текст песни", color = PlayerMuted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (branded) ThemedBody(theme, hooks) else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.songName ?: "Без названия",
                            style = if (expressive) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song.singers.orEmpty(), color = PlayerMuted, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onOpenArtist))
                    }
                    if (!expressive) IconButton(onClick = onDislike) { Icon(Icons.Rounded.ThumbDown, "Реже", Modifier.size(22.dp), tint = PlayerMuted) }
                    IconButton(onClick = onLike) {
                        Icon(
                            if (song.key() in state.likedKeys) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            "Нравится", Modifier.size(if (expressive) 32.dp else 28.dp), tint = PlayerAccent,
                        )
                    }
                }
                PlayerSeek(state, playbackProgress, onSeek, PlayerAccent, expressive)
                Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconToggleButton(checked = state.shuffle, onCheckedChange = { onShuffle() }) {
                        Icon(Icons.Rounded.Shuffle, "Перемешать", tint = if (state.shuffle) PlayerAccent else PlayerMuted)
                    }
                    if (expressive) {
                        ExpressiveKey(Icons.Rounded.SkipPrevious, "Предыдущий", scheme.primaryContainer, scheme.onPrimaryContainer,
                            Modifier.size(64.dp), CircleShape, onPrevious)
                        ExpressiveKey(
                            if (state.player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.player.isPlaying) "Пауза" else "Воспроизвести", scheme.primary, scheme.onPrimary,
                            Modifier.size(width = 128.dp, height = 92.dp), RoundedCornerShape(36.dp), onPlayPause,
                            buffering = state.player.isBuffering, iconSize = 38.dp,
                        )
                        ExpressiveKey(Icons.Rounded.SkipNext, "Следующий", scheme.primaryContainer, scheme.onPrimaryContainer,
                            Modifier.size(64.dp), CircleShape, onNext)
                    } else {
                        TransportButton(direction = -1, tint = PlayerAccent, onClick = onPrevious)
                        TransportButton(playing = state.player.isPlaying, buffering = state.player.isBuffering,
                            prominent = true, tint = PlayerAccent, onClick = onPlayPause)
                        TransportButton(direction = 1, tint = PlayerAccent, onClick = onNext)
                    }
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

/** Material 3 Expressive seek bar: a thick rounded track split by a vertical handle. */
@Composable
private fun ExpressiveSeekBar(value: Float, onChange: (Float) -> Unit, onCommit: (Float) -> Unit, enabled: Boolean, accent: Color) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown()
                var fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                onChange(fraction)
                drag(down.id) { change ->
                    fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onChange(fraction)
                    change.consume()
                }
                onCommit(fraction)
            }
        }.semantics { contentDescription = "Перемотка" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val trackHeight = 28.dp.toPx()
            val top = (size.height - trackHeight) / 2f
            val x = size.width * value.coerceIn(0f, 1f)
            val gap = 6.dp.toPx()
            val radius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f)
            val activeEnd = (x - gap).coerceAtLeast(0f)
            if (activeEnd > 0f) drawRoundRect(accent, androidx.compose.ui.geometry.Offset(0f, top),
                androidx.compose.ui.geometry.Size(activeEnd.coerceAtLeast(trackHeight / 2f), trackHeight), radius)
            val inactiveStart = (x + gap).coerceAtMost(size.width)
            if (inactiveStart < size.width) drawRoundRect(Color.White.copy(alpha = 0.18f),
                androidx.compose.ui.geometry.Offset(inactiveStart, top),
                androidx.compose.ui.geometry.Size(size.width - inactiveStart, trackHeight), radius)
            val handleWidth = 5.dp.toPx()
            drawRoundRect(accent, androidx.compose.ui.geometry.Offset((x - handleWidth / 2f).coerceIn(0f, size.width - handleWidth), 0f),
                androidx.compose.ui.geometry.Size(handleWidth, size.height), androidx.compose.ui.geometry.CornerRadius(handleWidth / 2f))
        }
    }
}

/** Filled tonal key of the expressive player: circles on the sides, a large rounded square in the middle. */
@Composable
private fun ExpressiveKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, container: Color, content: Color,
    modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit,
    buffering: Boolean = false, iconSize: androidx.compose.ui.unit.Dp = 28.dp,
) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "keyPress")
    Surface(
        onClick = onClick, interactionSource = interactions, shape = shape, color = container, contentColor = content,
        modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.semantics { this.contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (buffering) CircularProgressIndicator(Modifier.size(iconSize), color = content, strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.size(iconSize))
        }
    }
}

/**
 * The current cover as the player's background: full-bleed, blurred, cropped without distortion and
 * cross-faded between tracks, so switching never flashes a black frame.
 */
@Composable
private fun PlayerBackdrop(bitmap: android.graphics.Bitmap?, palette: CoverPalette, scrim: Float = 1f) {
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
                        0f to Color.Black.copy(alpha = 0.42f * scrim),
                        0.45f to Color.Black.copy(alpha = 0.55f * scrim),
                        1f to Color.Black.copy(alpha = 0.86f * scrim),
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
private fun PlayerSeek(state: MusicUiState, progress: Float, onSeek: (Float) -> Unit, PlayerAccent: Color, expressive: Boolean = false) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.player.song?.key()) { seeking = null }
    Column {
        if (expressive) ExpressiveSeekBar(value = seeking ?: progress, onChange = { seeking = it },
            onCommit = { value -> onSeek(value); seeking = null }, enabled = state.player.durationMs > 0,
            accent = PlayerAccent)
        else VinylSeekBar(value = seeking ?: progress, onChange = { seeking = it },
            onCommit = { seeking?.let(onSeek); seeking = null }, enabled = state.player.durationMs > 0,
            tint = PlayerAccent)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val position = seeking?.let { (it * state.player.durationMs).toLong() } ?: state.player.positionMs
            Text(playerTime(position), color = PlayerMuted, style = MaterialTheme.typography.labelMedium)
            Text(playerTime(state.player.durationMs), color = PlayerMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun playerTime(ms: Long): String =
    "${ms.coerceAtLeast(0) / 60000}:${(ms.coerceAtLeast(0) / 1000 % 60).toString().padStart(2, '0')}"

@Composable
private fun LyricsCarousel(state: MusicUiState, size: Float, opacity: Float, modifier: Modifier,
                           onSeek: (Float) -> Unit, PlayerAccent: Color, style: LyricsStyle = LyricsStyle.DEFAULT, tint: Color = Color.White) {
    val lyrics = state.lyrics
    when {
        state.isLyricsLoading -> Box(modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = PlayerAccent)
        }
        lyrics == null -> Box(modifier, contentAlignment = Alignment.Center) {
            Text(state.lyricsError ?: "Текст пока не найден", textAlign = TextAlign.Center, color = PlayerMuted)
        }
        lyrics.hasTimings -> BoxWithConstraints(modifier) {
            val styled = style != LyricsStyle.DEFAULT
            val animated = Appearance.lyricsAnimated || styled
            // A per-frame clock keeps the fill and the line changes smooth between the player's sparse position ticks.
            val clock = rememberLyricsClock(state.player.positionMs, state.player.isPlaying, state.playbackSpeed, animated)
            val lines = lyrics.lines
            val activeState = remember(lines) { derivedStateOf { lines.indexOfLast { it.timeMs <= clock.value } } }
            val active = activeState.value
            val listState = rememberLazyListState()
            // Animated mode keeps the active line near the top, like the reference; plain mode centres it.
            val anchor = when { style == LyricsStyle.YANDEX -> 0.4f; animated -> 0.28f; else -> 0.5f }
            val topPadding = if (animated) (maxHeight * anchor - 30.dp).coerceAtLeast(0.dp) else (maxHeight / 2 - 44.dp).coerceAtLeast(0.dp)
            val bottomPadding = if (animated) maxHeight * 0.6f else topPadding
            LaunchedEffect(active, lyrics, maxHeight, size, animated) {
                if (active >= 0) {
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == active }) listState.scrollToItem(active)
                    val layout = listState.layoutInfo
                    layout.visibleItemsInfo.firstOrNull { it.index == active }?.let { row ->
                        val target = layout.viewportStartOffset + (layout.viewportEndOffset - layout.viewportStartOffset) * anchor
                        val delta = row.offset + row.size / 2f - target
                        if (animated) listState.animateScrollBy(delta, tween(600)) else listState.scrollBy(delta)
                    }
                }
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                horizontalAlignment = if (animated && style != LyricsStyle.YANDEX) Alignment.Start else Alignment.CenterHorizontally) {
                itemsIndexed(lines) { index, line ->
                    val distance = abs(index - active)
                    val onClickLine = { onSeek((line.timeMs.toFloat() / state.player.durationMs).coerceIn(0f, 1f)) }
                    if (styled) {
                        val spotify = style == LyricsStyle.SPOTIFY
                        val color = animateColorAsState(if (spotify && distance != 0) tint else Color.White, tween(300), label = "lyricColor").value
                        val alpha = animateFloatAsState(if (spotify || distance == 0) 1f else (0.55f - distance * 0.1f).coerceAtLeast(0.2f), tween(300), label = "lyricAlpha")
                        Text(line.text, fontSize = (size * if (spotify) 1.15f else 1.3f).sp, lineHeight = (size * if (spotify) 1.35f else 1.45f).sp,
                            fontWeight = if (spotify) FontWeight.ExtraBold else FontWeight.Black, letterSpacing = (-0.5).sp,
                            textAlign = if (spotify) TextAlign.Start else TextAlign.Center, color = color,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = state.player.durationMs > 0, onClick = onClickLine)
                                .padding(vertical = if (spotify) 8.dp else 10.dp).graphicsLayer { this.alpha = alpha.value })
                    } else if (animated) {
                        // The animated values are read inside graphicsLayer, so a tween frame only redraws.
                        val scale = animateFloatAsState(if (distance == 0) 1f else 0.94f, tween(380), label = "lineScale")
                        val alpha = animateFloatAsState(if (distance == 0) 1f else (0.5f - distance * 0.06f).coerceAtLeast(0.18f), tween(380), label = "lineOpacity")
                        val blurDp = animateFloatAsState(if (distance == 0 || active < 0) 0f else (distance * 1.6f).coerceAtMost(6f), tween(380), label = "lineBlur")
                        val next = lines.getOrNull(index + 1)?.timeMs ?: state.player.durationMs
                        val lineDuration = minOf(next - line.timeMs, maxOf(1500L, line.text.length * 90L)).coerceAtLeast(500L)
                        Box(Modifier.fillMaxWidth().clickable(enabled = state.player.durationMs > 0, onClick = onClickLine)
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .graphicsLayer {
                                scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value
                                transformOrigin = TransformOrigin(0f, 0.5f)
                                if (android.os.Build.VERSION.SDK_INT >= 31) {
                                    val radius = blurDp.value.dp.toPx()
                                    renderEffect = if (radius > 0.5f) androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal) else null
                                }
                            },
                        ) {
                            KaraokeLine(line.text, (size * 0.92f).sp, (size * 1.2f).sp) {
                                if (distance == 0) ((clock.value - line.timeMs).toFloat() / lineDuration).coerceIn(0f, 1f) else 0f
                            }
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(enabled = state.player.durationMs > 0, onClick = onClickLine)
                            .padding(vertical = 10.dp).graphicsLayer {
                                this.alpha = if (distance == 0) 1f else (opacity / distance.coerceAtLeast(1)).coerceAtLeast(0.08f)
                            }, contentAlignment = Alignment.Center) {
                            Text(line.text, fontSize = size.sp, lineHeight = (size * 1.25f).sp, textAlign = TextAlign.Center,
                                fontWeight = if (index == active) FontWeight.Bold else FontWeight.Medium,
                                color = if (index == active) PlayerInk else PlayerMuted)
                        }
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

internal enum class LyricsStyle { DEFAULT, SPOTIFY, YANDEX }

/** Playback position extrapolated every frame while playing; re-synced whenever the real position drifts or jumps. */
@Composable
private fun rememberLyricsClock(positionMs: Long, playing: Boolean, speed: Float, smooth: Boolean): androidx.compose.runtime.State<Long> {
    if (!smooth) return androidx.compose.runtime.rememberUpdatedState(positionMs)
    val clock = remember { androidx.compose.runtime.mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing, speed) {
        if (!playing || abs(positionMs - clock.longValue) > 400) clock.longValue = positionMs
        if (playing) {
            val startPos = clock.longValue
            val startNanos = androidx.compose.runtime.withFrameNanos { it }
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                clock.longValue = startPos + ((now - startNanos) / 1_000_000f * speed).toLong()
            }
        }
    }
    return clock
}

/**
 * One lyric line: dim text with a bright copy revealed left to right. The reveal is computed while drawing,
 * so a frame of the sweep never recomposes anything.
 */
@Composable
private fun KaraokeLine(text: String, fontSize: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit, fill: () -> Float) {
    var layout by remember(text) { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        text, fontSize = fontSize, lineHeight = lineHeight, fontWeight = FontWeight.Bold, color = PlayerInk.copy(alpha = 0.55f),
        onTextLayout = { layout = it },
        modifier = Modifier.drawWithContent {
            drawContent()
            val l = layout ?: return@drawWithContent
            val position = fill() * text.length
            if (position <= 0f) return@drawWithContent
            for (i in 0 until l.lineCount) {
                val start = l.getLineStart(i)
                val end = l.getLineEnd(i)
                if (position <= start) break
                val x = if (position >= end) size.width
                else l.getLineLeft(i) + (l.getLineRight(i) - l.getLineLeft(i)) * ((position - start) / (end - start).coerceAtLeast(1))
                clipRect(0f, l.getLineTop(i), x, l.getLineBottom(i)) {
                    drawText(l, color = PlayerInk)
                }
            }
        },
    )
}
