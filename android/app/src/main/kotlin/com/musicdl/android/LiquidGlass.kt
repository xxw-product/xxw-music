package com.musicdl.android

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.dp

/**
 * A "liquid glass" surface for Compose: a translucent body, a light sheen from the top, a bright specular rim
 * that fades around the shape and a faint inner glow. It does not refract what lies behind it (Compose has no
 * cheap backdrop capture); the effect comes from translucency plus the lit edge.
 *
 * With [enabled] false the modifier is a no-op, so call sites keep their original look.
 */
internal fun Modifier.glassSurface(
    shape: Shape,
    base: Color,
    enabled: Boolean,
    opacity: Float = 0.78f,
): Modifier {
    if (!enabled) return this
    val s = Appearance.glassStrength
    return this
        .clip(shape)
        .background(base.copy(alpha = (opacity - (s - 1f) * 0.18f).coerceIn(0.3f, 0.95f)))
        .drawWithContent {
            drawContent()
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = (0.20f * s).coerceAtMost(0.5f)),
                    0.45f to Color.White.copy(alpha = 0.03f * s),
                    1f to Color.White.copy(alpha = 0.08f * s),
                ),
            )
            val rim = 2.dp.toPx()
            drawOutline(
                outline,
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = (0.75f * s).coerceAtMost(0.95f)),
                    0.35f to Color.White.copy(alpha = 0.10f),
                    0.65f to Color.White.copy(alpha = 0.04f),
                    1f to Color.White.copy(alpha = (0.42f * s).coerceAtMost(0.8f)),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                ),
                style = Stroke(rim),
            )
        }
}
