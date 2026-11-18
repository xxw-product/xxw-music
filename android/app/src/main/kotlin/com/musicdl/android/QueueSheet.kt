package com.musicdl.android

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.musicdl.core.model.SongInfo
import kotlin.math.roundToInt

/**
 * The playback queue, independent from any playlist.
 *
 * Reordering is a real drag: the handle owns the pointer, the dragged row follows the finger and a
 * drop commits one [onMove]. The rows have a fixed 64 dp height so the target index is exact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QueueSheet(
    items: List<SongInfo>,
    currentKey: String?,
    shuffle: Boolean,
    onClose: () -> Unit,
    onPlay: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit,
    onClear: () -> Unit,
    onShuffle: () -> Unit,
    candidates: List<SongInfo> = emptyList(),
    onAdd: (SongInfo) -> Unit = {},
    onAddAll: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    var picker by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = Color(0xFF101114),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(40.dp, 4.dp).background(Color(0xFF3A3D44), RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Очередь", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(trackCount(items.size), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { picker = true }, enabled = candidates.isNotEmpty()) {
                    Icon(Icons.Rounded.PlaylistAdd, "Добавить треки в очередь")
                }
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Rounded.Shuffle, "Перемешать очередь",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onClear, enabled = items.isNotEmpty()) {
                    Icon(Icons.Rounded.DeleteSweep, "Очистить очередь")
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть очередь") }
            }
            if (items.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.QueueMusic, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Очередь пуста", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text("Меню трека → «Добавить в очередь»", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val rowHeight = 64.dp
                val rowHeightPx = with(LocalDensity.current) { rowHeight.toPx() }
                var dragIndex by remember { mutableStateOf<Int?>(null) }
                var dragOffset by remember { mutableFloatStateOf(0f) }
                val currentIndex = items.indexOfFirst { it.key() == currentKey }
                LaunchedEffect(currentIndex) {
                    if (currentIndex >= 0) listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
                }
                LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.fillMaxSize().height(420.dp)) {
                    itemsIndexed(items, key = { index, song -> "queue:$index:${song.key()}" }) { index, song ->
                        val dragging = dragIndex == index
                        val isCurrent = song.key() == currentKey
                        QueueRow(
                            song = song,
                            isCurrent = isCurrent,
                            modifier = Modifier
                                .height(rowHeight)
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                                .padding(horizontal = 8.dp),
                            onClick = { onPlay(index) },
                            onRemove = { onRemove(index) },
                            dragModifier = Modifier.pointerInput(items.size, index) {
                                detectDragGestures(
                                    onDragStart = { dragIndex = index; dragOffset = 0f },
                                    onDragEnd = {
                                        val target = ((dragIndex ?: index) + (dragOffset / rowHeightPx).roundToInt())
                                            .coerceIn(0, items.lastIndex)
                                        if (target != (dragIndex ?: index)) onMove(dragIndex ?: index, target)
                                        dragIndex = null; dragOffset = 0f
                                    },
                                    onDragCancel = { dragIndex = null; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
        if (picker) {
            val missing = candidates.filter { candidate -> items.none { it.key() == candidate.key() } }
            AlertDialog(
                onDismissRequest = { picker = false },
                title = { Text("Добавить в очередь") },
                text = {
                    if (missing.isEmpty()) Text("Все найденные треки уже в очереди")
                    else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(missing.take(60), key = { "pick:${it.key()}" }) { song ->
                            TextButton(onClick = { onAdd(song) }, modifier = Modifier.fillMaxWidth()) {
                                Text("+ ${song.songName.orEmpty()} · ${song.singers.orEmpty()}", maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = missing.isNotEmpty(), onClick = { onAddAll(); picker = false }) {
                        Text("Добавить все")
                    }
                },
                dismissButton = { TextButton(onClick = { picker = false }) { Text("Закрыть") } },
            )
        }
    }
}

@Composable
private fun QueueRow(
    song: SongInfo,
    isCurrent: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    dragModifier: Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center) {
                CoverImage(song.coverUrl, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.QueueMusic, null, Modifier.size(18.dp)) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(song.songName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                Text(song.singers.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isCurrent) Icon(Icons.Rounded.Equalizer, "Играет", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Убрать из очереди", Modifier.size(18.dp)) }
            Box(dragModifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragHandle, "Перетащить", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
