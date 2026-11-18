package com.musicdl.android

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.musicdl.core.model.SongInfo
import com.musicdl.core.net.ClipResolution
import com.musicdl.core.net.ClipSpec

/** Clips screen state: the vertical feed is the current queue, not the result list. */
data class ClipUiState(
    val clips: List<SongInfo> = emptyList(),
    val index: Int = -1,
    val resolution: ClipResolution? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
) {
    val song: SongInfo? get() = clips.getOrNull(index)
}

/**
 * Full-screen vertical clips.
 *
 * The 9:16 stage keeps its aspect ratio on any screen; a swipe up/down moves through the queue and
 * every clip is resolved with its own timeout, so a provider that never answers shows its message
 * and hands over to the next track instead of freezing the screen.
 */
@Composable
internal fun ClipsScreen(
    state: ClipUiState,
    onClose: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onRetry: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stageWidth = minOf(maxWidth - 24.dp, (maxHeight - 220.dp) * 9f / 16f)
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(state.song?.key()) {
                            detectVerticalDragGestures { _, dragAmount ->
                                if (dragAmount < -70f) onNext() else if (dragAmount > 70f) onPrevious()
                            }
                        },
                ) {
                    Column(
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть клипы", tint = Color.White) }
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Клипы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    listOfNotNull(
                                        state.resolution?.clip?.provider,
                                        if (state.clips.isNotEmpty()) "${(state.index + 1).coerceAtLeast(1)} из ${state.clips.size}" else null,
                                    ).joinToString(" · ").ifBlank { "вертикальные клипы" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF9A9EA7),
                                )
                            }
                            IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Найти клип заново", tint = Color.White) }
                        }

                        Box(
                            Modifier
                                .width(stageWidth)
                                .aspectRatio(9f / 16f)
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color(0xFF0B0C0E)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val resolution = state.resolution
                            when {
                                state.isLoading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Silver, strokeWidth = 2.dp)
                                    Text("Ищем клип…", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF9A9EA7))
                                }
                                resolution == null -> ClipEmpty(state.error ?: "Клип не найден")
                                else -> when (val clip = resolution.clip) {
                                    is ClipSpec.Video -> ClipVideo(clip, onError = onNext, modifier = Modifier.fillMaxSize())
                                    is ClipSpec.Motion -> MotionClip(clip.coverUrl, state.song)
                                }
                            }
                            state.error?.takeIf { !state.isLoading }?.let { message ->
                                Surface(
                                    color = Color.Black.copy(alpha = 0.72f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                                ) {
                                    Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFE8E8E6), textAlign = TextAlign.Center)
                                }
                            }
                        }

                        Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.song?.songName ?: "Без названия", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(state.song?.singers.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF9A9EA7), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                            FilledTonalButton(onClick = onNext) {
                                Text("Далее")
                                Icon(Icons.Rounded.SkipNext, null, Modifier.padding(start = 6.dp))
                            }
                        }
                        Text("Смахните вверх или вниз", Modifier.padding(top = 10.dp),
                            style = MaterialTheme.typography.labelSmall, color = Color(0xFF6F737B))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipEmpty(message: String) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(40.dp), tint = Color(0xFF6F737B))
        Text(message, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9A9EA7), textAlign = TextAlign.Center)
    }
}

/** No video provider answered: the artwork itself becomes a 9:16 loop. */
@Composable
private fun MotionClip(coverUrl: String?, song: SongInfo?) {
    val motion = motionEnabled()
    val transition = rememberInfiniteTransition(label = "clipMotion")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (motion) 1.12f else 1f,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "clipZoom",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CoverImage(coverUrl, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom }) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF1B1D21), Color(0xFF08090B))),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text((song?.songName ?: "?").trim().take(1).uppercase(), fontSize = 72.sp,
                    fontWeight = FontWeight.Black, color = Color(0xFF2E3138))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))),
            ),
        )
        Text("Обложка · живой фон", Modifier.align(Alignment.BottomCenter).padding(14.dp),
            style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9BDC4))
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun ClipVideo(clip: ClipSpec.Video, onError: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f
        // Canvas is silent decoration: it must never take audio focus away from the music player.
        setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false,
        )
    } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(clip.url) {
        val http = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(clip.headers)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .createMediaSource(MediaItem.fromUri(clip.url))
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = true
    }
    AndroidView(
        factory = { view ->
            PlayerView(view).apply {
                useController = false
                resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}
