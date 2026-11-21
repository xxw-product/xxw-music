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
    val s = Appearance.glassStrength
    val matte = Appearance.glassMatte
    return this
        .clip(shape)
        .background(base.copy(alpha = (opacity - (s - 1f) * 0.18f + (matte - 0.35f) * 0.6f).coerceIn(0.2f, 0.97f)))
        .drawWithContent {
            drawContent()
            drawGlassRim(shape, s)
        }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassRim(shape: Shape, s: Float) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline,
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = (0.20f * s).coerceAtMost(0.5f)),
            0.45f to Color.White.copy(alpha = 0.03f * s),
            1f to Color.White.copy(alpha = 0.08f * s),
        ),
    )
    drawOutline(
        outline,
        Brush.linearGradient(
            0f to Color.White.copy(alpha = (0.75f * s).coerceAtMost(0.95f)),
            0.35f to Color.White.copy(alpha = 0.10f),
            0.65f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = (0.42f * s).coerceAtMost(0.8f)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        ),
        style = Stroke(2.dp.toPx()),
    )
}

private class EffectCache {
    var key: List<Float>? = null
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
    float2 o = n * bend * 0.12;
    float2 lo = float2(0.5);
    float2 hi = size - float2(0.5);
    half r = content.eval(clamp(uv - o, lo, hi)).r;
    half g = content.eval(clamp(uv, lo, hi)).g;
    half b = content.eval(clamp(uv + o, lo, hi)).b;
    half3 c = half3(r, g, b);
    float l1 = max(dot(n, normalize(float2(-0.55, -0.83))), 0.0);
    float l2 = max(dot(n, normalize(float2(0.55, 0.83))), 0.0);
    float rim = pow(t, 3.0) * (l1 + 0.4 * l2) * 0.55 * strength;
    c += half3(rim);
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
            val s = Appearance.glassStrength
            val blurPx = (1.5f + 22f * matte).dp.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            val depth = minOf(size.height / 2f, 26.dp.toPx())
            val dx = backdrop.origin.x - origin.x
            val dy = backdrop.origin.y - origin.y
            glassLayer.record {
                drawRect(base.copy(alpha = 0.6f))
                translate(dx, dy) { drawLayer(backdrop.layer) }
            }
            val key = listOf(w.toFloat(), h.toFloat(), radius, depth, blurPx, s)
            if (cache.key != key) {
                cache.key = key
                glassLayer.renderEffect = if (Build.VERSION.SDK_INT >= 33) {
                    val shader = cache.shader ?: RuntimeShader(GLASS_AGSL).also { cache.shader = it }
                    shader.setFloatUniform("size", w.toFloat(), h.toFloat())
                    shader.setFloatUniform("radius", radius)
                    shader.setFloatUniform("depth", depth)
                    shader.setFloatUniform("strength", s)
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
            drawGlassRim(shape, s)
            drawContent()
        }
}
