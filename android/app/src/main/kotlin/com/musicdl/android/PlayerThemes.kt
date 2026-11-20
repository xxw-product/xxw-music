package com.musicdl.android

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt

// Brand-inspired palettes only: no logos, fonts or artwork from the services themselves.
internal val YandexBg = Color(0xFF2B2A2D)
internal val SpotifyBg = Color(0xFF121212)

/** Everything the themed player layouts need, so they stay out of the already huge FullPlayer. */
internal class PlayerHooks(
    val state: MusicUiState,
    val progress: Float,
    val title: String,
    val artist: String,
    val subtitle: String,
    val songKey: String,
    val liked: Boolean,
    val lyricsMode: Boolean,
    val seed: Color,
    val cover: android.graphics.Bitmap?,
    val accent: Color,
    val onClose: () -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onLike: () -> Unit,
    val onDislike: () -> Unit,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
    val onSpeed: () -> Unit,
    val onAddToPlaylist: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenActions: () -> Unit,
    val onToggleLyrics: () -> Unit,
) {
    val onAccent: Color get() = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val playing get() = state.player.isPlaying
    val speedLabel get() = "${state.playbackSpeed.toString().removeSuffix(".0")}×"
}

// ---------- backdrops ----------

/** Spotify: the cover colour darkened at the top, fading into the near-black app surface. */
@Composable
internal fun SpotifyBackdrop(seed: Color) {
    val top = androidx.compose.animation.animateColorAsState(
        lerp(seed, Color.Black, 0.62f), androidx.compose.animation.core.tween(600), label = "spotifyTop",
    ).value
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.75f to SpotifyBg, 1f to SpotifyBg)))
}

/** Apple Music: the sharp cover fills the top of the screen and dissolves into a soft blurred wash. */
@Composable
internal fun AppleBackdrop(bitmap: android.graphics.Bitmap?, lyricsProgress: Float, wash: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        wash()
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.66f).align(Alignment.TopCenter)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = 1f - lyricsProgress.coerceIn(0f, 1f) }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            )
        }
            }
}

// ---------- header ----------

@Composable
internal fun ThemedHeader(theme: PlayerTheme, h: PlayerHooks) {
    when (theme) {
        PlayerTheme.YANDEX -> Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = h.onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", Modifier.size(32.dp)) }
            Text(h.subtitle, Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = h.onSpeed) { Text(h.speedLabel, color = PlayerInk) }
            IconButton(onClick = h.onOpenQueue) { Icon(Icons.Rounded.QueueMusic, "Очередь") }
        }
        PlayerTheme.SPOTIFY -> Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = h.onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть плеер", Modifier.size(32.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Сейчас звучит", style = MaterialTheme.typography.labelSmall, color = PlayerInk.copy(alpha = 0.85f))
                Text(h.subtitle, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = h.onOpenActions) { Icon(Icons.Rounded.MoreVert, "Действия с треком") }
        }
        else -> if (h.lyricsMode) AppleLyricsHeader(h) else Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.width(44.dp).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.55f))
                    .clickable(onClick = h.onClose).semantics { contentDescription = "Свернуть плеер" },
            )
        }
    }
}

// ---------- seek bars ----------

/** Thin seek/volume bar: rounded track, optional round handle. */
@Composable
internal fun ThinSeek(
    value: Float, onChange: (Float) -> Unit, onCommit: (Float) -> Unit, enabled: Boolean,
    active: Color, inactive: Color, track: Dp, handle: Dp?, modifier: Modifier = Modifier, description: String = "Перемотка",
) {
    Box(
        modifier.fillMaxWidth().height(32.dp).pointerInput(enabled) {
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
        }.semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = track.toPx()
            val top = (size.height - h) / 2f
            val radius = CornerRadius(h / 2f)
            val r = handle?.toPx()?.div(2f) ?: 0f
            val x = (size.width * value.coerceIn(0f, 1f)).coerceIn(r, (size.width - r).coerceAtLeast(r))
            drawRoundRect(inactive, Offset(0f, top), Size(size.width, h), radius)
            drawRoundRect(active, Offset(0f, top), Size(x.coerceAtLeast(h), h), radius)
            if (handle != null) drawCircle(active, r, Offset(x, size.height / 2f))
        }
    }
}

@Composable
internal fun ThemedSeek(theme: PlayerTheme, h: PlayerHooks) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(h.songKey) { seeking = null }
    val duration = h.state.player.durationMs
    val position = seeking?.let { (it * duration).toLong() } ?: h.state.player.positionMs
    val apple = theme == PlayerTheme.APPLE
    Column {
        ThinSeek(
            value = seeking ?: h.progress, onChange = { seeking = it }, onCommit = { v -> h.onSeek(v); seeking = null },
            enabled = duration > 0,
            active = if (apple) Color.White.copy(alpha = 0.9f) else Color.White,
            inactive = Color.White.copy(alpha = if (apple) 0.28f else 0.3f),
            track = if (apple) 7.dp else 4.dp,
            handle = if (apple) null else if (theme == PlayerTheme.YANDEX) 18.dp else 12.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = if (theme == PlayerTheme.YANDEX) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall
            val muted = if (theme == PlayerTheme.YANDEX) Color(0xFF8E8D90) else PlayerInk.copy(alpha = 0.65f)
            Text(playerTime(position), color = muted, style = style)
            Text(if (apple) "−" + playerTime(duration - position) else playerTime(duration), color = muted, style = style)
        }
    }
}

