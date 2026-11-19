package com.musicdl.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.*
import kotlinx.coroutines.launch

internal val Silver = Color(0xFFDDE0E5)
internal val Vinyl = Color(0xFF050506)

@Composable
internal fun motionEnabled(): Boolean {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val scale = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale > 0f && lifecycle.isAtLeast(Lifecycle.State.STARTED)
}

/** One shared, only lightly bouncy spring so the bar's parts move as one piece. */
private fun navigationSpring(): SpringSpec<Float> =
    spring(dampingRatio = 0.9f, stiffness = 320f, visibilityThreshold = 0.5f)

/** A single slow phase drives grooves, reflected light and ribbons together. */
@Composable
internal fun VinylAtmosphere(modifier: Modifier = Modifier, active: Boolean = true) {
    val animate = motionEnabled() && active
    val phase = if (animate) {
        val transition = rememberInfiniteTransition(label = "vinylLight")
        transition.animateFloat(0f, (PI * 2).toFloat(),
            infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "reflections")
    } else remember { mutableFloatStateOf(0.7f) }
    Canvas(modifier) {
        // Animated parents may draw a zero-size intermediate frame. A radial shader needs a radius.
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val angle = phase.value
        drawRect(Brush.verticalGradient(listOf(Color(0xFF17191D), Vinyl)))
        val center = Offset(size.width * 0.84f, size.height * 0.50f)
        val radius = size.width * 0.58f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF31343B), Color(0xFF08090B), Color.Black), center, radius), radius, center)
        for (index in 0..54) {
            val r = radius * (0.22f + index * 0.014f)
            drawCircle(Silver.copy(alpha = if (index % 4 == 0) 0.13f else 0.045f), r, center, style = Stroke(if (index % 4 == 0) 1.1f else 0.6f))
        }
        rotate(sin(angle) * 22f - 30f, center) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, Silver.copy(alpha = 0.02f),
                Silver.copy(alpha = 0.28f), Color.Transparent, Color.Transparent,
                Silver.copy(alpha = 0.12f), Color.Transparent), center), radius, center)
        }
        for (layer in 0..10) {
            val path = Path()
            val y = size.height * 0.64f + layer * 4.5f
            path.moveTo(-20f, y)
            path.cubicTo(size.width * 0.3f, y - 110f * sin(angle + layer * 0.035f),
                size.width * 0.6f, y + 110f * cos(angle * 0.5f + layer * 0.04f), size.width + 30f, y - 65f)
            drawPath(path, Brush.horizontalGradient(listOf(Color.Transparent, Silver.copy(alpha = 0.09f),
                Silver.copy(alpha = 0.23f), Color.Transparent)), style = Stroke(0.7f + layer * 0.06f))
        }
        drawCircle(Color(0xFF101114), radius * 0.19f, center)
        drawCircle(Silver.copy(alpha = 0.3f), radius * 0.19f, center, style = Stroke(1f))
        drawCircle(Color.Black, radius * 0.025f, center)
        drawRect(Brush.horizontalGradient(listOf(Vinyl.copy(alpha = 0.94f), Vinyl.copy(alpha = 0.62f), Color.Transparent)))
    }
}

