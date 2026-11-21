package com.musicdl.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.getBottom
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Tab bar and mini player as one piece that folds: as [fold] goes 0 → 1 the bar narrows into a circle at the
 * left (showing the active tab) while the mini player slides down beside it onto the same row. Everything is
 * driven from the layout and draw phases, so the animation never recomposes anything.
 */
@Composable
internal fun BottomBarMorph(
    fold: State<Float>,
    folded: Boolean,
    miniShown: Boolean,
    selected: FeedTab,
    onSelect: (FeedTab) -> Unit,
    onUnfold: () -> Unit,
    mini: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val barHeight = navBarHeight()
    val insetBottom = WindowInsets.navigationBars.getBottom(density)
    val navBlock = with(density) { insetBottom.toDp() } + 10.dp + barHeight
    val circleSize = 52.dp
    Box(Modifier.fillMaxWidth().height(navBlock + if (miniShown) 60.dp else 0.dp)) {
        // The tab bar itself: its width shrinks to a circle, its glass shrinks with it.
        Box(
            Modifier.fillMaxSize()
                .layout { measurable, c ->
                    val p = fold.value
                    val circleWidth = (barHeight + 32.dp).roundToPx()
                    val w = (c.maxWidth + (circleWidth - c.maxWidth) * p).roundToInt()
                    val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = c.maxHeight))
                    layout(c.maxWidth, c.maxHeight) { placeable.place(0, c.maxHeight - placeable.height) }
                }
                .graphicsLayer {
                    val p = fold.value
                    val s = 1f + (circleSize.toPx() / barHeight.toPx() - 1f) * p
                    scaleX = s; scaleY = s
                    transformOrigin = TransformOrigin(16.dp.toPx() / size.width, 1f - (insetBottom + 10.dp.toPx()) / size.height)
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            AppNavigation(selected, onSelect, fold = { fold.value })
            Box(
                Modifier.padding(start = 16.dp, bottom = with(density) { insetBottom.toDp() } + 10.dp).size(barHeight)
                    .graphicsLayer { alpha = ((fold.value - 0.45f) / 0.55f).coerceIn(0f, 1f) }
                    .clickable(enabled = folded, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onUnfold),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(barHeight - 14.dp).clip(CircleShape).background(Silver), contentAlignment = Alignment.Center) {
                    NavigationGlyphIcon(navGlyphOf(selected), Vinyl)
                }
            }
        }
        // The mini player: above the bar when unfolded, on the bar's row (right of the circle) when folded.
        Box(
            Modifier.fillMaxSize().layout { measurable, c ->
                val p = fold.value
                val offset = ((circleSize + 6.dp).toPx() * p).roundToInt()
                val w = c.maxWidth - offset
                val placeable = measurable.measure(Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = c.maxHeight))
                val drop = ((barHeight + 4.dp).toPx() * p).roundToInt()
                layout(c.maxWidth, c.maxHeight) {
                    placeable.place(offset, c.maxHeight - navBlock.roundToPx() - placeable.height + drop)
                }
            },
        ) {
            AnimatedVisibility(miniShown) { mini() }
        }
    }
}