// ---------- body: title row, seek, controls, footer ----------

@Composable
internal fun ThemedBody(theme: PlayerTheme, h: PlayerHooks) {
    when (theme) {
        PlayerTheme.YANDEX -> YandexBody(h)
        PlayerTheme.SPOTIFY -> SpotifyBody(h)
        else -> AppleBody(h)
    }
}

@Composable
private fun PlayGlyph(h: PlayerHooks, tint: Color, size: Dp) {
    if (h.state.player.isBuffering) CircularProgressIndicator(Modifier.size(size * 0.7f), color = tint, strokeWidth = 3.dp)
    else Icon(if (h.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (h.playing) "Пауза" else "Воспроизвести",
        Modifier.size(size), tint = tint)
}

@Composable
private fun Glyph(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 26.dp, tint: Color = PlayerInk, box: Dp = 48.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(box)) { Icon(icon, description, Modifier.size(size), tint = tint) }
}

// Yandex: flat graphite, yellow round play key, rounded pills for repeat / lyrics / shuffle.
@Composable
private fun YandexBody(h: PlayerHooks) {
    val muted = Color(0xFF8E8D90)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.artist, color = muted, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(Icons.Rounded.PlaylistAdd, "В плейлист", h.onAddToPlaylist)
        Glyph(Icons.Rounded.MoreVert, "Действия с треком", h.onOpenActions)
    }
    ThemedSeek(PlayerTheme.YANDEX, h)
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.Rounded.ThumbDown, "Реже", h.onDislike, tint = muted)
        Glyph(Icons.Rounded.SkipPrevious, "Предыдущий", h.onPrevious, size = 40.dp, box = 56.dp)
        Surface(onClick = h.onPlayPause, shape = CircleShape, color = h.accent, contentColor = h.onAccent,
            modifier = Modifier.size(90.dp).semantics { contentDescription = if (h.playing) "Пауза" else "Воспроизвести" }) {
            Box(contentAlignment = Alignment.Center) { PlayGlyph(h, h.onAccent, 44.dp) }
        }
        Glyph(Icons.Rounded.SkipNext, "Следующий", h.onNext, size = 40.dp, box = 56.dp)
        Glyph(if (h.liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Нравится", h.onLike)
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Pill(if (h.state.repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор трека", h.state.repeatOne, h.onRepeat)
        Pill(Icons.Rounded.FormatQuote, "Текст песни", h.lyricsMode, h.onToggleLyrics)
        Pill(Icons.Rounded.Shuffle, "Перемешать", h.state.shuffle, h.onShuffle)
    }
}

@Composable
private fun Pill(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.1f),
        border = if (active) BorderStroke(2.dp, Color.White) else null,
        modifier = Modifier.width(100.dp).height(56.dp).semantics { contentDescription = description },
    ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(26.dp), tint = if (active) Color.White else Color.White.copy(alpha = 0.7f)) } }
}

// Spotify: gradient from the cover colour, white play circle, green active states, cross + plus on the title row.
@Composable
private fun SpotifyBody(h: PlayerHooks) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.artist, color = PlayerInk.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(Icons.Rounded.Close, "Реже", h.onDislike, size = 28.dp)
        Glyph(if (h.liked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Нравится", h.onLike, size = 30.dp,
            tint = if (h.liked) h.accent else PlayerInk)
    }
    ThemedSeek(PlayerTheme.SPOTIFY, h)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.Rounded.Shuffle, "Перемешать", h.onShuffle, tint = if (h.state.shuffle) h.accent else PlayerInk.copy(alpha = 0.7f))
        Glyph(Icons.Rounded.SkipPrevious, "Предыдущий", h.onPrevious, size = 42.dp, box = 56.dp)
        Surface(onClick = h.onPlayPause, shape = CircleShape, color = Color.White, contentColor = Color.Black,
            modifier = Modifier.size(70.dp).semantics { contentDescription = if (h.playing) "Пауза" else "Воспроизвести" }) {
            Box(contentAlignment = Alignment.Center) { PlayGlyph(h, Color.Black, 36.dp) }
        }
        Glyph(Icons.Rounded.SkipNext, "Следующий", h.onNext, size = 42.dp, box = 56.dp)
        Glyph(if (h.state.repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор трека", h.onRepeat,
            tint = if (h.state.repeatOne) h.accent else PlayerInk.copy(alpha = 0.7f))
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = h.onSpeed) { Text(h.speedLabel, color = PlayerInk) }
        Row {
            Glyph(Icons.Rounded.FormatQuote, "Текст песни", h.onToggleLyrics, size = 24.dp, tint = if (h.lyricsMode) h.accent else PlayerInk)
            Glyph(Icons.Rounded.PlaylistAdd, "В плейлист", h.onAddToPlaylist, size = 24.dp)
            Glyph(Icons.Rounded.QueueMusic, "Очередь", h.onOpenQueue, size = 24.dp)
        }
    }
}

