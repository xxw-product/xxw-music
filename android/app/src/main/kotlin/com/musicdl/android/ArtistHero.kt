package com.musicdl.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Artist page header: a large portrait that fades into the app background, the name, and three
 * round actions (follow, shuffle, play) with the accent colour on play. Same structure as the
 * big streaming-app artist pages, drawn in this app's dark, rounded, accent-coloured style.
 */
@Composable
fun ArtistHero(
    name: String,
    following: Boolean,
    loading: Boolean,
    trackCount: Int,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onAlbums: () -> Unit,
) {
    val context = LocalContext.current
    val photosOn = Appearance.showArtistPhotos
    val profile by produceState<ArtistPhotos.Profile?>(null, name, photosOn) {
        value = if (photosOn) ArtistPhotos.profile(context, name) else null
    }
    // The saved copy keeps the page recognisable offline, before the big portrait arrives.
    val saved by produceState<String?>(null, name, photosOn) {
        value = if (photosOn) ArtistPhotos.photoUrl(context, name) else null
    }
    val accent = Appearance.accentColor
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White
    val background = MaterialTheme.colorScheme.background
    val enabled = trackCount > 0

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            val initial = @Composable {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))),
                    contentAlignment = Alignment.Center) {
                    Text(name.trim().take(1).uppercase(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
                }
            }
            if (photosOn) CoverImage(profile?.photo ?: saved, Modifier.fillMaxSize()) { initial() } else initial()
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.62f to background.copy(alpha = 0.55f), 1f to background)))
            RoundIcon(Icons.Rounded.ArrowBack, "Назад", onBack, Modifier.align(Alignment.TopStart).padding(12.dp))
            RoundIcon(Icons.Rounded.Refresh, "Обновить", onRefresh, Modifier.align(Alignment.TopEnd).padding(12.dp))
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val caption = profile?.fans?.takeIf { it > 0 }?.let { "${groupDigits(it)} поклонников" }
                    ?: if (loading) "Ищу треки…" else if (trackCount > 0) "$trackCount треков" else ""
                if (caption.isNotEmpty()) Text(caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HeroAction(
                if (following) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (following) "Подписан" else "Подписаться", onFollow, true,
                container = MaterialTheme.colorScheme.surfaceContainerHigh, tint = if (following) accent else MaterialTheme.colorScheme.onSurface,
            )
            HeroAction(Icons.Rounded.Shuffle, "Вперемешку", onShuffle, enabled,
                container = MaterialTheme.colorScheme.surfaceContainerHigh, tint = MaterialTheme.colorScheme.onSurface)
            HeroAction(Icons.Rounded.PlayArrow, "Слушать", onPlay, enabled, container = accent, tint = onAccent)
        }
        TextButton(onClick = onAlbums, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Альбомы и сборники") }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(modifier = modifier.size(42.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.45f)) {
        IconButton(onClick = onClick) { Icon(icon, description, tint = Color.White) }
    }
}

@Composable
private fun HeroAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean, container: Color, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick, enabled = enabled, shape = CircleShape, color = container,
            modifier = Modifier.size(72.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(32.dp), tint = if (enabled) tint else tint.copy(alpha = 0.4f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun groupDigits(value: Long): String = String.format(Locale.US, "%,d", value).replace(',', ' ')
