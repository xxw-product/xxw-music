package com.musicdl.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive

/** One turn per 24 seconds of visible playback. Pause preserves the current orientation. */
@Composable
internal fun rememberCoverRotation(collapsed: Boolean, playing: Boolean, enabled: Boolean): FloatState {
    val angle = remember { mutableFloatStateOf(0f) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(collapsed, playing, enabled, owner) {
        if (!enabled) {
            angle.floatValue = 0f
        } else if (!collapsed) {
            val target = if (angle.floatValue > 180f) 360f else 0f
            Animatable(angle.floatValue).animateTo(target, tween(450)) { angle.floatValue = value }
            angle.floatValue = 0f
        } else if (playing) {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var previous = withFrameNanos { it }
                while (isActive) {
                    withFrameNanos { now ->
                        val elapsedSeconds = (now - previous) / 1_000_000_000.0
                        previous = now
                        angle.floatValue = ((angle.floatValue + elapsedSeconds * 15.0) % 360.0).toFloat()
                    }
                }
            }
        }
    }
    return angle
}