/** The current synced lyric line under the cover (Spotify shows it in the player). */
@Composable
internal fun SpotifyLyricLine(state: MusicUiState, modifier: Modifier = Modifier) {
    val lyrics = state.lyrics
    val line = if (lyrics?.hasTimings == true) lyrics.lines.lastOrNull { it.timeMs <= state.player.positionMs }?.text else null
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        if (!line.isNullOrBlank()) Text(line, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium)
    }
}

// Apple Music: bare transport glyphs, star, volume slider, quiet bottom row.
@Composable
private fun AppleBody(h: PlayerHooks) {
    if (h.lyricsMode) { AppleLyricsBar(h); return }
    val tint = lerp(h.seed, Color.White, 0.6f)
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(h.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.artist, fontSize = 20.sp, color = tint.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Glyph(if (h.liked) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Нравится", h.onLike, size = 28.dp)
        Surface(onClick = h.onOpenActions, shape = CircleShape, color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.padding(start = 4.dp).size(30.dp).semantics { contentDescription = "Действия с треком" }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(20.dp)) }
        }
    }
    ThemedSeek(PlayerTheme.APPLE, h)
    Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.Rounded.FastRewind, "Предыдущий", h.onPrevious, size = 54.dp, box = 72.dp)
        IconButton(onClick = h.onPlayPause, modifier = Modifier.size(84.dp)) { PlayGlyph(h, Color.White, 68.dp) }
        Glyph(Icons.Rounded.FastForward, "Следующий", h.onNext, size = 54.dp, box = 72.dp)
    }
    AppleVolume()
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.Rounded.FormatQuote, "Текст песни", h.onToggleLyrics, tint = if (h.lyricsMode) Color.White else Color.White.copy(alpha = 0.6f))
        Glyph(Icons.Rounded.Shuffle, "Перемешать", h.onShuffle, tint = if (h.state.shuffle) Color.White else Color.White.copy(alpha = 0.6f))
        TextButton(onClick = h.onSpeed) { Text(h.speedLabel, color = Color.White.copy(alpha = 0.8f)) }
        Glyph(if (h.state.repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Повтор трека", h.onRepeat,
            tint = if (h.state.repeatOne) Color.White else Color.White.copy(alpha = 0.6f))
        Glyph(Icons.Rounded.QueueMusic, "Очередь", h.onOpenQueue, tint = Color.White.copy(alpha = 0.8f))
    }
}

/** System music volume as a thin slider, like the one under the transport keys in Apple's player. */
@Composable
private fun AppleVolume() {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.VolumeDown, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.7f))
        ThinSeek(
            value = volume, onChange = { v ->
                volume = v
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0) }
            }, onCommit = {}, enabled = true,
            active = Color.White.copy(alpha = 0.85f), inactive = Color.White.copy(alpha = 0.28f), track = 6.dp, handle = null,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp), description = "Громкость",
        )
        Icon(Icons.Rounded.VolumeUp, null, Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.7f))
    }
}

/** Lyrics view of the Apple theme: a compact track header on top, the text takes the rest of the screen. */
@Composable
private fun AppleLyricsHeader(h: PlayerHooks) {
    Row(Modifier.fillMaxWidth().height(72.dp).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.15f))
                .clickable(onClick = h.onToggleLyrics).semantics { contentDescription = "Вернуться к плееру" },
        ) {
            h.cover?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(h.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(h.artist, fontSize = 16.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Surface(onClick = h.onLike, shape = CircleShape, color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.size(34.dp).semantics { contentDescription = "Нравится" }) {
            Box(contentAlignment = Alignment.Center) { Icon(if (h.liked) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, Modifier.size(20.dp)) }
        }
        Surface(onClick = h.onOpenActions, shape = CircleShape, color = Color.White.copy(alpha = 0.2f),
            modifier = Modifier.padding(start = 10.dp).size(34.dp).semantics { contentDescription = "Действия с треком" }) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MoreHoriz, null, Modifier.size(20.dp)) }
        }
    }
}

/** A slim, quiet control row under the lyrics so playback stays reachable without leaving the text. */
@Composable
private fun AppleLyricsBar(h: PlayerHooks) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Glyph(Icons.Rounded.FormatQuote, "Скрыть текст", h.onToggleLyrics, tint = Color.White)
        Glyph(Icons.Rounded.FastRewind, "Предыдущий", h.onPrevious, size = 32.dp, tint = Color.White.copy(alpha = 0.8f))
        IconButton(onClick = h.onPlayPause, modifier = Modifier.size(56.dp)) { PlayGlyph(h, Color.White, 40.dp) }
        Glyph(Icons.Rounded.FastForward, "Следующий", h.onNext, size = 32.dp, tint = Color.White.copy(alpha = 0.8f))
        Glyph(Icons.Rounded.QueueMusic, "Очередь", h.onOpenQueue, tint = Color.White.copy(alpha = 0.8f))
    }
}
