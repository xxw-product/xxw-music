package com.musicdl.android

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One capsule with a floating pill that glides to the chosen option, like the thumb of the bottom bar. */
@Composable
internal fun SegmentedTabs(options: List<Triple<String, Boolean, () -> Unit>>, modifier: Modifier = Modifier) {
    val glass = Appearance.liquidGlass && Appearance.glassNav
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val xs = remember(options.size) { mutableStateListOf<Float>().apply { repeat(options.size) { add(0f) } } }
    val ws = remember(options.size) { mutableStateListOf<Float>().apply { repeat(options.size) { add(0f) } } }
    val selectedIndex = options.indexOfFirst { it.second }
    val spec = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)
    val thumbX by animateFloatAsState(if (selectedIndex >= 0) xs[selectedIndex] else 0f, spec, label = "segmentX")
    val thumbW by animateFloatAsState(if (selectedIndex >= 0) ws[selectedIndex] else 0f, spec, label = "segmentW")
    val thumbColor = if (glass) Silver.copy(alpha = 0.92f) else Silver
    Row(
        modifier
            .then(
                if (Appearance.searchOverlay) Modifier.glassSurface(CircleShape, container, true, opacity = 0.55f, real = true)
                else Modifier.glassOutline(CircleShape, glass),
            )
            .horizontalScroll(rememberScrollState())
            .padding(4.dp)
            .drawBehind {
                if (selectedIndex >= 0 && thumbW > 0f) {
                    drawRoundRect(thumbColor, Offset(thumbX, 0f), Size(thumbW, size.height), CornerRadius(size.height / 2f))
                }
            },
    ) {
        options.forEachIndexed { index, (label, selected, onClick) ->
            val text by animateColorAsState(if (selected) Vinyl else Color(0xFF9297A1), tween(180), label = "segmentText")
            Text(
                label, color = text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                modifier = Modifier
                    .onGloballyPositioned { xs[index] = it.positionInParent().x; ws[index] = it.size.width.toFloat() }
                    .clip(CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