/** Transport glyphs share geometry across the full player, wave and mini player. */
@Composable
internal fun TransportButton(
    playing: Boolean = false, buffering: Boolean = false, direction: Int = 0,
    prominent: Boolean = false, compact: Boolean = false, tint: Color = Silver, onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion = motionEnabled()
    val scale by animateFloatAsState(if (pressed) 0.91f else 1f,
        if (motion) spring(dampingRatio = 0.65f, stiffness = 550f) else snap(), label = "transportPress")
    val morph by animateFloatAsState(if (playing) 1f else 0f, if (motion) tween(220) else snap(), label = "playPause")
    val description = when { direction < 0 -> "Предыдущий"; direction > 0 -> "Следующий"; playing -> "Пауза"; else -> "Воспроизвести" }
    val extent = if (compact) 48.dp else if (prominent) 84.dp else 56.dp
    Box(Modifier.size(extent).graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(CircleShape).background(if (prominent) tint else Color.Transparent)
        .then(if (prominent) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape) else Modifier)
        .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description; if (buffering) stateDescription = "Загрузка" },
        contentAlignment = Alignment.Center) {
        val ink = if (prominent) Vinyl else tint
        if (buffering) CircularProgressIndicator(Modifier.size(26.dp), color = ink, strokeWidth = 1.5.dp)
        else Canvas(Modifier.size(if (prominent) 30.dp else 24.dp)) {
            if (direction == 0) {
                val triangle = Path().apply { moveTo(size.width * 0.23f, size.height * 0.08f)
                    lineTo(size.width * 0.90f, size.height * 0.5f); lineTo(size.width * 0.23f, size.height * 0.92f); close() }
                drawPath(triangle, ink.copy(alpha = 1f - morph))
                drawRoundRect(ink.copy(alpha = morph), Offset(size.width * 0.18f, size.height * 0.1f), Size(size.width * 0.22f, size.height * 0.8f))
                drawRoundRect(ink.copy(alpha = morph), Offset(size.width * 0.60f, size.height * 0.1f), Size(size.width * 0.22f, size.height * 0.8f))
            } else {
                fun x(value: Float) = size.width * if (direction > 0) value else 1f - value
                val path = Path().apply { moveTo(x(0.18f), size.height * 0.16f); lineTo(x(0.72f), size.height * 0.5f)
                    lineTo(x(0.18f), size.height * 0.84f); close() }
                drawPath(path, ink)
                drawLine(ink, Offset(x(0.81f), size.height * 0.17f), Offset(x(0.81f), size.height * 0.83f), 2.3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun VinylSeekBar(value: Float, enabled: Boolean, onChange: (Float) -> Unit, onCommit: () -> Unit,
    tint: Color = Silver) {
    var width by remember { mutableStateOf(1f) }
    val inset = with(LocalDensity.current) { 6.dp.toPx() }
    val interactions = remember { MutableInteractionSource() }
    val dragged by interactions.collectIsDraggedAsState()
    var touching by remember { mutableStateOf(false) }
    val engaged = enabled && (dragged || touching)
    val motion = motionEnabled()
    val expansion by animateFloatAsState(if (engaged) 1f else 0f,
        if (motion) spring(dampingRatio = 0.8f, stiffness = 550f) else snap(), label = "seekGrip")
    var dragPosition by remember { mutableFloatStateOf(value) }
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Canvas(Modifier.fillMaxWidth().height(44.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .semantics {
            contentDescription = "Позиция воспроизведения"
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            if (!enabled) disabled()
            if (enabled) setProgress { target -> change(target.coerceIn(0f, 1f)); commit(); true }
        }
        .pointerInput(enabled, inset) {
            if (enabled) detectTapGestures(
                onPress = { touching = true; try { tryAwaitRelease() } finally { touching = false } },
                onTap = { point -> change(((point.x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)); commit() },
            )
        }
        .draggable(rememberDraggableState { delta ->
            dragPosition = (dragPosition + delta / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
            change(dragPosition)
        }, Orientation.Horizontal, enabled = enabled, interactionSource = interactions,
            onDragStarted = { point ->
                dragPosition = ((point.x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                change(dragPosition)
            }, onDragStopped = { commit() })) {
        val trackWidth = size.width - 2 * inset
        if (trackWidth <= 0f) return@Canvas
        val y = size.height / 2
        val x = inset + trackWidth * value.coerceIn(0f, 1f)
        val count = (trackWidth / 6.dp.toPx()).toInt().coerceIn(24, 80)
        val spacing = trackWidth / count
        val segmentWidth = (spacing - 2.dp.toPx()).coerceAtLeast(1f)
        val height = (4f + 4f * expansion).dp.toPx()
        repeat(count) { index ->
            val left = inset + index * spacing
            val filled = left + segmentWidth / 2 < x
            drawRoundRect(if (filled && enabled) tint else tint.copy(alpha = if (enabled) 0.22f else 0.12f),
                Offset(left, y - height / 2), Size(segmentWidth, height), CornerRadius(1.dp.toPx()))
        }
        if (enabled) {
            val gripHeight = (18f + 8f * expansion).dp.toPx()
            val gripWidth = (4f + expansion).dp.toPx()
            drawRoundRect(tint, Offset(x - gripWidth / 2, y - gripHeight / 2),
                Size(gripWidth, gripHeight), CornerRadius(gripWidth / 2))
        }
    }
}

private enum class NavigationGlyph { WAVE, SEARCH, LIBRARY, ARTIST, SETTINGS }

@Composable
private fun NavigationGlyphIcon(glyph: NavigationGlyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(33.dp)) {
        val stroke = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            NavigationGlyph.WAVE -> {
                val xs = listOf(0.18f, 0.38f, 0.62f, 0.82f)
                val heights = listOf(0.32f, 0.68f, 0.48f, 0.78f)
                xs.forEachIndexed { index, x ->
                    val halfHeight = heights[index] * size.height / 2f
                    drawLine(tint, Offset(x * size.width, size.height / 2f - halfHeight),
                        Offset(x * size.width, size.height / 2f + halfHeight),
                        strokeWidth = stroke.width, cap = stroke.cap)
                }
            }
            NavigationGlyph.SEARCH -> {
                val radius = size.minDimension * 0.25f
                val center = Offset(size.width * 0.42f, size.height * 0.42f)
                drawCircle(tint, radius, center, style = stroke)
                drawLine(tint, Offset(center.x + radius * 0.72f, center.y + radius * 0.72f),
                    Offset(size.width * 0.82f, size.height * 0.82f),
                    strokeWidth = stroke.width, cap = stroke.cap)
            }
            NavigationGlyph.LIBRARY -> {
                drawCircle(tint, size.minDimension * 0.11f,
                    Offset(size.width * 0.27f, size.height * 0.72f), style = stroke)
                drawLine(tint, Offset(size.width * 0.38f, size.height * 0.72f),
                    Offset(size.width * 0.38f, size.height * 0.25f),
                    strokeWidth = stroke.width, cap = stroke.cap)
                val flag = Path().apply {
                    moveTo(size.width * 0.38f, size.height * 0.25f)
                    cubicTo(size.width * 0.66f, size.height * 0.28f, size.width * 0.64f, size.height * 0.52f,
                        size.width * 0.38f, size.height * 0.48f)
                }
                drawPath(flag, tint, style = stroke)
            }
            NavigationGlyph.ARTIST -> {
                drawCircle(tint, size.minDimension * 0.14f,
                    Offset(size.width * 0.5f, size.height * 0.31f), style = stroke)
                drawArc(tint, 0f, 180f, false, Offset(size.width * 0.21f, size.height * 0.49f),
                    Size(size.width * 0.58f, size.height * 0.42f), style = stroke)
            }
            NavigationGlyph.SETTINGS -> {
                val ys = listOf(0.27f, 0.5f, 0.73f)
                val knobs = listOf(0.38f, 0.64f, 0.32f)
                ys.forEachIndexed { index, y ->
                    drawLine(tint, Offset(size.width * 0.16f, size.height * y),
                        Offset(size.width * 0.84f, size.height * y),
                        strokeWidth = stroke.width, cap = stroke.cap)
                    drawCircle(tint, size.minDimension * 0.065f,
                        Offset(size.width * knobs[index], size.height * y), style = stroke)
                }
            }
        }
    }
}

/**
 * A weight-based floating tab bar. The selected destination owns three of the seven width units,
 * so every other destination visibly compresses under it. The same spring moves the solid thumb
 * and the slots, while a direct drag gesture lets the thumb be pulled between destinations.
 */
@Composable
internal fun AppNavigation(selected: FeedTab, onSelect: (FeedTab) -> Unit) {
    val items = listOf(
        Triple(FeedTab.WAVE, "Волна", NavigationGlyph.WAVE),
        Triple(FeedTab.SEARCH, "Поиск", NavigationGlyph.SEARCH),
        Triple(FeedTab.LIKES, "Моё", NavigationGlyph.LIBRARY),
        Triple(FeedTab.ARTISTS, "Артисты", NavigationGlyph.ARTIST),
    )
    // Settings live inside «Моё»; the bar keeps highlighting the library while they are open.
    val shown = if (selected == FeedTab.SETTINGS) FeedTab.LIKES else selected
    val totalWeight = items.size + 2f
    val motion = motionEnabled()
    var requestedTab by remember { mutableStateOf(shown) }
    var dragging by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    var dragAnchorPx by remember { mutableFloatStateOf(0f) }
    var dragStartIndex by remember { mutableIntStateOf(0) }
    var dragIndex by remember { mutableIntStateOf(0) }
    val targetIndex = items.indexOfFirst { it.first == requestedTab }.coerceAtLeast(0)
    val thumbPosition = remember { Animatable(0f) }
    var laidOut by remember { mutableStateOf(false) }
    val animatedWeights = items.map { item ->
        animateFloatAsState(
            targetValue = if (item.first == requestedTab) 3f else 1f,
            animationSpec = if (motion) navigationSpring() else snap(),
            label = "navigationWeight${item.first.name}",
        )
    }
    val weights = animatedWeights.map { it.value }

    // Keep the local selection in sync with the screen, but never while a drag owns it.
    LaunchedEffect(shown) {
        if (!dragging) requestedTab = shown
    }

    // One continuous spring: a new target only retargets the animation, it never restarts it.
    LaunchedEffect(targetIndex, trackWidthPx, totalWeight) {
        if (trackWidthPx <= 0f) return@LaunchedEffect
        val target = targetIndex / totalWeight * trackWidthPx
        if (!laidOut || !motion) {
            thumbPosition.snapTo(target)
            laidOut = true
        } else {
            thumbPosition.animateTo(
                targetValue = target,
                animationSpec = navigationSpring(),
            )
        }
    }

    Box(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(32.dp))
                .draggable(
                    state = rememberDraggableState { delta ->
                        val unitPx = trackWidthPx / totalWeight
                        if (unitPx <= 0f) return@rememberDraggableState
                        dragAnchorPx += delta
                        // The finger walks the thumb slot by slot; the spring carries the motion.
                        val next = (dragStartIndex + (dragAnchorPx / unitPx).roundToInt())
                            .coerceIn(0, items.lastIndex)
                        if (next != dragIndex) {
                            dragIndex = next
                            requestedTab = items[next].first
                        }
                    },
                    orientation = Orientation.Horizontal,
                    onDragStarted = {
                        dragging = true
                        dragAnchorPx = 0f
                        dragStartIndex = targetIndex
                        dragIndex = targetIndex
                    },
                    onDragStopped = {
                        // The whole capsule is the handle: starting anywhere drags the active thumb.
                        dragging = false
                        val committed = items[dragIndex].first
                        if (committed != shown) onSelect(committed)
                    },
                ),
        ) {
            // The thumb always owns three of the six width units, so switching tabs never re-sizes it.
            val indicatorWidth = maxWidth * 3f / totalWeight
            val visualStartPx = thumbPosition.value
            val thumbScale by animateFloatAsState(
                targetValue = if (dragging) 1.025f else 1f,
                animationSpec = if (motion) spring(dampingRatio = 0.72f, stiffness = 520f) else snap(),
                label = "navigationThumbScale",
            )
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset { androidx.compose.ui.unit.IntOffset(visualStartPx.roundToInt(), 0) }
                    .width(indicatorWidth).height(52.dp)
                    .graphicsLayer {
                        scaleX = thumbScale
                        scaleY = thumbScale
                    }
                    .clip(RoundedCornerShape(26.dp))
                    .background(Silver),
            )
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, (tab, label, glyph) ->
                    val active = tab == requestedTab
                    val interactions = remember { MutableInteractionSource() }
                    val pressed by interactions.collectIsPressedAsState()
                    val tint by animateColorAsState(
                        targetValue = if (active) Vinyl else Color(0xFF9297A1),
                        animationSpec = if (motion) tween(180, easing = FastOutSlowInEasing) else snap(),
                        label = "navigationTint",
                    )
                    val scale by animateFloatAsState(
                        targetValue = if (pressed) 0.88f else if (active) 1.06f else 1f,
                        animationSpec = if (motion) spring(dampingRatio = 0.68f, stiffness = 560f) else snap(),
                        label = "navigationScale",
                    )
                    Box(
                        Modifier.weight(weights[index]).fillMaxHeight()
                            .selectable(
                                selected = active,
                                interactionSource = interactions,
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    requestedTab = tab
                                    dragStartIndex = index
                                    dragIndex = index
                                    onSelect(tab)
                                },
                            )
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        NavigationGlyphIcon(
                            glyph,
                            tint,
                            Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsContent(state: MusicUiState, model: SearchViewModel) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    var confirmCovers by rememberSaveable { mutableStateOf(false) }
    var yandexToken by rememberSaveable { mutableStateOf(state.yandexToken) }
    var qobuzAppId by rememberSaveable { mutableStateOf(state.qobuzAppId) }
    var qobuzSecret by rememberSaveable { mutableStateOf(state.qobuzAppSecret) }
    var qobuzUserToken by rememberSaveable { mutableStateOf(state.qobuzToken) }
    var soundcloudId by rememberSaveable { mutableStateOf(state.soundcloudClientId) }
    val used = state.downloadUsedBytes
    val limit = state.downloadLimitBytes
    val vpnContext = LocalContext.current
    val vpnRepository = remember(vpnContext) { VpnSubscriptionRepository(vpnContext) }
    var vpnProfiles by remember { mutableStateOf(runCatching { vpnRepository.getProfiles() }.getOrDefault(emptyList())) }
    var vpnInput by rememberSaveable { mutableStateOf("") }
    val vpnPings = remember { mutableStateMapOf<String, Long>() }
    var vpnPinging by remember { mutableStateOf(false) }
    var vpnClient by remember { mutableStateOf(vpnRepository.subscriptionClient) }
    val vpnPrefs = remember(vpnContext) { vpnContext.getSharedPreferences(MusicVpnService.PREFS, android.content.Context.MODE_PRIVATE) }
    var vpnSelectedId by rememberSaveable { mutableStateOf(vpnPrefs.getString(MusicVpnService.KEY_SELECTED_PROFILE, null) ?: vpnPrefs.getString(MusicVpnService.KEY_ACTIVE_PROFILE, null).orEmpty()) }
    var vpnState by remember { mutableStateOf(vpnPrefs.getString(MusicVpnService.KEY_STATE, MusicVpnService.STATE_DISCONNECTED) ?: MusicVpnService.STATE_DISCONNECTED) }
    var vpnStatus by remember { mutableStateOf(vpnPrefs.getString(MusicVpnService.KEY_MESSAGE, "").orEmpty()) }
    var vpnBusy by remember { mutableStateOf(false) }
    val vpnScope = rememberCoroutineScope()
    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK && vpnSelectedId.isNotBlank()) {
            MusicVpnService.start(vpnContext, vpnSelectedId)
        } else {
            vpnStatus = "Разрешение Android на VPN не предоставлено"
        }
    }
    DisposableEffect(vpnContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MusicVpnService.ACTION_STATE) {
                    vpnState = intent.getStringExtra(MusicVpnService.EXTRA_STATE) ?: vpnState
                    vpnStatus = intent.getStringExtra(MusicVpnService.EXTRA_MESSAGE) ?: vpnStatus
                }
            }
        }
        val filter = IntentFilter(MusicVpnService.ACTION_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            vpnContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            vpnContext.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { vpnContext.unregisterReceiver(receiver) } }
    }
    val usedFraction = if (limit > 0L) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    val full = limit in 1..used
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("Музыка в вашем ритме", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)

        SettingsGroup("VPN для музыки") {
            Text("Добавьте личную VPN-подписку или отдельную ссылку на сервер. Конфигурации хранятся на устройстве в зашифрованном виде.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = vpnInput,
                onValueChange = { vpnInput = it.take(2 * 1024 * 1024) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("URL подписки или VLESS / VMess / Trojan / SS") },
                minLines = 2,
                maxLines = 4,
                enabled = !vpnBusy && vpnState != MusicVpnService.STATE_CONNECTED,
            )
            Text("Подписка запрашивается как клиент:", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                SubscriptionClient.entries.forEach { client ->
                    FilterChip(
                        selected = vpnClient == client,
                        onClick = { vpnClient = client; vpnRepository.subscriptionClient = client },
                        label = { Text(client.label) },
                    )
                }
            }
            Text("«Авто» пробует Happ, v2rayNG и INCY, пока провайдер не отдаст серверы. Если провайдер просит другое приложение, выберите клиент вручную.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    vpnBusy = true
                    vpnStatus = "Импортируем подписку…"
                    vpnScope.launch {
                        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vpnRepository.add(vpnInput) } }
                            .onSuccess { updated ->
                                vpnProfiles = updated
                                if (vpnSelectedId.isBlank()) {
                                    vpnSelectedId = updated.firstOrNull()?.id.orEmpty()
                                    vpnPrefs.edit().putString(MusicVpnService.KEY_SELECTED_PROFILE, vpnSelectedId).apply()
                                }
                                vpnInput = ""
                                vpnStatus = "Добавлено серверов: ${updated.size}"
                            }
                            .onFailure { error -> vpnStatus = error.vpnMessage("Не удалось импортировать подписку") }
                        vpnBusy = false
                    }
                },
                enabled = vpnInput.isNotBlank() && !vpnBusy && vpnState != MusicVpnService.STATE_CONNECTED,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (vpnBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить подписку")
            }
            if (vpnProfiles.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ВЫБЕРИТЕ СЕРВЕР", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(
                        enabled = !vpnPinging && vpnState != MusicVpnService.STATE_CONNECTED,
                        onClick = {
                            vpnPinging = true
                            vpnProfiles.forEach { vpnPings[it.id] = PING_MEASURING }
                            vpnScope.launch {
                                ServerPing.pingAll(vpnProfiles) { id, ms -> vpnPings[id] = ms }
                                vpnPinging = false
                            }
                        },
                    ) { Text(if (vpnPinging) "Проверяем…" else "Проверить пинг") }
                }
                if (vpnState == MusicVpnService.STATE_CONNECTED) {
                    Text("Чтобы замерить пинг, отключите VPN: при включённом туннеле замер был бы неточным.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            vpnProfiles.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (vpnSelectedId == profile.id) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
                        .clickable(enabled = vpnState != MusicVpnService.STATE_CONNECTED) {
                            vpnSelectedId = profile.id
                            vpnPrefs.edit().putString(MusicVpnService.KEY_SELECTED_PROFILE, profile.id).apply()
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vpnSelectedId == profile.id,
                        onClick = {
                            if (vpnState != MusicVpnService.STATE_CONNECTED) {
                                vpnSelectedId = profile.id
                                vpnPrefs.edit().putString(MusicVpnService.KEY_SELECTED_PROFILE, profile.id).apply()
                            }
                        },
                        enabled = vpnState != MusicVpnService.STATE_CONNECTED)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${profile.protocol} · ${profile.server}:${profile.port}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        vpnPings[profile.id]?.let { ms ->
                            val (label, color) = pingLabel(ms)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                                modifier = Modifier.clickable(enabled = ms != PING_MEASURING && vpnState != MusicVpnService.STATE_CONNECTED) {
                                    vpnPings[profile.id] = PING_MEASURING
                                    vpnScope.launch { vpnPings[profile.id] = ServerPing.ping(profile) }
                                })
                        }
                    }
                    if (profile.subscriptionUrl != null) {
                        IconButton(enabled = !vpnBusy && vpnState != MusicVpnService.STATE_CONNECTED, onClick = {
                            vpnBusy = true
                            vpnStatus = "Обновляем подписку…"
                            vpnScope.launch {
                                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vpnRepository.refresh(profile.subscriptionUrl) } }
                                    .onSuccess { vpnProfiles = it; vpnStatus = "Подписка обновлена" }
                                    .onFailure { vpnStatus = it.vpnMessage("Не удалось обновить подписку") }
                                vpnBusy = false
                            }
                        }) { Icon(Icons.Rounded.Refresh, "Обновить подписку") }
                    }
                    IconButton(enabled = !vpnBusy && vpnState != MusicVpnService.STATE_CONNECTED, onClick = {
                        vpnScope.launch {
                            vpnProfiles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vpnRepository.remove(profile.id) }
                            if (vpnSelectedId == profile.id) {
                                vpnSelectedId = vpnProfiles.firstOrNull()?.id.orEmpty()
                                vpnPrefs.edit().putString(MusicVpnService.KEY_SELECTED_PROFILE, vpnSelectedId).apply()
                            }
                        }
                    }) { Icon(Icons.Rounded.DeleteOutline, "Удалить сервер") }
                }
            }
            Button(
                onClick = {
                    if (vpnState == MusicVpnService.STATE_CONNECTED || vpnState == MusicVpnService.STATE_CONNECTING) {
                        MusicVpnService.stop(vpnContext)
                    } else if (vpnSelectedId.isBlank()) {
                        vpnStatus = "Сначала добавьте подписку и выберите сервер"
                    } else {
                        val permissionIntent = VpnService.prepare(vpnContext)
                        if (permissionIntent == null) MusicVpnService.start(vpnContext, vpnSelectedId)
                        else vpnPermissionLauncher.launch(permissionIntent)
                    }
                },
                enabled = !vpnBusy && vpnProfiles.isNotEmpty() && vpnState != MusicVpnService.STATE_CONNECTING,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vpnState == MusicVpnService.STATE_CONNECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(if (vpnState == MusicVpnService.STATE_CONNECTED || vpnState == MusicVpnService.STATE_CONNECTING) Icons.Rounded.PowerSettingsNew else Icons.Rounded.Shield,
                    contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(when (vpnState) {
                    MusicVpnService.STATE_CONNECTED -> "Отключить VPN"
                    MusicVpnService.STATE_CONNECTING -> "Подключаем…"
                    else -> "Подключить VPN"
                })
            }
            if (vpnStatus.isNotBlank()) Text(vpnStatus, style = MaterialTheme.typography.bodySmall,
                color = if (vpnState == MusicVpnService.STATE_ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Через VPN направляется только трафик XXW Music. Остальные приложения телефона используют обычное подключение. Для подключения сейчас реализованы VLESS, VMess, Trojan и Shadowsocks.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsGroup("Музыкальные сервисы") {
            Text("Ваша музыка — в одном месте", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Яндекс: публичный плейлист переносится без входа.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.serviceStatus.isNotBlank()) Text(state.serviceStatus,
                style = MaterialTheme.typography.bodyMedium)
            if (state.serviceConnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            var serviceLink by rememberSaveable { mutableStateOf("") }
            val serviceContext = LocalContext.current
            val clipboard = serviceContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            TextButton(enabled = !state.isImporting, onClick = {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    serviceLink = clip.getItemAt(0).coerceToText(serviceContext)?.toString().orEmpty().take(1000)
                }
            }) {
                Icon(Icons.Rounded.ContentPaste, null)
                Spacer(Modifier.width(8.dp))
                Text("Вставить ссылку")
            }
            OutlinedTextField(value = serviceLink, onValueChange = { serviceLink = it.take(1000) },
                label = { Text("Ссылка на плейлист Яндекса") },
                supportingText = { Text("Можно вставить всё сообщение со ссылкой") },
                modifier = Modifier.fillMaxWidth(), enabled = !state.isImporting)
            Button(onClick = { model.importPlaylistLink(serviceLink, download = true) },
                enabled = serviceLink.isNotBlank() && !state.isImporting,
                modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isImporting) "Переносим…" else "Перенести и скачать")
            }
            Text("Создадим плейлист, найдём доступные файлы и поставим их на скачивание. Недоступные треки не скрываем в итоговом счёте.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.isImporting) {
                Text(state.importProgress)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.linkImportResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        SettingsGroup("Офлайн") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автоскачивание", style = MaterialTheme.typography.titleMedium)
                    Text("Любимые и прослушанные треки", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.autoDownload, onCheckedChange = model::setAutoDownload, colors = vinylSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "Автоскачивание" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Занято", style = MaterialTheme.typography.labelLarge)
                    Text("${DownloadStore.sizeLabel(used)} из ${DownloadStore.label(limit)} · файлов ${state.downloads.size}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    if (limit <= 0L) "без лимита" else "свободно ${DownloadStore.sizeLabel((limit - used).coerceAtLeast(0L))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { if (limit <= 0L) 0f else usedFraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (full) Color(0xFFE8A0A0) else Silver,
                trackColor = Color(0xFF25272C),
            )
            if (full) {
                Text("Лимит заполнен: новые загрузки заблокированы, пока вы не удалите трек или не увеличите лимит.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text("Лимит объёма", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DownloadStore.LIMITS.forEach { bytes ->
                    val selected = bytes == limit
                    Surface(onClick = { model.setDownloadLimit(bytes) }, shape = RoundedCornerShape(10.dp),
                        color = if (selected) Silver else Color.Transparent,
                        contentColor = if (selected) Vinyl else Silver,
                        border = BorderStroke(1.dp, if (selected) Silver else Color(0xFF34363C)),
                        modifier = Modifier.semantics { this.selected = selected }) {
                        Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 44.dp).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center) { Text(DownloadStore.label(bytes)) }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Скачивать обложки треков", style = MaterialTheme.typography.titleMedium)
                    Text("Локальная обложка работает без интернета · занято ${DownloadStore.sizeLabel(state.downloads.sumOf { it.coverBytes })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.downloadCovers, onCheckedChange = model::setDownloadCovers, colors = vinylSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "Скачивать обложки треков" })
            }
            if (state.downloads.any { it.coverPath != null }) {
                TextButton(onClick = { confirmCovers = true }) { Text("Очистить скачанные обложки") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Скачивать тексты песен", style = MaterialTheme.typography.titleMedium)
                    Text("LRC с таймингами · UTF-8 · занято ${DownloadStore.sizeLabel(state.downloads.sumOf { it.lyricsBytes })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.downloadLyrics, onCheckedChange = model::setDownloadLyrics, colors = vinylSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "Скачивать тексты песен" })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Скачивать клипы", style = MaterialTheme.typography.titleMedium)
                    Text("Вертикальные MP4 · H.264 · занято ${DownloadStore.sizeLabel(state.downloads.sumOf { it.clipBytes })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.downloadClips, onCheckedChange = model::setDownloadClips, colors = vinylSwitchColors(),
                    modifier = Modifier.semantics { contentDescription = "Скачивать клипы" })
            }
            Text("Обложки сохраняются как WebP 1200 px: качество визуально не отличается от JPEG, а декодируются быстрее и весят меньше. Клипы — готовый H.264 MP4 без перекодирования.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Скачивание работает, пока приложение открыто, и может использовать мобильный интернет.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        SettingsGroup("Ключи и токены") {
            Text("Qobuz", style = MaterialTheme.typography.titleMedium)
            Text("App ID и секрет подписывают каждый запрос каталога; без своих значений используются встроенные. Пользовательский токен открывает аккаунт Qobuz, публичный каталог работает и без него.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = qobuzAppId,
                onValueChange = { qobuzAppId = it },
                label = { Text("Qobuz app_id") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Qobuz app id" },
            )
            OutlinedTextField(
                value = qobuzSecret,
                onValueChange = { qobuzSecret = it },
                label = { Text("Qobuz app_secret") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Qobuz app secret" },
            )
            OutlinedTextField(
                value = qobuzUserToken,
                onValueChange = { qobuzUserToken = it },
                label = { Text("Qobuz user_auth_token (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Qobuz token" },
            )
            TextButton(onClick = { model.setQobuzCredentials(qobuzAppId, qobuzSecret, qobuzUserToken) }) {
                Text("Сохранить ключи Qobuz")
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("SoundCloud", style = MaterialTheme.typography.titleMedium)
            Text("client_id не обязателен: без него приложение само читает публичный id со страницы SoundCloud. Свой id убирает эту лишнюю загрузку.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = soundcloudId,
                onValueChange = { soundcloudId = it },
                label = { Text("SoundCloud client_id (необязательно)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "SoundCloud client id" },
            )
            TextButton(onClick = { model.setSoundcloudClientId(soundcloudId) }) {
                Text(if (soundcloudId.isBlank()) "Очистить client_id" else "Сохранить client_id")
            }
            Text("Ключи хранятся только на этом устройстве и никогда не попадают в экспорт медиатеки.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        SettingsGroup("Источники музыки") {
            ALL_SOURCES.sortedBy { sourceName(it) }.forEach { source ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sourceName(source), style = MaterialTheme.typography.bodyLarge)
                        if (needsToken(source)) Text("нужен токен · не входит в «Авто»",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(source in state.activeSources, onCheckedChange = { model.toggleSource(source) },
                        colors = vinylSwitchColors(),
                        enabled = state.activeSources.size > 1 || source !in state.activeSources,
                        modifier = Modifier.semantics { contentDescription = sourceName(source) })
                }
            }
            Text("Qobuz и SoundCloud работают без ключей; ключи выше нужны только для собственного приложения или аккаунта.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        SettingsGroup("Яндекс · вся медиатека аккаунта") {
            Text("Лайки, плейлисты, исполнители и альбомы", style = MaterialTheme.typography.titleMedium)
            Text("Только для всей медиатеки нужен токен аккаунта. Для публичного плейлиста используйте ссылку в разделе «Музыкальные сервисы» выше — без токена. Треки сопоставляются с каталогом XXW Music; спорные версии попадают в плейлист «Перенос · требует проверки», ненайденные показываются списком.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = yandexToken,
                onValueChange = { yandexToken = it },
                label = { Text("Токен Яндекс Музыки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = yandexToken.isNotBlank() && !state.isImporting,
                    onClick = { model.setYandexToken(yandexToken); model.importYandexMusic() }) {
                    Text(if (state.isImporting) "Переносим…" else "Начать перенос")
                }
                if (state.isImporting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Silver)
                    Text(state.importProgress, Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Импорт только читает ваш аккаунт. Плейлисты создаются как ссылки на треки каталога, файлы Яндекса не копируются.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        SettingsGroup("Моя волна") {
            Text("Ваш вкус меняется вместе с вами", style = MaterialTheme.typography.titleMedium)
            Text("Волна учитывает любимые треки, исполнителей, жанры, полные прослушивания, повторы и прослушивания до конца. " +
                "Штрафы за ранние и повторные пропуски, короткое прослушивание, дизлайк, удаление из медиатеки переносятся на исполнителя, жанр и похожие треки и затухают со временем (полураспад ~45 дней). " +
                "Учтено прослушиваний: ${state.listeningCount}.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
            Surface(onClick = { confirm = true }, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.RestartAlt, null, Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Сбросить мою волну", fontWeight = FontWeight.Medium)
                        Text("Начать историю вкуса заново", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
        }
        Text("XXW MUSIC", style = MaterialTheme.typography.labelSmall, letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Сбросить мою волну?") },
        text = { Text("История прослушиваний, пропуски, дизлайки и настройки жанров будут сброшены. Любимые треки, подписки, плейлисты и скачанная музыка сохранятся и помогут собрать новую волну.") },
        confirmButton = { TextButton(onClick = { confirm = false; model.resetWave() }) { Text("Сбросить") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    if (confirmCovers) AlertDialog(onDismissRequest = { confirmCovers = false },
        title = { Text("Удалить скачанные обложки?") },
        text = { Text("Аудиофайлы останутся на месте. Обложки снова появятся при следующем скачивании трека.") },
        confirmButton = { TextButton(onClick = { confirmCovers = false; model.clearCovers() }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmCovers = false }) { Text("Отмена") } })
    state.importReport?.let { report ->
        ImportReportDialog(report, model::dismissImportReport)
    }
}

@Composable
private fun ImportReportDialog(report: YandexImportReport, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Перенос из Яндекс Музыки") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (report.account.isNotBlank()) Text("Аккаунт: ${report.account}", style = MaterialTheme.typography.labelLarge)
                Text("Точных: ${report.exact} · под вопросом: ${report.doubtful} · не найдено: ${report.unmatched}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Добавлено треков: ${report.addedTracks} · плейлистов: ${report.addedPlaylists} · подписок: ${report.followedArtists} · дубликатов пропущено: ${report.skippedDuplicates}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val doubtful = report.doubtfulList()
                if (doubtful.isNotEmpty()) {
                    Text("Проверьте версии", style = MaterialTheme.typography.labelLarge)
                    doubtful.take(25).forEach { match ->
                        Text("• ${match.source.title} — ${match.source.artists.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                val missing = report.unmatchedList()
                if (missing.isNotEmpty()) {
                    Text("Не удалось перенести", style = MaterialTheme.typography.labelLarge)
                    missing.take(25).forEach { match ->
                        Text("• ${match.source.title} — ${match.source.artists.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (report.albums.isNotEmpty()) {
                    Text("Альбомы", style = MaterialTheme.typography.labelLarge)
                    report.albums.take(15).forEach { (label, matches) ->
                        Text("• $label · ${matches.count { it.confidence != MatchConfidence.NONE }} из ${matches.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
}

@Composable
private fun vinylSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Vinyl, checkedTrackColor = Silver, checkedBorderColor = Silver,
    uncheckedThumbColor = Color(0xFF9A9EA7), uncheckedTrackColor = Color.Transparent,
    uncheckedBorderColor = Color(0xFF61656D), disabledCheckedThumbColor = Vinyl,
    disabledCheckedTrackColor = Silver.copy(alpha = 0.7f), disabledCheckedBorderColor = Silver.copy(alpha = 0.7f),
)

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = Color(0xFF101114), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Color(0xFF25272C))) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

internal const val PING_MEASURING = -2L

/** Text and colour for a ping value; green is comfortable, red is slow or unreachable. */
@Composable
internal fun pingLabel(ms: Long): Pair<String, Color> = when {
    ms == PING_MEASURING -> "измеряем…" to MaterialTheme.colorScheme.onSurfaceVariant
    ms == ServerPing.UNSUPPORTED -> "пинг не поддерживается (UDP)" to MaterialTheme.colorScheme.onSurfaceVariant
    ms < 0 -> "недоступен" to MaterialTheme.colorScheme.error
    ms < 150 -> "$ms мс" to Color(0xFF4CAF50)
    ms < 400 -> "$ms мс" to Color(0xFFFFB300)
    else -> "$ms мс" to MaterialTheme.colorScheme.error
}
