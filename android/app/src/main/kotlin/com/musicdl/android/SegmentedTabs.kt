package com.musicdl.android

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One capsule holding several choices; the chosen one is a light pill, like the thumb of the bottom bar. */
@Composable
internal fun SegmentedTabs(options: List<Triple<String, Boolean, () -> Unit>>, modifier: Modifier = Modifier) {
    val glass = Appearance.liquidGlass && Appearance.glassNav
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        modifier
            .then(
                if (glass) Modifier.glassSurface(CircleShape, container, true)
                else Modifier.clip(CircleShape).background(container).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        options.forEach { (label, selected, onClick) ->
            val text by animateColorAsState(if (selected) Vinyl else Color(0xFF9297A1), tween(180), label = "segmentText")
            val fill by animateColorAsState(if (selected) Silver else Color.Transparent, tween(180), label = "segmentFill")
            Text(
                label, color = text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false,
                modifier = Modifier.clip(CircleShape).background(fill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}
