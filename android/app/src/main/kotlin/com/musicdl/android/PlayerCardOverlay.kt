package com.musicdl.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.launch

/** Keep the modal alive until its card has left the screen; never interrupt playback. */
@Composable
internal fun PlayerCardOverlay(onClosed: () -> Unit, content: @Composable (() -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    val animation = remember { Animatable(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density
    val motion = motionEnabled()
    val finish by rememberUpdatedState(onClosed)
    // Until the card has measured itself and started its entry, it waits just below the bottom
    // edge: the dialog window appears without a visible pop, then the card rises into place.
    val offset = when {
        dragging -> dragOffset
        entered -> animation.value
        else -> height
    }

    LaunchedEffect(height) {
        if (!entered && height > 2f) {
            entered = true
            animation.snapTo(height)
            if (motion) {
                animation.animateTo(0f, spring(dampingRatio = 1f, stiffness = 460f))
            } else {
                animation.snapTo(0f)
            }
        }
    }

    suspend fun dismiss(velocity: Float = 0f) {
        val start = if (dragging) dragOffset else animation.value
        animation.snapTo(start)
        dragging = false
        if (motion) animation.animateTo(height * 1.04f,
            tween(300, easing = FastOutSlowInEasing), initialVelocity = velocity)
        finish()
    }
    val requestClose: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch { dismiss() }
        }
    }

    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Draw the scrim with the card so it fades continuously, without a second window animation.
        DisposableEffect(window) {
            val previousDim = window?.attributes?.dimAmount ?: 0f
            val previousAnimation = window?.attributes?.windowAnimations ?: 0
            window?.setDimAmount(0f)
            window?.setWindowAnimations(0)
            onDispose {
                window?.setDimAmount(previousDim)
                window?.setWindowAnimations(previousAnimation)
            }
        }
        val progress = (offset / height).coerceIn(0f, 1f)
        val reveal = (progress / 0.3f).coerceIn(0f, 1f)
        Box(Modifier.fillMaxSize().onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
            .background(Color.Black.copy(alpha = 0.28f * (1f - progress)))) {
            Box(Modifier.fillMaxSize()
                .graphicsLayer {
                    translationY = offset
                    scaleX = 1f - 0.08f * reveal
                    scaleY = 1f - 0.02f * reveal
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    shape = RoundedCornerShape((28f * reveal).dp)
                    clip = true
                    shadowElevation = 20.dp.toPx() * reveal
                }
                .testTag("player-card")
                .draggable(rememberDraggableState { delta ->
                    dragOffset = (dragOffset + delta).coerceIn(0f, height * 1.08f)
                }, orientation = Orientation.Vertical, enabled = !closing,
                    onDragStarted = {
                        dragOffset = animation.value
                        dragging = true
                        animation.stop()
                    },
                    onDragStopped = { velocity ->
                        val farEnough = dragOffset >= height * 0.18f && velocity > -400f * density
                        val flickedDown = dragOffset > 12f * density && velocity > 850f * density
                        if (farEnough || flickedDown) {
                            closing = true
                            dismiss(velocity)
                        } else {
                            animation.snapTo(dragOffset)
                            dragging = false
                            animation.animateTo(0f, if (motion) spring(dampingRatio = 0.86f, stiffness = 420f) else snap(),
                                initialVelocity = velocity)
                        }
                    })) {
                content(requestClose)
            }
        }
    }
}
