package com.musicdl.android

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Liquid glass for Compose.
 *
 * Two levels:
 *  - "real": the screen content is recorded into one GraphicsLayer ([GlassBackdrop]); glass elements draw that layer
 *    again behind themselves, blurred (Android 12+) and bent at the edges by an AGSL shader (Android 13+).
 *  - fallback: a translucent body with a sheen and a lit rim, no access to what is behind (menus in their own
 *    windows, old phones, or "real glass" switched off).
 *
 * With [enabled] false every modifier here is a no-op, so call sites keep their original look.
 */
internal class GlassBackdrop(val layer: GraphicsLayer) {
    var origin by mutableStateOf(Offset.Zero)
}

internal object GlassBackdropRegistry {
    var current: GlassBackdrop? by mutableStateOf(null)
}

@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer) }
    DisposableEffect(backdrop) {
        GlassBackdropRegistry.current = backdrop
        onDispose { if (GlassBackdropRegistry.current === backdrop) GlassBackdropRegistry.current = null }
    }
    return backdrop
}

/** Marks the content that glass elements refract. Place it on the list that scrolls under the bars. */
internal fun Modifier.glassSource(backdrop: GlassBackdrop, enabled: Boolean): Modifier {
    if (!enabled) return this
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record {
                this@drawWithContent.drawContent()
            }
            drawLayer(backdrop.layer)
        }
}

internal fun Modifier.glassSurface(
    shape: Shape,
    base: Color,
    enabled: Boolean,
    opacity: Float = 0.78f,
    real: Boolean = false,
): Modifier {
    if (!enabled) return this
    return if (real) realGlass(shape, base, opacity) else softGlass(shape, base, opacity)
}

private fun Modifier.softGlass(shape: Shape, base: Color, opacity: Float): Modifier {
    val matte = Appearance.glassMatte
    return this
        .clip(shape)
        .background(base.copy(alpha = (opacity + (matte - 0.35f) * 0.6f).coerceIn(0.2f, 0.97f)))
        .drawWithContent {
            drawContent()
            drawGlassRim(shape)
        }
}

/** Only a hairline edge: no glow or sheen. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassRim(shape: Shape) {
    drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
}

private class EffectCache {
    var w = 0
    var h = 0
    var radius = -1f
    var blur = -1f
    var shader: RuntimeShader? = null
}

private const val GLASS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
uniform float strength;

float sdf(float2 p) {
    float2 b = size * 0.5;
    float r = min(radius, min(b.x, b.y));
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 p = coord - size * 0.5;
    float inside = -sdf(p);
    float t = clamp(1.0 - inside / depth, 0.0, 1.0);
    float2 n = normalize(float2(sdf(p + float2(1.0, 0.0)) - sdf(p - float2(1.0, 0.0)),
                                sdf(p + float2(0.0, 1.0)) - sdf(p - float2(0.0, 1.0))) + 0.00001);
    float bend = t * t * depth * strength;
    float2 uv = coord - n * bend;
    float2 lo = float2(0.5);
    float2 hi = size - float2(0.5);
    half3 c = content.eval(clamp(uv, lo, hi)).rgb;
    return half4(c, 1.0);
}
"""

private fun Modifier.realGlass(shape: Shape, base: Color, opacity: Float): Modifier = composed {
    val backdrop = GlassBackdropRegistry.current
    if (backdrop == null || !Appearance.realGlassActive) return@composed Modifier.softGlass(shape, base, opacity)
    val glassLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    val cache = remember { EffectCache() }
    Modifier
        .onGloballyPositioned { origin = it.positionInRoot() }
        .clip(shape)
        .drawWithContent {
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val matte = Appearance.glassMatte
            val blurPx = (1.5f + 16f * matte).dp.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            val depth = minOf(size.height / 2f, 26.dp.toPx())
            val dx = backdrop.origin.x - origin.x
            val dy = backdrop.origin.y - origin.y
            glassLayer.record {
                drawRect(base.copy(alpha = 0.6f))
                translate(dx, dy) { drawLayer(backdrop.layer) }
            }
            if (cache.w != w || cache.h != h || cache.radius != radius || cache.blur != blurPx) {
                cache.w = w; cache.h = h; cache.radius = radius; cache.blur = blurPx
                glassLayer.renderEffect = if (Build.VERSION.SDK_INT >= 33) {
                    val shader = cache.shader ?: RuntimeShader(GLASS_AGSL).also { cache.shader = it }
                    shader.setFloatUniform("size", w.toFloat(), h.toFloat())
                    shader.setFloatUniform("radius", radius)
                    shader.setFloatUniform("depth", depth)
                    shader.setFloatUniform("strength", 1f)
                    val refract = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
                    val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                    android.graphics.RenderEffect.createChainEffect(refract, blur).asComposeRenderEffect()
                } else {
                    BlurEffect(blurPx, blurPx, TileMode.Clamp)
                }
            }
            drawLayer(glassLayer)
            // Frosting: the matte end of the slider lays a stronger tint over the blur.
            drawRect(base.copy(alpha = (0.02f + 0.5f * matte).coerceAtMost(0.75f)))
            drawGlassRim(shape)
            drawContent()
        }
}
