package com.musicdl.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicdl.core.model.SongInfo

/**
 * The track action sheet.
 *
 * Replaces the small dropdown that only held four unrelated items. Rows share one geometry
 * (56 dp, 20 dp icon box, 14 dp gap), one press animation and one type scale, so the sheet matches
 * the rest of the monochrome design instead of looking like a system popup.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackActionsSheet(
    song: SongInfo,
    isLiked: Boolean,
    isFollowed: Boolean,
    isCurrent: Boolean,
    save: SaveState?,
    downloaded: DownloadEntry?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onDeleteDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFollow: () -> Unit,
    onLyrics: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF101114),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(40.dp, 4.dp).background(Color(0xFF3A3D44), RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverImage(song.coverUrl, Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Lyrics, null, Modifier.size(24.dp), tint = Silver)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        song.songName ?: "Без названия",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.singers ?: "Неизвестный исполнитель",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            sourceName(song.source).takeIf { it.isNotBlank() },
                            downloaded?.let { "скачано · ${DownloadStore.sizeLabel(it.totalBytes)}" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF25272C), modifier = Modifier.padding(vertical = 8.dp))
            TrackActionRow("Слушать", if (isCurrent) Icons.Rounded.PlaylistPlay else Icons.Rounded.PlayArrow, onClick = onPlay)
            TrackActionRow("Проигрывать следующим", Icons.Rounded.QueuePlayNext, onClick = onPlayNext)
            TrackActionRow("Добавить в очередь", Icons.Rounded.PlaylistPlay, onClick = onAddToQueue)
            TrackActionRow(if (isLiked) "Убрать из избранного" else "В избранное",
                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, onClick = onLike)
            when {
                downloaded != null -> TrackActionRow("Удалить скачанный трек", Icons.Rounded.DeleteOutline,
                    tint = Color(0xFFE8A0A0), onClick = onDeleteDownload)
                save == SaveState.RUNNING -> TrackActionRow("Скачивается…", Icons.Rounded.Download, enabled = false, onClick = {})
                save == SaveState.FAILED -> TrackActionRow("Повторить скачивание", Icons.Rounded.Download, onClick = onDownload)
                else -> TrackActionRow("Скачать", Icons.Rounded.Download, onClick = onDownload)
            }
            TrackActionRow("Добавить в плейлист", Icons.Rounded.PlaylistAdd, onClick = onAddToPlaylist)
            TrackActionRow(if (isFollowed) "Отписаться от исполнителя" else "Подписаться на исполнителя",
                if (isFollowed) Icons.Rounded.PersonRemove else Icons.Rounded.PersonAdd, onClick = onFollow)
            TrackActionRow("Открыть текст", Icons.Rounded.Lyrics, onClick = onLyrics)
        }
    }
}

@Composable
private fun TrackActionRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = Silver,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale = if (pressed && enabled) 0.985f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 8.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactions, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (enabled) tint else tint.copy(alpha = 0.35f))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The "..." affordance used on cards, kept in one place so its size never drifts. */
@Composable
internal fun TrackMenuButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MoreVert, "Действия с треком", Modifier.size(22.dp))
    }
}

/** Compact downloaded badge for library rows. */
@Composable
internal fun DownloadedBadge(entry: DownloadEntry) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Rounded.DownloadDone, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            DownloadStore.sizeLabel(entry.totalBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
